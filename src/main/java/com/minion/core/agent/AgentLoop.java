package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.context.ContextManager;
import com.minion.core.context.TokenCounter;
import com.minion.core.llm.ImagePart;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.llm.UsageTracker;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolArguments;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 主 agent 循环：请求 → 工具执行 → 回传，拟结束回复经统一门禁确认。 */
public class AgentLoop {
    static final String TASK_COMPLETE_MARKER = "[[MINION_TASK_COMPLETE]]";

    public static final int DEFAULT_ROUND_LIMIT = 1000;
    /** 两次有明确执行计划但无动作后升级协议，不能靠反复“继续”耗尽上下文。 */
    private static final int ACTION_RECOVERY_AFTER = 2;
    /** 只匹配句尾主动计划；有限修饰词避免“做全面比对”等变体漏判。 */
    private static final java.util.regex.Pattern TERMINAL_COMPARISON_PLAN = java.util.regex.Pattern.compile(
            "(?:^|[。！？!?]\\s*)(?:让我|我(?:将|会|准备|打算)|现在(?:我)?|接下来(?:我)?(?:会|将)?|下一步(?:我)?(?:会|将)?|还需要)"
            + "(?:再|先|立即|现在|继续|开始|做(?:一次|一个|一轮|一遍|一份)?|进行|全面|完整|逐项|逐行|详细|进一步|实际|着手){0,8}"
            + "(?:(?:比对|对比|比较|匹配)(?!结果|结论|情况|报告|已|完成|完毕|结束)|交叉验证|编写|编制|写入|制作|核验|查验|检查|核对|验证|分析|处理|执行|生成|读取|查看|补充|复核|确认|排查|扫描|派发|汇总)"
            + "[^。！？!?]*[：:，,。.!！]*$");

    private volatile LlmClient llm;
    private final ToolRegistry registry;
    /** 工具注册表（供 MCP 连接完成后的补注册：SessionManager 经会话句柄访问） */
    public ToolRegistry registry() { return registry; }
    private final SystemPromptBuilder promptBuilder;
    private final ConfirmGate confirmGate;
    private final AgentUi ui;
    private ContextManager contextManager; // null = 不启用压缩；final 移除
    private final Workspace workspace;
    private final Session session;
    private final ToolExecutionGuard executionGuard;
    private volatile java.util.function.BiFunction<String,String,AgentRuntime> runtimeFactory;
    private final java.util.concurrent.ConcurrentMap<String,SubAgentLoop> activeChildren = new java.util.concurrent.ConcurrentHashMap<String,SubAgentLoop>();
    private volatile String budgetText="尚未发出请求";
    public String contextBudget() { return budgetText; }
    public Workspace workspace() { return workspace; }
    public boolean evidencePathAllowed(java.nio.file.Path p) {
        return com.minion.core.tools.PathsGuard.inside(workspace.workDir(),p)
                ||com.minion.core.tools.PathsGuard.insideExtra(workspace,p)
                ||com.minion.core.tools.PathsGuard.inside(promptBuilder.tempDir(),p);
    }
    public void setAgentRuntimeFactory(java.util.function.BiFunction<String,String,AgentRuntime> factory) { runtimeFactory=factory; }

    /** 可选：会话自动落盘（Task 18）。null = 不保存 */
    private SessionStore store;

    private volatile boolean interrupted = false;
    private volatile boolean lastTurnSucceeded;
    public boolean lastTurnSucceeded() { return lastTurnSucceeded; }
    private List<Skill> allSkills = new ArrayList<Skill>();
    /** 待注入的技能加载队列（FX 线程 /skill 与工具线程 Skill 工具入队；主循环检查点 drain 后注入历史）。
     *  队列级去重：同名已在队列 → 跳过（同轮防重复插入）；历史级幂等由 Skill 工具报告、模型判断 */
    private final List<SkillLoad> pendingSkillLoads = new ArrayList<SkillLoad>();
    private java.util.function.Function<JsonObject, String> subAgentRunner; // Task 15 注入

    public int roundLimit = DEFAULT_ROUND_LIMIT;
    /** 瞬时错误长重试策略（429/500/502；默认固定 5s/次，总时长 20 分钟；测试可覆写小参数） */
    public RetryPolicy retryPolicy = RetryPolicy.transientErrors();
    /** 工具空输出占位（配置 agent.emptyOutput.placeholder 注入；开启时成功空输出发「输出内容为空」占位） */
    public boolean emptyOutputPlaceholder = false;
    /** 连续工具失败止损阈值：达到后注入提醒让模型停止尝试并请求用户补充信息 */
    private static final int STUCK_THRESHOLD = 30;
    /** 连续失败工具计数（成功即清零；注入提醒后重置） */
    private int consecutiveToolErrors = 0;
    /** 本回合自动压缩失败/无收益时的 token 水位；至少再增长 1024 才重试，防止每个工具轮反复调用压缩模型。 */
    private int lastIneffectiveAutoCompressTokens = -1;
    public int threads = 4;
    private final ExecutorService pool;
    /** AskUserQuestion 工具实例（构造注册；answerAskUser 经其送达回答） */
    private final com.minion.core.tools.AskUserQuestionTool askUserTool;
    /** 进行中的工具 future（供 interrupt() 取消） */
    private final List<Future<ToolResult>> inFlight = new ArrayList<Future<ToolResult>>();

