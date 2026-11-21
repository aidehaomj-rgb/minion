package com.minion.gui.session;

import com.google.gson.Gson;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.AgentRuntime;
import com.minion.core.agent.TaskState;
import com.minion.core.agent.RetryProgress;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.agent.TitleGenerator;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.config.WorkspacePaths;
import com.minion.core.context.ContextManager;
import com.minion.core.context.TokenCounter;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.llm.DeepSeekClient;
import com.minion.core.llm.ImagePart;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpServer;
import com.minion.core.mcp.McpToolInfo;
import com.minion.core.security.SecretStore;
import com.minion.core.tools.mcp.McpProxyTool;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillSet;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.BashTool;
import com.minion.core.tools.EditTool;
import com.minion.core.tools.GlobTool;
import com.minion.core.tools.GrepTool;
import com.minion.core.tools.ReadTool;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.WebFetchTool;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.WriteTool;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.plugin.ToolPlugin;
import com.minion.core.tools.plugin.ToolPluginManager;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.checkpoint.CheckpointTool;
import com.minion.core.tools.data.ExcelTool;
import com.minion.core.tools.data.DatabaseTool;
import com.minion.core.tools.data.DocumentTool;
import com.minion.core.tools.data.TextProcessTool;
import com.minion.core.tools.data.SqliteTool;
import com.minion.core.tools.dev.BuildTool;
import com.minion.core.tools.dev.FilesTool;
import com.minion.core.tools.dev.GitTool;
import com.minion.core.tools.dev.ProcessTool;
import com.minion.core.tools.dev.ProjectTool;
import com.minion.core.tools.knowledge.KnowledgeTool;
import com.minion.core.tools.context.ContextTool;
import com.minion.core.tools.context.MemoryTool;
import com.minion.core.tools.python.PythonRuntime;
import com.minion.core.tools.python.PythonTool;
import com.minion.core.tools.system.DiagnosticsTool;
import com.minion.core.tools.system.LogsTool;
import com.minion.core.tools.system.OpenUrlTool;
import com.minion.core.tools.system.UpdateTool;
import com.minion.core.tools.security.PermissionTool;
import com.minion.core.tools.security.SecretsTool;
import com.minion.core.tools.skills.SkillAdminTool;
import com.minion.gui.command.CommandDispatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 会话外壳：每会话一个 AgentLoop + 独占工作线程（真并行）；
 * 每工作空间一套上下文（Workspace/SessionStore/ConfirmGate 空间级共享，
 * ToolRegistry 每会话独立——AgentLoop 构造按名注册 TaskTool 绑定本会话 loop）；
 * 切换不打断后台运行，EventList 事件缓冲由 UI 重放。
 */
public class SessionManager {

    public interface Listener {
        void onSessionTitleChanged(SessionHandle h);
        void onSessionRunningChanged(SessionHandle h, boolean running);
        void onSessionActivated(SessionHandle h);
        /** 已激活会话再次被点击，清除“完成未查看”状态；无需重绑消息视图。 */
        default void onSessionViewed(SessionHandle h) { }
        void onWorkspaceChanged();
        void onError(String message);
        /** AskUserQuestion 挂起状态变化（asking=true 且 question 非空=开始挂起；asking=false=复位） */
        default void onSessionAskChanged(SessionHandle h, boolean asking, String question) { }
        /** 上下文压缩状态变化（true=压缩中；仅当前激活会话时 GUI 显示） */
        default void onExternalWaitChanged(SessionHandle h, String jobId) { }
        default void onCompressingChanged(SessionHandle h, boolean compressing) { }
        /** 429 长重试进度（attempt ≥ 1 进入/更新；0 退出；仅当前激活会话时 GUI 显示） */
        default void onRetryProgress(SessionHandle h, RetryProgress p) { }
        /** 上下文统计变化（used/max 估算 token；GUI 环形进度圈，仅当前激活会话显示） */
        default void onContextStatsChanged(SessionHandle h, int used, int max) { }
        /** 会话生成了报告等工作区文件。 */
        default void onArtifactCreated(SessionHandle h, String path) { }
        default void onSteeringReceiptChanged(SessionHandle h, com.minion.core.agent.SteeringReceipt receipt) { }
        /** 会话被删除（deleteSession / deleteWorkspace 均通知，含非当前空间）：UI 清理页签与缓存 */
        default void onSessionDeleted(SessionHandle h) { }
        default void onSessionQueueChanged(SessionHandle h) { }
    }

    /** 删除工作空间时等待会话退出的总超时（秒）：AgentLoop 中断后走退出落盘路径，正常远快于此 */
    private static final long DELETE_TERMINATE_TIMEOUT_SECONDS = 5;

