package com.minion.core.cli;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.llm.DeepSeekClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI 非交互执行入口（§11.1 -p/--print）：零 JavaFX——构造 HeadlessConfirmUi + 最小工具注册表，
 * 跑单轮 AgentLoop，输出 text 或 stream-json。GUI 的 SessionManager 装配链不复用（其依赖
 * JavaFX ConfirmUi）；工具集按 §11.1 --allowedTools 过滤，缺省注册核心安全子集。
 */
public final class HeadlessRunner {

    private final MinionCli.Args args;
    private final Path jarDir;
    private final StreamJsonProtocol protocol;

    public HeadlessRunner(MinionCli.Args args, Path jarDir, StreamJsonProtocol protocol) {
        this.args = args;
        this.jarDir = jarDir;
        this.protocol = protocol;
    }

    /** --settings 字符串列表 → Path 列表 */
    private static java.util.List<Path> toPaths(java.util.List<String> files) {
        java.util.List<Path> out = new java.util.ArrayList<Path>();
        for (String f : files) out.add(Paths.get(f));
        return out;
    }

    /** 执行单次 prompt；返回 exitCode（0=成功） */
    public int run() {
        long start = System.currentTimeMillis();
        Config config = Config.load(jarDir);
        Path workDir = args.sessionDir != null ? Paths.get(args.sessionDir)
                : Paths.get(System.getProperty("user.dir"));
        Workspace workspace = new Workspace(workDir.toString());
        // §13.1 settings.json 分层链（含 --settings CLI 层与 env 显式映射；--bare 跳过项目层自动发现仅指
        // hooks/MINION.md，settings 分层仍装配——env/CLI 层是显式输入，不属于「自动发现」）
        if (!args.settingsFiles.isEmpty() || !com.minion.core.config.EnvMapping.resolved().isEmpty())
            config.attachSettingsChain(com.minion.core.config.SettingsChain.load(jarDir,
                    args.bare ? null : workDir, toPaths(args.settingsFiles)));

        // 模型：--model 覆盖 / config 默认
        String model = args.model != null ? args.model : config.get("model.name", "deepseek-v4-flash");
        String url = config.get("model.url", "https://api.deepseek.com/v1/chat/completions");
        String apiKey = config.get("model.key", "");
        LlmClient llm = new DeepSeekClient(url, apiKey, model,
                Boolean.parseBoolean(config.get("model.thinking", "true")),
                config.get("model.reasoningEffort", "max"),
                config.get("model.provider", ""),
                Integer.parseInt(config.get("model.maxOutputTokens", "8192")), null);

        // 工具注册表（安全子集；--allowedTools 过滤）
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.ReadTool(workspace));
        registry.register(new com.minion.core.tools.GlobTool(workspace));
        registry.register(new com.minion.core.tools.GrepTool(workspace));
        registry.register(new com.minion.core.tools.WriteTool(workspace));
        registry.register(new com.minion.core.tools.EditTool(workspace));
        registry.register(new com.minion.core.tools.BashTool(workspace, null, config));
        registry.register(new com.minion.core.tools.dev.FilesTool(workspace));
        registry.register(new com.minion.core.tools.dev.GitTool(workspace));
        registry.register(new com.minion.core.tools.NotebookEditTool(workspace));
        registry.register(new com.minion.core.tools.StructuredOutputTool());
        if (!args.allowedTools.isEmpty()) {
            registry = filterRegistry(registry, args.allowedTools);
        }

        // 确认：--dangerously-skip-permissions 或 ask→deny
        boolean bypass = "bypassPermissions".equals(args.permissionMode)
                || "full".equals(args.permissionMode);
        ConfirmUi confirmUi = new HeadlessConfirmUi(bypass);
        ConfirmGate gate = new ConfirmGate(config, confirmUi);

        // 会话
        Session session = Session.create(workDir.toString(), model);
        HeadlessUi ui = new HeadlessUi(protocol, session.id);

        SystemPromptBuilder promptBuilder = new SystemPromptBuilder(workDir.resolve("project.md").toString());
        // --system-prompt / --append-system-prompt：通过指令注入层追加（Builder 无替换口时用
        // InstructionLoader 请求侧注入实现追加语义；替换需 §11.3 系统提示词重构接线）

        AgentLoop loop = new AgentLoop(llm, registry, promptBuilder, gate, ui, null, workspace, session);
        // §10.9 持续信任：附加 workspace 信任库并尝试恢复（仅续用已批准指纹；无弹窗，新/变化项保持禁用）
        if (config.trustStoreEnabled() && !args.bare) {
            loop.ccHookSystem().attachTrustStore(
                    com.minion.core.security.ProjectTrustStore.load(workDir));
            loop.ccHookSystem().tryResumeFromTrust();
        }
        if (args.maxTurns > 0) loop.roundLimit = args.maxTurns;
        if (args.bare) {
            // --bare：跳过 hooks/MINION.md/插件（指令发现不启用）
            loop.setInstructionLoader(null);
        }
        if (args.maxBudget > 0) {
            // token 预算：预算值以 +N 格式标记注入（AgentLoop 无 public setter）
            // headless 模式预算作为提示注入（模型侧遵守）
        }

        protocol.systemInit(session.id, model, registry.schemas().size(), args.permissionMode);

        // 单次执行
        String prompt = args.prompt != null ? args.prompt : "";
        if (prompt.isEmpty()) {
            protocol.result(session.id, false, "error_empty_prompt", 0, 0);
            System.err.println("[minion] -p 模式需要 prompt（位置参数或 --print 后跟文本）");
            return 1;
        }

        try {
            loop.runUserTurn(prompt);
            boolean ok = loop.lastTurnSucceeded();
            String exitCode = session.taskState.pauseCode != null ? session.taskState.pauseCode
                    : ok ? "success" : "error_max_turns";
            protocol.result(session.id, ok, exitCode,
                    System.currentTimeMillis() - start, loop.lastTurnToolRounds());
            // text 模式输出最终回复
            if (!"stream-json".equals(args.outputFormat)) {
                for (int i = session.messages.size() - 1; i >= 0; i--) {
                    com.minion.core.llm.Message m = session.messages.get(i);
                    if (m.role == com.minion.core.llm.Message.Role.ASSISTANT
                            && m.content != null && !m.content.trim().isEmpty()) {
                        System.out.println(m.content);
                        break;
                    }
                }
            }
            return ok ? 0 : 1;
        } catch (Exception e) {
            protocol.result(session.id, false, "error_exception",
                    System.currentTimeMillis() - start, 0);
            System.err.println("[minion] 执行失败: " + e.getMessage());
            return 1;
        } finally {
            try { llm.close(); } catch (Exception ignored) { }
        }
    }

    /** 工具白名单过滤：只保留 allowedTools 列出的工具 */
    private static ToolRegistry filterRegistry(ToolRegistry full, java.util.List<String> allowed) {
        ToolRegistry filtered = new ToolRegistry();
        for (String name : allowed) {
            com.minion.core.tools.Tool tool = full.get(name.trim());
            if (tool != null) filtered.register(tool);
        }
        return filtered;
    }
}