    public AgentLoop(LlmClient llm, ToolRegistry registry,
                     SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui,
                     ContextManager contextManager, Workspace workspace, Session session) {
        this.llm = llm;
        this.registry = registry;
        this.promptBuilder = promptBuilder;
        this.confirmGate = confirmGate;
        this.ui = ui;
        this.contextManager = contextManager;
        this.workspace = workspace;
        this.session = session;
        this.executionGuard = new ToolExecutionGuard(workspace,false);
        this.executionGuard.allowTrackingRoot(promptBuilder.tempDir());
        if (session.taskState == null) session.taskState = new TaskState();
        session.taskState.restoreFromHistory(session.messages);
        session.taskState.recoverAfterRestart();
        // daemon 线程：main() 返回后 JVM 可正常退出（T21 REPL）
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "minion-tools");
            t.setDaemon(true);
            return t;
        });
        // T15：构造末尾自动注册 task 工具并注入默认子 agent 执行器
        registry.register(new com.minion.core.tools.TaskTool(this));
        registry.register(new com.minion.core.tools.SkillTool(this));
        // 终审修复：TodoWriteTool 按会话自动注册（构造捕获 session.todos 实例引用，
        // restoreSession/startNewSession 原地装载保证引用持续有效——与旧 Main 接线语义一致；
        // 每会话独立 registry 下模型可见 todo 工具，此前仅 TaskTool 自动注册导致 TodoWrite 静默丢失）
        registry.register(new com.minion.core.tools.TodoWriteTool(session.todos));
        this.askUserTool = new com.minion.core.tools.AskUserQuestionTool(ui);
        registry.register(askUserTool);
        registry.register(new com.minion.core.tools.AgentControlTool(this,workspace));
        // 模型热切换回归修复：lambda 写在构造器内，简单名 llm 会按作用域规则捕获「构造器形参」
        // （创建会话时的旧客户端引用），导致 setLlm 切模型后主 agent 用新模型、子 agent 仍用旧模型
        // （事故：选 qwen 却由子 agent 烧光 deepseek 额度并报 402 余额不足）。
        // 必须显式 this.llm 读 volatile 字段，才与主循环请求路径（456/485 行）同源。
        setSubAgentRunner(args -> launchChild(args));
    }

    private String launchChild(JsonObject args) {
        String desc=text(args,"description","无描述");
        if(args.has("prompt")&&!args.get("prompt").isJsonNull()) desc+="\n补充要求/返回格式: "+args.get("prompt").getAsString();
        String mode=text(args,"mode",session.subAgentMode==null?"worker":session.subAgentMode);
        if(!"worker".equals(mode)&&!"explore".equals(mode)) return "子Agent失败: mode只支持worker/explore";
        TaskState.Child child=session.taskState.startChild(desc);
        String path=java.nio.file.Paths.get(workspace.workDir(),".minion","subagents",session.id,child.id+".json").toAbsolutePath().toString();
        session.taskState.configureChild(child.id,mode,text(args,"allowedTools",session.subAgentTools),text(args,"model",session.subAgentModelName),path);
        return executeChild(session.taskState.child(child.id),false);
    }
    private static String text(JsonObject o,String key,String fallback) {
        return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():fallback;
    }
    private AgentRuntime childRuntime(String model,String id) {
        if(runtimeFactory!=null) return runtimeFactory.apply(model,id);
        if(model!=null&&!model.isEmpty()) throw new IllegalArgumentException("当前入口未配置子Agent模型工厂，不能忽略指定模型");
        return new AgentRuntime(llm,session.modelName,contextManager==null?60000:contextManager.maxTokens(),
                contextManager==null?0.8:contextManager.threshold(),contextManager==null?8192:contextManager.outputReserveTokens(),registry,workspace,false);
    }
    private String executeChild(TaskState.Child child,boolean resume) {
        String result="子Agent未执行"; SubAgentLoop sub=null; boolean registered=false, duplicate=false;
        ChildAgentUi childUi=new ChildAgentUi(ui,child.id);
        try(AgentRuntime runtime=childRuntime(child.model,child.id)) {
            sub=new SubAgentLoop(buildSystemPrompt(),child.assignment,runtime.workspace.workDir(),runtime.client,runtime.tools,confirmGate,childUi);
            sub.setContextWindow(runtime.maxContext,runtime.threshold,runtime.outputReserveTokens);
            sub.setExecutionScope(runtime.workspace,session.taskState,child.id,new AgentToolPolicy("explore".equals(child.mode),child.allowedTools));
            sub.setOwnClient(runtime.owned);
            sub.setRequestProfile(session.contextProfile);
            java.nio.file.Path record=java.nio.file.Paths.get(workspace.workDir(),".minion","subagents",session.id,child.id+".json").toAbsolutePath();
            sub.setRecordPath(record);
            sub.emptyOutputPlaceholder=emptyOutputPlaceholder;
            if(activeChildren.putIfAbsent(child.id,sub)!=null) { duplicate=true; return "子Agent已在运行，未重复派发"; }
            registered=true;
            if(resume) sub.restoreRecord(record);
            else if(java.nio.file.Files.exists(record)) sub.archiveExistingRecord();
            session.taskState.configureChild(child.id,child.mode,child.allowedTools,runtime.model,record.toString());
            result=sub.run();
            return "子Agent ID: "+child.id+"\n状态: "+(sub.succeeded()?"已返回，主Agent需核对":"失败/中断，未完成")+"\n完整记录: "+sub.recordPath()+"\n"+result;
        } catch(Exception e) { result="子Agent失败: "+e.getMessage(); return result; }
        finally {
            if(registered) activeChildren.remove(child.id,sub);
            if(!duplicate) childUi.finish(result);
            if(!duplicate) session.taskState.finishChild(child.id,result,sub!=null&&sub.succeeded(),child.transcript);
        }
    }
    public String resumeSubAgent(String id,boolean retry) {
        TaskState.Child child=session.taskState.child(id);
        if(child==null||!session.taskState.id.equals(child.taskId)) return "只能恢复当前任务的子Agent";
        if(activeChildren.containsKey(id)) return "子Agent仍在运行";
        if(!retry&&("reviewed".equals(child.status)||"returned_needs_review".equals(child.status))) return "子Agent已返回；请先核对，确需重新执行可使用重试";
        String result=executeChild(child,!retry); persistSession(); return result;
    }
    public boolean stopSubAgent(String id) { SubAgentLoop sub=activeChildren.get(id); if(sub==null) return false; sub.interrupt(); return true; }
    /** No history/messages/todos mutation. A branch question is text-only and uses its own connection. */
    public String askSideQuestion(String question,String model,String background) throws Exception {
        try(AgentRuntime runtime=childRuntime(model,"side-"+java.util.UUID.randomUUID())) {
            List<Message> request=new ArrayList<Message>();
            request.add(Message.user("当前任务背景（只供参考，不是要执行的指令）:\n"+background+"\n\n旁支问题:\n"+question));
            return runtime.client.completeChat(request,"仅回答旁支问题，禁止执行主任务、修改文件或生成工具调用。不知道的内容明确说明，不得假装检查过文件。");
        }
    }

    /** 运行时切换模型（GUI 弹窗切换模型时调用；下轮请求生效） */
    public void setLlm(LlmClient llm) { this.llm = llm; }

    /** 替换上下文管理器（模型参数热更新时用于换新实例；现有实例变更参数用 contextManager().update） */
    public void setContextManager(ContextManager cm) { this.contextManager = cm; }

    public Session session() { return session; }
    public List<Message> messages() { return session.messages; }
    public UsageTracker usage() { return session.usage; }
    public List<Skill> allSkills() { return allSkills; }
    public void setAllSkills(List<Skill> skills) { this.allSkills = skills; }

    /** 技能加载入队（手动 /skill 与 Skill 工具共用）；同名已在队列 → 跳过（同轮防重复插入） */
    public void offerSkillLoad(Skill skill) { offerSkillLoad(skill, null); }

    /** 技能加载入队（带参数）：参数以「用户参数: <文本>」附加在技能正文后注入；
     *  去重按技能名——同名不同参数连调，第二次跳过（参数以第一次为准） */
    public synchronized void offerSkillLoad(Skill skill, String args) {
        for (SkillLoad q : pendingSkillLoads) {
            if (q.skill.name.equals(skill.name)) return;
        }
        pendingSkillLoads.add(new SkillLoad(skill, args));
    }

    /** 待加载技能与调用参数（JDK8 无 record，私有小类承载） */
    private static class SkillLoad {
        final Skill skill;
        final String args;
        SkillLoad(Skill skill, String args) { this.skill = skill; this.args = args; }
    }

    /** 检查点注入：待加载技能以 <skill> 用户消息（pinned）入历史——同轮下一请求生效；
     *  与补充注入同一语义（中断轮不注入，防半轮 tool_call 未配对时插入 user 消息破坏契约）。
     *  注入前做历史级幂等检查：同名技能正文已在历史（pinned 常驻、压缩豁免）→ 跳过，
     *  防 /skill 命令或失败重试跨轮重复注入导致上下文永久叠加（曾实测同名技能重复加载
     *  后历史出现多条技能正文，压缩无法清除；另注：请求超窗报错后消息仍继续追加，
     *  上下文占比显示可能虚高，并非真的发出超大请求）。 */
    private void drainPendingSkillLoads() {
        List<SkillLoad> queue;
        synchronized (this) {
            if (pendingSkillLoads.isEmpty()) return;
            queue = new ArrayList<SkillLoad>(pendingSkillLoads);
            pendingSkillLoads.clear();
        }
        for (SkillLoad q : queue) {
            boolean alreadyInHistory = false;
            for (Message m : session.messages) {
                if (m.role == Message.Role.USER && m.content != null
                        && m.content.contains("<skill name=\"" + q.skill.name + "\">")) {
                    alreadyInHistory = true;
                    break;
                }
            }
            if (alreadyInHistory) continue; // 历史已含同名技能正文（常驻），不重复注入
            // I-1：注入即发 UI 事件——技能正文整条 content 渲染为一条用户消息（透明可审计，
            // 与 runUserTurn 发用户输入同一语义：session 工作线程调用，事件驱动 live 渲染）
            // 用户参数放 <skill> 标签外：标签内严格等于 SKILL.md 正文（技能定义不变量，
            // 幂等/判重/正文独立处理均不受参数干扰）；参数属调用上下文，紧邻技能块注入
            String content = "<skill name=\"" + q.skill.name + "\">\n"
                    + q.skill.instructions + "\n</skill>";
            if (q.args != null && !q.args.isEmpty()) {
                content += "\n\n用户参数: " + q.args;
            }
            Message msg = Message.skill(content);
            session.messages.add(msg);
            ui.onUserMessage(msg.content);
        }
    }

    /** 启用会话自动落盘（Task 18） */
    public void setSessionStore(SessionStore store) { this.store = store; }

    /** 会话落盘（失败不阻断主流程，仅告警） */
    private void saveSession() {
        if (store != null) {
            try { store.save(session); }
            catch (Exception e) { ui.onWarning("会话落盘失败: " + e.getMessage()); }
        }
    }

    /**
     * 落盘当前会话:先快照当前工作区 cwd 到会话再保存。
     * 此前会话文件 cwd 恒为 null,/resume 后 cd 跨会话持久化静默失效;
     * 所有保存入口(自动落盘/退出保存//new 预保存)统一走本方法。
     */
    public void persistSession() {
        session.cwd = workspace.cwd().toString();
        saveSession();
    }

    public void persistQueue() {
        if(store!=null) try { store.saveQueue(session); }
        catch(Exception e) { ui.onWarning("队列保存失败: " + e.getMessage()); }
    }

    /** 当前系统提示（含已加载技能），子 agent 复用 */
    public String buildSystemPrompt() {
        return promptBuilder.build(allSkills);
    }

    public void setSubAgentRunner(java.util.function.Function<JsonObject, String> runner) {
        this.subAgentRunner = runner;
    }

    /** 回答 AskUserQuestion（SessionManager.sendAnswer 转发）；无挂起时忽略 */
    public boolean answerAskUser(String answer) {
        return askUserTool.complete(answer);
    }

    public void interrupt() {
        DiagnosticLog.info("agent-interrupt", "source=explicit_interrupt task=" + session.taskState.id);
        interrupted = true;
        for(SubAgentLoop child:activeChildren.values()) child.interrupt();
        llm.cancel(); // 中断进行中的流式请求
        List<Future<ToolResult>> cancelThese;
        synchronized (inFlight) {
            cancelThese = new ArrayList<Future<ToolResult>>(inFlight);
        }
        for (Future<ToolResult> f : cancelThese) {
            f.cancel(true); // 中断执行中的工具，避免等待全部 in-flight 完成
        }
    }

    /** 运行中补充：入挂起队列（随会话落盘），检查点或下次发送时入历史 */
    public void offerSupplement(String text) { offerSupplement(text, null); }

    /** 运行中补充（带图）：文本与图片同步入挂起队列（随会话落盘） */
    public void offerSupplement(String text, List<ImagePart> images) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (session.pendingSupplements) {
            session.pendingSupplements.add(text);
            session.pendingSupplementImages.add(images == null
                    ? new ArrayList<ImagePart>() : new ArrayList<ImagePart>(images));
        }
    }

    /** 挂起补充全部入历史并清空队列（UI 事件在点击时已发，此处不再发） */
    private void drainSupplements() {
        List<String> texts;
        List<List<ImagePart>> imgs;
        synchronized (session.pendingSupplements) {
            if (session.pendingSupplements.isEmpty()) return;
            texts = new ArrayList<String>(session.pendingSupplements);
            imgs = new ArrayList<List<ImagePart>>(session.pendingSupplementImages);
            session.pendingSupplements.clear();
            session.pendingSupplementImages.clear();
        }
        for (int i = 0; i < texts.size(); i++) {
            List<ImagePart> images = i < imgs.size() ? imgs.get(i) : null;
            session.taskState.addGuidance(texts.get(i));
            session.messages.add(Message.userSupplement(texts.get(i), images));
        }
    }

    /** 关闭工具执行池（会话删除/应用退出时调用；daemon 线程，shutdownNow 不等任务完成） */
    public void shutdown() {
        pool.shutdownNow();
    }

    /**
     * 等工具池清理完成（应用退出收口用）：interrupt 后正在执行的 Bash 工具会走
     * killTree 清杀子进程，但工具池是 daemon 线程——JVM 不等 daemon 线程，若此处
     * 不等待，JVM 退出时 killTree 可能没执行完，bash 子进程变孤儿继续占 CPU
     * （关窗残留实测根因）。中断后工具任务最多 killTree(5s) + join(5s) 即结束，
     * 限时等待足够；杀不掉的极端情况限时返回，不拖死退出流程。
     */
    public void awaitToolsTerminated(long timeoutMs) {
        try {
            pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void compactNow() {
        if (contextManager == null) {
            ui.onWarning("未启用上下文压缩");
            return;
        }
        ui.onCompressingChanged(true);
        try {
            int beforeTokens = contextManager.estimate(session.messages);
            List<Message> beforeMessages = session.messages;
            // 用户主动 /compact 不受自动阈值限制；配置窗口可能大于服务端真实窗口，
            // 即使界面仅显示 39%，也必须能强制缩减当前活跃工具链。
            session.messages = contextManager.compressForRecovery(session.messages, session.taskState.render(session.todos), session.taskState.objective);
            int afterTokens = contextManager.estimate(session.messages);
            if (session.messages != beforeMessages) {
                persistSession();
                ui.onWarning("已压缩上下文（" + beforeTokens + " → " + afterTokens + " token，历史摘要已置前）");
            } else if (contextManager.lastCompressAttempted()) {
                // take>0 但压缩 LLM 调用失败（网络/超窗）原样返回：与"无可压缩"区分开，避免误导
                ui.onWarning("压缩失败（模型调用异常），请稍后重试");
            } else {
                ui.onWarning("暂无可压缩内容");
            }
        } finally {
            ui.onCompressingChanged(false);
        }
        pushContextStats();
    }

    /** 推送上下文统计（GUI 环形进度圈）：contextManager 未启用时不推送 */
    private void pushContextStats() {
        if (contextManager == null) return;
        contextManager.setTaskAnchorTokens(TokenCounter.estimate(session.taskState.render(session.todos)));
        ui.onContextStats(contextManager.estimate(session.messages), contextManager.maxTokens());
    }

    /** REPL 统计用：上下文估算 */
    public ContextManager contextManager() { return contextManager; }

    /** REPL 渲染用 */
    public AgentUi ui() { return ui; }

    /** 恢复历史会话（Task 21 /resume、-r）：消息引用直接复用；
     *  todo/usage 必须原地装载而非换新实例：Main 注册 TodoWriteTool 时捕获的是
     *  session.todos 实例引用，换实例会让工具继续写已废弃清单（任务状态丢失，同 /new 修复）。 */
    public void restoreSession(Session s) {
        session.messages = s.messages;
        session.id = s.id;
        session.createdAt = s.createdAt;
        session.workDir = s.workDir;
        session.modelName = s.modelName;
        session.modelDisplayName = s.modelDisplayName;
        session.subAgentModelName=s.subAgentModelName; session.subAgentMode=s.subAgentMode;
        session.subAgentTools=s.subAgentTools; session.contextProfile=s.contextProfile;
        session.preferredContextLimit=s.preferredContextLimit;
        session.title = s.title;
        session.taskState = s.taskState != null ? s.taskState : new TaskState();
        session.taskState.restoreFromHistory(session.messages);
        session.taskState.recoverAfterRestart();
        session.queuedTasks = s.queuedTasks != null ? s.queuedTasks : new ArrayList<Session.QueuedTask>();
        scrubHalfTurn(); // 恢复历史同样清洗半轮残留（外部/旧格式文件可能含残缺 toolCalls）
        scrubInvalidToolArguments();
        if (s.todos != null) session.todos.replace(s.todos.items); // 原地装载（replace 内部 clear+addAll）
        if (s.usage != null) session.usage.restore(s.usage);
        workspace.restore(s.cwd);
        // 挂起补充随会话恢复（旧文件缺字段时 Gson 初始化器已兜底，此处再防御一次）
        session.pendingSupplements = s.pendingSupplements != null
                ? s.pendingSupplements : new ArrayList<String>();
        session.pendingSupplementImages = s.pendingSupplementImages != null
                ? s.pendingSupplementImages : new ArrayList<List<ImagePart>>();
    }

    /** /new:清空当前会话内容并回到工作区根。
     *  todo/usage 必须原地清空而非换新实例：Main 注册 TodoWriteTool 时捕获的是 session.todos
     *  的实例引用，换新实例会让工具继续写已废弃的空清单（任务状态丢失）。
     *  id/createdAt 必须重新生成：旧 id 会话已随 /new 落盘，沿用旧 id 会让新会话的
     *  自动落盘覆盖上一个会话文件。 */
    public void startNewSession() {
        session.messages.clear();
        session.pendingSupplements.clear();
        session.todos.clear();
        session.taskState = new TaskState();
        session.queuedTasks.clear();
        session.usage.reset();
        session.regenerateId();
        workspace.resetCwd();
    }

    /**
     * 半轮残留清洗：最近一条 assistant 消息带 toolCalls 但后续 TOOL 结果不完整（工具阶段中断/损坏历史），
     * 剥离其 toolCalls；残缺的 tool 结果一并移除；纯工具调用空壳消息整条移除。
     * 否则下轮请求发出「assistant 含 tool_calls 无对应 tool 结果」→ API 400 且非重试。
     * 空壳判定只看 content：仅思考无正文的 assistant 同样必须移除（DeepSeek 思考模式硬性要求
     * assistant 消息带 content 或 tool_calls，仅 reasoning_content 回传会 400）。
     */
    private void scrubHalfTurn() {
        for (int i = session.messages.size() - 1; i >= 0; i--) {
            Message m = session.messages.get(i);
            if (m.role != Message.Role.ASSISTANT) continue;
            int need = m.toolCalls == null ? 0 : m.toolCalls.size();
            int have = 0;
            for (int j = i + 1; j < session.messages.size(); j++) {
                if (session.messages.get(j).role == Message.Role.TOOL) have++;
            }
            if (need > 0 && have < need) {
                m.toolCalls = null;
                // 其后不完整的 tool 结果与剥离后的消息不对应，一并移除
                while (session.messages.size() > i + 1) {
                    session.messages.remove(session.messages.size() - 1);
                }
            }
            // 空壳 assistant 整条移除（无正文且无工具调用——含仅思考消息与旧格式残留），避免空消息进请求
            if (m.content == null && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                session.messages.remove(i);
            }
            break; // 只需检查最近一条 assistant
        }
    }

    /** 清洗旧版本落盘的非法 tool arguments；否则服务端校验历史时会让会话永久 400。 */
    private int scrubInvalidToolArguments() {
        List<String> invalidIds = new ArrayList<String>();
        for (int i = session.messages.size() - 1; i >= 0; i--) {
            Message m = session.messages.get(i);
            if (m.role != Message.Role.ASSISTANT || m.toolCalls == null) continue;
            List<ToolCall> valid = new ArrayList<ToolCall>();
            for (ToolCall tc : m.toolCalls) {
                if (normalizeAndValidateToolArguments(tc)) valid.add(tc);
                else if (tc != null && tc.id != null) invalidIds.add(tc.id);
            }
            m.toolCalls = valid.isEmpty() ? null : valid;
            if (!hasVisibleText(m.content) && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                session.messages.remove(i);
            }
        }
        for (int i = session.messages.size() - 1; i >= 0; i--) {
            Message m = session.messages.get(i);
            if (m.role == Message.Role.TOOL && invalidIds.contains(m.toolCallId)) session.messages.remove(i);
        }
        return invalidIds.size();
    }

    private static boolean normalizeAndValidateToolArguments(ToolCall tc) {
        if (tc == null) return false;
        if (tc.arguments == null || tc.arguments.trim().isEmpty()) {
            tc.arguments = "{}";
            return true;
        }
        try {
            return new JsonParser().parse(tc.arguments).isJsonObject();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 原地保留合法调用，返回被丢弃的损坏调用数；一个坏调用不再连带丢弃同批合法调用。 */
    private static int removeInvalidToolCalls(List<ToolCall> calls) {
        if (calls == null) return 0;
        int removed = 0;
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (!normalizeAndValidateToolArguments(calls.get(i))) {
                calls.remove(i);
                removed++;
            }
        }
        return removed;
    }

    public void runUserTurn(String input) { runUserTurn(input, null); }

    public void runUserTurn(String input, List<ImagePart> images) {
        runUserTurn(input, images, false);
    }

    /** Queued inputs explicitly start a new task, even when their text says "continue". */
    public void runUserTurn(String input, List<ImagePart> images, boolean forceNewTask) {
        interrupted = false;
        lastTurnSucceeded = false;
        if (session.taskState.begin(input, forceNewTask)) {session.todos.clear();session.taskState.bindReferenceHistory(session.messages);}
        lastIneffectiveAutoCompressTokens = -1;
        int repairedToolCalls = scrubInvalidToolArguments();
        if (repairedToolCalls > 0) {
            ui.onWarning("已自动清理会话历史中 " + repairedToolCalls
                    + " 个损坏的工具调用参数，正在重新发送请求");
            persistSession();
        }
        long start = System.currentTimeMillis(); // 统计行：轮次耗时
        int reportFromIndex = session.messages.size();
        List<String> reportTools = new ArrayList<String>();
        boolean taskCompleted = false;
        // 上次回合遗留的挂起补充先入历史（模型提问自然收尾/中断遗留），与本次输入拼接发送
        drainSupplements();
        drainPendingSkillLoads();
        ui.onUserMessage(ImagePart.displayText(images, input));
        session.messages.add(Message.userWithImages(input, images));
        pushContextStats(); // 用户消息入历史：进度圈即时刷新
        int rounds = 0;
        int retries = 0;
        int contextRecoveryAttempts = 0;
        int lengthContinuationAttempts = 0;
        int interruptedStreamAttempts = 0;
        int malformedToolRecoveries = 0;
        int prematureStopRecoveries = 0;
        boolean noActionCompressionAttempted = false;
        String lengthContinuationHint = null;
        boolean nextRequestWithoutThinking = false;
        boolean nextRequestForAction = false;
        int repetitionRecoveries = 0; // 本轮总预算；真实工具动作不重置，防止间歇循环无限续接。
        List<StopGate.Receipt> stopReceipts=new ArrayList<StopGate.Receipt>();
        StopGate.Progress stopProgress=new StopGate.Progress();
        final int stopHistoricalEvidenceCount=session.taskState.evidence.size();
        try {
            while (!interrupted) {
                if(contextManager!=null) contextManager.setRequestProfile(session.contextProfile);
                if (contextManager != null) contextManager.setTaskAnchorTokens(TokenCounter.estimate(session.taskState.render(session.todos)));
                if (rounds >= roundLimit) {
                    ui.onWarning("达到工具轮数上限(" + roundLimit + ")，已停止本轮");
                    break;
                }
                int currentTokens = contextManager == null ? 0 : contextManager.estimate(session.messages);
                if (contextManager != null && contextManager.shouldCompress(session.messages)
                        && (lastIneffectiveAutoCompressTokens < 0
                        || currentTokens >= lastIneffectiveAutoCompressTokens + 1024)) {
                    ui.onCompressingChanged(true);
                    try {
                        int beforeTokens = currentTokens;
                        List<Message> beforeMessages = session.messages;
                        ui.onWarning("上下文接近安全上限，正在自动压缩（" + beforeTokens + "/"
                                + contextManager.maxTokens() + " token）");
                        session.messages = contextManager.compress(session.messages, session.taskState.render(session.todos), session.taskState.objective);
                        int afterTokens = contextManager.estimate(session.messages);
                        if (session.messages != beforeMessages) {
                            lastIneffectiveAutoCompressTokens = -1;
                            int pct = (int) (afterTokens * 100
                                    / contextManager.maxTokens());
                            ui.onWarning("自动压缩已完成（" + beforeTokens + " → " + afterTokens
                                    + " token，当前 " + pct + "%）");
                            pushContextStats(); // 压缩完成：进度圈回落
                        } else {
                            lastIneffectiveAutoCompressTokens = beforeTokens;
                            ui.onWarning(contextManager.lastCompressAttempted()
                                    ? "自动压缩本次未成功，将在上下文继续增长后重试"
                                    : "近期消息需要保留，暂时没有可自动压缩的历史");
                        }
                    } finally {
                        ui.onCompressingChanged(false);
                    }
                }
                String system = promptBuilder.build(allSkills) + session.taskState.render(session.todos);
                List<Message> request = new ArrayList<Message>();
                request.add(Message.system(system));
                List<Message> modelMessages = contextManager == null
                        ? session.messages : contextManager.prepareForRequest(session.messages);
                request.addAll(modelMessages);
                if (lengthContinuationHint != null) {
                    // 仅加入本次 API 请求，不写入会话/界面，避免伪造一条用户消息。
                    request.add(Message.user(lengthContinuationHint));
                    lengthContinuationHint = null;
                }
                final boolean requestWithoutThinking = nextRequestWithoutThinking;
                nextRequestWithoutThinking = false;
                final boolean requestForAction = nextRequestForAction;
                nextRequestForAction = false;

                final List<ToolCall>[] toolCalls = new List[1];
                final Usage[] usage = new Usage[1];
                final String[] finish = new String[1];
                final LlmException[] err = new LlmException[1];
                final StringBuilder content = new StringBuilder();
                final StringBuilder thinking = new StringBuilder();
                final boolean[] inRetry = new boolean[1]; // 瞬时错误重试循环进行中（首个流式增量到达即复位指示器）
                final com.minion.core.llm.StreamHandler handler = new com.minion.core.llm.RepetitionGuard(new com.minion.core.llm.StreamHandler() {
                    @Override
                    public void onThinking(String delta) {
                        resetRetryOnFirstDelta();
                        thinking.append(delta);
                        ui.onThinking(delta);
                    }
                    @Override
                    public void onContent(String delta) {
                        resetRetryOnFirstDelta();
                        content.append(delta);
                        ui.onContent(delta);
                    }
                    @Override
                    public void onFinish(String finishReason, Usage u, List<ToolCall> tcs) {
                        resetRetryOnFirstDelta(); // 零增量成功（如纯 tool_calls 回复）兜底复位
                        finish[0] = finishReason;
                        usage[0] = u;
                        toolCalls[0] = tcs;
                        ui.onResponseEnd();
                    }
                    /** 重试成功后的首个流式回调：立即复位指示器（"重试中"文案消失，恢复常规轮换） */
                    void resetRetryOnFirstDelta() {
                        if (inRetry[0]) {
                            inRetry[0] = false;
                            ui.onRetryProgress(RetryProgress.none());
                        }
                    }
                    @Override
                    public void onError(LlmException e) {
                        finish[0] = "error";
                        err[0] = e; // 暂存：检查点统一决定显示错误还是图片降级重试
                    }
                });
                try {
                    streamChat(request, handler, requestWithoutThinking, requestForAction);
                } catch (LlmException e) {
                    if (interrupted) {
                        // 用户主动中断（如 DeepSeekClient.cancel → Canceled）：不重试不打警告，
                        // 已收到的流式内容补入历史（不含 toolCalls——切断的 tool_calls 流不可信，回传会 400）
                        appendPartialAssistant(content, thinking);
                        break;
                    }
                    if (isContextOverflow(e) && noOutputYet(content, thinking)
                            && recoverFromContextOverflow(contextRecoveryAttempts++)) {
                        continue;
                    } else if (isTransientError(e) && noOutputYet(content, thinking)) {
                        // 瞬时错误长重试（内网模型资源差）：固定 5s/次，墙钟总时长 20 分钟（RetryPolicy.transientErrors）；
                        // 覆盖 429/500/502 + 网络超时 + 可恢复网络错误；进度经 onRetryProgress 进左下角指示器，
                        // 成功/首个流式增量静默恢复，超时一次性总结停止。
                        // 零增量闸门：本次请求已吐过正文/思考即不重试——重试复用同一 handler 与累加器，
                        // ChatView 已渲染的半截无法回退，重来必然重复输出
                        int attempts = 0;
                        long retryStart = System.currentTimeMillis(); // 墙钟基准：含每次请求自身耗时
                        LlmException last = e;
                        inRetry[0] = true;
                        while (true) {
                            attempts++;
                            ui.onRetryProgress(RetryProgress.from(attempts, last)); // 尝试前立即更新指示器
                            long delay = retryPolicy.delayMs(attempts);
                            if (!sleepWithInterruptCheck(delay)) break; // 用户中断
                            long elapsed = System.currentTimeMillis() - retryStart;
                            if (retryPolicy.isExhausted(elapsed)) {
                                ui.onError(RetryProgress.tag(last) + " 重试了 " + attempts + " 次，持续 "
                                        + (elapsed / 60000) + " 分钟仍失败，已停止重试");
                                break;
                            }
                            try {
                                streamChat(request, handler, requestWithoutThinking, requestForAction);
                                // 成功后静默恢复（不打扰正文）：首个流式增量/onFinish 已复位指示器，
                                // 若流中断（onError 回调）则落下方 finish=="error" 检查点统一处理
                                break;
                            } catch (LlmException re) {
                                if (interrupted) break;
                                if (!isTransientError(re) || !noOutputYet(content, thinking)) {
                                    ui.onError("请求失败: " + re.getMessage());
                                    break;
                                }
                                last = re; // 仍可重试：指示器标签/错误体随最近一次失败更新
                            }
                        }
                        if (inRetry[0]) { inRetry[0] = false; ui.onRetryProgress(RetryProgress.none()); } // 退出重试态统一复位（幂等）
                        if (interrupted) {
                            appendPartialAssistant(content, thinking);
                            break;
                        }
                        if (finish[0] == null && usage[0] == null) {
                            break; // 未成功（超时/换错已提示），结束本轮
                        }
                        // 重试成功：落入下方正常处理（usage 记录、回复入历史）
                    } else if (e.retryable && retries < 1 && noOutputYet(content, thinking)) {
                        retries++;
                        ui.onWarning("请求失败（" + e.getMessage() + "），自动重试 1 次");
                        // 退避：429 限流 2s，其余（网络/超时）0.5s；立即重试 429 几乎必然再 429
                        Thread.sleep(e.type == LlmException.Type.RATE_LIMIT ? 2000 : 500);
                        continue; // 消息未变，直接重发本轮
                    } else if (noOutputYet(content, thinking) && degradeImagesOnFailure()) {
                        continue; // 带图请求失败：清图降级纯文本重试
                    } else {
                        ui.onError(e.getMessage());
                        break;
                    }
                }

                if (usage[0] != null) session.usage.record(usage[0]);
                if (contextManager != null && usage[0] != null && !usage[0].estimated) {
                    contextManager.observeInputTokens(modelMessages, usage[0].inputTokens);
                    pushContextStats();
                }
                DiagnosticLog.info("agent-response", "finish=" + finish[0]
                        + " localMessages=" + session.messages.size()
                        + " contentChars=" + content.length()
                        + " toolCalls=" + (toolCalls[0] == null ? 0 : toolCalls[0].size()));
                if (com.minion.core.llm.RepetitionGuard.FINISH.equals(finish[0])) {
                    if (interrupted) break;
                    // UI保留收到的全文；重复响应不入模型历史，也不更新任务进度为完成。
                    DiagnosticLog.info("stream-repetition", "scope=main attempt=" + repetitionRecoveries
                            + " contentChars=" + content.length() + " thinkingChars=" + thinking.length());
                    if (repetitionRecoveries >= com.minion.core.llm.RepetitionGuard.MAX_RECOVERIES) {
                        DiagnosticLog.info("agent-exit", "reason=repetition_budget_exhausted");
                        ui.onError("模型流式重复循环恢复3次仍失败，已暂停，任务未完成；请查看日志或切换模型。");
                        break;
                    }
                    repetitionRecoveries++;
                    lengthContinuationHint = com.minion.core.llm.RepetitionGuard.HINT;
                    nextRequestForAction = true;
                    ui.onWarning("检测到单次响应重复工具承诺，已结束该响应，正在工具动作恢复（第 " + repetitionRecoveries + " 次）");
                    persistSession();
                    continue;
                }
                if ("error".equals(finish[0])) {
                    if (err[0] != null && isContextOverflow(err[0])
                            && noOutputYet(content, thinking)
                            && recoverFromContextOverflow(contextRecoveryAttempts++)) {
                        continue;
                    }
                    if (noOutputYet(content, thinking) && degradeImagesOnFailure()) continue; // 同上：onError 回调路径（如 API 400）——闸门在前，degrade 有清图+警告副作用
                    ui.onError(err[0] == null ? "请求失败" : err[0].getMessage());
                    break;
                }

                if ("incomplete".equals(finish[0])) {
                    // 未收到终止信号的工具参数绝不执行，即便它碰巧是合法 JSON。
                    appendPartialAssistant(content, thinking);
                    if (interrupted) break;
                    if (++interruptedStreamAttempts > 3) {
                        ui.onError("模型响应连续 4 次缺少结束信号，本轮未完成；请查看 .minion/logs/minion.log。");
                        break;
                    }
                    lengthContinuationHint = "[传输恢复] 上次响应流在收到完成信号前断开。已显示的正文仅为部分内容，"
                            + "该次工具调用没有执行。请从断点继续，若需工具请重新生成完整调用，不要重复之前已经执行成功的操作。";
                    ui.onWarning("模型响应流提前结束，正在恢复任务（第 " + interruptedStreamAttempts + " 次）");
                    continue;
                }
                if ("stop".equals(finish[0]) && toolCalls[0] != null && !toolCalls[0].isEmpty()) {
                    // 部分兼容网关把工具回合标为 stop；完整参数仍走正常校验和执行。
                    finish[0] = "tool_calls";
                }
                boolean truncatedByLength = "length".equalsIgnoreCase(finish[0]);
                boolean truncatedToolCall = truncatedByLength
                        && toolCalls[0] != null && !toolCalls[0].isEmpty();
                if (toolCalls[0] != null) toolCalls[0] = new ArrayList<ToolCall>(toolCalls[0]);
                int malformedToolCallCount = !truncatedByLength
                        ? removeInvalidToolCalls(toolCalls[0]) : 0;
                boolean malformedToolCall = malformedToolCallCount > 0
                        && (toolCalls[0] == null || toolCalls[0].isEmpty());
                if (truncatedByLength) {
                    // 参数可能只收到半截 JSON；绝不能入历史或执行，否则下一轮会 400，
                    // 更严重时可能执行与模型原意不同的残缺命令。
                    toolCalls[0] = null;
                }
                if (malformedToolCall) toolCalls[0] = null;
                else if (malformedToolCallCount > 0) {
                    ui.onWarning("已丢弃 " + malformedToolCallCount
                            + " 个损坏的工具调用，其余合法调用将继续执行");
                }

                // assistant 回复（含思考与工具调用）入会话历史——reasoningContent 回传硬性要求；
                // 无正文且无工具调用的空回复不入历史（仅思考消息回传会 400）
                boolean hasContent = hasVisibleText(content);
                if (hasContent
                        || (toolCalls[0] != null && !toolCalls[0].isEmpty())) {
                    Message assistantMsg = Message.assistant(
                            hasContent ? content.toString() : null);
                    assistantMsg.reasoningContent = thinking.length() == 0 ? null : thinking.toString();
                    assistantMsg.toolCalls = toolCalls[0];
                    session.messages.add(assistantMsg);
                    if(hasContent) session.taskState.progress(content.toString());
                    pushContextStats(); // 回复入历史：进度增长
                }

                if (interrupted) break;

                if (malformedToolCall) {
                    if (malformedToolRecoveries >= 4) {
                        ui.onError("模型连续 4 次生成损坏的工具参数，已停止重复请求，避免继续消耗上下文。"
                                + "请压缩上下文后重试，或让模型将大文件分块写入。 ");
                        break;
                    }
                    malformedToolRecoveries++;
                    lengthContinuationHint = buildMalformedToolRecoveryHint(malformedToolRecoveries);
                    nextRequestWithoutThinking = true;
                    ui.onWarning("模型工具参数 JSON 损坏，正在要求重新生成（第 "
                            + malformedToolRecoveries + " 次）");
                    persistSession();
                    continue;
                }

                if (truncatedByLength) {
                    if (lengthContinuationAttempts >= 12) {
                        ui.onError("模型连续 12 次达到单次输出上限，已停止自动续接。请提高模型的 maxOutputTokens 或拆分任务。");
                        break;
                    }
                    lengthContinuationAttempts++;
                    lengthContinuationHint = truncatedToolCall
                            ? "[系统续接] 上一次输出因长度上限在工具调用生成过程中被截断，该工具没有执行。请从头重新生成一个完整、合法的工具调用，然后继续原任务；不要重复已经完成的工作。"
                            : "[系统续接] 上一次输出因达到单次输出长度上限而被截断。请紧接已完成的内容继续原任务，不要重复，不要提前总结；需要调用工具时请生成完整合法的工具调用。";
                    // 长度用尽常由思考耗光输出预算引起；恢复轮直接要求动作。
                    nextRequestWithoutThinking = true;
                    ui.onWarning("模型输出达到单次上限，正在自动续接（第 "
                            + lengthContinuationAttempts + " 次）");
                    persistSession();
                    continue;
                }

                // 部分 Qwen 兼容服务会在长思考后返回 finish=stop，但既无正文也无工具调用。
                // 这不是任务完成；reasoning_content 又不能单独写回历史（下轮 API 会 400），
                // 因此用一次性内部指令要求它把思考落实为完整动作。
                if ((toolCalls[0] == null || toolCalls[0].isEmpty())
                        && !hasContent && prematureStopRecoveries < 12) {
                    prematureStopRecoveries++;
                    boolean hadThinking = hasVisibleText(thinking);
                    lengthContinuationHint = buildNoActionRecoveryHint(
                            prematureStopRecoveries, hadThinking);
                    // 只有思考 -> 下一轮关思考；关思考后返回空包 -> 恢复思考。
                    // 某些 Qwen 网关在 enable_thinking=false 时无法生成任何 token，不能持续强关。
                    nextRequestWithoutThinking = hadThinking;
                    // 兼容服务在有效窗口耗尽时也可能连续返回“只有思考”，不能只等空包才压缩。
                    // 小会话的偶发思考不触发强制压缩；空包保持原有恢复行为。
                    if (prematureStopRecoveries >= 2
                            && !noActionCompressionAttempted && contextManager != null
                            && (!hadThinking || contextManager.estimate(session.messages) >= 4096)) {
                        noActionCompressionAttempted = true;
                        if (recoverFromSilentResponses()) {
                            prematureStopRecoveries = 0;
                            nextRequestWithoutThinking = false;
                            continue;
                        }
                    }
                    ui.onWarning("模型未产生可执行动作，正在自动继续任务（第 "
                            + prematureStopRecoveries + " 次）");
                    continue;
                }
                if ((toolCalls[0] == null || toolCalls[0].isEmpty()) && !hasContent) {
                    ui.onError("模型连续 12 次只返回思考或空响应，已停止自动恢复。"
                            + "这通常表示模型服务未按要求生成正文或工具调用，请稍后重试或切换模型。 ");
                    break;
                }

                // 每个拟结束正文统一核验；字数、词汇和完成标记不能直接放行。
                if (toolCalls[0] == null || toolCalls[0].isEmpty()) {
                    if (hasPendingSupplements()) {
                        drainSupplements();
                        lengthContinuationHint="[系统结束检查] 有新用户补充，旧交付未通过验收。先处理最新补充，不得执行旧目标。";
                        continue;
                    }
                    StopGate.Snapshot snapshot=stopSnapshot(content.toString(),stopReceipts,stopHistoricalEvidenceCount,reportFromIndex);
                    ui.onWarning("正在核验当前任务是否完成（独立只读检查）");
                    StopGate.Decision decision=StopGate.evaluate(llm,snapshot,session.usage::record,()->interrupted||Thread.currentThread().isInterrupted());
                    if(interrupted || Thread.currentThread().isInterrupted()) break;
                    if (hasPendingSupplements() || !snapshot.sameFacts(stopSnapshot(content.toString(),stopReceipts,stopHistoricalEvidenceCount,reportFromIndex))) {
                        drainSupplements();
                        lengthContinuationHint="[系统结束检查] 核验期间目标/补充/证据发生变化，旧审批已作废。重新核对最新要求。";
                        continue;
                    }
                    session.taskState.recordStop(decision);
                    DiagnosticLog.info("stop-gate","scope=root status="+decision.status+" reason="+decision.reason);
                    if("waiting_user".equals(decision.status)||"unknown".equals(decision.status)) {
                        lastTurnSucceeded=false;taskCompleted=false;
                        ui.onWarning("任务尚未完成/交付未确认，"+("waiting_user".equals(decision.status)?"等待用户补充或授权":"结束核验无法确认")
                                +"："+decision.reason+"；已保留断点，不自动发送后续队列。");
                        break;
                    }
                    if("continue".equals(decision.status)) {
                        int denied=stopProgress.deny();decision.noProgress=denied;
                        int limit=hasTaskCompleteMarker(content.toString())?StopGate.COMPLETION_CONFLICT_LIMIT:StopGate.NO_PROGRESS_LIMIT;
                        if(denied>limit) {
                            lastTurnSucceeded=false;taskCompleted=false;
                            ui.onError("任务尚未完成/交付尚未确认："+decision.reason+"。无实质进展的收尾预算已用尽，停止自动续接；断点已保留，未标记为完成。");
                            break;
                        }
                        lengthContinuationHint="[系统统一结束检查] 当前目标尚未交付："+decision.reason+"。下一步："+decision.nextStep
                                +("task".equals(decision.kind)?"。立即调用现有授权工具落实缺项；已完成步骤不能重跑，不得扩大用户范围。"
                                +"子Agent需读取证据后AgentControl review；实际完成待办后TodoWrite；合法更新文件实际复核后refresh_checkpoint(note=复核及更新原因)或完整checkpoint。"
                                +"缺少资料/授权调用AskUserQuestion。status只是查询，完成标记不是完成证据。"
                                :"。补足用户要求的回答，已交付的段落不要重写；不能为了结束检查增加文件操作或新的工作范围，缺少必要信息请明确询问用户。");
                        nextRequestForAction=denied>=ACTION_RECOVERY_AFTER&&"task".equals(decision.kind);
                        nextRequestWithoutThinking=denied>=ACTION_RECOVERY_AFTER&&"answer".equals(decision.kind);
                        ui.onWarning("阶段性回复/最终交付尚未确认，统一结束检查要求继续（无进展 "+denied+" 次）："+decision.reason);
                        persistSession();
                        continue;
                    }
                    synchronized(session.pendingSupplements) {
                        if(!session.pendingSupplements.isEmpty()) {
                            lengthContinuationHint="[系统结束检查] 用户新增补充使完成审批失效，先处理补充。";
                        } else {
                            taskCompleted="task".equals(decision.kind)||hasTaskCompleteMarker(content.toString());
                            lastTurnSucceeded=true;
                            session.taskState.progress(content.toString());
                        }
                    }
                    if(!lastTurnSucceeded) {drainSupplements();continue;}
                    DiagnosticLog.info("agent-exit","reason=stop_gate_approved finish="+finish[0]+" toolRounds="+rounds);
                    break;
                }
                // 只统计连续无动作：一旦模型成功产生工具调用，恢复计数归零。
                prematureStopRecoveries = 0;
                lengthContinuationAttempts = 0;
                interruptedStreamAttempts = 0;
                malformedToolRecoveries = 0;
                rounds++;

                List<ToolCall> calls = toolCalls[0];
                List<Future<ToolResult>> futures = new ArrayList<Future<ToolResult>>();
                for (ToolCall call : calls) {
                    if (interrupted) break; // 提交间隙的中断兜底（T14 IM-2 残留 race）
                    futures.add(pool.submit(() -> runOneTool(call)));
                }
                synchronized (inFlight) {
                    inFlight.addAll(futures);
                }
                try {
                    // 循环上界用 futures.size()：提交阶段被打断时未提交的调用没有对应 future
                    for (int i = 0; i < futures.size(); i++) {
                        ToolResult result;
                        try {
                            result = futures.get(i).get();
                        } catch (ExecutionException e) {
                            result = ToolResult.error("工具执行异常: " + e.getMessage());
                        } catch (CancellationException e) {
                            break; // 已被 interrupt() 取消，本轮剩余工具结果丢弃
                        }
                        if (result == null) result = ToolResult.error("工具执行失败");
                        if (result.ok) {
                            consecutiveToolErrors = 0;
                        } else {
                            consecutiveToolErrors++;
                        }
                        session.messages.add(Message.toolResult(
                                calls.get(i).id, calls.get(i).name,
                                new ToolOutputStore(workspace.workDir(), session.id).forContext(
                                        ToolResult.outputForApi(result.output, emptyOutputPlaceholder),calls.get(i))));
                        reportTools.add(calls.get(i).name + " — " + (result.ok ? "成功" : "失败"));
                        ToolCall executed=calls.get(i);
                        StopGate.Receipt receipt=new StopGate.Receipt("tool:"+executed.id+"@"+stopReceipts.size(),executed.name,executed.arguments,result);
                        stopReceipts.add(receipt);stopProgress.observe(receipt);
                        ui.onToolResult(calls.get(i).name, result);
                    }
                } finally {
                    synchronized (inFlight) {
                        inFlight.clear();
                    }
                }
                pushContextStats(); // 工具结果入历史：进度增长
                // 运行中补充注入检查点：工具结果全部入历史后、下一轮请求前；
                // AskUserQuestion 挂起时补充等回答的 TOOL 消息入历史后同请求发出；
                // interrupted 不注入——半轮 tool_call 未配对时插入 user 消息会破坏契约（400）
                if (!interrupted) {
                    drainSupplements();
                    drainPendingSkillLoads();
                }
                // 卡住止损：连续失败达阈值时注入系统提醒（user 消息而非 system——
                // OpenAI 兼容 API 只接受首条 system，插在对话中间会 400），
                // 模型下轮应输出提问文本而非再调工具；注入后计数重置，roundLimit 为最外层兜底
                if (consecutiveToolErrors >= STUCK_THRESHOLD) {
                    String hint = "[系统提醒] 你已连续 " + consecutiveToolErrors
                            + " 次工具调用失败。请停止调用工具，向用户说明已尝试的方案、失败原因，"
                            + "并列出完成任务还需要用户补充的信息或需要用户选择的方案。";
                    session.messages.add(Message.user(hint));
                    ui.onWarning("工具连续失败 " + consecutiveToolErrors
                            + " 次，已提醒模型停止尝试并请求用户补充信息");
                    consecutiveToolErrors = 0;
                }
                // 工具结果已入历史，每轮落盘一次（含中断取消提前退出的情况）
                persistSession();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ui.onWarning("已中断");
        } catch (Exception e) {
            ui.onError("异常: " + e.getMessage());
        }
        if (interrupted) {
            lastTurnSucceeded = false;
            DiagnosticLog.info("agent-exit", "reason=explicit_interrupt task=" + session.taskState.id);
            ui.onWarning("本轮已被中断");
            scrubHalfTurn(); // 兜底：任何中断退出路径（含工具阶段）的 toolCalls 半轮残留清洗
        }
        // 所有退出路径的兜底落盘：正常结束 / 轮数上限 / 错误 / 中断 / 异常
        if (taskCompleted && lastTurnSucceeded && !interrupted) {
            appendCompletionTime(java.time.Clock.systemUTC());
        }
        session.taskState.finish(lastTurnSucceeded);
        persistSession();
        if (!reportTools.isEmpty()) {
            try {
                java.nio.file.Path report=TaskReportWriter.write(workspace,session,reportFromIndex,
                        reportTools,taskCompleted && lastTurnSucceeded,System.currentTimeMillis()-start);
                ui.onWarning("本轮执行记录已保存: "+report);
                ui.onArtifactCreated(report.toAbsolutePath().normalize().toString());
            } catch (Exception e) {
                ui.onWarning("生成任务执行记录失败: "+e.getMessage());
            }
        }
        // 每轮结束统计行（置于 scrubHalfTurn/persistSession 之后：中断路径的 ctx 估算是清洗半轮后的准确值）
        long elapsed = System.currentTimeMillis() - start;
        int currentCtx = contextManager != null
                ? contextManager.estimate(session.messages)
                : TokenCounter.estimateMessages(session.messages);
        int maxCtx = contextManager != null ? contextManager.maxTokens() : 0;
        ui.onStatsLine(StatsLine.format(session.usage, elapsed, currentCtx, maxCtx));
        ui.onContextStats(currentCtx, maxCtx); // 轮次结束兜底推送（含中断/异常路径）
    }

    /** 瞬时错误（429 限流 / 500 服务端报错 / 502 网关报错 / 网络超时 / 可恢复网络错误）：可进长重试。
     *  网络类靠 retryable 区分永久性故障——DNS 解析失败在 DeepSeekClient 置 retryable=false，此处不放行。
     *  与 SubAgentLoop 同名方法保持字面一致（两处重复，本次不抽公共组件） */
    private boolean isTransientError(LlmException e) {
        return e.type == LlmException.Type.RATE_LIMIT
                || e.type == LlmException.Type.TIMEOUT
                || (e.type == LlmException.Type.NETWORK && e.retryable)
                || e.httpCode == 500 || e.httpCode == 502;
    }

    private void streamChat(List<Message> request, com.minion.core.llm.StreamHandler handler,
                            boolean withoutThinking, boolean forAction) throws LlmException {
        List<JsonObject> schemas=registry.schemas();
        budgetText=com.minion.core.context.ContextBudget.describe(request,schemas,
                contextManager==null?0:contextManager.configuredMaxTokens(),contextManager==null?0:contextManager.maxTokens(),
                contextManager==null?0:contextManager.autoCompressAtTokens(),session.contextProfile);
        if (forAction && !schemas.isEmpty()) {
            ui.onWarning("模型连续只返回执行计划，正在切换工具动作恢复；工具仍按原权限确认执行");
            DiagnosticLog.info("agent-continue", "reason=action_protocol_recovery");
            llm.streamChatForAction(request, schemas, handler);
        } else if (withoutThinking) llm.streamChatWithoutThinking(request, schemas, handler);
        else llm.streamChat(request, schemas, handler);
    }

    /** 旧措辞诊断与历史测试兼容；生产完成放行不调用本方法，统一由StopGate决定。 */
    static boolean looksLikeInterimAnswer(String text) {
        if (text == null) return false;
        String s = text.trim().replace('\r', ' ').replace('\n', ' ');
        if (s.isEmpty() || hasTaskCompleteMarker(s)) return false;
        if (hasToolActionPromise(s)) return true;
        // 长篇分析后以“让我再查验…”结尾仍是进度回复。只检查末尾，避免把正文中
        // 提到的计划误当成未完成；原有短回复规则继续保持保守。
        if (s.length() > 240) {
            String tail = s.substring(Math.max(0, s.length() - 200));
            return endsWithActionPlan(tail);
        }
        if (endsWithActionPlan(s)) return true;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("我先") || lower.startsWith("先看")
                || lower.startsWith("接下来") || lower.startsWith("下一步")
                || lower.startsWith("正在") || lower.startsWith("准备")
                || lower.startsWith("好的，继续") || lower.startsWith("好，继续")
                || lower.matches("^继续[。！!，, ]*.*(?:现在|接下来|下一步)(?:要|将|先|开始|继续|立即)?(?:补齐|检查|核对|验证|分析|处理|执行|生成|读取|查看).*")
                || lower.matches("^(?:好的?，?继续[。！!，, ]*)?(?:现在|接下来|下一步)(?:要|将|先|开始|继续|立即)?(?:补齐|检查|核对|验证|分析|处理|执行|生成|读取|查看).*")
                || lower.matches(".*(?:我(?:还)?需要先|我接下来(?:会|将)|随后我会|然后我会)(?:确认|检查|核对|验证|分析|处理|执行|生成|读取|查看|补充|继续).*")
                || lower.matches("^(?:let me|i will|i'll|next,? i).*(?:check|verify|analy[sz]e|continue|run|read|inspect).*")
                || lower.matches(".*(?:找到|发现|检测到).{0,40}(?:个)?(?:文件|表格|工作簿|数据源)[了。！!]*$")
                || lower.endsWith("继续处理") || lower.endsWith("继续分析");
    }

    /** 只识别末尾明确的下一步意图；不因已交付正文中提及过去计划而续接。 */
    private static boolean endsWithActionPlan(String text) {
        if (TERMINAL_COMPARISON_PLAN.matcher(text).find()) return true;
        return text.matches(".*(?:让我(?:再|先)?|我(?:再|先|将|会)|继续(?:推进|执行|处理|派发)[。！!，, ]*(?:先|再)?"
                + "|接下来(?:我)?(?:会|要|将)?(?:先|再)?|下一步(?:我)?(?:会|要|将)?(?:先|再)?"
                + "|现在(?:我)?(?:会|要|将)?(?:先|再)?|还需(?:要)?|需要继续)"
                + "(?:查验|检查|核对|验证|分析|处理|执行|生成|读取|查看|补充|复核|确认|排查|扫描|派发|汇总)"
                + "[^。！？!?]*[：:，,。.!！]*$");
    }

    /** 工具调用的口头承诺不是执行事实；仅识别靠近末尾的主动意图。 */
    private static boolean hasToolActionPromise(String text) {
        String tail = text.substring(Math.max(0, text.length() - 300));
        return tail.matches("(?i).*(?:我(?:现在|立即|将|会)(?:实际)?(?:调用|使用)|我实际(?:调用|使用)|先(?:调用|使用)|立即(?:调用|使用)|让我(?:先)?(?:调用|使用))"
                + "(?:bash|python|read|write|edit|task|todowrite|agentcontrol|工具)[^。！？!?]*"
                + "(?:[。.!！][ ]*(?:执行工具调用|现在|执行|good|execute)[。.!！]*)*[。.!！]*$");
    }

    /** 旧措辞诊断与测试兼容；生产waiting_user由统一结束核验决定。 */
    static boolean looksLikeUserBlock(String text) {
        if (text == null || hasTaskCompleteMarker(text)) return false;
        String s = text.trim().replace('\r',' ').replace('\n',' ');
        return s.matches(".*(?:缺少|缺失|无法访问|找不到|尚未提供|需要您|需要你|等待您|等待你|等待用户)[^。！？!?]*"
                + "(?:请(?:您|你)?(?:先)?(?:提供|上传|补充|选择|确认|授权)|才能继续|后(?:才能|再)继续|确认|授权)[^。！？!?]*[。.!！?？]*$")
                || s.matches(".*(?:请(?:您|你)?(?:先)?(?:提供|上传|补充|选择|确认|授权))[^。！？!?]*"
                + "(?:才能继续|后(?:才能|再)继续|等待(?:您|你|用户)(?:回答|确认|授权))[^。！？!?]*[。.!！?？]*$")
                || s.matches("(?i).*(?:waiting for (?:your|user)|need your) (?:input|confirmation|approval|permission).*" );
    }

    private boolean hasStructuredWork() {
        synchronized(session.todos) { if (!session.todos.items.isEmpty()) return true; }
        return !session.taskState.childSnapshot().isEmpty();
    }

    private StopGate.Snapshot stopSnapshot(String candidate,List<StopGate.Receipt> current,int historicalCount,int fromIndex) {
        JsonObject facts=new JsonObject();List<StopGate.Receipt> receipts=new ArrayList<StopGate.Receipt>();
        List<FileEvidence> files=new ArrayList<FileEvidence>();
        String id,goal;List<String> guidance;
        synchronized(session.taskState) {
            id=session.taskState.id;goal=session.taskState.objective;guidance=new ArrayList<String>(session.taskState.guidance);
            facts.add("referenceContext",new com.google.gson.Gson().toJsonTree(session.taskState.referenceContext));
            facts.addProperty("referenceContextTruncated",session.taskState.referenceContextTruncated);
            com.google.gson.JsonArray children=new com.google.gson.JsonArray();
            int childCount=0,reviewedCount=0;
            for(TaskState.Child child:session.taskState.childSnapshot()) {
                childCount++;if("reviewed".equals(child.status)){reviewedCount++;continue;}
                JsonObject c=new JsonObject();c.addProperty("id",child.id);c.addProperty("status",child.status);
                c.addProperty("assignment",child.assignment==null?"":child.assignment.substring(0,Math.min(300,child.assignment.length())));children.add(c);
            }
            facts.add("pendingChildren",children);facts.addProperty("childCount",childCount);facts.addProperty("reviewedChildren",reviewedCount);
            int historicalShown=0;
            for(int i=session.taskState.evidence.size()-1;i>=0;i--) {
                TaskState.Evidence e=session.taskState.evidence.get(i);if(!id.equals(e.taskId)||!"root".equals(e.scope))continue;
                if(i<historicalCount&&historicalShown++<24)receipts.add(0,new StopGate.Receipt("evidence:"+i,e.tool,"",e.success?ToolResult.success(e.result):ToolResult.error(e.result)));
                if(e.file!=null)files.add(e.file);
            }
            java.util.Collections.reverse(files);
            files.addAll(session.taskState.checkpointFiles);
        }
        // 文件IO不占用任务台账锁，避免大文件核验阻塞JavaFX任务面板/子Agent进度。
        facts.addProperty("checkpoint",authorizedCheckpointStatus());
        facts.add("knownFiles",StopGate.fileFacts(files,this::evidencePathAllowed));
        facts.addProperty("todos",session.todos.render());facts.addProperty("toolsUsed",!receipts.isEmpty()||!current.isEmpty());
        facts.addProperty("structured",hasStructuredWork());
        com.google.gson.JsonArray priorReplies=new com.google.gson.JsonArray();
        for(int i=session.messages.size()-2;i>=Math.max(0,fromIndex)&&priorReplies.size()<4;i--) {
            Message m=session.messages.get(i);if(m.role!=Message.Role.ASSISTANT||m.content==null||(m.toolCalls!=null&&!m.toolCalls.isEmpty()))continue;
            JsonObject part=new JsonObject();part.addProperty("text",m.content.substring(0,Math.min(2000,m.content.length())));
            part.addProperty("truncated",m.content.length()>2000);priorReplies.add(part);
        }
        facts.add("earlierDeliveredReplies",priorReplies);
        synchronized(session.pendingSupplements){facts.add("pendingSupplements",new com.google.gson.Gson().toJsonTree(session.pendingSupplements));}
        receipts.addAll(current);
        return new StopGate.Snapshot(id,goal,guidance,facts,receipts,candidate,pendingExecutionReason());
    }

    private boolean hasPendingSupplements() {
        synchronized(session.pendingSupplements){return !session.pendingSupplements.isEmpty();}
    }

    /** 仅当前任务的结构化事实；旧任务子Agent不能阻挡新问题。 */
    private String pendingExecutionReason() {
        List<String> reasons = new ArrayList<String>();
        if (hasPendingTodos()) reasons.add("主任务待办尚未完成");
        for (TaskState.Child child : session.taskState.childSnapshot()) {
            if (!"reviewed".equals(child.status)) {
                reasons.add("子Agent尚未核对或仍在运行/失败（" + child.id + "，" + child.status + "）");
                break;
            }
        }
        List<FileEvidence> files;synchronized(session.taskState){files=new ArrayList<FileEvidence>(session.taskState.checkpointFiles);}
        for(FileEvidence file:files) {
            try{if(!evidencePathAllowed(java.nio.file.Paths.get(file.path))){reasons.add("断点证据文件已超出当前权限");break;}}
            catch(Exception e){reasons.add("断点证据路径无法核验");break;}
            if(!"unchanged".equals(file.verify())){reasons.add("断点证据文件已变更或缺失");break;}
        }
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    public String authorizedCheckpointStatus() {
        StringBuilder b=new StringBuilder();List<FileEvidence> files;
        synchronized(session.taskState){b.append("处理范围: ").append(session.taskState.processedRange).append("\n下一步: ").append(session.taskState.nextStep);files=new ArrayList<FileEvidence>(session.taskState.checkpointFiles);}
        for(FileEvidence file:files) {
            String status="permission_denied";
            try{if(evidencePathAllowed(java.nio.file.Paths.get(file.path)))status=file.verify();}catch(Exception ignored){}
            b.append("\n文件 [").append(status).append("] ").append(file.path).append(" | ").append(file.size).append(" bytes | SHA256 ").append(file.sha256);
        }
        return b.toString();
    }

    /** 旧截断文本诊断与测试兼容；生产完成放行不调用本方法。 */
    static boolean looksLikeInterruptedReply(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty() || hasTaskCompleteMarker(s)) return false;
        if (s.matches("(?s).*(?:但|但是|因为|由于|并且|以及|不过|然而|而|所以|因此|:|：|,|，|;|；)$")
                || s.matches("(?is).*\\b(?:then|but|because|and|however)$")) return true;
        if (countChar(s, '（') > countChar(s, '）')
                || countChar(s, '(') > countChar(s, ')')) return true;
        return s.split("```", -1).length % 2 == 0;
    }

    private static int countChar(String s, char target) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == target) count++;
        return count;
    }

    /** OpenAI 兼容服务对上下文超限没有统一 code，只能兼容常见中英文错误正文。 */
    private boolean isContextOverflow(LlmException e) {
        if (e == null || (e.httpCode != 400 && e.httpCode != 413)) return false;
        String text = ((e.getMessage() == null ? "" : e.getMessage()) + " "
                + (e.body == null ? "" : e.body)).toLowerCase(java.util.Locale.ROOT);
        return text.contains("context length") || text.contains("context window")
                || text.contains("maximum context") || text.contains("max context")
                || text.contains("too many tokens") || text.contains("token limit")
                || text.contains("上下文") && (text.contains("超") || text.contains("长度"));
    }

    /** 超限后压缩并让 while 循环重建请求；最多两次，避免错误配置导致无限循环。 */
    private boolean recoverFromContextOverflow(int attempt) {
        if (contextManager == null || attempt >= 2) return false;
        int before = contextManager.estimate(session.messages);
        session.observedContextLimit = contextManager.learnFromContextFailure(before);
        ui.onCompressingChanged(true);
        try {
            ui.onWarning("模型返回上下文超限，正在强制压缩并自动继续任务（第 "
                    + (attempt + 1) + " 次）");
            List<Message> old = session.messages;
            session.messages = contextManager.compressForRecovery(session.messages, session.taskState.render(session.todos), session.taskState.objective);
            int after = contextManager.estimate(session.messages);
            if (session.messages == old || after >= before) {
                ui.onWarning("强制压缩没有释放足够上下文，无法自动恢复");
                return false;
            }
            ui.onWarning("强制压缩完成（" + before + " → " + after + " token），正在继续执行");
            pushContextStats();
            persistSession();
            return true;
        } finally {
            ui.onCompressingChanged(false);
        }
    }

    /** 零增量闸门：本次请求是否还没吐出任何可见内容。tool_calls 不参与判定——
     *  它累积在 DeepSeekClient 方法内的局部变量里，onFinish 前既不对外暴露也不渲染，重来无重复显示风险 */
    private boolean noOutputYet(StringBuilder content, StringBuilder thinking) {
        return !hasVisibleText(content) && !hasVisibleText(thinking);
    }

    /** 服务端不报上下文超限、只静默返回空包时的恢复路径。配置的 maxContextTokens
     *  可能高于真实模型窗口，不能再依赖百分比阈值。 */
    private boolean recoverFromSilentResponses() {
        int before = contextManager.estimate(session.messages);
        session.observedContextLimit = contextManager.learnFromContextFailure(before);
        ui.onCompressingChanged(true);
        try {
            ui.onWarning("模型连续返回空响应，正在强制压缩上下文并重建请求（当前 "
                    + before + " token）");
            List<Message> old = session.messages;
            session.messages = contextManager.compressForRecovery(session.messages, session.taskState.render(session.todos), session.taskState.objective);
            int after = contextManager.estimate(session.messages);
            if (session.messages == old || after >= before) {
                ui.onWarning("空响应恢复压缩未成功，将切换思考策略继续尝试");
                return false;
            }
            ui.onWarning("空响应恢复压缩完成（" + before + " → " + after
                    + " token），正在继续原任务");
            pushContextStats();
            persistSession();
            return true;
        } finally {
            ui.onCompressingChanged(false);
        }
    }

    /** 部分兼容服务会在正文通道只返回空格、换行、BOM 或零宽空格。 */
    static boolean hasVisibleText(CharSequence text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u200B' && c != '\uFEFF') return true;
        }
        return false;
    }

    /** 程序生成完成时间，不让模型猜测；历史中保留末尾完成标记。 */
    private void appendCompletionTime(java.time.Clock clock) {
        if (session.messages.isEmpty()) return;
        Message reply = session.messages.get(session.messages.size() - 1);
        if (reply.role != Message.Role.ASSISTANT || reply.content==null) return;
        String line = completionTimeLine(clock);
        String body = reply.content.trim();
        if(hasTaskCompleteMarker(body)) body = body.substring(0, body.length() - TASK_COMPLETE_MARKER.length()).trim();
        reply.content = body + "\n\n" + line + "\n\n" + TASK_COMPLETE_MARKER;
        session.taskState.progress(reply.content);
        ui.onContent("\n\n" + line);
        ui.onResponseEnd();
    }

    static String completionTimeLine(java.time.Clock clock) {
        return "完成时间：" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai")).format(clock.instant()) + "（北京时间）";
    }

    static boolean hasTaskCompleteMarker(String text) {
        return text != null && text.trim().endsWith(TASK_COMPLETE_MARKER);
    }

    private boolean hasPendingTodos() {
        synchronized(session.todos) {
            for (TodoList.TodoItem item : session.todos.items) if (!item.done) return true;
        }
        return false;
    }

    /** Qwen 部署有时忽略 enable_thinking=false；/no_think 同时作用于聊天模板。
     *  连续失败后升级措辞，防止模型反复复述“我要继续/我要输出报告”。 */
    static String buildNoActionRecoveryHint(int attempt, boolean hadThinking) {
        String prefix = hadThinking ? "/no_think\n[系统完成性检查] "
                : "[系统完成性检查] 上一轮关闭思考后模型返回了空响应，本轮允许必要的简短思考，但必须产生正文或工具调用。";
        if (attempt >= 3) {
            return prefix + "这是第 " + attempt + " 次纠偏。禁止解释计划、禁止复述任务、禁止输出思考过程。"
                    + "你的下一条响应只允许是一个可执行的完整工具调用，或者直接交付完整最终结果；"
                    + "若已有分析结果，立即从尚未输出的位置续写正文。";
        }
        return prefix + (hadThinking
                ? "你刚才只生成了思考过程，没有给出正文或工具调用，原始任务尚未完成。"
                : "上一次模型响应没有可见正文或工具调用，原始任务尚未完成。")
                + "不要说明接下来准备做什么；立即生成下一步完整工具调用，"
                + "或在任务确已完成时直接给出包含实际结果的最终答复。";
    }

    static String buildMalformedToolRecoveryHint(int attempt) {
        return "/no_think\n[系统工具调用修复] 第 " + attempt
                + " 次：上一次 arguments 因过长或截断而不是合法 JSON，该调用未保存、未执行。"
                + "禁止原样重试超长调用。每次 arguments 总长度必须尽量小，文件 content 每块不超过1000字符；"
                + "写大文件时先调用 Write(mode=overwrite) 写首块，再多次调用 Write(mode=append) 追加后续块。"
                + "若只需修改局部，优先使用 Edit，不能把整份文件重新放进参数。"
                + "每轮严格只生成一个工具调用；Edit 每次只能修改一处，成功后下一轮再修改下一处。"
                + "现在只生成第一个短小、完整的工具调用。";
    }

    /** 可中断等待：100ms 小片轮询 interrupted 标志（interrupt() 只设标志不中断线程，
     *  直接 sleep 无法及时响应停止；返回 false 表示用户已中断） */
    private boolean sleepWithInterruptCheck(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (interrupted) return false;
            Thread.sleep(Math.min(100, end - System.currentTimeMillis()));
        }
        return !interrupted;
    }

    /** 失败降级：本次请求含带图消息（历史或挂起补充）且请求失败时，清除全部图片以纯文本重发。
     *  根因修复：图片一旦入历史，模型不支持视觉时（如 DeepSeek 对 image_url 报 400）每次请求
     *  都会重新失败，会话永久卡死——失败即降级，历史图片只清一次，下轮请求不再触发。
     *  返回 true=已降级可重发；false=无图可降，按原失败路径退出。 */
    private boolean degradeImagesOnFailure() {
        boolean hasImages = false;
        for (Message m : session.messages) {
            if (m.images != null && !m.images.isEmpty()) {
                hasImages = true;
                break;
            }
        }
        if (!hasImages) return false;
        for (Message m : session.messages) {
            if (m.images != null) m.images = null;
        }
        // 与 offerSupplement 同锁：FX 线程可能并发入队补充，防止遍历与写入竞争
        synchronized (session.pendingSupplements) {
            for (List<ImagePart> imgs : session.pendingSupplementImages) {
                if (imgs != null) imgs.clear(); // 文本保留，仅弃图
            }
        }
        ui.onWarning("当前模型不支持图片，已自动移除图片并以纯文本重试");
        return true;
    }

    /** 中断时把已收到的流式内容补进历史；不含 toolCalls（切断的 tool_calls 流不可信）。
     *  正文未到达时（仅思考）不入历史：仅 reasoning_content 无 content/tool_calls 的
     *  assistant 消息回传会 400（DeepSeek 思考模式硬性要求）。 */
    private void appendPartialAssistant(StringBuilder content, StringBuilder thinking) {
        if (!hasVisibleText(content)) return;
        Message assistantMsg = Message.assistant(content.toString());
        assistantMsg.reasoningContent = thinking.length() == 0 ? null : thinking.toString();
        session.messages.add(assistantMsg);
    }

    private ToolResult runOneTool(ToolCall call) throws Exception {
        long started = System.nanoTime();
        try {
            Tool tool = registry.get(call.name);
            if (tool == null) {
                ToolResult result = ToolResult.error("工具不存在或已停用: " + call.name);
                DiagnosticLog.tool(call.name, elapsedMs(started), false, result.output);
                return result;
            }
            JsonObject args;
            try {
                args = ToolArguments.parseObject(call.arguments);
            } catch (Exception e) {
                ToolResult result = ToolResult.error("工具参数 JSON 解析失败: " + e.getMessage()
                        + "。请仅返回符合 schema 的 JSON 对象后重试，不要使用 Markdown 代码围栏");
                DiagnosticLog.tool(call.name, elapsedMs(started), false, result.output);
                return result;
            }
            if (!confirmGate.check(tool, args)) {
                String detail = args.has("command") ? args.get("command").getAsString()
                        : (args.has("path") ? args.get("path").getAsString() : args.toString());
                ToolResult result = ToolResult.error("用户拒绝了该操作（" + call.name + " → " + detail + "），请调整方案");
                DiagnosticLog.tool(call.name, elapsedMs(started), false, result.output);
                return result;
            }
            ui.onToolCall(call.name, args);
            ToolResult result;
            try {
                result = executionGuard.execute(tool,args);
            } catch (Exception e) {
                result = ToolResult.error("工具执行异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "。请读取最新状态并调整参数后重试");
            }
            DiagnosticLog.tool(call.name, elapsedMs(started), result.ok, result.output);
            FileEvidence file=null;
            if(result.ok&&args.has("path")&&java.util.Arrays.asList("Read","Write","Edit").contains(call.name)) {
                try { file=FileEvidence.capture(com.minion.core.tools.PathsGuard.resolve(workspace.cwd().toString(),args.get("path").getAsString())); }
                catch(Exception ignored) { }
            }
            if(!"task".equals(call.name)) session.taskState.recordEvidence("root",call.name,result.ok,result.output,file);
            return result;
        } catch (RuntimeException e) {
            // 防御：参数类型非法等 unchecked 异常不得穿透执行线程
            ToolResult result = ToolResult.error("工具执行异常: " + e.getMessage());
            DiagnosticLog.tool(call.name, elapsedMs(started), false, result.output);
            return result;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /** 派发子 agent（Task 15 由 TaskTool 调用） */
    public String runSubAgent(JsonObject args) {
        if (subAgentRunner == null) {
            return "子 agent 不可用";
        }
        return subAgentRunner.apply(args);
    }
}
