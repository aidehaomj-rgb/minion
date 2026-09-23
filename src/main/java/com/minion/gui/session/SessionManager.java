package com.minion.gui.session;

import com.google.gson.Gson;
import com.minion.core.agent.AgentLoop;
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
        void onWorkspaceChanged();
        void onError(String message);
        /** AskUserQuestion 挂起状态变化（asking=true 且 question 非空=开始挂起；asking=false=复位） */
        default void onSessionAskChanged(SessionHandle h, boolean asking, String question) { }
        /** 上下文压缩状态变化（true=压缩中；仅当前激活会话时 GUI 显示） */
        default void onCompressingChanged(SessionHandle h, boolean compressing) { }
        /** 429 长重试进度（attempt ≥ 1 进入/更新；0 退出；仅当前激活会话时 GUI 显示） */
        default void onRetryProgress(SessionHandle h, RetryProgress p) { }
        /** 上下文统计变化（used/max 估算 token；GUI 环形进度圈，仅当前激活会话显示） */
        default void onContextStatsChanged(SessionHandle h, int used, int max) { }
        /** 会话生成了报告等工作区文件。 */
        default void onArtifactCreated(SessionHandle h, String path) { }
        /** 会话被删除（deleteSession / deleteWorkspace 均通知，含非当前空间）：UI 清理页签与缓存 */
        default void onSessionDeleted(SessionHandle h) { }
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
    private final List<Listener> listeners = new ArrayList<Listener>();

    private final Map<String, WorkspaceCtx> ctxByName = new HashMap<String, WorkspaceCtx>();
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
        final List<SessionHandle> sessions = new ArrayList<SessionHandle>();
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
        this.skillSet = new SkillSet(allSkills == null ? new ArrayList<Skill>() : allSkills);
        this.plugins = plugins;
        this.mcp = mcp;
        DiagnosticLog.initialize(jarDir);
        DiagnosticLog.info("startup", "SessionManager 初始化，jarDir=" + jarDir);
        this.secretStore = new SecretStore(jarDir);
        this.dispatcher = new CommandDispatcher();
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
    }

    public WorkspaceManager workspaces() { return workspaces; }
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
        h.completionUnread = !running;
        if (!running) h.closeRetired();
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
        h.lastArtifactPath = path;
        for (Listener l : listeners) l.onArtifactCreated(h, path);
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

    /**
     * 恢复历史会话：store.list() 跳过损坏项（SessionStore 现有行为），逐 id 装载；
     * 标题取落盘 session.title，titlePending=false（恢复会话已有标题或旧格式无标题→显示占位）。
     */
    private void restoreSessions(WorkspaceCtx ctx) {
        List<SessionStore.SessionMeta> restored;
        try {
            restored = ctx.store.list();
        } catch (Exception e) {
            notifyError("恢复会话失败: " + e.getMessage());
            return;
        }
        // 快照每空间只算一次（扫描一次盘），全部恢复会话共享同一不可变快照：
        // 恢复会话保持当前配置下的技能上下文（技能清单不落盘，无逐会话差异可言）
        SkillSet.Result sk = skillsOf(ctx.name);
        String mdAbs = projectMdOf(ctx.name);
        String projSkills = projectSkillsDirOf(ctx.name);
        for (SessionStore.SessionMeta meta : restored) {
            try {
                Session s = ctx.store.load(meta.id);
                ModelConfig mc = modelForSession(s);
                LlmClient llm = newLlm(mc);
                ContextManager cm = new ContextManager(mc.maxContextTokens, mc.compressThreshold,
                        mc.keepRecentMessages, llm,
                        TokenCounter.estimate(new SystemPromptBuilder(mdAbs, ctx.workspace.workDir(),
                                tmpDirOf(meta.id).toString(), config.emptyOutputPlaceholder(), projSkills)
                                .build(sk.skills)));
                SessionController controller = new SessionController();
                controller.replayHistory(s.messages); // 历史消息灌入事件流：点击会话即可重放显示
                AgentLoop loop = new AgentLoop(llm, newRegistry(ctx, s),
                        new SystemPromptBuilder(mdAbs, ctx.workspace.workDir(),
                                tmpDirOf(meta.id).toString(), config.emptyOutputPlaceholder(), projSkills),
                        ctx.confirmGate, controller, cm, ctx.workspace, s);
                loop.emptyOutputPlaceholder = config.emptyOutputPlaceholder(); // 工具空输出占位开关注入
                loop.setAllSkills(sk.skills); // 会话级快照：本会话独享、不可变
                loop.setSessionStore(ctx.store); // 落盘接线：恢复后随每轮/退出兜底落盘
                loop.restoreSession(s); // 原地装载 + 半轮残留清洗 + cwd 恢复
                SessionHandle h = new SessionHandle(s.id, ctx.name, s, loop, controller,
                        s.title, false, llm);
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
                ctx.sessions.add(h);
                registerConnectedMcpTools(h); // 兜底：恢复会话也带 MCP 工具（连接已完成场景）
            } catch (Exception e) {
                notifyError("会话恢复失败（跳过）: " + e.getMessage());
            }
        }
    }

    private WorkspaceCtx buildCtx(WorkspaceConfig w) {
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        Workspace workspace = new Workspace(w.workDir);
        String projSkills = WorkspacePaths.projectSkillsDir(w);
        workspace.setExtraAllowedDirs(projSkills == null
                ? new ArrayList<String>() : java.util.Collections.singletonList(projSkills));
        ConfirmGate gate = new ConfirmGate(config, confirmUi);
        WorkspaceCtx ctx = new WorkspaceCtx(w.workSpaceName, workspace,
                new SessionStore(WorkspaceManager.sessionDirFor(jarDir, w.workSpaceName)),
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
        ToolRegistry registry = new ToolRegistry();
        String skillsDir = ctx.skillsDir;
        Workspace workspace = ctx.workspace;
        ConfirmGate gate = ctx.confirmGate;
        String sessionId = session.id;
        String tmpDir = tmpDirOf(sessionId).toString();
        CheckpointStore checkpoints = new CheckpointStore(Paths.get(workspace.workDir()),
                Paths.get(workspace.workDir()).resolve(".minion").resolve("checkpoints").resolve(sessionId));
        registry.register(new ReadTool(workspace, skillsDir, tmpDir, gate));
        registry.register(new WriteTool(workspace, skillsDir, tmpDir, checkpoints, gate));
        registry.register(new EditTool(workspace, skillsDir, tmpDir, checkpoints, gate));
        registry.register(new GlobTool(workspace, skillsDir, tmpDir, gate));
        registry.register(new GrepTool(workspace, skillsDir, tmpDir, gate));
        registry.register(new BashTool(workspace, tmpDirOf(sessionId)));
        PythonRuntime python = new PythonRuntime(config.pythonPath());
        registry.register(new FilesTool(workspace));
        registry.register(new GitTool(workspace));
        ProcessTool processTool = new ProcessTool(workspace);
        registry.register(processTool);
        registry.register(new ProjectTool(workspace));
        registry.register(new BuildTool(workspace, python, processTool));
        registry.register(new PythonTool(workspace, python));
        registry.register(new ExcelTool(workspace, python, checkpoints));
        registry.register(new SqliteTool(workspace, python));
        registry.register(new DatabaseTool(workspace, python, checkpoints, secretStore));
        registry.register(new DocumentTool(workspace, python, checkpoints));
        registry.register(new TextProcessTool(workspace, checkpoints));
        registry.register(new KnowledgeTool(workspace));
        registry.register(new MemoryTool(workspace, checkpoints));
        registry.register(new ContextTool(session, modelForSession(session).maxContextTokens, config));
        registry.register(new PermissionTool(config));
        registry.register(new SecretsTool(secretStore));
        registry.register(new SkillAdminTool(skillsDir, workspace));
        registry.register(new CheckpointTool(checkpoints));
        registry.register(new DiagnosticsTool(config, workspace, python, jarDir,
                plugins == null ? null : plugins.store().browserConfig()));
        registry.register(new LogsTool(workspace));
        registry.register(new UpdateTool(jarDir));
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
                registry.register(new McpProxyTool(mcp, server.name, info));
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
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        if (ctx == null) return null; // 终审修复：deleteWorkspace 有运行中会话时 ctx 先移除、currentWorkspaceName 后台回退（≤5s 窗口），防 FX 线程 NPE
        ModelConfig mc = models.current();
        Session s = Session.create(ctx.workspace.workDir(), mc.modelName);
        s.modelDisplayName = mc.displayName;
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
        SessionController controller = new SessionController();
        AgentLoop loop = new AgentLoop(llm, newRegistry(ctx, s),
                new SystemPromptBuilder(mdAbs, ctx.workspace.workDir(),
                        tmpDirOf(s.id).toString(), config.emptyOutputPlaceholder(), projSkills),
                ctx.confirmGate, controller, cm, ctx.workspace, s);
        loop.emptyOutputPlaceholder = config.emptyOutputPlaceholder(); // 工具空输出占位开关注入
        loop.setAllSkills(sk.skills);   // 会话级快照：本会话独享、不可变
        loop.setSessionStore(ctx.store); // 落盘接线：每轮/退出兜底落盘生效
        SessionHandle h = new SessionHandle(s.id, currentWorkspaceName, s, loop, controller,
                title, title == null, llm);
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
        ctx.sessions.add(h);
        if (!ctx.order.isEmpty()) {
            ctx.order.add(0, h.id);
            saveSessionOrder(ctx);
        }
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

    public ModelConfig modelForSession(SessionHandle h) {
        return h == null ? models.current() : modelForSession(h.session);
    }

    private ModelConfig modelForSession(Session session) {
        ModelConfig chosen = session.modelDisplayName == null
                ? null : models.get(session.modelDisplayName);
        return chosen == null ? models.current() : chosen;
    }

    /** 只切换指定会话；不修改全局默认模型。 */
    public boolean selectModelForSession(SessionHandle h, String name) {
        ModelConfig mc = models.get(name);
        WorkspaceCtx ctx = h == null ? null : ctxByName.get(h.workspaceName);
        if (mc == null || ctx == null || h.deleted || !ctx.sessions.contains(h)) return false;
        if (name.equals(h.session.modelDisplayName)) return true;
        replaceSessionModel(h, mc);
        if (!h.running) persist(h); // 运行中的会话在本轮结束时自然落盘，避免并发遍历消息
        return true;
    }

    private void replaceSessionModel(SessionHandle h, ModelConfig mc) {
        LlmClient fresh = newLlm(mc);
        LlmClient old = h.llm;
        h.llm = fresh;
        h.retireLlm(old);
        h.loop.setLlm(fresh);
        ContextManager cm = h.loop.contextManager();
        if (cm != null) {
            cm.setLlm(fresh);
            cm.update(mc.maxContextTokens, mc.compressThreshold, mc.keepRecentMessages);
        }
        Tool contextTool = h.loop.registry().get("Context");
        if (contextTool instanceof ContextTool) ((ContextTool) contextTool).setMaxTokens(mc.maxContextTokens);
        h.session.modelName = mc.modelName;
        h.session.modelDisplayName = mc.displayName;
    }

    /** 配置修改后按各会话自己的选择刷新客户端；全局激活模型作为新会话默认值。 */
    public void applyModelChanged() {
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                replaceSessionModel(h, modelForSession(h));
                if (!h.running) persist(h);
            }
        }
    }

    public List<SessionHandle> sessions() {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        if (ctx == null) return new ArrayList<SessionHandle>();
        List<SessionHandle> visible = new ArrayList<SessionHandle>();
        for (SessionHandle h : ctx.sessions) if (!h.session.archived) visible.add(h);
        visible.sort(new Comparator<SessionHandle>() {
            @Override public int compare(SessionHandle a, SessionHandle b) {
                if (!ctx.order.isEmpty()) {
                    int ai = ctx.order.indexOf(a.id), bi = ctx.order.indexOf(b.id);
                    if (ai >= 0 && bi >= 0) return Integer.compare(ai, bi);
                    if (ai >= 0) return -1;
                    if (bi >= 0) return 1;
                }
                if (a.session.pinned != b.session.pinned) return a.session.pinned ? -1 : 1;
                return b.id.compareTo(a.id);
            }
        });
        return visible;
    }

    /** 同项目中把会话拖到目标的上方或下方；排序即时落盘，重启后保持。 */
    public boolean reorderSession(SessionHandle source, SessionHandle target, boolean before) {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        if (ctx == null || source == null || target == null || source == target
                || source.deleted || target.deleted || source.session.archived || target.session.archived
                || !ctx.sessions.contains(source) || !ctx.sessions.contains(target)) return false;
        List<SessionHandle> visible = sessions();
        visible.remove(source);
        int index = visible.indexOf(target);
        if (index < 0) return false;
        visible.add(before ? index : index + 1, source);
        ctx.order.clear();
        for (SessionHandle h : visible) ctx.order.add(h.id);
        for (SessionHandle h : ctx.sessions) if (h.session.archived) ctx.order.add(h.id);
        saveSessionOrder(ctx);
        return true;
    }

    private void saveSessionOrder(WorkspaceCtx ctx) {
        try { ctx.store.saveOrder(ctx.order); }
        catch (IOException e) { notifyError("保存会话顺序失败: " + e.getMessage()); }
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

    public SessionHandle currentSession() { return currentSession; }

    public void renameSession(SessionHandle h, String newTitle) {
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
        h.loop.shutdown();
        h.pool.shutdownNow();
        h.closeAll(); // 会话删除即释放其 LLM 客户端（当前 + 待回收，okhttp 资源）
        ctx.sessions.remove(h);
        if (ctx.order.remove(h.id)) saveSessionOrder(ctx);
        try {
            ctx.store.delete(h.id);
        } catch (Exception e) {
            notifyError("删除会话文件失败: " + e.getMessage());
        }
        // 会话临时目录一并清理；运行中删除时落盘文件可能被占用（Windows 句柄），
        // 删除失败静默容错（deleteRecursively 内部吞错），由启动清理（Main 3 天过期清理）兜底
        deleteRecursively(tmpDirOf(h.id));
        h.controller.eventList().setActive(false, null); // 移除被删会话的 active 残留
        if (currentSession == h) currentSession = null;
    }

    public void activateSession(SessionHandle h) {
        if (h.deleted) return; // 已删句柄不可再激活（防已删会话残留激活态/后续 send 落空）
        if (!currentWorkspaceName.equals(h.workspaceName)) return; // 非当前工作空间的句柄不激活
        h.completionUnread = false; // 用户点击查看后清除绿色完成提示；重复激活同样生效
        if (currentSession == h) return; // 重复激活（页签选中/左侧点击重叠）幂等跳过，避免重放闪烁
        if (currentSession != null) currentSession.controller.eventList().setActive(false, null);
        currentSession = h;
        h.controller.eventList().setActive(true, null);
        notifyActivated(h);
    }

    /** 取消激活态（关闭激活中会话的页签时调用）：
     *  UI 已卸载该会话视图但 currentSession 仍指向它——activateSession 的幂等守卫
     *  （currentSession == h 直接 return）会挡住用户从左侧再次打开，故须置空。 */
    public void deactivateSession(SessionHandle h) {
        if (currentSession == h) {
            currentSession.controller.eventList().setActive(false, null);
            currentSession = null;
        }
    }

    /** 工作空间切换（UI 层负责换绑视图；此处切上下文与激活态） */
    public void switchWorkspace(String name) {
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
        ctx.store = new SessionStore(WorkspaceManager.sessionDirFor(jarDir, newName)); // 目录已迁移，store 跟随
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
            h.loop.shutdown();
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
        if (h == null) return;
        final WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        h.pool.submit(new Runnable() {
            @Override public void run() {
                try {
                    if (h.deleted) return; // 队列积压期间被删除
                    if (h.titlePending) {
                        h.title = TitleGenerator.localTitle(text); // 本地截取，不再走 LLM 摘要
                        if (h.deleted) return; // 摘要期间被删除：不再落盘/通知
                        h.titlePending = false;
                        h.session.title = h.title;
                        persist(h);
                        notifyTitleChanged(h);
                    }
                    h.running = true;
                    notifyRunningChanged(h, true);
                    try {
                        String currentText = text;
                        List<ImagePart> currentImages = images;
                        while (!h.deleted) {
                            h.loop.runUserTurn(currentText, currentImages);
                            SessionHandle.QueuedMessage queued = h.pollQueued();
                            if (queued == null) break;
                            h.controller.onSystem("开始执行队列中的下一项任务");
                            currentText = queued.text;
                            currentImages = null;
                        }
                    } finally {
                        if (h.deleted) {
                            // 运行中被删除：runUserTurn 退出路径已把文件写回（deleted 对 AgentLoop 不可见），
                            // 此处补删，防重启后 restore 复活
                            try { ctx.store.delete(h.id); }
                            catch (Exception e) { notifyError("删除会话文件失败: " + e.getMessage()); }
                        }
                        h.running = false;
                        if (h.askPending) { // 中断路径 onAskUserDone 不回调，此处兜底复位
                            h.askPending = false;
                            h.askQuestion = null;
                            notifyAskChanged(h, false);
                        }
                        notifyRunningChanged(h, false);
                    }
                } catch (Exception e) {
                    h.running = false;
                    if (h.askPending) { // 中断路径 onAskUserDone 不回调，此处兜底复位
                        h.askPending = false;
                        h.askQuestion = null;
                        notifyAskChanged(h, false);
                    }
                    notifyRunningChanged(h, false);
                    notifyError("任务执行异常: " + e.getMessage());
                }
            }
        });
    }

    public void stop(SessionHandle h) {
        if (h != null && h.running) {
            h.loop.interrupt(); // 只取消当前客户端；换模型后 in-flight 请求在旧（已退役）客户端上
            h.closeRetired();   // 一并取消旧客户端的流式请求，防「终止」失效等旧流自然结束（数分钟）
        }
    }

    /** 运行中补充：入 AgentLoop 挂起队列 + 发聊天标识事件（UI 事件仅在点击时发一次，注入不重发） */
    public void sendSupplement(final SessionHandle h, final String text) { sendSupplement(h, text, null); }

    /** 运行中补充（带图）：图片占位并入聊天标识事件（聊天区不渲染图片本体） */
    public void sendSupplement(final SessionHandle h, final String text, final List<ImagePart> images) {
        if (h == null || text == null || text.trim().isEmpty()) return;
        h.loop.offerSupplement(text, images);
        h.controller.onUserSupplement(ImagePart.displayText(images, text));
    }

    /** 回答 AskUserQuestion：完成挂起的等待（未挂起时忽略）；回答作为工具结果回传继续本轮 */
    public void sendAnswer(final SessionHandle h, final String text) {
        if (h == null || !h.running) return;
        h.loop.answerAskUser(text);
    }

    /** 当前工作空间 workDir（文件补全遍历根；无当前空间返回 null） */
    public String currentWorkspaceDir() {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        return ctx == null ? null : ctx.workspace.workDir();
    }

    /** 斜杠命令本地分发：命中 → 聊天区回显命令 + 系统行结果（不入 LLM 历史）；未命中 → 按普通消息发送 */
    public void dispatchCommand(SessionHandle h, String text) {
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
        if (!(trimmed.equalsIgnoreCase("/sessions") || trimmed.toLowerCase().startsWith("/sessions ")
                || trimmed.equalsIgnoreCase("/session") || trimmed.toLowerCase().startsWith("/session "))) return null;
        String[] parts = trimmed.split("\\s+", 3);
        if ("/sessions".equalsIgnoreCase(parts[0])) {
            String query = parts.length > 1 ? trimmed.substring(trimmed.indexOf(' ') + 1).trim() : "";
            boolean archivedOnly = "archived".equalsIgnoreCase(query), all = "all".equalsIgnoreCase(query);
            WorkspaceCtx ctx = ctxByName.get(h.workspaceName); if (ctx == null) return "工作空间不存在";
            StringBuilder out = new StringBuilder("会话目录："); int count=0;
            for (SessionHandle item : ctx.sessions) {
                String hay=(item.title==null?"":item.title)+" "+item.session.preview()+" "+item.id;
                if(archivedOnly&&!item.session.archived)continue;
                if(!all&&!archivedOnly&&!query.isEmpty()&&!hay.toLowerCase().contains(query.toLowerCase()))continue;
                if(!all&&!archivedOnly&&query.isEmpty()&&item.session.archived)continue;
                out.append('\n').append(item.session.pinned?"[置顶] ":"").append(item.session.archived?"[归档] ":"")
                        .append(item.id).append("  ").append(item.title==null?"(新会话)":item.title).append(" — ").append(item.session.preview());count++;
            }
            return count==0?"未找到会话":out.append("\n共 ").append(count).append(" 个").toString();
        }
        if(parts.length<2)return "用法: /session pin|unpin|archive|unarchive|fork|export [路径]|import <路径>";
        String action=parts[1].toLowerCase();
        if("pin".equals(action)||"unpin".equals(action)){h.session.pinned="pin".equals(action);persist(h);notifyTitleChanged(h);return h.session.pinned?"会话已置顶":"已取消置顶";}
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
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
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
        com.minion.core.tools.Tool tool = h.loop.registry().get("Process");
        if (tool instanceof ProcessTool) ((ProcessTool) tool).shutdown();
    }
}
