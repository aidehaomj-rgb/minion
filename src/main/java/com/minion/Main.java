package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpStore;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.tools.plugin.ToolPluginManager;
import com.minion.core.tools.plugin.ToolStore;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.OutputDump;
import com.minion.gui.MinionApp;
import com.minion.gui.confirm.GuiConfirmUi;
import com.minion.gui.session.SessionManager;

import java.nio.file.Paths;
import java.util.List;

/** 入口：装配配置/技能/浏览器/GUI，启动 JavaFX 主窗口（GUI 为唯一界面，CLI 已移除） */
public class Main {

    public static void main(String[] args) throws Exception {
        com.minion.core.tools.WindowsAppIdentity.initialize();
        Config config = Config.load();
        java.nio.file.Path jarDir = Config.jarDir();
        WorkspaceManager workspaces = WorkspaceManager.load(jarDir);

        // 过期输出清理不挡在窗口出现前；交给低优先级后台线程。
        Thread cleanup = new Thread(() -> OutputDump.cleanup(
                jarDir.resolve(".session").resolve("tmp"), OutputDump.RETENTION_MS), "minion-temp-cleanup");
        cleanup.setDaemon(true);
        cleanup.setPriority(Thread.MIN_PRIORITY);
        cleanup.start();
        ModelManager models = ModelManager.load(jarDir);

        // 全局技能目录（所有工作空间/模型共用）
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        SkillManager skillManager = new SkillManager(skillsDir);
        List<Skill> skills = skillManager.scanWithBuiltins();

        // MCP 服务器管理（mcp.json；惰性连接，退出钩子关停子进程）
        // §10.9：stdio 启动命令经持续信任闸门（jarDir 根——用户作用域配置随配置本身保存信任）
        McpManager mcpManager = new McpManager(McpStore.load(jarDir));
        final ConfirmUi mcpConfirmUi = new GuiConfirmUi();
        mcpManager.setTrustGate(new com.minion.core.security.TrustGate(
                com.minion.core.security.ProjectTrustStore.load(jarDir),
                config.trustStoreEnabled(),
                new com.minion.core.security.TrustGate.Prompt() {
                    @Override public boolean askApproval(
                            com.minion.core.security.TrustedExecutable spec,
                            String reason, String summary) {
                        return mcpConfirmUi.ask("MCP 服务器持续信任确认（§10.9）\n服务器: " + spec.id
                                + "\n命令: " + summary + "\n原因: " + reason
                                + "\n\n批准后此指纹持久生效，命令/参数/env 再变化须重新批准。是否批准启动？")
                                == ConfirmUi.Decision.APPROVE;
                    }
                }));

        // 可插拔工具（tools.json）：浏览器懒启动 Chrome、数据库每次调用新建连接；
        // 四项默认不启用，启用状态由设置页「工具」页实时改，全局会话下一轮请求生效
        final ToolPluginManager plugins = new ToolPluginManager(ToolStore.load(jarDir));

        ConfirmUi confirmUi = new GuiConfirmUi();
        SessionManager manager = new SessionManager(confirmUi, config, jarDir,
                workspaces, models, skills, plugins, mcpManager);

        // 退出钩子统一收口：先关会话（AgentLoop + LLM okhttp 资源 + 线程池 + MCP 子进程），再停自启 Chrome
        // （manager.shutdown 幂等——关窗已 shutdown 时此处空转）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            manager.shutdown();
            mcpManager.shutdown();
            plugins.shutdown();
        }));

        MinionApp.start(config, workspaces, models, manager);
    }
}
