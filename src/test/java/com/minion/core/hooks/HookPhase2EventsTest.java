package com.minion.core.hooks;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ToolCall;
import com.minion.core.security.TrustedExecutable;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * v2.4 §4.7/§14.4 Hook 二期事件验收：8 个新事件注册合法、cc-hooks.json 可声明并触发；
 * TaskCreated/TaskCompleted/StopFailure/PermissionDenied 在 AgentLoop 真实路径触发
 * （marker 落盘证明）；超时/畸形沿既有 §4.7 语义（ask/deny；headless 场景 ask 对通知
 * 事件等效忽略不阻断主流程）。
 */
public class HookPhase2EventsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 8 个二期事件均已注册（配置声明不再报未知事件） */
    @Test public void phase2EventsRegistered() throws Exception {
        Path root = tmp.newFolder().toPath();
        // 全部二期事件写进配置能通过 parse（未知事件会抛异常）
        StringBuilder sb = new StringBuilder("{\"hooks\":{");
        String[] events = {"PermissionDenied", "StopFailure", "TeammateIdle", "TaskCreated",
                "TaskCompleted", "ConfigChange", "WorktreeCreate", "WorktreeRemove"};
        for (int i = 0; i < events.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(events[i]).append("\":[{\"matcher\":\"\",\"hooks\":["
                    + "{\"type\":\"command\",\"command\":\"cmd /c echo x>").append(i)
                    .append(".txt\"}]}]");
        }
        sb.append("}}");
        Path cfg = root.resolve(".minion/cc-hooks.json");   // 本测试 HookSystem 根=root 本身
        Files.createDirectories(cfg.getParent());
        Files.write(cfg, sb.toString().getBytes(StandardCharsets.UTF_8));
        HookSystem system = new HookSystem(root);
        String hash = TrustedExecutable.sha256Hex(Files.readAllBytes(cfg));
        system.approve(hash);
        assertTrue(system.enabled());
        for (String event : events)
            assertTrue(event + " 应可匹配", system.matches(event, ""));
    }

    /** AgentLoop 真实路径：TaskCreated（新目标）→ TaskCompleted（成功收束）触发 */
    @Test public void taskCreatedAndCompletedFire() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows"); return; }
        Path root = tmp.newFolder().toPath();
        Path marker = root.resolve("ev-created.txt");
        Path doneMarker = root.resolve("ev-completed.txt");
        writeHook(root, "TaskCreated", marker.toString());
        appendHook(root, "TaskCompleted", doneMarker.toString());
        FakeLlmClient llm = new FakeLlmClient();
        llm.addTurn("最终答案：42。" + AgentLoop.TASK_COMPLETE_MARKER);
        AgentLoop loop = loop(root, llm);
        approve(root, loop);
        loop.runUserTurn("计算 6×7");
        assertTrue(loop.lastTurnSucceeded());
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline
                && (!Files.exists(marker) || !Files.exists(doneMarker)))
            Thread.sleep(150);
        assertTrue("TaskCreated 应触发", Files.exists(marker));
        assertTrue("TaskCompleted 应触发", Files.exists(doneMarker));
        loop.shutdown();
    }

    /** StopFailure：未完成退出（no_progress 暂停）触发 */
    @Test public void stopFailureFiresOnIncompleteExit() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows"); return; }
        Path root = tmp.newFolder().toPath();
        Path failMarker = root.resolve("ev-failure.txt");
        writeHook(root, "StopFailure", failMarker.toString());
        FakeLlmClient llm = new FakeLlmClient();
        // pendingTodo 下永不交付 → no_progress 暂停（StopFailure）
        llm.addTurn("阶段结果已返回。" + AgentLoop.TASK_COMPLETE_MARKER);
        llm.addTurn("阶段结果已返回。" + AgentLoop.TASK_COMPLETE_MARKER);
        llm.addTurn("阶段结果已返回。" + AgentLoop.TASK_COMPLETE_MARKER);
        llm.addTurn("阶段结果已返回。" + AgentLoop.TASK_COMPLETE_MARKER);
        AgentLoop loop = loop(root, llm);
        approve(root, loop);
        loop.session().taskState.begin("完成整表", false);
        loop.session().todos.replace(Collections.singletonList(
                new com.minion.core.agent.TodoList.TodoItem("汇总整表", false)));
        loop.runUserTurn("继续执行");
        assertFalse(loop.lastTurnSucceeded());
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !Files.exists(failMarker))
            Thread.sleep(150);
        assertTrue("StopFailure 应触发（失败/暂停退出）", Files.exists(failMarker));
        loop.shutdown();
    }

    /** PermissionDenied：用户拒绝（REJECT ConfirmUi）触发 */
    @Test public void permissionDeniedFiresOnUserReject() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows"); return; }
        Path root = tmp.newFolder().toPath();
        Path denyMarker = root.resolve("ev-denied.txt");
        writeHook(root, "PermissionDenied", denyMarker.toString());
        FakeLlmClient llm = new FakeLlmClient();
        // 高危工具调用（Write 覆盖预存在文件）→ ConfirmGate 询问 → REJECT
        Path target = root.resolve("outside.txt");
        Files.write(target, "旧内容".getBytes(StandardCharsets.UTF_8));
        String jsonPath = new com.google.gson.Gson().toJson(target.toString());
        ToolCall call = new ToolCall();
        call.id = "w1";
        call.name = "Write";
        call.arguments = "{\"path\":" + jsonPath + ",\"content\":\"新内容\"}";
        llm.addTurnWithTools(Collections.singletonList(call), null);
        llm.addTurn("好的，我换个方式。" + AgentLoop.TASK_COMPLETE_MARKER);
        Workspace ws = new Workspace(root.getParent().toString());
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.WriteTool(ws));
        AgentLoop loop = new AgentLoop(llm, registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(root),
                        new com.minion.core.tools.confirm.FakeConfirmUi(ConfirmUi.Decision.REJECT)),
                silentUi(), new ContextManager(900000, .9, 50, llm, 0), ws,
                Session.create(ws.workDir(), "deepseek-v4-flash-0731"));
        approve(root, loop);
        loop.runUserTurn("写文件");
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && !Files.exists(denyMarker))
            Thread.sleep(150);
        assertTrue("PermissionDenied 应在用户拒绝后触发", Files.exists(denyMarker));
        loop.shutdown();
    }

    /** Worktree 事件：成功进出工作树触发（工具名驱动，headless 注册表注入） */
    @Test public void worktreeEventsFireOnToolSuccess() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows"); return; }
        Path root = tmp.newFolder().toPath();
        Path createMarker = root.resolve("ev-wt-create.txt");
        Path removeMarker = root.resolve("ev-wt-remove.txt");
        writeHook(root, "WorktreeCreate", createMarker.toString());
        appendHook(root, "WorktreeRemove", removeMarker.toString());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeWorktreeTool("EnterWorktree"));
        registry.register(new FakeWorktreeTool("ExitWorktree"));
        FakeLlmClient seq = llm;
        AgentLoop loop = new AgentLoop(seq, registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(root),
                        new com.minion.core.tools.confirm.FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                silentUi(), new ContextManager(900000, .9, 50, seq, 0),
                new Workspace(root.getParent().toString()),
                Session.create(root.getParent().toString(), "deepseek-v4-flash-0731"));
        approve(root, loop);
        ToolCall enter = new ToolCall();
        enter.id = "e1"; enter.name = "EnterWorktree"; enter.arguments = "{}";
        ToolCall exit = new ToolCall();
        exit.id = "x1"; exit.name = "ExitWorktree"; exit.arguments = "{}";
        seq.addTurnWithTools(Collections.singletonList(enter), null);
        seq.addTurnWithTools(Collections.singletonList(exit), null);
        seq.addTurn("工作树进出完成。" + AgentLoop.TASK_COMPLETE_MARKER);
        loop.runUserTurn("进出工作树");
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline
                && (!Files.exists(createMarker) || !Files.exists(removeMarker)))
            Thread.sleep(150);
        assertTrue("WorktreeCreate 应触发", Files.exists(createMarker));
        assertTrue("WorktreeRemove 应触发", Files.exists(removeMarker));
        loop.shutdown();
    }

    // ==================== 辅助 ====================

    private static final class FakeWorktreeTool implements com.minion.core.tools.Tool {
        private final String name;
        FakeWorktreeTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public com.google.gson.JsonObject schema() { return new com.google.gson.JsonObject(); }
        @Override public boolean isHighRisk(com.google.gson.JsonObject args) { return false; }
        @Override public com.minion.core.tools.ToolResult execute(com.google.gson.JsonObject args) {
            return com.minion.core.tools.ToolResult.success(name + " ok");
        }
    }

    private static com.minion.core.agent.AgentUi silentUi() {
        return new com.minion.core.agent.AgentUi() {
            public void onContent(String s) { }
            public void onWarning(String s) { }
            public void onError(String s) { }
        };
    }

    private AgentLoop loop(Path root, FakeLlmClient llm) {
        Workspace ws = new Workspace(root.getParent().toString());
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.ReadTool(ws));
        return new AgentLoop(llm, registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(root),
                        new com.minion.core.tools.confirm.FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                silentUi(), new ContextManager(900000, .9, 50, llm, 0), ws,
                Session.create(ws.workDir(), "deepseek-v4-flash-0731"));
    }

    private static void writeHook(Path root, String event, String marker) throws Exception {
        Path cfg = root.getParent().resolve(".minion/cc-hooks.json");   // loop workspace=root.getParent()
        Files.createDirectories(cfg.getParent());
        String command = new com.google.gson.Gson().toJson("cmd /c echo x>" + marker);
        String json = "{\"hooks\":{\"" + event + "\":[{\"matcher\":\"\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":" + command + "}]}]}}";
        Files.write(cfg, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void appendHook(Path root, String event, String marker) throws Exception {
        Path cfg = root.getParent().resolve(".minion/cc-hooks.json");   // loop workspace=root.getParent()
        String existing = new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8);
        String command = new com.google.gson.Gson().toJson("cmd /c echo x>" + marker);
        // addition = ,"Ev":[组对象] —— 结构同 writeHook 的事件条目；substring 截去顶层 }} 再拼回
        String addition = ",\"" + event + "\":[{\"matcher\":\"\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":" + command + "}]}]";
        String json = existing.substring(0, existing.length() - 2) + addition + "}}";
        Files.write(cfg, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void approve(Path root, AgentLoop loop) throws Exception {
        Path cfg = root.getParent().resolve(".minion/cc-hooks.json");   // loop workspace=root.getParent()
        String hash = TrustedExecutable.sha256Hex(Files.readAllBytes(cfg));
        loop.ccHookSystem().approve(hash);
    }
}
