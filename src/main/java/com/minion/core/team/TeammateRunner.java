package com.minion.core.team;

import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.llm.DeepSeekClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Teammate 线程生命周期（v2.4 §8.3 Win7 实现）：独立 Java 线程跑独立 AgentLoop 实例
 * （不 spawn tmux）——独立 Session/LlmClient/工具注册表/权限上下文；共享连接池由全局
 * Runtime 持有，成员退出不关闭共享资源。用条件变量唤醒轮询邮箱（§8.3：跨进程场景才
 * 2s 轮询兜底）。
 */
public final class TeammateRunner {

    /** 单个 teammate 的运行时状态 */
    public static final class Teammate {
        public final String name;
        public volatile Thread thread;   // 构造后注入（start() 时）
        final TeamMailbox mailbox;
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile String status = "starting";
        volatile String lastResult = "";

        Teammate(String name, Thread thread, TeamMailbox mailbox) {
            this.name = name;
            this.thread = thread;
            this.mailbox = mailbox;
        }
    }

    private final Path teamsRoot;
    private final String workDir;
    private final Config config;
    private final Map<String, Teammate> teammates = new ConcurrentHashMap<String, Teammate>();
    private final List<String> eventLog = new CopyOnWriteArrayList<String>();

    public TeammateRunner(String workDir, Config config) {
        this.workDir = workDir;
        this.config = config;
        this.teamsRoot = Paths.get(workDir, ".minion", "teams");
    }

    /** 启动一个 teammate（线程即返回；线程内跑 AgentLoop 消费邮箱消息） */
    public Teammate start(String team, String name) {
        return teammates.compute(name, (n, existing) -> {
            if (existing != null && existing.thread.isAlive()) return existing;
            TeamMailbox mailbox = new TeamMailbox(teamsRoot, team);
            final Teammate holder = new Teammate(n, null, mailbox);
            Thread t = new Thread(() -> runTeammate(team, holder),
                    "minion-teammate-" + n);
            t.setDaemon(true);
            holder.thread = t;   // 构造后注入（volatile 保证可见性）
            t.start();
            eventLog.add("started: " + n);
            return holder;
        });
    }

    /** 停止一个 teammate（shutdown_request 消息 + 线程 join） */
    public void stop(String name) {
        Teammate t = teammates.remove(name);
        if (t == null) return;
        t.running.set(false);
        try { t.thread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        eventLog.add("stopped: " + name);
    }

    /** 停止全部 */
    public void stopAll() {
        for (String name : new java.util.HashSet<String>(teammates.keySet())) stop(name);
    }

    public Map<String, Teammate> active() { return teammates; }
    public List<String> events() { return eventLog; }

    /** teammate 线程主循环：轮询邮箱消费消息 → 跑一轮 AgentLoop → 写回执 */
    private void runTeammate(String team, Teammate self) {
        String cursorId = null;
        while (self.running.get()) {
            try {
                List<TeamMailbox.Message> incoming = self.mailbox.receive(self.name);
                if (!incoming.isEmpty()) {
                    for (TeamMailbox.Message msg : incoming) {
                        if ("shutdown_request".equals(msg.type)) {
                            self.status = "shutting-down";
                            self.running.set(false);
                            break;
                        }
                        // 正常消息 → 跑一轮
                        self.status = "working";
                        String reply = executeOneTurn(team, self.name, msg.from, msg.text);
                        self.mailbox.send(self.name, msg.from, "text", reply);
                        cursorId = msg.messageId;
                        self.status = "idle";
                    }
                    if (cursorId != null) self.mailbox.ack(self.name, cursorId);
                }
                Thread.sleep(2000);   // 邮箱轮询间隔
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                DiagnosticLog.warn("teammate", self.name + " 异常: " + e.getMessage());
                self.status = "error";
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            }
        }
        self.status = "stopped";
    }

    /** 单轮执行：构造独立 AgentLoop（受限工具集）跑一次 prompt */
    private String executeOneTurn(String team, String name, String from, String prompt) {
        try {
            Workspace workspace = new Workspace(workDir);
            LlmClient llm = new DeepSeekClient(
                    config.get("model.url", ""),
                    config.get("model.key", ""),
                    config.get("model.name", "deepseek-v4-flash"),
                    Boolean.parseBoolean(config.get("model.thinking", "true")),
                    config.get("model.reasoningEffort", "max"),
                    config.get("model.provider", ""),
                    Integer.parseInt(config.get("model.maxOutputTokens", "8192")), null);
            ToolRegistry registry = new ToolRegistry();
            registry.register(new com.minion.core.tools.ReadTool(workspace));
            registry.register(new com.minion.core.tools.GlobTool(workspace));
            registry.register(new com.minion.core.tools.GrepTool(workspace));
            ConfirmGate gate = new ConfirmGate(config,
                    new ConfirmUi() {
                        @Override public Decision ask(String message) { return Decision.REJECT; }
                    });
            Session session = Session.create(workDir, config.get("model.name", "deepseek-v4-flash"));
            com.minion.core.agent.AgentLoop loop = new com.minion.core.agent.AgentLoop(
                    llm, registry, new SystemPromptBuilder(workDir + "/project.md"),
                    gate, new com.minion.core.cli.HeadlessUi(null, session.id), null, workspace, session);
            // §10.9 持续信任：仅续用已批准 Hook 指纹（无弹窗；新/变化项在 teammate 内保持禁用）
            if (Boolean.parseBoolean(config.get("trust.store.v2", "true"))) {
                loop.ccHookSystem().attachTrustStore(
                        com.minion.core.security.ProjectTrustStore.load(Paths.get(workDir)));
                loop.ccHookSystem().tryResumeFromTrust();
            }
            loop.roundLimit = 50;
            loop.runUserTurn("[teammate:" + name + "] 来自 " + from + " 的任务:\n" + prompt);
            boolean ok = loop.lastTurnSucceeded();
            // 取最后 assistant 消息
            for (int i = session.messages.size() - 1; i >= 0; i--) {
                com.minion.core.llm.Message m = session.messages.get(i);
                if (m.role == com.minion.core.llm.Message.Role.ASSISTANT
                        && m.content != null && !m.content.trim().isEmpty())
                    return (ok ? "" : "[部分完成] ") + m.content;
            }
            return ok ? "（完成，无文本输出）" : "（执行失败）";
        } catch (Exception e) {
            return "执行异常: " + e.getMessage();
        }
    }
}