    private final ConfirmUi confirmUi;
    private final Config config;
    private final Path jarDir;
    private final WorkspaceManager workspaces;
    private final ModelManager models;
    private final SkillSet skillSet; // 内置列表 + 项目实扫合并；建会话时取一次不可变快照
    private final ToolPluginManager plugins; // 可为 null（测试）；可插拔工具的启用判定与配置来源
    private final McpManager mcp; // 可为 null（测试）；MCP 服务器管理：惰性连接 + 工具补注册
    private final CommandDispatcher dispatcher; // 斜杠命令本地分发（GUI 输入路径）
    private final SecretStore secretStore;
    /** §13.1 FeatureFlags：全部行为开关统一读取（/config 视图 + 会话内存覆盖） */
    private final com.minion.core.config.FeatureFlags featureFlags;
    private final java.util.Map<SessionHandle,com.minion.core.agent.LocalJobs> localJobs=new java.util.concurrent.ConcurrentHashMap<SessionHandle,com.minion.core.agent.LocalJobs>();
    private final java.util.Map<String,String> localJobErrors=new java.util.concurrent.ConcurrentHashMap<String,String>();
    private final java.util.concurrent.ScheduledExecutorService jobClock=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"minion-local-jobs");t.setDaemon(true);return t;});
    /** 会话线程/MCP 连接线程/jobClock 线程都会触发 notify*；写少读多，用 COW 防遍历-注册竞态 */
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<Listener>();

    /** 工作空间名 → 上下文。除 FX 线程外，MCP 连接线程（registerMcpToolsToAllSessions）、
     *  jobClock 线程（tickJobs）与导航加载线程也会读——用并发容器，遍历不再依赖「仅 FX 线程」假设 */
    private final Map<String, WorkspaceCtx> ctxByName = new java.util.concurrent.ConcurrentHashMap<String, WorkspaceCtx>();
    /** 技能快照按空间缓存。仅 FX 线程访问：读于 createSession / currentSkills（及构造期
     *  restoreSessions），失效于 updateWorkspace / renameWorkspace / deleteWorkspace 的同步段
     *  （finishDeleteWorkspace 的后台 daemon 段不触碰）——与 ctxByName 同一线程模型 */
    private final Map<String, SkillSet.Result> skillCache = new HashMap<String, SkillSet.Result>();
    private String currentWorkspaceName;
    private SessionHandle currentSession;

    /** 每工作空间上下文（空间级共享对象；工具注册与工作线程下沉到每会话）。
     *  name/store 可变：工作空间重命名时同步（store 目录已迁移，须重建指向新目录，防旧路径复活）。 */
    private static class WorkspaceCtx {
        String name;
        final Workspace workspace;
        SessionStore store;
        final ConfirmGate confirmGate;
        final String skillsDir;
        /** FX 线程增删，MCP/jobClock/导航线程遍历——CopyOnWriteArrayList 防并发修改 */
        final List<SessionHandle> sessions = new java.util.concurrent.CopyOnWriteArrayList<SessionHandle>();
        final List<String> order = new ArrayList<String>();

        WorkspaceCtx(String name, Workspace workspace, SessionStore store,
                     ConfirmGate confirmGate, String skillsDir) {
            this.name = name;
            this.workspace = workspace;
            this.store = store;
            this.confirmGate = confirmGate;
            this.skillsDir = skillsDir;
        }
    }

    public SessionManager(ConfirmUi confirmUi, Config config, Path jarDir,
                          WorkspaceManager workspaces, ModelManager models,
                          List<Skill> allSkills, ToolPluginManager plugins,
                          McpManager mcp) {
        this.confirmUi = confirmUi;
        this.config = config;
        this.jarDir = jarDir;
        this.workspaces = workspaces;
        this.models = models;
        long startupStarted = System.nanoTime();
        this.skillSet = new SkillSet(allSkills == null ? new ArrayList<Skill>() : allSkills);
        this.plugins = plugins;
        this.mcp = mcp;
        DiagnosticLog.initialize(jarDir);
        DiagnosticLog.info("startup", "SessionManager 初始化，jarDir=" + jarDir);
        // §4.6 SecretStore v2（secret.store.v2 默认 true）：随机主密钥 + DPAPI/GCM；false 应急回退旧实现
        this.secretStore = new SecretStore(jarDir, config.secretStoreV2(), null);
        this.featureFlags = new com.minion.core.config.FeatureFlags(config);
        this.dispatcher = new CommandDispatcher().featureFlags(() -> featureFlags)
                .stats(this::statsText)
                .rewind(new CommandDispatcher.RewindApi() {
                    public String list(com.minion.core.agent.Session s) { return rewindList(s); }
                    public String execute(com.minion.core.agent.Session s, String eventId, String mode) {
                        return rewindExecute(s, eventId, mode);
                    }
                });
        if (mcp != null) {
            // 连接完成（后台线程）：补注册 MCP 工具进所有存活会话（下一轮 schemas() 可见）
            mcp.addListener(new McpManager.Listener() {
                @Override public void onStateChanged(McpServer server) {
                    registerMcpToolsToAllSessions(server);
                }
            });
        }
        loadWorkspaceContexts();
        this.currentWorkspaceName = workspaces.currentName();
        DiagnosticLog.info("startup", "会话列表就绪，未打开历史不创建运行时；elapsedMs="
                + (System.nanoTime() - startupStarted) / 1000000);
        // No scheduled work is dispatched until JavaFX is running. Existing enabled jobs
        // restart from a fresh interval/file baseline; missed runs are never replayed.
        // tick 直接在 jobClock 线程执行：账本读取/目录扫描是磁盘 IO，不再经 runLater 占用 FX 线程；
        // 到期派发走 send（后台线程安全），界面刷新由各监听器自行 runLater。
        jobClock.scheduleWithFixedDelay(() -> { try { tickJobs(); } catch (RuntimeException t) { DiagnosticLog.error("jobs", String.valueOf(t)); } }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
    }
    public com.minion.core.agent.LocalJobs jobs(SessionHandle h)throws java.io.IOException {
        if(h==null||h.deleted)throw new java.io.IOException("会话不存在");
        WorkspaceCtx ctx=ctxByName.get(h.workspaceName);
        if(ctx==null||!ctx.sessions.contains(h))throw new java.io.IOException("工作空间或会话不存在");
        // The legacy ledger is keyed by physical project root + session ID.
        // Refuse ambiguous copies rather than sharing a rule or running it twice.
        Path root=Paths.get(ctx.workspace.workDir()).toAbsolutePath().normalize();
        for(WorkspaceCtx other:ctxByName.values())if(other!=ctx){
            boolean duplicate=false;
            for(SessionHandle candidate:other.sessions)if(!candidate.deleted&&h.id.equals(candidate.id)){duplicate=true;break;}
            if(duplicate){
                Path path=Paths.get(other.workspace.workDir()).toAbsolutePath().normalize();
                if(root.equals(path)||(Files.isDirectory(root)&&Files.isDirectory(path)&&Files.isSameFile(root,path)))
                    throw new IOException("该目录下存在跨项目同 ID 对话，无法安全绑定自动化。请在本项目新建专用对话。");
            }
        }
        com.minion.core.agent.LocalJobs existing=localJobs.get(h);if(existing!=null)return existing;
        com.minion.core.agent.LocalJobs fresh=new com.minion.core.agent.LocalJobs(ctx.workspace,
                root.resolve(".minion").resolve("reliability").resolve(h.id));
        com.minion.core.agent.LocalJobs prior=localJobs.putIfAbsent(h,fresh);localJobErrors.remove(h.id);return prior==null?fresh:prior;
    }
    private void reportJobError(SessionHandle h,Exception e){
        String message=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        if(h!=null&&!message.equals(localJobErrors.put(h.id,message)))notifyError("本地计划任务已暂停，请检查配置: "+message);
    }
    public void armJobs(SessionHandle h){try{jobs(h);}catch(Exception e){reportJobError(h,e);}}
    private void tickJobs(){
        for(WorkspaceCtx ctx:ctxByName.values())for(SessionHandle h:new ArrayList<SessionHandle>(ctx.sessions)){
            if(h.deleted)continue;
            if(!localJobs.containsKey(h)&&!Files.isRegularFile(Paths.get(ctx.workspace.workDir(),".minion","reliability",h.id,"jobs.json")))continue;
            try{com.minion.core.agent.LocalJobs.Job due=jobs(h).tick(System.currentTimeMillis(),h.running,false,com.minion.core.agent.ModelProbe.fingerprint(modelForSession(h)));
                if(due!=null)sendScheduled(h,due);
            }catch(Exception e){reportJobError(h,e);}
        }
    }

    public WorkspaceManager workspaces() { return workspaces; }
    public Path sidebarPreferencesPath(){return jarDir.resolve("sidebar-view.json");}
    private final java.util.concurrent.atomic.AtomicLong navigationSerial=new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean navigationClosed;
    // Separate from side-question/model work; a slow model must not block opening local history.
    private final java.util.concurrent.ExecutorService navigationLoader=new java.util.concurrent.ThreadPoolExecutor(
            2,2,30,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<Runnable>(16),r->{
                Thread t=new Thread(r,"minion-session-navigation");t.setDaemon(true);return t;
            },new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    /** Latest explicit navigation wins; slow old history loading must not steal the user's focus. */
    public void openSessionAcrossProjects(SessionHandle h,java.util.function.Consumer<String> feedback){
        if(navigationClosed)return;
        final long ticket=navigationSerial.incrementAndGet();
        if(!owns(h)){if(feedback!=null)feedback.accept("对话已移除或项目不存在");return;}
        Runnable commit=()->{
            if(ticket!=navigationSerial.get()||!owns(h))return;
            if(!h.isLoaded()){if(feedback!=null)feedback.accept("加载失败，原记录保留");return;}
            if(!h.workspaceName.equals(currentWorkspaceName))switchWorkspace(h.workspaceName);
            activateSession(h,true);if(feedback!=null)feedback.accept("");
        };
        if(h.isLoaded()){commit.run();return;}
        if(feedback!=null)feedback.accept("正在打开对话…");
        navigationLoader.execute(()->{
            if(navigationClosed||ticket!=navigationSerial.get()||!owns(h))return;
            prepareNavigation(h);
            if(navigationClosed){shutdownSessionTools(h);if(h.loop!=null)h.loop.shutdown();h.closeAll();return;}
            if(ticket==navigationSerial.get()&&owns(h))javafx.application.Platform.runLater(commit);
        });
    }
    protected boolean prepareNavigation(SessionHandle h){return ensureLoaded(h,false);}
    private boolean owns(SessionHandle h){
        WorkspaceCtx ctx=h==null?null:ctxByName.get(h.workspaceName);
        return h!=null&&!h.deleted&&ctx!=null&&ctx.sessions.contains(h);
    }
    public void setArchived(SessionHandle h,boolean archived){
        if(!owns(h)||h.running||!ensureLoaded(h))return;
        h.session.archived=archived;persist(h);notifyTitleChanged(h);
    }
    public ModelManager models() { return models; }
    /** MCP 管理器（设置窗 MCP 页/启用开关共用；Main 装配后非 null） */
    public McpManager mcpManager() { return mcp; }

    /** 可插拔工具管理器（设置窗「工具」页共用；Main 装配后非 null） */
    public ToolPluginManager plugins() { return plugins; }

    public void addListener(Listener l) { listeners.add(l); }

    private void notifyTitleChanged(SessionHandle h) {
        for (Listener l : listeners) l.onSessionTitleChanged(h);
    }
    /** running 回调：会话空闲时顺带回收换模型遗留的旧客户端（防 okhttp 资源滞留） */
    private void notifyRunningChanged(SessionHandle h, boolean running) {
        if (!running && h.running) return; // A newer dispatch already started; do not swap its live client.
        if(!running)h.externalJob=null;
        h.completionUnread = !running && "completed".equals(h.session.taskState.status);
        h.pauseUnread = !running && "paused".equals(h.session.taskState.status);
        if (!running) {
            synchronized (h) {
                if (h.running) return; // beginDispatch won the race after the check above.
                if (h.pendingModelRefresh && !h.deleted) {
                    try {
                        replaceSessionModel(h, modelForSession(h));
                        persist(h);
                    } catch (Exception e) { notifyError("任务结束后刷新模型失败: " + e.getMessage()); }
                }
                h.closeRetired();
            }
        }
        for (Listener l : listeners) l.onSessionRunningChanged(h, running);
    }
    private void notifyActivated(SessionHandle h) {
        for (Listener l : listeners) l.onSessionActivated(h);
    }
    private void notifyWorkspaceChanged() {
        for (Listener l : listeners) l.onWorkspaceChanged();
    }
    private void notifyError(String msg) {
        DiagnosticLog.error("session", msg);
        for (Listener l : listeners) l.onError(msg);
    }
    private void notifyAskChanged(SessionHandle h, boolean asking) {
        for (Listener l : listeners) l.onSessionAskChanged(h, asking, asking ? h.askQuestion : null);
    }
    private void notifyCompressingChanged(SessionHandle h, boolean compressing) {
        for (Listener l : listeners) l.onCompressingChanged(h, compressing);
    }
    private void notifyRetryProgress(SessionHandle h, RetryProgress p) {
        for (Listener l : listeners) l.onRetryProgress(h, p);
    }
    private void notifyContextStats(SessionHandle h, int used, int max) {
        for (Listener l : listeners) l.onContextStatsChanged(h, used, max);
    }
    private void notifyArtifactCreated(SessionHandle h, String path) {
        String preferred=com.minion.gui.artifact.ArtifactSelection.preferred(h.session);
        h.lastArtifactPath = preferred==null?path:preferred;
        for (Listener l : listeners) l.onArtifactCreated(h, path);
    }
    private void notifySteeringReceiptChanged(SessionHandle h,com.minion.core.agent.SteeringReceipt receipt) {
        for(Listener l:listeners)l.onSteeringReceiptChanged(h,receipt.copy());
    }
    private void notifySessionDeleted(SessionHandle h) {
        for (Listener l : listeners) l.onSessionDeleted(h);
    }

    /** 装配所有工作空间上下文（对照 Main 现有注册代码），并恢复历史会话 */
    private void loadWorkspaceContexts() {
        for (WorkspaceConfig w : workspaces.list()) {
            WorkspaceCtx ctx = buildCtx(w);
            ctxByName.put(w.workSpaceName, ctx);
            restoreSessions(ctx);
        }
    }

    /** 启动只恢复列表头，不加载消息、事件、模型客户端或工具。 */
    private void restoreSessions(WorkspaceCtx ctx) {
        try {
            for (SessionStore.SessionMeta meta : ctx.store.list()) {
                Session s = new Session();
                s.id = meta.id; s.createdAt = meta.createdAt; s.title = meta.title;
                s.workDir = meta.workDir == null ? ctx.workspace.workDir() : meta.workDir;
                s.modelName = meta.modelName; s.modelDisplayName = meta.modelDisplayName;
                ModelConfig mc = modelForSession(s);
                s.modelDisplayName = mc.displayName;
                s.pinned = meta.pinned; s.archived = meta.archived;
                SessionHandle h = new SessionHandle(s.id, ctx.name, s, null,
                        new SessionController(), s.title, false, null);
                h.listPreview = meta.preview; h.listSummary = meta.lastSummary;
                h.listMessageTs = meta.lastMessageTs;
                ctx.sessions.add(h);
            }
        } catch (Exception e) { notifyError("恢复会话列表失败: " + e.getMessage()); }
    }

    /** 只在打开/操作该会话时加载；失败时不允许把列表占位数据覆盖到原快照。 */
    public boolean ensureLoaded(final SessionHandle h) {return ensureLoaded(h,true);}
    private boolean ensureLoaded(final SessionHandle h,boolean reportError) {
        if (h == null || h.deleted) return false;
        synchronized (h) {
            if (h.isLoaded()) return true;
            WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
            if (ctx == null) return false;
            long started = System.nanoTime();
            try {
                loadSession(ctx, h);
                if(h.deleted){shutdownSessionTools(h);if(h.loop!=null)h.loop.shutdown();h.closeAll();return false;}
                DiagnosticLog.info("session-load", "id=" + h.id + " ms=" + (System.nanoTime() - started) / 1000000);
                return true;
            } catch (Exception e) { if(reportError)notifyError("加载会话失败: " + e.getMessage()); return false; }
        }
    }

    private void loadSession(WorkspaceCtx ctx, final SessionHandle h) throws IOException {
        SkillSet.Result sk = skillsOf(ctx.name);
        String mdAbs = projectMdOf(ctx.name);
        String projSkills = projectSkillsDirOf(ctx.name);
        Session s = ctx.store.load(h.id);
        ModelConfig mc = modelForSession(s);
        boolean missingModel = s.modelDisplayName != null && models.get(s.modelDisplayName) == null;
        String previousModel = s.modelDisplayName;
        boolean restored150k = migrateLegacy150kSession(s, mc);
        s.modelDisplayName = mc.displayName;
        s.modelName = mc.modelName;
        s.runtimeModelConfig = mc.copy();
        s.subAgentModelName = null; // Migrate old per-child overrides to the parent-only rule.
        LlmClient llm = newLlm(mc);
        ContextManager cm = new ContextManager(mc.maxContextTokens, mc.compressThreshold,
                mc.keepRecentMessages, llm,
                TokenCounter.estimate(new SystemPromptBuilder(mdAbs, ctx.workspace.workDir(),
                        tmpDirOf(h.id).toString(), config.emptyOutputPlaceholder(), projSkills)
                        .build(sk.skills)));
        configureSafeContext(cm, mc, s);
        SessionController controller = h.controller;
        s.recoverSteeringReceipts(); // Reconcile journal orphans before rendering, not after a duplicate input row exists.
        controller.replayHistory(s.messages,s.steeringSnapshot()); // 历史及本地送达状态一起恢复
        controller.replayArtifacts(s.artifactsSnapshot());
        h.lastArtifactPath=com.minion.gui.artifact.ArtifactSelection.preferred(s);
        if (missingModel) controller.onWarning("原会话模型「" + previousModel
                + "」已不存在，已回退到「" + mc.displayName + "」，可在输入框重新选择。");
        if (restored150k) controller.onWarning("已清除旧版会话学习到的低安全窗口，并将误填的 150K 手动安全上限改为自动；本会话会按 150K 稳定工作区重新尝试。若服务端明确报上下文超限，仍会自动收紧。");
        AgentLoop loop = new AgentLoop(llm, newRegistry(ctx, s),
                new SystemPromptBuilder(mdAbs, ctx.workspace.workDir(),
                        tmpDirOf(h.id).toString(), config.emptyOutputPlaceholder(), projSkills),
                ctx.confirmGate, controller, cm, ctx.workspace, s);
        loop.emptyOutputPlaceholder = config.emptyOutputPlaceholder(); // 工具空输出占位开关注入
        loop.configureTaskBudget(config.taskContinuationWindows(), 0 /* no cumulative token cap, including restored tasks */);
        loop.setAllSkills(sk.skills); // 会话级快照：本会话独享、不可变
        loop.setSessionStore(ctx.store); // 落盘接线：恢复后随每轮/退出兜底落盘
        loop.setGlobalMemoryRoot(jarDir); // 只读显式核验的跨项目个人偏好，不检索其他工作区对话
        configureAgentRuntime(loop,ctx,s);
        loop.restoreSession(s); // 原地装载 + 半轮残留清洗 + cwd 恢复
        h.session = s; h.llm = llm; h.loop = loop;
        controller.setExternalWaitListener(job->{h.externalJob=job;for(Listener listener:listeners)listener.onExternalWaitChanged(h,job);});
        controller.setAskStateListener(new java.util.function.Consumer<String>() {
            @Override public void accept(String question) {
                h.askQuestion = question;
                h.askPending = question != null;
                notifyAskChanged(h, question != null);
            }
        });
        // 恢复会话也接线压缩状态转发（压缩回调随 AgentLoop 驱动，与新建会话一致）
        controller.setCompressingStateListener(new java.util.function.Consumer<Boolean>() {
            @Override public void accept(Boolean compressing) {
                notifyCompressingChanged(h, compressing);
            }
        });
        // 瞬时错误重试进度转发（AgentLoop 长重试回调 → 指示器）
        controller.setRetryStateListener(new java.util.function.Consumer<RetryProgress>() {
            @Override public void accept(RetryProgress p) {
                notifyRetryProgress(h, p);
            }
        });
        // 上下文统计转发（AgentLoop 关键节点推送 → 环形进度圈）
        controller.setContextStatsListener(new java.util.function.Consumer<SessionController.ContextStat>() {
            @Override public void accept(SessionController.ContextStat s) {
                notifyContextStats(h, s.used, s.max);
            }
        });
        controller.setArtifactListener(new java.util.function.Consumer<String>() {
            @Override public void accept(String path) { notifyArtifactCreated(h, path); }
        });
        controller.setSteeringReceiptListener(receipt->notifySteeringReceiptChanged(h,receipt));

        registerConnectedMcpTools(h); // 兜底：恢复会话也带 MCP 工具（连接已完成场景）
        if (restored150k) persist(h);
    }

    /** V39 could pin 1M models to ~60K after a single timeout/empty response.
     *  Migrate once on restart; an actual HTTP context overflow can still learn a smaller limit again. */
    private static boolean migrateLegacy150kSession(Session s, ModelConfig mc) {
        if (s.context150kMigrationVersion >= 1) return false;
        s.context150kMigrationVersion = 1;
        String id = (mc.modelName == null ? "" : mc.modelName).toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-').replace(' ', '-');
        if (mc.maxContextTokens < 200000 || (mc.stableContextTokens != 0 && mc.stableContextTokens != 150000)
                || !(id.contains("deepseek") || id.contains("qwen3.8-flash")))
            return false;
        boolean changed = s.observedContextLimit > 0 || s.preferredContextLimit == 150000;
        // 150K entered in the old "safe window" box meant only 120K at the default 80% ratio.
        if (s.preferredContextLimit == 150000) s.preferredContextLimit = 0;
        if (s.observedContextLimit > 0) s.observedContextLimit = 0;
        return changed;
    }

    private WorkspaceCtx buildCtx(WorkspaceConfig w) {
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        Workspace workspace = new Workspace(w.workDir);
        String projSkills = WorkspacePaths.projectSkillsDir(w);
        workspace.setExtraAllowedDirs(projSkills == null
                ? new ArrayList<String>() : java.util.Collections.singletonList(projSkills));
        ConfirmGate gate = new ConfirmGate(config, confirmUi);        // v2.4 §4.1 项目/本地层权限规则来源（settings.json；文件缺失=空来源）
        gate.setProjectSettings(
                java.nio.file.Paths.get(w.workDir, ".minion", "settings.json"),
                java.nio.file.Paths.get(w.workDir, ".minion", "settings.local.json"));
        WorkspaceCtx ctx = new WorkspaceCtx(w.workSpaceName, workspace,
                new SessionStore(WorkspaceManager.sessionDirFor(jarDir, w.workSpaceName),
                        config.transcriptJsonl() ? jarDir.resolve(".minion").resolve("transcripts").resolve(w.workSpaceName) : null),
                gate, skillsDir);
        try { ctx.order.addAll(ctx.store.loadOrder()); }
        catch (IOException e) { notifyError("读取会话顺序失败: " + e.getMessage()); }
        return ctx;
    }

    /** 会话临时目录：jarDir/.session/tmp/<sessionId>（工具落盘与模型临时文件统一位置） */
    private Path tmpDirOf(String sessionId) {
        return jarDir.resolve(".session").resolve("tmp").resolve(sessionId);
    }

    /** 递归删除目录（文件占用失败静默跳过；JDK8 Files.walk 需 try-with-resources 关流） */
    private void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /**
     * 每会话独立 ToolRegistry：AgentLoop 构造时按名注册 TaskTool(this)，若同空间共享
     * 单个 registry，task 工具会永远绑定最后构造的 loop（会话 A 的 task 调用事件流入会话 B）。
     * 工具对象本身无状态（构造参数 workspace/skillsDir/gate 为空间级共享对象），
     * 每次 new ToolRegistry 复制注册同样的工具即可；TaskTool 由 AgentLoop 自动注册、绑定本会话。
     * 文件工具（Read/Write/Edit/Glob/Grep/BrowserScreenshot/Bash）携带会话级临时目录
     * （tmpDirOf(sessionId)），工具对象随会话创建、随会话删除。
     */
    private ToolRegistry newRegistry(WorkspaceCtx ctx, Session session) {
        return newRegistry(ctx,session,ctx.workspace);
    }
    private ToolRegistry newRegistry(WorkspaceCtx ctx, Session session, Workspace workspace) {
        ToolRegistry registry = new ToolRegistry();
        String skillsDir = ctx.skillsDir;
        ConfirmGate gate = ctx.confirmGate;
        String sessionId = session.id;
        String tmpDir = tmpDirOf(sessionId).toString();
        CheckpointStore checkpoints = new CheckpointStore(Paths.get(workspace.workDir()),
                Paths.get(workspace.workDir()).resolve(".minion").resolve("checkpoints").resolve(sessionId));
        // §4.5 读状态：同一会话的 Read/Edit/Write 共用 session.readState；ccSemantics 只控制消费侧
        com.minion.core.security.ReadStateStore readState = session.readState;
        boolean ccSemantics = config.readCcSemantics();
        registry.register(new ReadTool(workspace, skillsDir, tmpDir, gate, readState, ccSemantics));
        registry.register(new com.minion.core.tools.ViewImageTool(workspace, skillsDir, tmpDir, gate));
        registry.register(new com.minion.core.tools.artwork.ArtworkTool(workspace,tmpDir));
        registry.register(new WriteTool(workspace, skillsDir, tmpDir, checkpoints, gate, readState, ccSemantics));
        registry.register(new EditTool(workspace, skillsDir, tmpDir, checkpoints, gate, readState, ccSemantics));
        registry.register(new GlobTool(workspace, skillsDir, tmpDir, gate));
        registry.register(new GrepTool(workspace, skillsDir, tmpDir, gate));
        ProcessTool processTool = new ProcessTool(workspace);
        registry.register(processTool);
        // §3.2：Bash run_in_background 委托同一后台账本（唯一真源）；TaskOutput/TaskStop/Sleep 补齐
        {
            BashTool bash = new BashTool(workspace, tmpDirOf(sessionId), config);
            bash.setBackgroundRunner(processTool);
            registry.register(bash);
            registry.register(new com.minion.core.tools.TaskOutputTool(processTool));
            registry.register(new com.minion.core.tools.TaskStopTool(processTool));
            registry.register(new com.minion.core.tools.SleepTool(() -> session.taskState != null
                    && "interrupted_needs_review".equals(session.taskState.status)));
        }
        if (config.teamEnabled()) {   // §8.3 团队邮箱工具（默认 false；replica-offline 开启）
            String workDir = workspace.workDir();
            registry.register(new com.minion.core.team.TeamTools.TeamCreateTool(workDir));
            registry.register(new com.minion.core.team.TeamTools.TeamDeleteTool(workDir));
            registry.register(new com.minion.core.team.TeamTools.SendMessageTool(workDir));
        }
        PythonRuntime python = new PythonRuntime(config.pythonPath());
        registry.register(new FilesTool(workspace));
        {   // §13.2 提交归因（attribution.commit 默认 true）：commit 追加 Generated with Minion + 模型共同作者
            GitTool git = new GitTool(workspace);
            if (config.attributionCommit()) git.setAttribution(session.modelDisplayName == null
                    ? session.modelName : session.modelDisplayName);
            registry.register(git);
        }
        registry.register(new com.minion.core.tools.dev.CodeIndexTool(workspace));
        registry.register(new ProjectTool(workspace));
        registry.register(new BuildTool(workspace, python, processTool));
        registry.register(new com.minion.core.tools.dev.ArtifactVerificationTool(workspace,()->
                plugins!=null&&plugins.browserPlugin().enabled()&&plugins.browserPlugin().canEnable()
                        ?plugins.browserManager().session():null));
        registry.register(new PythonTool(workspace, python));
        registry.register(new ExcelTool(workspace, python, checkpoints));
        registry.register(new SqliteTool(workspace, python));
        registry.register(new DatabaseTool(workspace, python, checkpoints, secretStore));
        registry.register(new DocumentTool(workspace, python, checkpoints,config,gate));
        registry.register(new TextProcessTool(workspace, checkpoints));
        registry.register(new KnowledgeTool(workspace, config));
        registry.register(new MemoryTool(workspace, checkpoints, ctx.store, session.id, session, jarDir));
        ModelConfig contextModel = modelForSession(session);
        registry.register(new ContextTool(session, safeContextLimit(contextModel, session), config));
        registry.register(new PermissionTool(config));
        registry.register(new SecretsTool(secretStore));
        registry.register(new SkillAdminTool(skillsDir, workspace));
        registry.register(new CheckpointTool(checkpoints));
        registry.register(new DiagnosticsTool(config, workspace, python, jarDir,
                plugins == null ? null : plugins.store().browserConfig()));
        registry.register(new LogsTool(workspace));
        registry.register(new UpdateTool(jarDir));
        registry.register(new OpenUrlTool());
        registry.register(new WebFetchTool());
        // 可插拔工具：无条件注册并打插件标签，可见性交给 gate 在 schemas()/get() 时判定
        // （拉模式——改开关无需遍历会话，AgentLoop 下一轮 registry.schemas() 自动生效）
        if (plugins != null) {
            ToolContext tc = new ToolContext(workspace, skillsDir, tmpDir, gate);
            for (ToolPlugin p : plugins.plugins()) {
                for (Tool t : p.createTools(tc)) registry.register(p.id(), t);
            }
            registry.setGate(plugins);
        }
        if (mcp != null) {
            for (McpServer s : mcp.servers()) {
                if (!s.enabled) continue;
                mcp.ensureConnectedAsync(s.name); // 惰性预连接：首次建会话即后台拉起，连接完成后工具补注册
                registerMcpTools(registry, s);    // 已连接（恢复场景）：直接注册
            }
        }
        return registry;
    }

    /**
     * 注册 MCP 服务器工具：与内置/其它服务器工具重名者跳过并计数（设置页展示 skippedTools）。
     * 幂等：本服务器工具已注册（重连/兜底路径重复回调）→ 跳过且不计跳过数，
     * 只把「真冲突」（内置或其它 MCP 服务器占名）计入 skipped——否则重复注册会把
     * 全部工具算成跳过，设置页「N 工具」显示 0 而工具实际可用。
     */
    private void registerMcpTools(ToolRegistry registry, McpServer server) {
        int skipped = 0;
        for (McpToolInfo info : server.tools) {
            Tool exist = registry.get(info.name);
            if (exist == null) {
                registry.register(new McpProxyTool(mcp, server.name, info, !config.mcpConfirmSkip()));
                continue;
            }
            if (exist instanceof McpProxyTool
                    && server.name.equals(((McpProxyTool) exist).serverName())) {
                continue; // 本服务器此前已注册（连接完成回调/兜底路径），幂等跳过
            }
            skipped++; // 与内置工具或其它 MCP 服务器同名：本服务器此工具注册不上
        }
        server.skippedTools = skipped;
    }

    /** 连接完成（工具表变化）：补注册进所有存活会话（连接线程回调，只读遍历会话列表） */
    private void registerMcpToolsToAllSessions(McpServer server) {
        if (server.tools == null || server.tools.isEmpty()) return;
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (!h.isLoaded()) continue;
                registerMcpTools(h.loop.registry(), server);
            }
        }
    }

    /**
     * 新会话兜底：注册连接已完成的 MCP 工具。
     * 覆盖「newRegistry 时未连接、连接完成于 sessions.add 之前」的竞态窗口——
     * 彼时 listener 遍历不到本会话，而 newRegistry 又早于工具填充。
     */
    private void registerConnectedMcpTools(SessionHandle h) {
        if (mcp == null) return;
        for (McpServer s : mcp.servers()) {
            if (s.state == McpServer.State.CONNECTED && !s.tools.isEmpty()) {
                registerMcpTools(h.loop.registry(), s);
            }
        }
    }

    /** 创建会话（恢复会话传 title；新建传 null → titlePending） */
    public SessionHandle createSession(String title) {
        navigationSerial.incrementAndGet();
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        if (ctx == null) return null; // 终审修复：deleteWorkspace 有运行中会话时 ctx 先移除、currentWorkspaceName 后台回退（≤5s 窗口），防 FX 线程 NPE
        ModelConfig mc = models.defaultModel();
        Session s = Session.create(ctx.workspace.workDir(), mc.modelName);
        s.modelDisplayName = mc.displayName;
        s.runtimeModelConfig = mc.copy();
        s.title = title;
        LlmClient llm = newLlm(mc);
        SkillSet.Result sk = skillsOf(currentWorkspaceName);
        if (sk.warning != null) notifyError(sk.warning);
        String mdAbs = projectMdOf(currentWorkspaceName);
        String projSkills = projectSkillsDirOf(currentWorkspaceName);
        ContextManager cm = new ContextManager(mc.maxContextTokens, mc.compressThreshold,
                mc.keepRecentMessages, llm,
                TokenCounter.estimate(new SystemPromptBuilder(mdAbs, ctx.workspace.workDir(),
                        tmpDirOf(s.id).toString(), config.emptyOutputPlaceholder(), projSkills)
                        .build(sk.skills)));
        configureSafeContext(cm, mc, s);
        SessionController controller = new SessionController();
        AgentLoop loop = new AgentLoop(llm, newRegistry(ctx, s),
                new SystemPromptBuilder(mdAbs, ctx.workspace.workDir(),
                        tmpDirOf(s.id).toString(), config.emptyOutputPlaceholder(), projSkills),
                ctx.confirmGate, controller, cm, ctx.workspace, s);
        loop.emptyOutputPlaceholder = config.emptyOutputPlaceholder(); // 工具空输出占位开关注入
        loop.setAllSkills(sk.skills);   // 会话级快照：本会话独享、不可变
        loop.configureTaskBudget(config.taskContinuationWindows(), 0 /* no cumulative token cap, including restored tasks */);
        loop.setSessionStore(ctx.store); // 落盘接线：每轮/退出兜底落盘生效
        loop.setGlobalMemoryRoot(jarDir);
        configureAgentRuntime(loop,ctx,s);
        SessionHandle h = new SessionHandle(s.id, currentWorkspaceName, s, loop, controller,
                title, title == null, llm);
        controller.setExternalWaitListener(job->{h.externalJob=job;for(Listener listener:listeners)listener.onExternalWaitChanged(h,job);});
        controller.setAskStateListener(new java.util.function.Consumer<String>() {
            @Override public void accept(String question) {
                h.askQuestion = question;
                h.askPending = question != null;
                notifyAskChanged(h, question != null);
            }
        });
        // 压缩状态接线：AgentLoop.onCompressingChanged → 控制器 → Listener（Task 4 MainWindow 显示指示器）
        controller.setCompressingStateListener(new java.util.function.Consumer<Boolean>() {
            @Override public void accept(Boolean compressing) {
                notifyCompressingChanged(h, compressing);
            }
        });
        // 瞬时错误重试进度转发（AgentLoop 长重试回调 → 指示器）
        controller.setRetryStateListener(new java.util.function.Consumer<RetryProgress>() {
            @Override public void accept(RetryProgress p) {
                notifyRetryProgress(h, p);
            }
        });
        // 上下文统计转发（AgentLoop 关键节点推送 → 环形进度圈）
        controller.setContextStatsListener(new java.util.function.Consumer<SessionController.ContextStat>() {
            @Override public void accept(SessionController.ContextStat s) {
                notifyContextStats(h, s.used, s.max);
            }
        });
        controller.setArtifactListener(new java.util.function.Consumer<String>() {
            @Override public void accept(String path) { notifyArtifactCreated(h, path); }
        });
        controller.setSteeringReceiptListener(receipt->notifySteeringReceiptChanged(h,receipt));
        ctx.sessions.add(h);
        synchronized(ctx.order){if (!ctx.order.isEmpty()) {
            ctx.order.add(0, h.id);
            saveSessionOrder(ctx);
        }}
        registerConnectedMcpTools(h); // 兜底：连接已完成场景（listener 遍历不到新建会话时）
        try {
            ctx.store.save(s); // 立即落盘（含空会话）
        } catch (Exception e) {
            notifyError("会话落盘失败: " + e.getMessage());
        }
        return h;
    }

    /**
     * 该空间当次配置解析出的技能快照（项目级覆盖同名内置）；扫描告警由调用方提示。
     * 空间级缓存：同一空间内创建/恢复多个会话、无会话时补全展示都只扫一次盘；
     * 配置变更（updateWorkspace/renameWorkspace/deleteWorkspace）时失效。
     */
    private SkillSet.Result skillsOf(String workspaceName) {
        SkillSet.Result hit = skillCache.get(workspaceName);
        if (hit != null) return hit;
        SkillSet.Result r = skillSet.resolve(projectSkillsDirOf(workspaceName));
        skillCache.put(workspaceName, r);
        return r;
    }

    /** 项目级技能目录绝对路径（相对写法按该空间项目路径解析）；未配置 → null */
    private String projectSkillsDirOf(String workspaceName) {
        return WorkspacePaths.projectSkillsDir(workspaces.get(workspaceName));
    }

    /** 项目主说明文件绝对路径；取代原先按进程 cwd 解析的 projectMdPath（跨空间串台根因） */
    private String projectMdOf(String workspaceName) {
        return WorkspacePaths.projectMd(workspaces.get(workspaceName));
    }

    /** 当前应展示的技能清单：激活会话用其快照；无会话则按当前空间实算 */
    public List<Skill> currentSkills() {
        if (currentSession != null) return currentSession.loop.allSkills();
        return skillsOf(currentWorkspaceName).skills;
    }

    /** 新建 LlmClient（模型配置工厂；GUI 弹窗切模型也用它） */
    public LlmClient newLlm(ModelConfig mc) {
        return new DeepSeekClient(mc.url, mc.apiKey, mc.modelName,
                mc.thinking, mc.reasoningEffort, mc.provider, mc.maxOutputTokens, mc.sessionId);
    }

    /** §13.3 /stats：本地使用统计（会话数/消息数/token 累计，数据源当前空间已装载会话 +
     * UsageTracker），快照持久化 {jarDir}/.minion/stats.json（覆盖写，含生成时间）。
     */
    /** /rewind 消息清单（§9.3）：最近 10 条带 eventId 的用户消息 */
    private String rewindList(Session s) {
        StringBuilder sb = new StringBuilder("可回退的用户消息（/rewind <eventId> <conversation|summarize>）:\n");
        int shown = 0;
        for (int i = s.messages.size() - 1; i >= 0 && shown < 10; i--) {
            Message m = s.messages.get(i);
            if (m.eventId == null || !m.isUserInstruction()) continue;
            String preview = m.content == null ? "" : m.content.replace('\n', ' ');
            if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
            sb.append("  ").append(m.eventId).append("  ").append(preview).append('\n');
            shown++;
        }
        if (shown == 0) sb.append("  （无带 eventId 的用户消息）");
        sb.append("\nmode：conversation=仅对话回退（transcript 审计链保留）；summarize=压缩到该点");
        return sb.toString();
    }

    /** /rewind 执行：conversation/summarize 经 RewindService；code/both 需 §9.2 快照接线（诚实报未就绪） */
    private String rewindExecute(Session s, String eventId, String mode) {
        if (!java.util.Arrays.asList("conversation", "summarize", "code", "both").contains(mode))
            return "mode 只支持 conversation/summarize/code/both";
        if ("code".equals(mode) || "both".equals(mode))
            return "code/both 回退需要 checkpoint.messageLevel 文件快照跟踪（§9.2 生产接线随消息级检查点切片）；"
                    + "当前可用 conversation/summarize";
        try {
            com.minion.core.storage.RewindService svc =
                    new com.minion.core.storage.RewindService(null, Paths.get(s.workDir));
            com.minion.core.storage.RewindService.Result r = svc.rewind(s, eventId, mode, null);
            SessionHandle h = handleOf(s);
            if (h != null) {
                h.controller.replayHistory(s.messages);
                persist(h);
            }
            StringBuilder sb = new StringBuilder("回退" + (r.ok ? "完成" : "未完成") + "（mode=" + mode + "）\n");
            for (String n : r.notes) sb.append("  ").append(n).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "回退失败: " + e.getMessage();
        }
    }

    private SessionHandle handleOf(Session s) {
        for (WorkspaceCtx ctx : ctxByName.values())
            for (SessionHandle h : ctx.sessions)
                if (h.session == s) return h;
        return null;
    }

    public String statsText() {
        int sessionCount = 0, messageCount = 0;
        long inputTokens = 0, outputTokens = 0;
        List<SessionHandle> all = sessions(currentWorkspaceName, true);
        StringBuilder per = new StringBuilder();
        for (SessionHandle h : all) {
            Session s = h.session;
            if (s == null) continue;   // 未打开的历史会话无内存运行时
            sessionCount++;
            messageCount += s.messages == null ? 0 : s.messages.size();
            inputTokens += s.usage.sessionInput();
            outputTokens += s.usage.sessionOutput();
            if (per.length() < 2000)
                per.append("\n  ").append(s.title == null ? s.id : s.title)
                        .append("：").append(s.messages == null ? 0 : s.messages.size())
                        .append(" 条消息，").append(s.usage.sessionInput()).append(" in/")
                        .append(s.usage.sessionOutput()).append(" out");
        }
        StringBuilder out = new StringBuilder("本地使用统计（当前工作空间，")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                        .format(new java.util.Date())).append("）\n")
                .append("会话数: ").append(sessionCount)
                .append("（已装载；未打开的历史会话不计数）\n")
                .append("消息数: ").append(messageCount).append("\n")
                .append("token 累计: 输入 ").append(inputTokens).append(" / 输出 ").append(outputTokens)
                .append("（含估算口径；详情见 /tokens）")
                .append(per.length() == 0 ? "" : "\n各会话:" + per);
        try {
            Path statsFile = jarDir.resolve(".minion").resolve("stats.json");
            Files.createDirectories(statsFile.getParent());
            com.google.gson.JsonObject snap = new com.google.gson.JsonObject();
            snap.addProperty("generatedAt", System.currentTimeMillis());
            snap.addProperty("sessions", sessionCount);
            snap.addProperty("messages", messageCount);
            snap.addProperty("inputTokens", inputTokens);
            snap.addProperty("outputTokens", outputTokens);
            Files.write(statsFile, snap.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception persistFailure) {
            com.minion.core.diagnostics.DiagnosticLog.warn("stats", "stats.json 写入失败: " + persistFailure.getMessage());
        }
        return out.toString();
    }

    /**
     * §1.3 模型降级切换（仅本会话）：替换 loop/ContextManager 客户端并同步 handle 与 Session
     * 模型字段；不动 ModelManager.setCurrent（那只影响新会话默认，不得用于降级）。
     * 返回实际切换的模型名；找不到配置或构造失败返回 null（调用方取链中下一项）。
     */
    private String switchFallbackModel(AgentLoop loop, WorkspaceCtx ctx, Session parent, String modelName) {
        try {
            ModelConfig mc = models.get(modelName);
            if (mc == null) return null;
            LlmClient fresh = newLlm(mc);
            loop.setLlm(fresh);
            ContextManager cm = loop.contextManager();
            if (cm != null) {
                cm.setLlm(fresh);
                cm.update(mc.maxContextTokens, mc.compressThreshold, mc.keepRecentMessages);
            }
            parent.runtimeModelConfig = mc;
            parent.modelDisplayName = mc.displayName;
            parent.modelName = mc.modelName;
            for (SessionHandle h : ctx.sessions)
                if (h.session == parent) { h.llm = fresh; break; }
            return mc.modelName;
        } catch (Exception failed) {
            return null;
        }
    }

    private void configureAgentRuntime(AgentLoop loop,WorkspaceCtx ctx,Session parent) {
        // §1.2 retry.profile（legacy 默认；cc 复刻参数）+ §1.3 模型降级链（默认空=完全保持现状）
        loop.retryPolicy = com.minion.core.agent.RetryPolicy.fromProfile(config);
        loop.setModelFallback(config.get("model.fallbackChain", ""),
                next -> switchFallbackModel(loop, ctx, parent, next));
        // §1.4 长度升档/续写上限 + §1.5 token 预算（默认值=V68 现状）
        loop.llmAutoContinue = config.llmAutoContinueEnabled();
        loop.llmEscalatedMaxTokens = config.llmEscalatedMaxTokens();
        loop.llmMaxContinueRecovery = config.llmMaxContinueRecovery();
        loop.budgetEnabled = config.budgetEnabled();
        loop.toolsParallelMetadataDriven = config.toolsParallelMetadataDriven();
        // §5.1/5.2/5.3 上下文体系（默认值=V68 现状；microcompact 默认关、公式制默认关、恢复默认开）
        loop.microcompactEnabled = config.microcompactEnabled();
        loop.microcompactIdleMinutes = config.microcompactIdleMinutes();
        loop.compactRestoreEnabled = config.compactRestoreEnabled();
        ContextManager cmFormula = loop.contextManager();
        if (cmFormula != null) cmFormula.setAutocompactFormula(config.autocompactFormula());
        // §6.1 MINION.md 自动发现（默认关；projectMd 原行为不变）
        if (config.instructionsEnabled())
            loop.setInstructionLoader(new com.minion.core.context.InstructionLoader(
                    Paths.get(loop.workspace().workDir()), jarDir, true));
        loop.setToolProfileSupplier(()->{
            try {
                ModelConfig current=parent.runtimeModelConfig==null?modelForSession(parent):parent.runtimeModelConfig;
                String fingerprint=com.minion.core.agent.ModelProbe.fingerprint(current.copy());
                Path root=Paths.get(loop.workspace().workDir()).resolve(".minion/model-capabilities");
                if(!com.minion.core.tools.PathsGuard.inside(loop.workspace().workDir(),root))return null;
                return new com.minion.core.llm.ModelCapabilityStore(root).load(fingerprint);
            }catch(Exception unavailable){return null;}
        });
        // 视觉拒绝自愈：上游 400 拒图（如 1210）时把 VISION=UNSUPPORTED 写入能力存储，
        // 本会话立即剥图重试，重启/新会话由注入前检查直接跳过图片
        loop.setVisionRejectionSink(mark -> {
            if (mark == null || !mark) return;
            try {
                ModelConfig current=parent.runtimeModelConfig==null?modelForSession(parent):parent.runtimeModelConfig;
                String fingerprint=com.minion.core.agent.ModelProbe.fingerprint(current.copy());
                Path root=Paths.get(loop.workspace().workDir()).resolve(".minion/model-capabilities");
                if(!com.minion.core.tools.PathsGuard.inside(loop.workspace().workDir(),root))return;
                com.minion.core.llm.ModelCapabilityStore store=new com.minion.core.llm.ModelCapabilityStore(root);
                com.minion.core.llm.ModelCapabilityProfile profile=store.load(fingerprint);
                store.save(profile.withCapability(
                        com.minion.core.llm.ModelCapabilityProfile.Capability.VISION,
                        com.minion.core.llm.ModelCapabilityProfile.State.UNSUPPORTED));
            }catch(Exception unavailable){/* 能力存储写失败不影响当前会话的运行期抑制 */}
        });
        loop.setRootedRuntimeFactory((name,id,executionRoot) -> {
            ModelConfig mc=name==null||name.trim().isEmpty()
                    ? (parent.runtimeModelConfig==null?modelForSession(parent):parent.runtimeModelConfig.copy())
                    : models.get(name); // Explicit model is reserved for side questions, never task subagents.
            if(mc==null) throw new IllegalArgumentException("指定模型不存在: "+name);
            Workspace isolated=new Workspace(executionRoot==null?ctx.workspace.workDir():executionRoot);
            List<String> roots=new ArrayList<String>(ctx.workspace.extraAllowedDirs());
            roots.add(tmpDirOf(parent.id).toString());
            roots.add(tmpDirOf(parent.id+"-agent-"+id).toString());
            roots.add(ctx.skillsDir);
            isolated.setExtraAllowedDirs(roots);
            if(executionRoot==null)isolated.restore(loop.workspace().cwd().toString());
            Session child=Session.create(isolated.workDir(),mc.modelName);
            child.id=parent.id+"-agent-"+id; child.modelDisplayName=mc.displayName;
            LlmClient client=newLlm(mc);
            try {
                int safe=safeContextLimit(mc,child);
                TaskState.Child prior=parent.taskState.child(id);
                if(prior!=null&&mc.displayName.equals(prior.model)&&prior.maxContextTokens>=8192) safe=Math.min(safe,prior.maxContextTokens);
                ToolRegistry tools=newRegistry(ctx,child,isolated);
                tools.register(new com.minion.core.tools.TodoWriteTool(child.todos));
                return new AgentRuntime(client,mc.displayName,safe,mc.compressThreshold,mc.maxOutputTokens,mc.stableContextTokens,tools,isolated,true);
            } catch(RuntimeException e) { client.close(); throw e; }
        });
    }

    public void configureAgentDefaults(SessionHandle h,String model,String mode,String tools) {
        if(h==null||h.deleted) throw new IllegalArgumentException("会话不存在");
        if(!ensureLoaded(h)) throw new IllegalStateException("会话加载失败");
        if(model!=null&&!model.trim().isEmpty()) throw new IllegalArgumentException("子Agent模型已锁定为主会话模型，不能单独配置");
        if(!"worker".equals(mode)&&!"explore".equals(mode)) throw new IllegalArgumentException("模式只支持worker/explore");
        h.session.subAgentModelName=null; h.session.subAgentMode=mode; h.session.subAgentTools=tools==null?"":tools.trim();
        if(!h.running) persist(h);
    }

    public void configureContext(SessionHandle h,String profile,int safeWindow) {
        if(h==null||h.deleted) throw new IllegalArgumentException("会话不存在");
        if(!ensureLoaded(h)) throw new IllegalStateException("会话加载失败");
        int configured=modelForSession(h).maxContextTokens;
        if(safeWindow!=0&&(safeWindow<8192||safeWindow>configured)) throw new IllegalArgumentException("安全窗口需为8192到模型配置上限之间；0表示自动");
        if(!"compact".equals(profile)&&!"balanced".equals(profile)) throw new IllegalArgumentException("profile支持balanced/compact");
        h.session.contextProfile=profile; h.session.preferredContextLimit=safeWindow;
        ContextManager cm=h.loop.contextManager();
        if(cm!=null) { cm.setRequestProfile(profile); configureSafeContext(cm,modelForSession(h),h.session); }
        if(!h.running) persist(h);
    }
    public void resetObservedContext(SessionHandle h) {
        if(h==null||h.deleted||h.running) throw new IllegalArgumentException("请等待会话空闲后重置；请先确认服务端窗口已调整");
        if(!ensureLoaded(h)) throw new IllegalStateException("会话加载失败");
        h.session.observedContextLimit=0;
        configureSafeContext(h.loop.contextManager(),modelForSession(h),h.session); persist(h);
    }

    /** Restore a single child without re-issuing the user's root task or draining queued work. */
    public String controlSubAgent(SessionHandle h,String id,String action) {
        if(h==null||h.deleted) return "会话不存在";
        if(!ensureLoaded(h)) return "会话加载失败";
        if("stop".equals(action)) return h.loop.stopSubAgent(id)?"已请求停止子Agent，保留断点":"子Agent不在运行";
        if(!"resume".equals(action)&&!"retry".equals(action)) return "支持stop/resume/retry";
        if(h.running) return "恢复/重试前请先停止主任务并等待结束，避免重复执行";
        TaskState.Child child=h.session.taskState.child(id);
        if(child==null||!java.util.Objects.equals(child.taskId,h.session.taskState.id)) return "未找到当前任务的子Agent";
        final long generation=h.beginDispatch(); promoteSession(h); notifyRunningChanged(h,true);
        h.pool.submit(() -> {
            try { h.controller.onSystem(h.loop.resumeSubAgent(id,"retry".equals(action))); }
            finally { if(h.finishDispatch(generation)) notifyRunningChanged(h,false); }
        });
        return "已安排单个子Agent"+("retry".equals(action)?"重试（新一轮，旧记录归档）":"从记录恢复（先核对文件，仅补缺项）");
    }

    public boolean cancelQueued(SessionHandle h,int index) {
        if(!ensureLoaded(h)) return false;
        if(h==null||!h.removeQueued(index)) return false;
        persistQueue(h); return true;
    }
    public void cancelQueuedTask(SessionHandle h,SessionHandle.QueuedMessage expected) {
        if(!ensureLoaded(h)) throw new IllegalArgumentException("会话加载失败");
        synchronized(h) {
            if(h.removeQueued(expected.id,expected.revision)){persistQueue(h);return;}
        }
        throw new IllegalArgumentException("队列已变化，任务可能已开始；未取消其他任务");
    }
    public void editQueuedTask(SessionHandle h,SessionHandle.QueuedMessage expected,String text) {
        if(h==null||h.deleted)throw new IllegalArgumentException("会话已删除");
        if(!ensureLoaded(h)) throw new IllegalArgumentException("会话加载失败");
        synchronized(h){
            if(!h.editQueued(expected.id,expected.revision,text))throw new IllegalArgumentException("队列条目已开始、被取消或被修改；未覆盖当前任务，请刷新后检查");
            WorkspaceCtx ctx=ctxByName.get(h.workspaceName);
            try {if(ctx==null)throw new java.io.IOException("工作空间不存在");ctx.store.saveQueue(h.session);}
            catch(Exception e){h.rollbackQueuedEdit(expected.id,expected.revision+1,expected.text);throw new IllegalStateException("队列保存失败，已撤回文字修改: "+e.getMessage(),e);}
            for(Listener l:listeners)l.onSessionQueueChanged(h);
        }
    }
    public void background(Runnable work) { branches.submit(work); }
    public void saveSettings(SessionHandle h) { if(ensureLoaded(h)&&!h.running) persist(h); }

    public static final class SideQuestion {
        public String question,answer,model,record;
        public long time;
    }
    private final java.util.concurrent.ExecutorService branches=java.util.concurrent.Executors.newFixedThreadPool(2,r -> {
        Thread t=new Thread(r,"minion-side-question"); t.setDaemon(true); return t;
    });

    public void askSideQuestion(SessionHandle h,String question,String model,java.util.function.Consumer<SideQuestion> done) {
        if(h==null||h.deleted||question==null||question.trim().isEmpty()) throw new IllegalArgumentException("请输入旁支问题");
        if(!ensureLoaded(h)) throw new IllegalStateException("会话加载失败");
        if(question.length()>12000) throw new IllegalArgumentException("旁支问题限12000字符");
        String state=h.session.taskState.render(null);
        final String background=state.length()>6000?state.substring(0,6000):state;
        branches.submit(() -> {
            SideQuestion q=new SideQuestion(); q.question=question; q.model=model==null?modelForSession(h).displayName:model; q.time=System.currentTimeMillis();
            try {
                q.answer=h.loop.askSideQuestion(question,model,background);
                Path file=Paths.get(h.session.workDir,".minion","side-questions",h.id,q.time+"-"+java.util.UUID.randomUUID()+".json");
                Files.createDirectories(file.getParent()); q.record=file.toAbsolutePath().toString();
                Files.write(file,new Gson().toJson(q).getBytes(StandardCharsets.UTF_8));
            } catch(Exception e) { q.answer="旁支提问失败: "+e.getMessage(); }
            done.accept(q);
        });
    }

    public List<SideQuestion> sideQuestionHistory(SessionHandle h) {
        List<SideQuestion> result=new ArrayList<SideQuestion>();
        Path dir=Paths.get(h.session.workDir,".minion","side-questions",h.id);
        if(!Files.isDirectory(dir)) return result;
        try(Stream<Path> paths=Files.list(dir)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted(Comparator.reverseOrder()).limit(50).forEach(p -> {
                try { if(Files.size(p)<2*1024*1024) result.add(new Gson().fromJson(new String(Files.readAllBytes(p),StandardCharsets.UTF_8),SideQuestion.class)); } catch(Exception ignored) { }
            });
        } catch(IOException ignored) { }
        return result;
    }

    public ModelConfig modelForSession(SessionHandle h) {
        return h == null ? models.defaultModel() : modelForSession(h.session);
    }

    private ModelConfig modelForSession(Session session) {
        ModelConfig chosen = session.modelDisplayName == null
                ? null : models.get(session.modelDisplayName);
        if (chosen != null) return chosen;
        // 旧版会话只保存服务端modelName；只有唯一匹配才迁移，避免同名不同接口误选。
        if (session.modelDisplayName == null || session.modelDisplayName.trim().isEmpty()) {
            ModelConfig unique = null;
            for (ModelConfig candidate : models.list()) {
                if (session.modelName != null && session.modelName.equals(candidate.modelName)) {
                    if (unique != null) return models.current();
                    unique = candidate;
                }
            }
            if (unique != null) return unique;
            return models.current(); // 无法识别的旧会话保留旧版全局回退规则。
        }
        return models.defaultModel();
    }

    /** 只切换指定会话；不修改全局默认模型。 */
    public boolean selectModelForSession(SessionHandle h, String name) {
        ModelConfig mc = models.get(name);
        WorkspaceCtx ctx = h == null ? null : ctxByName.get(h.workspaceName);
        if (mc == null || ctx == null || h.deleted || !ctx.sessions.contains(h)) return false;
        if (!ensureLoaded(h)) return false;
        synchronized (h) {
            if (name.equals(h.session.modelDisplayName)) return true;
            if (h.running) {
                h.controller.onWarning("当前任务或子Agent仍在运行；为保证模型一致，请结束任务后再切换主模型");
                return false;
            }
            replaceSessionModel(h, mc);
            persist(h);
        }
        return true;
    }

    private void replaceSessionModel(SessionHandle h, ModelConfig mc) {
        LlmClient fresh = newLlm(mc);
        // 手动安全窗口属于旧模型；切换到另一个模型时不能把旧60K窗口带过去。
        // 修改同一模型设置时仍保留本会话的手动窗口。
        if (!java.util.Objects.equals(h.session.modelDisplayName, mc.displayName)
                || !java.util.Objects.equals(h.session.modelName, mc.modelName))
            h.session.preferredContextLimit = 0;
        LlmClient old = h.llm;
        h.llm = fresh;
        h.retireLlm(old);
        h.loop.setLlm(fresh);
        ContextManager cm = h.loop.contextManager();
        if (cm != null) {
            cm.setLlm(fresh);
            cm.update(mc.maxContextTokens, mc.compressThreshold, mc.keepRecentMessages);
            h.session.observedContextLimit = 0;
            configureSafeContext(cm, mc, h.session);
        }
        Tool contextTool = h.loop.registry().get("Context");
        if (contextTool instanceof ContextTool)
            ((ContextTool) contextTool).setMaxTokens(cm == null ? mc.maxContextTokens : cm.maxTokens());
        h.session.modelName = mc.modelName;
        h.session.modelDisplayName = mc.displayName;
        h.session.runtimeModelConfig = mc.copy();
        h.pendingModelRefresh = false;
    }

    /**
     * 旧版针对用户内网Qwen3.8空响应现象的经验保护，不代表模型官方窗口。
     * 对旧版非Flash型号的自动模式先按60k运行；Flash部署可按模型配置使用更大窗口。
     * 若服务端实际窗口更小，
     * AgentLoop 只从明确的 HTTP 上下文超限学习，并把结果保存在 session.observedContextLimit。
     */
    private void configureSafeContext(ContextManager cm, ModelConfig mc, Session session) {
        cm.setOutputReserveTokens(mc.maxOutputTokens);
        cm.setStableContextTokens(mc.stableContextTokens);
        cm.setInitialSafeMax(safeContextLimit(mc, session));
        cm.enableCalibration(com.minion.core.context.TokenCalibrationStore.key(mc.provider, mc.url, mc.modelName),
                tokenCalibrationStore());
    }

    private com.minion.core.context.TokenCalibrationStore tokenCalibrationStore;

    private com.minion.core.context.TokenCalibrationStore tokenCalibrationStore() {
        if (tokenCalibrationStore == null) {
            tokenCalibrationStore = new com.minion.core.context.TokenCalibrationStore(
                    jarDir.resolve(".session").resolve("token-calibration.json"));
        }
        return tokenCalibrationStore;
    }

    private static int safeContextLimit(ModelConfig mc, Session session) {
        int safe = mc.maxContextTokens;
        // 以实际模型名判断；用户沿用旧“千问3.8”标识配置DeepSeek时也不能误套60K。
        String id = (mc.modelName != null && !mc.modelName.trim().isEmpty() ? mc.modelName
                : mc.displayName == null ? "" : mc.displayName).toLowerCase(java.util.Locale.ROOT);
        if ((session==null||session.preferredContextLimit==0)&&!id.contains("flash")
                &&(id.contains("qwen3.8") || id.contains("qwen-3.8")
                || id.contains("qwen_3.8") || id.contains("千问3.8"))) {
            safe = Math.min(safe, 60000);
        }
        if(session!=null&&session.preferredContextLimit>0) safe=Math.min(safe,session.preferredContextLimit);
        if (session != null && session.observedContextLimit > 0)
            safe = Math.min(safe, session.observedContextLimit);
        return safe;
    }

    /** 配置修改后按各会话自己的选择刷新客户端；修改新会话默认值不调用本方法。 */
    public void applyModelChanged() {
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (!h.isLoaded()) continue; // 未打开会话在首次加载时直接使用最新配置。
                synchronized (h) {
                    if (h.running) { h.pendingModelRefresh = true; continue; }
                    replaceSessionModel(h, modelForSession(h));
                    persist(h);
                }
            }
        }
    }

    public List<SessionHandle> sessions() { return sessions(currentWorkspaceName,false); }
    /** Metadata-only project projection: never switch workspace or hydrate a conversation. */
    public List<SessionHandle> sessions(String workspaceName,boolean includeArchived) {
        WorkspaceCtx ctx = ctxByName.get(workspaceName);
        if (ctx == null) return new ArrayList<SessionHandle>();
        List<SessionHandle> visible = new ArrayList<SessionHandle>();
        for (SessionHandle h : ctx.sessions) if (!h.deleted&&(includeArchived||!h.session.archived)) visible.add(h);
        final Map<String,Integer> ranks = new HashMap<String,Integer>();
        synchronized(ctx.order){for (int i=0;i<ctx.order.size();i++) ranks.put(ctx.order.get(i),i);}
        visible.sort(new Comparator<SessionHandle>() {
            @Override public int compare(SessionHandle a, SessionHandle b) {
                if (a.session.pinned != b.session.pinned) return a.session.pinned ? -1 : 1;
                if (!ranks.isEmpty()) {
                    int ai = ranks.containsKey(a.id) ? ranks.get(a.id) : -1;
                    int bi = ranks.containsKey(b.id) ? ranks.get(b.id) : -1;
                    if (ai >= 0 && bi >= 0) return Integer.compare(ai, bi);
                    if (ai >= 0) return -1;
                    if (bi >= 0) return 1;
                }
                return b.id.compareTo(a.id);
            }
        });
        return visible;
    }

    /** 同项目中把会话拖到目标的上方或下方；排序即时落盘，重启后保持。 */
    public boolean reorderSession(SessionHandle source, SessionHandle target, boolean before) {
        WorkspaceCtx ctx = source==null?null:ctxByName.get(source.workspaceName);
        if (ctx == null || source == null || target == null || source == target
                || source.deleted || target.deleted || source.session.archived || target.session.archived
                || !source.workspaceName.equals(target.workspaceName)
                || !ctx.sessions.contains(source) || !ctx.sessions.contains(target)
                || source.session.pinned != target.session.pinned) return false;
        synchronized(ctx.order){
        List<SessionHandle> visible = sessions(source.workspaceName,false);
        visible.remove(source);
        int index = visible.indexOf(target);
        if (index < 0) return false;
        visible.add(before ? index : index + 1, source);
        List<String> nextOrder=new ArrayList<String>();
        for (SessionHandle h : visible) nextOrder.add(h.id);
        for (SessionHandle h : ctx.sessions) if (h.session.archived) nextOrder.add(h.id);
        try{ctx.store.saveOrder(nextOrder);}
        catch(IOException e){notifyError("保存会话顺序失败: "+e.getMessage());return false;}
        ctx.order.clear();ctx.order.addAll(nextOrder);
        return true;
        }
    }

    private void saveSessionOrder(WorkspaceCtx ctx) {
        synchronized(ctx.order){
        try { ctx.store.saveOrder(new ArrayList<String>(ctx.order)); }
        catch (IOException e) { notifyError("保存会话顺序失败: " + e.getMessage()); }
        }
    }

    /** 运行时将会话移到所属项目的组首；手动置顶始终优先于普通会话。 */
    private void promoteSession(SessionHandle h) {
        WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null || h.deleted || !ctx.sessions.contains(h)) return;
        synchronized(ctx.order){ctx.order.remove(h.id);ctx.order.add(0,h.id);saveSessionOrder(ctx);}
        // The first send assigns the local title on the session worker. Do not emit
        // a title-changed event before that assignment; observers would see a
        // pending title and could mistake reordering for successful title creation.
        if (!h.titlePending) notifyTitleChanged(h);
    }

    public void setPinned(SessionHandle h, boolean pinned) {
        if (h == null || h.deleted || h.session.pinned == pinned) return;
        if (!ensureLoaded(h)) return;
        h.session.pinned = pinned;
        persist(h);
        promoteSession(h);
    }

    /** 按 id 查找会话（跨所有工作空间）：页签点击路径——页签与工作空间无关 */
    public SessionHandle findSession(String id) {
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (h.id.equals(id)) return h;
            }
        }
        return null;
    }
    public SessionHandle findSession(String workspaceName,String id){
        WorkspaceCtx ctx=ctxByName.get(workspaceName);if(ctx==null)return null;
        for(SessionHandle h:ctx.sessions)if(!h.deleted&&h.id.equals(id))return h;
        return null;
    }

    public SessionHandle currentSession() { return currentSession; }

    public void renameSession(SessionHandle h, String newTitle) {
        if (!ensureLoaded(h)) return;
        h.title = newTitle;
        h.session.title = newTitle;
        persist(h);
        notifyTitleChanged(h);
    }

    public void deleteSession(SessionHandle h) {
        h.deleted = true; // 先置位：send 中据此中止，防已删除会话的文件/事件复活
        notifySessionDeleted(h);
        WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        if (h.running) stop(h);
        shutdownSessionTools(h);
        if (h.isLoaded()) h.loop.shutdown();
        h.pool.shutdownNow();
        h.closeAll(); // 会话删除即释放其 LLM 客户端（当前 + 待回收，okhttp 资源）
        ctx.sessions.remove(h);
        synchronized(ctx.order){if (ctx.order.remove(h.id)) saveSessionOrder(ctx);}
        try {
            ctx.store.delete(h.id);
        } catch (Exception e) {
            notifyError("删除会话文件失败: " + e.getMessage());
        }
        // 会话临时目录一并清理；运行中删除时落盘文件可能被占用（Windows 句柄），
        // 删除失败静默容错（deleteRecursively 内部吞错），由启动清理（Main 3 天过期清理）兜底
        deleteRecursively(tmpDirOf(h.id));
        deleteRecursively(ctx.workspace.cwd().resolve(".minion/artifact-history").resolve(h.id));
        h.controller.eventList().setActive(false, null); // 移除被删会话的 active 残留
        if (currentSession == h) currentSession = null;
    }

    public void activateSession(SessionHandle h) {
        activateSession(h, false);
    }

    /**
     * 激活会话。显式导航（侧栏/页签点击）允许强制通知 UI 重新绑定，
     * 修复 currentSession 已指向会话但聊天区因时序或视图清理而为空的情况。
     */
    public void activateSession(SessionHandle h, boolean forceNotify) {
        navigationSerial.incrementAndGet();
        if (h.deleted) return; // 已删句柄不可再激活（防已删会话残留激活态/后续 send 落空）
        if (!currentWorkspaceName.equals(h.workspaceName)) return; // 非当前工作空间的句柄不激活
        if (!ensureLoaded(h)) return;
        boolean wasUnread = h.completionUnread || h.pauseUnread;
        h.completionUnread = false; // 用户点击查看后清除绿色完成提示；重复激活同样生效
        h.pauseUnread = false;
        if (currentSession == h) {
            if (wasUnread) for (Listener l : listeners) l.onSessionViewed(h);
            if (forceNotify) notifyActivated(h);
            return; // 普通重复激活不重绑视图，避免重放闪烁
        }
        if (currentSession != null) currentSession.controller.eventList().setActive(false, null);
        currentSession = h;
        h.controller.eventList().setActive(true, null);
        notifyActivated(h);
    }

    /** 取消激活态（关闭激活中会话的页签时调用）：
     *  UI 已卸载该会话视图但 currentSession 仍指向它——activateSession 的幂等守卫
     *  （currentSession == h 直接 return）会挡住用户从左侧再次打开，故须置空。 */
    public void deactivateSession(SessionHandle h) {
        navigationSerial.incrementAndGet();
        if (currentSession == h) {
            currentSession.controller.eventList().setActive(false, null);
            currentSession = null;
        }
    }

    /** 工作空间切换（UI 层负责换绑视图；此处切上下文与激活态） */
    public void switchWorkspace(String name) {
        navigationSerial.incrementAndGet();
        if (ctxByName.get(name) == null) return;
        if (currentSession != null) currentSession.controller.eventList().setActive(false, null);
        currentWorkspaceName = name;
        currentSession = null;
        workspaces.setCurrent(name);
        notifyWorkspaceChanged();
    }

    /** 新建工作空间：配置落盘 + 建上下文（不自动切换，用户点击列表项切换）。false=名称非法或重名 */
    public boolean addWorkspace(String name, String workDir, String projectMd, String projectSkillsDir) {
        if (!workspaces.add(name, workDir, projectMd, projectSkillsDir)) return false;
        ctxByName.put(name, buildCtx(workspaces.get(name)));
        return true;
    }

    /**
     * 重命名：配置迁移 + 会话目录迁移（WorkspaceManager.rename 内部完成）+ ctx 换键 + 当前名同步
     * + 全部会话 workspaceName 同步（否则 activateSession 守卫「非当前空间不激活」拒绝页签切换、
     * send/persist 按旧名查 ctx 落空）+ store 重建指向新目录。false=新名非法/重名
     */
    public boolean renameWorkspace(String oldName, String newName) {
        if (!workspaces.rename(oldName, newName)) return false;
        skillCache.remove(oldName); // 快照键跟随空间名迁移，防旧键残留（重新添加同名空间时误命中）
        WorkspaceCtx ctx = ctxByName.remove(oldName);
        ctx.name = newName;
        ctxByName.put(newName, ctx);
        ctx.store = new SessionStore(WorkspaceManager.sessionDirFor(jarDir, newName),
                config.transcriptJsonl() ? jarDir.resolve(".minion").resolve("transcripts").resolve(newName) : null); // 目录已迁移，store 跟随
        for (SessionHandle h : ctx.sessions) h.workspaceName = newName;
        if (currentWorkspaceName.equals(oldName)) currentWorkspaceName = newName;
        notifyWorkspaceChanged();
        return true;
    }

    /**
     * 修改工作空间：配置落盘 + workDir 热更新（所有会话共享同一 workspace 实例，
     * setWorkDir 后下一轮工具调用即按新根守卫，无需重启）；projectMd 对新会话生效（运行中会话
     * 的 system prompt 在创建时构建，不热换）。技能放行目录同样按新配置刷新，
     * 但已存活会话的技能快照与提示词不刷新（新会话才用新配置）。
     * false = 空间不存在或项目路径为空（core 已拒绝，此处不产生任何副作用）。
     */
    public boolean updateWorkspace(String name, String workDir, String projectMd, String projectSkillsDir) {
        if (!workspaces.update(name, workDir, projectMd, projectSkillsDir)) return false;
        skillCache.remove(name); // 技能目录配置可能已变：空间快照缓存失效，下次解析重扫
        WorkspaceCtx ctx = ctxByName.get(name);
        if (ctx == null) return true;
        ctx.workspace.setWorkDir(workDir);
        String abs = projectSkillsDirOf(name);   // 按更新后的配置重新解析
        ctx.workspace.setExtraAllowedDirs(abs == null
                ? new ArrayList<String>() : java.util.Collections.singletonList(abs));
        return true;
    }

    /**
     * 工作空间拖拽排序：转发 WorkspaceManager（不发通知——notifyWorkspaceChanged 会触发
     * MainWindow 的 clearChatPane 清空右侧聊天区，拖拽排序不应清内容；UI 侧 drop 后自行 refresh）。
     */
    public boolean moveWorkspace(String name, int newIndex) {
        return workspaces.move(name, newIndex);
    }

    /**
     * 删除工作空间：先终止该空间所有会话（置 deleted + 中断 + 关闭，等退出完成）
     * → 再删配置/目录（remove 内部递归删 session/<name>/）→ 当前名同步。
     * 顺序不可颠倒：AgentLoop 所有退出路径无条件 persistSession（createDirectories 复活目录），
     * 必须先等会话退出完再删目录。运行中会话的等待最长 DELETE_TERMINATE_TIMEOUT_SECONDS 秒；
     * 可能被 FX 线程调用（右键菜单 onAction），有运行中会话时整个终止+删除流程放后台
     * daemon 线程执行，FX 线程只发起。false=空间不存在或删最后一个被拒绝
     */
    public boolean deleteWorkspace(final String name) {
        WorkspaceCtx ctx = ctxByName.get(name);
        if (ctx == null) return false;
        if (workspaces.list().size() <= 1) return false; // 删最后一个被拒（与 remove 同判据），会话上下文不动
        boolean hasRunning = false;
        for (SessionHandle h : ctx.sessions) {
            h.deleted = true; // 先置位：send 中据此中止，防已删除会话的文件/事件复活
            notifySessionDeleted(h);
            if (h.running) { h.loop.interrupt(); hasRunning = true; } // 终止运行中循环（stop 语义）
            shutdownSessionTools(h);
            if (h.isLoaded()) h.loop.shutdown();
            h.pool.shutdownNow();
            h.closeAll(); // 工作空间删除即释放其全部会话的 LLM 客户端（当前 + 待回收）
            h.controller.eventList().setActive(false, null); // 移除被删会话的 active 残留
            deleteRecursively(tmpDirOf(h.id)); // 工作空间删除：会话临时目录一并清理
        }
        ctxByName.remove(name);
        skillCache.remove(name); // 空间已删：快照一并清，防同名重建后误用旧扫描结果
        if (hasRunning) {
            // 运行中会话的 awaitTermination 可能阻塞（最长超时）：后台 daemon 线程执行，不阻塞 FX 线程
            Thread t = new Thread(new Runnable() {
                @Override public void run() { finishDeleteWorkspace(ctx, name); }
            }, "minion-ws-delete");
            t.setDaemon(true);
            t.start();
        } else {
            finishDeleteWorkspace(ctx, name); // 无运行中会话：awaitTermination 即时返回，可同步完成
        }
        return true;
    }

    /** 等待该空间所有会话退出（超时按继续，AgentLoop 有 stop 语义正常不会存活）→ 删配置/目录 → 当前名同步 */
    private void finishDeleteWorkspace(WorkspaceCtx ctx, String name) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DELETE_TERMINATE_TIMEOUT_SECONDS);
        for (SessionHandle h : ctx.sessions) {
            long remain = deadline - System.nanoTime();
            if (remain <= 0) break;
            try {
                h.pool.awaitTermination(remain, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!workspaces.remove(name)) return; // 理论不可达（前面已校验），防御
        if (currentWorkspaceName.equals(name)) {
            currentWorkspaceName = workspaces.currentName(); // remove 已回落 currentName
            currentSession = null;
            notifyWorkspaceChanged();
        }
    }

    /** 发送：新会话（titlePending）先本地置标题，再跑正式任务 */
    public void send(final SessionHandle h, final String text) { send(h, text, null); }

    /** 发送（带图）：图片随消息以 OpenAI 视觉 content 数组传给模型 */
    public void send(final SessionHandle h, final String text, final List<ImagePart> images) {
        send(h, text, images, false);
    }

    private void send(final SessionHandle h, final String text, final List<ImagePart> images, final boolean queuedTask) {
        send(h,text,images,queuedTask,null);
    }

    private void sendScheduled(final SessionHandle h,final com.minion.core.agent.LocalJobs.Job job){
        try {
            if(!ensureLoaded(h)){finishScheduled(h,job,false);return;}
            send(h,job.prompt,null,true,job);
        } catch(RuntimeException e){finishScheduled(h,job,false);reportJobError(h,e);}
    }

    private void finishScheduled(SessionHandle h,com.minion.core.agent.LocalJobs.Job job,boolean success){
        if(job==null||h.deleted)return;
        com.minion.core.agent.LocalJobs ledger=localJobs.get(h);
        if(ledger!=null)try{ledger.finish(job.id,success);}catch(IOException e){reportJobError(h,e);}
    }

    private void send(final SessionHandle h, final String text, final List<ImagePart> images, final boolean queuedTask,
                      final com.minion.core.agent.LocalJobs.Job scheduledJob) {
        if (h == null) return;
        final WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        if (!ensureLoaded(h)) return;
        final long dispatchGeneration = h.beginDispatch();
        final long submittedAtNanos=System.nanoTime();
        promoteSession(h);
        h.pool.submit(new Runnable() {
            @Override public void run() {
                final long dispatchQueueMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-submittedAtNanos);
                boolean dispatchTimingRecorded=false;
                try {
                    if (h.deleted) return; // 队列积压期间被删除
                    if (!ensureLoaded(h)) { h.finishDispatch(dispatchGeneration); return; }
                    if (h.titlePending) {
                        h.title = TitleGenerator.localTitle(text); // 本地截取，不再走 LLM 摘要
                        if (h.deleted) return; // 摘要期间被删除：不再落盘/通知
                        h.titlePending = false;
                        h.session.title = h.title;
                        persist(h);
                        notifyTitleChanged(h);
                        // §6.3 LLM 标题升级：SideQuery 异步（≤8 字；失败静默保留本地标题，不阻塞首轮）
                        final String titleSource = text;
                        h.pool.submit(() -> {
                            try {
                                String upgraded = TitleGenerator.llmTitle(h.llm, titleSource);
                                if (upgraded == null || h.deleted || h.titlePending) return;
                                h.title = upgraded;
                                h.session.title = upgraded;
                                persist(h);
                                notifyTitleChanged(h);
                            } catch (Exception ignored) { /* 标题升级失败保留本地截取 */ }
                        });
                    }
                    h.running = true;
                    notifyRunningChanged(h, true);
                    try {
                        String currentText = text;
                        List<ImagePart> currentImages = images;
                        boolean forceNew = queuedTask;
                        boolean scheduledTurn = scheduledJob != null;
                        boolean automaticContinuation = false;
                        while (!h.deleted) {
                            if(automaticContinuation) {
                                automaticContinuation=false;
                                if(!h.loop.runContinuationTurn()) break;
                            }
                            else if(scheduledTurn)h.loop.runScheduledTurn(currentText,forceNew,new java.util.HashSet<String>(scheduledJob.allowedTools));
                            else h.loop.runUserTurn(currentText, currentImages, forceNew);
                            if(!dispatchTimingRecorded){h.loop.recordQueueWait(dispatchQueueMillis);dispatchTimingRecorded=true;}
                            synchronized(h) {
                                synchronized(h.session.pendingSupplements) {
                                    if(h.loop.lastTurnSucceeded() && !h.session.pendingSupplements.isEmpty()) {
                                        currentText="继续当前任务，先处理刚收到的补充要求";
                                        currentImages=null; forceNew=false;
                                        continue;
                                    }
                                }
                            }
                            if(scheduledTurn)finishScheduled(h,scheduledJob,h.loop.lastTurnSucceeded());
                            if (!h.loop.lastTurnSucceeded()) {
                                // Resume only an ordinary task stopped by the tool-round boundary.
                                // A queued user instruction, interruption, scheduled read-only job,
                                // or tool-free turn must never trigger unattended extra work.
                                boolean noSupplements;
                                synchronized(h.session.pendingSupplements) {noSupplements=h.session.pendingSupplements.isEmpty();}
                                if(!scheduledTurn && !h.deleted && !h.loop.wasInterrupted()
                                        && h.loop.lastTurnToolRounds()>0 && noSupplements
                                        && h.queuedSnapshot().isEmpty()
                                        && "paused".equals(h.session.taskState.status)
                                        && "round_limit".equals(h.session.taskState.pauseCode)
                                        && h.session.taskState.canAutomaticallyContinue()) {
                                    h.controller.onSystem("工具轮次边界已保存，正在自动续接未完成任务（有界且不重放用户指令）");
                                    automaticContinuation=true;
                                    continue;
                                }
                                if (!h.queuedSnapshot().isEmpty()) h.controller.onSystem("当前任务暂停/失败，后续队列保留，不会自动执行");
                                else if("round_limit".equals(h.session.taskState.pauseCode)
                                        && !h.session.taskState.canAutomaticallyContinue())
                                    h.controller.onSystem("自动续接预算已用尽，任务仍未完成；断点已保存，请人工检查后继续");
                                break;
                            }
                            SessionHandle.QueuedMessage queued;
                            synchronized(h) {
                                queued = h.pollQueued();
                                if(queued==null) h.running=false; // Atomic handoff with stage/promote.
                            }
                            if (queued == null) break;
                            persistQueue(h);
                            h.controller.onSystem("开始执行队列中的下一项任务");
                            currentText = queued.text;
                            currentImages = queued.images;
                            forceNew = true;
                            scheduledTurn = false;
                        }
                    } finally {
                        finishScheduled(h,scheduledJob,false); // no-op when the scheduled turn recorded its result
                        if (h.deleted) {
                            // 运行中被删除：runUserTurn 退出路径已把文件写回（deleted 对 AgentLoop 不可见），
                            // 此处补删，防重启后 restore 复活
                            try { ctx.store.delete(h.id); }
                            catch (Exception e) { notifyError("删除会话文件失败: " + e.getMessage()); }
                        }
                        if (h.finishDispatch(dispatchGeneration)) {
                            if (h.askPending) { // 中断路径 onAskUserDone 不回调，此处兜底复位
                                h.askPending = false;
                                h.askQuestion = null;
                                notifyAskChanged(h, false);
                            }
                            notifyRunningChanged(h, false);
                        }
                    }
                } catch (Exception e) {
                    finishScheduled(h,scheduledJob,false);
                    if (h.finishDispatch(dispatchGeneration)) {
                        if (h.askPending) { // 中断路径 onAskUserDone 不回调，此处兜底复位
                            h.askPending = false;
                            h.askQuestion = null;
                            notifyAskChanged(h, false);
                        }
                        notifyRunningChanged(h, false);
                    }
                    notifyError("任务执行异常: " + e.getMessage());
                }
            }
        });
    }

    public void stop(SessionHandle h) {
        if (h != null && h.running) {
            com.minion.core.diagnostics.DiagnosticLog.info("session-stop", "source=stop_action session=" + h.id);
            h.loop.interrupt(); // 只取消当前客户端；换模型后 in-flight 请求在旧（已退役）客户端上
            h.closeRetired();   // 一并取消旧客户端的流式请求，防「终止」失效等旧流自然结束（数分钟）
        }
    }

    public void persistQueue(SessionHandle h) {
        if (!ensureLoaded(h)) return;
        WorkspaceCtx ctx=ctxByName.get(h.workspaceName);
        if(ctx!=null && !h.deleted) {
            try { ctx.store.saveQueue(h.session); }
            catch(Exception e) { notifyError("任务队列保存失败: " + e.getMessage()); }
        }
        for(Listener l:listeners) l.onSessionQueueChanged(h);
    }

    /** Default is queue; only an explicit promotion steers the running task. */
    public void stageGuidance(SessionHandle h, String text, List<ImagePart> images) {
        if(h==null || h.deleted) return;
        if(!ensureLoaded(h)) return;
        synchronized(h) {
            h.enqueue(text,images,true);
            persistQueue(h);
            if(!h.running) {
                SessionHandle.QueuedMessage next=h.pollQueued();
                persistQueue(h);
                if(next!=null) send(h,next.text,next.images,true);
            }
        }
    }

    public void promoteGuidance(SessionHandle h) {
        if(h==null || h.deleted) return;
        if(!ensureLoaded(h)) return;
        synchronized(h) {
            SessionHandle.QueuedMessage task=h.takeGuide();
            if(task==null) return;
            if(h.running) h.loop.offerSteering(task.text,task.images);
            else send(h,task.text,task.images,true);
            persistQueue(h);
        }
    }

    public void discardGuidance(SessionHandle h) {
        if(ensureLoaded(h)) { h.takeGuide(); persistQueue(h); }
    }

    /** 运行中补充：入 AgentLoop 挂起队列 + 发聊天标识事件（UI 事件仅在点击时发一次，注入不重发） */
    public void sendSupplement(final SessionHandle h, final String text) { sendSupplement(h, text, null); }

    /** 运行中补充（带图）：图片占位并入聊天标识事件（聊天区不渲染图片本体） */
    public void sendSupplement(final SessionHandle h, final String text, final List<ImagePart> images) {
        if (h == null || ((text == null || text.trim().isEmpty())&&(images==null||images.isEmpty()))) return;
        if (!ensureLoaded(h)) return;
        h.loop.offerSteering(text, images);
    }

    /** 回答 AskUserQuestion：完成挂起的等待（未挂起时忽略）；回答作为工具结果回传继续本轮 */
    public void sendAnswer(final SessionHandle h, final String text) {
        sendAnswer(h, text, null);
    }

    /** 返回是否接收；失败时输入框保留草稿，不转成新任务或运行中补充。 */
    public boolean sendAnswer(final SessionHandle h, final String text, final List<ImagePart> images) {
        boolean accepted=h!=null&&!h.deleted&&h.running&&h.loop!=null&&h.loop.answerAskUser(text,images);
        if(accepted)promoteSession(h);return accepted;
    }

    public boolean sendAnswer(final SessionHandle h, final String text, final List<ImagePart> images, final String expectedQuestionId) {
        boolean accepted=h!=null&&!h.deleted&&h.running&&h.loop!=null&&h.loop.answerAskUser(text,images,expectedQuestionId);
        if(accepted)promoteSession(h);return accepted;
    }

    /** 当前工作空间 workDir（文件补全遍历根；无当前空间返回 null） */
    public String currentWorkspaceDir() {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        return ctx == null ? null : ctx.workspace.workDir();
    }

    /** 斜杠命令本地分发：命中 → 聊天区回显命令 + 系统行结果（不入 LLM 历史）；未命中 → 按普通消息发送 */
    public void dispatchCommand(SessionHandle h, String text) {
        if (!ensureLoaded(h)) return;
        String sessionResult = dispatchSessionCommand(h, text);
        if (sessionResult != null) {
            h.controller.onUserMessage(text);
            h.controller.onSystem(sessionResult);
            return;
        }
        String result = dispatcher.dispatch(h, text);
        if (result == null) { send(h, text); return; }
        h.controller.onUserMessage(text); // 仅展示回显，不注入 LLM 历史
        h.controller.onSystem(result);
    }

    /** 会话目录需要访问 manager/store，因此在通用 CommandDispatcher 之前本地处理。 */
    private String dispatchSessionCommand(SessionHandle h, String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if("/budget".equalsIgnoreCase(trimmed)) return h.loop.contextBudget();
        if("/agents".equalsIgnoreCase(trimmed)) return h.session.taskState.render(null);
        if(trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("/agent ")) {
            String[] control=trimmed.split("\\s+",3);
            return control.length<3?"用法: /agent stop|resume|retry <完整子AgentID>":controlSubAgent(h,control[2],control[1]);
        }
        if (!(trimmed.equalsIgnoreCase("/sessions") || trimmed.toLowerCase().startsWith("/sessions ")
                || trimmed.equalsIgnoreCase("/session") || trimmed.toLowerCase().startsWith("/session "))) return null;
        String[] parts = trimmed.split("\\s+", 3);
        if ("/sessions".equalsIgnoreCase(parts[0])) {
            String query = parts.length > 1 ? trimmed.substring(trimmed.indexOf(' ') + 1).trim() : "";
            boolean archivedOnly = "archived".equalsIgnoreCase(query), all = "all".equalsIgnoreCase(query);
            WorkspaceCtx ctx = ctxByName.get(h.workspaceName); if (ctx == null) return "工作空间不存在";
            StringBuilder out = new StringBuilder("会话目录："); int count=0;
            for (SessionHandle item : ctx.sessions) {
                String hay=(item.title==null?"":item.title)+" "+item.preview()+" "+item.id;
                if(archivedOnly&&!item.session.archived)continue;
                if(!all&&!archivedOnly&&!query.isEmpty()&&!hay.toLowerCase().contains(query.toLowerCase()))continue;
                if(!all&&!archivedOnly&&query.isEmpty()&&item.session.archived)continue;
                out.append('\n').append(item.session.pinned?"[置顶] ":"").append(item.session.archived?"[归档] ":"")
                        .append(item.id).append("  ").append(item.title==null?"(新会话)":item.title).append(" — ").append(item.preview());count++;
            }
            return count==0?"未找到会话":out.append("\n共 ").append(count).append(" 个").toString();
        }
        if(parts.length<2)return "用法: /session pin|unpin|archive|unarchive|fork|export [路径]|import <路径>";
        String action=parts[1].toLowerCase();
        if("pin".equals(action)||"unpin".equals(action)){setPinned(h,"pin".equals(action));return h.session.pinned?"会话已置顶":"已取消置顶";}
        if("archive".equals(action)||"unarchive".equals(action)){h.session.archived="archive".equals(action);persist(h);notifyTitleChanged(h);return h.session.archived?"会话已归档（/sessions archived 查看）":"会话已恢复";}
        if("fork".equals(action))return forkSession(h);
        if("export".equals(action))return exportSession(h,parts.length>2?parts[2]:"");
        if("import".equals(action))return importSession(parts.length>2?parts[2]:"");
        return "未知会话操作: "+action;
    }

    private String forkSession(SessionHandle source) {
        SessionHandle fork=createSession((source.title==null?"会话":source.title)+"（副本）");if(fork==null)return "创建副本失败";
        Message[] copied=new Gson().fromJson(new Gson().toJson(source.session.messages),Message[].class);
        fork.session.messages.clear();if(copied!=null)fork.session.messages.addAll(java.util.Arrays.asList(copied));
        fork.titlePending=false;fork.session.title=fork.title;fork.controller.replayHistory(fork.session.messages);persist(fork);notifyTitleChanged(fork);
        return "已创建会话副本: "+fork.id;
    }

    private String exportSession(SessionHandle h,String raw) {
        try { Path target=raw.trim().isEmpty()?Paths.get(h.session.workDir).resolve(".minion/session-exports").resolve(h.id+".md"):Paths.get(h.session.workDir).resolve(raw).normalize().toAbsolutePath();if(!PathsGuard.inside(h.session.workDir,target))return "导出路径必须位于工作区";if(target.getParent()!=null)Files.createDirectories(target.getParent());StringBuilder md=new StringBuilder("# ").append(h.title==null?h.id:h.title).append("\n\n");for(Message m:h.session.messages){md.append("## ").append(m.role).append("\n\n");if(m.content!=null)md.append(m.content).append("\n\n");if(m.reasoningContent!=null&&!m.reasoningContent.isEmpty())md.append("<details><summary>思考</summary>\n\n").append(m.reasoningContent).append("\n\n</details>\n\n");}Files.write(target,md.toString().getBytes(StandardCharsets.UTF_8));return "会话已导出: "+target;}catch(Exception e){return "导出失败: "+e.getMessage();}
    }

    private String importSession(String raw) {
        try {if(raw.trim().isEmpty())return "用法: /session import <工作区内 Markdown 路径>";WorkspaceCtx ctx=ctxByName.get(currentWorkspaceName);Path root=Paths.get(ctx.workspace.workDir()).toAbsolutePath().normalize(),source=root.resolve(raw).normalize().toAbsolutePath();if(!PathsGuard.inside(ctx.workspace.workDir(),source)||!Files.isRegularFile(source))return "导入文件不存在或越出工作区";if(Files.size(source)>2*1024*1024)return "导入文件超过 2MB，请先精简或分段";String body=new String(Files.readAllBytes(source),StandardCharsets.UTF_8);SessionHandle imported=createSession("导入-"+source.getFileName());imported.titlePending=false;imported.session.messages.add(Message.user("以下是导入的历史记录，请作为上下文参考：\n\n"+body));imported.controller.replayHistory(imported.session.messages);persist(imported);notifyTitleChanged(imported);return "已导入为新会话: "+imported.id;}catch(Exception e){return "导入失败: "+e.getMessage();}
    }

    private void persist(SessionHandle h) {
        if (!h.isLoaded() || h.deleted) return; // 列表占位对象绝不写回覆盖正文。
        WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        try {
            ctx.store.save(h.session);
        } catch (Exception e) {
            notifyError("会话落盘失败: " + e.getMessage());
        }
    }

    /** 是否有会话正在后台运行（窗口关闭确认用，跨工作空间） */
    public boolean hasRunning() {
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (h.running) return true;
            }
        }
        return false;
    }

    /** 关闭：终止所有运行中会话（窗口关闭时调用）。
     *  interrupt 后必须等工具池清理完（awaitToolsTerminated）：Bash 工具中断时
     *  killTree 清杀子进程需要时间，工具池是 daemon 线程，JVM 退出不等它——
     *  不等就退出会让 killTree 没跑完，bash 子进程变孤儿继续占 CPU（关窗残留实测根因）。
     *  工具任务中断后最多 killTree(5s)+join(5s) 结束，等 8s 足够；杀不掉的极端情况限时返回。 */
    public void shutdown() {
        navigationClosed=true;
        navigationSerial.incrementAndGet();
        navigationLoader.shutdownNow();
        jobClock.shutdownNow();
        branches.shutdownNow();
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (!h.isLoaded()) { h.pool.shutdownNow(); continue; }
                if (h.running) h.loop.interrupt();
                shutdownSessionTools(h);
                h.loop.shutdown();
                h.loop.awaitToolsTerminated(8000); // 等 bash 等子进程清理完成，防孤儿残留
                h.pool.shutdownNow();
                h.closeAll(); // 关 okhttp 连接池/线程（当前 + 待回收），防 JVM 残留
            }
        }
    }

    /** 回收由开发工作台启动的后台服务，防止关窗/删会话后残留 cmd、Python 或 Node 进程。 */
    private static void shutdownSessionTools(SessionHandle h) {
        if (!h.isLoaded()) return;
        com.minion.core.tools.Tool tool = h.loop.registry().get("Process");
        if (tool instanceof ProcessTool) ((ProcessTool) tool).shutdown();
    }
}
