package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.security.TrustedExecutable;
import com.minion.core.tools.ReadTool;
import com.minion.core.tools.TodoWriteTool;
import com.minion.core.tools.WriteTool;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * v2.4 §2.6 统一结束与恢复状态机验收（切片 27）：
 * ① Stop Hook 移入裁决级联（StopGate 放行后、预算前）——阻断=续跑补缺、有界封顶不伪造 success；
 * ② Token 预算后移——门禁要求继续时不触发预算续写，门禁全过后才裁决。
 * （协议/流式恢复、模型降级孤儿 tool_call、上下文恢复后工具续接、中断补齐由
 *  AgentContinuationV26Test/AgentActionRecoveryV27Test 既有 40+ 用例覆盖）
 */
public class UnifiedEndStateMachineTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private FakeLlmClient llm;
    private AgentLoop loop;
    private List<String> warnings;
    private Path root;

    @Before public void setup() {
        llm = new FakeLlmClient();
        warnings = new ArrayList<String>();
        root = tmp.getRoot().toPath();
        com.minion.core.tools.Workspace ws = new com.minion.core.tools.Workspace(root.toString());
        com.minion.core.tools.ToolRegistry registry = new com.minion.core.tools.ToolRegistry();
        registry.register(new ReadTool(ws));
        registry.register(new WriteTool(ws));
        loop = new AgentLoop(llm, registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(root),
                        new com.minion.core.tools.confirm.FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi() {
                    public void onContent(String s) { }
                    public void onWarning(String s) { warnings.add(s); }
                    public void onError(String s) { warnings.add(s); }
                }, new ContextManager(900000, .9, 50, llm, 0), ws,
                Session.create(ws.workDir(), "deepseek-v4-flash-0731"));
        registry.register(new TodoWriteTool(loop.session().todos));
    }

    @After public void cleanup() { loop.shutdown(); }

    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 写 Stop hook 配置并批准（会话内批准；无信任库=V68 快照模型） */
    private void approveStopHook(String command) throws Exception {
        Path cfg = root.resolve(".minion").resolve("cc-hooks.json");
        Files.createDirectories(cfg.getParent());
        String commandJson = new com.google.gson.Gson().toJson(command);
        Files.write(cfg, ("{\"hooks\":{\"Stop\":[{\"matcher\":\"\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":" + commandJson
                + ",\"timeout\":10000}]}]}}").getBytes(StandardCharsets.UTF_8));
        String hash = TrustedExecutable.sha256Hex(Files.readAllBytes(cfg));
        loop.ccHookSystem().approve(hash);
    }

    /** ① Stop Hook 阻断一次→续跑→放行→完成（阻断原因进下一请求；不伪造 success 之外路径） */
    @Test public void stopHookBlocksOnceThenAllows() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows：cmd hook 用例跳过"); return; }
        Path flag = root.resolve("hookflag.txt");
        approveStopHook("if exist hookflag.txt (exit 0) else (echo blocked>hookflag.txt & exit /b 2)");
        llm.addTurn("最终答案：42。已完成全部核对。");
        llm.addTurn("最终答案：42。已完成全部核对。");
        loop.runUserTurn("计算 6×7");
        assertEquals("阻断一次后续跑，第二次放行完成", 2, llm.requests.size());
        assertTrue(loop.lastTurnSucceeded());
        assertTrue("hook 实际执行过（flag 已建）", Files.exists(flag));
        // 阻断原因作为续跑提示进入第二个请求
        List<Message> second = llm.requests.get(1).messages;
        boolean hintFound = false;
        for (Message m : second) if (m.content != null && m.content.contains("Stop Hook")) hintFound = true;
        assertTrue("续跑提示须含 Stop Hook 原因", hintFound);
    }

    /** ② Stop Hook 持续阻断 → 无进展预算封顶 → stop_hook 暂停（非 completed，不伪造 success） */
    @Test public void stopHookAlwaysBlocksBoundedPause() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows：cmd hook 用例跳过"); return; }
        approveStopHook("echo 需人工复核 1>&2 & exit /b 2");
        for (int i = 0; i < 8; i++) llm.addTurn("最终答案：42。已完成全部核对。");
        loop.runUserTurn("计算 6×7");
        assertFalse(loop.lastTurnSucceeded());
        assertTrue("有界封顶（≤6 次请求），实际 " + llm.requests.size(), llm.requests.size() >= 2 && llm.requests.size() <= 6);
        assertNotNull(loop.session().taskState.pauseCode);
        assertEquals("stop_hook", loop.session().taskState.pauseCode);
    }

    /** ③ 门禁要求继续时不调用 Stop Hook（第 6 步优先于第 7 步；hook 全程零执行） */
    @Test public void gateContinueSkipsStopHook() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows：cmd hook 用例跳过"); return; }
        Path flag = root.resolve("hook-never.txt");
        approveStopHook("echo blocked>hook-never.txt & exit /b 2");
        String interim = "阶段结果已返回。" + AgentLoop.TASK_COMPLETE_MARKER;   // pendingTodo 下必然续跑
        loop.session().taskState.begin("产出汇总文件", false);
        loop.session().todos.replace(Collections.singletonList(
                new TodoList.TodoItem("写汇总", false)));
        for (int i = 0; i < 8; i++) llm.addTurn(interim);
        loop.runUserTurn("继续执行");
        assertFalse("未交付前不得完成", loop.lastTurnSucceeded());
        assertFalse("门禁继续路径不得执行 Stop Hook; warnings=" + warnings
                + "; pause=" + loop.session().taskState.pauseCode
                + "; requests=" + llm.requests.size(), Files.exists(flag));
    }

    /** ④a 预算后移：门禁继续期间不触发预算续写（旧顺序会在首次停顿就注入预算提醒） */
    @Test public void budgetNotConsultedWhileGateContinues() throws Exception {
        String interim = "阶段结果已返回。" + AgentLoop.TASK_COMPLETE_MARKER;
        loop.session().taskState.begin("产出汇总文件", false);
        loop.session().todos.replace(Collections.singletonList(
                new TodoList.TodoItem("写汇总", false)));
        for (int i = 0; i < 8; i++) llm.addTurn(interim);
        loop.runUserTurn("继续执行\n+999k");
        assertFalse(loop.lastTurnSucceeded());
        for (FakeLlmClient.RequestRecord r : llm.requests)
            for (Message m : r.messages)
                assertFalse("门禁继续期间不得出现预算提醒: " + m.content,
                        m.content != null && m.content.contains("[预算提醒]"));
    }

    /** ④b 预算在门禁全过后裁决：完成前注入预算提醒续写，收益递减后放行完成 */
    @Test public void budgetFiresAfterGatesPass() throws Exception {
        String answer = "最终答案：42。已完成全部核对。";
        for (int i = 0; i < 6; i++) llm.addTurn(answer);
        loop.runUserTurn("计算 6×7\n+999k");
        assertTrue("收益递减后应完成，实际 requests=" + llm.requests.size(), loop.lastTurnSucceeded());
        assertTrue("至少续写一次", llm.requests.size() >= 2);
        boolean budgetHint = false;
        for (FakeLlmClient.RequestRecord r : llm.requests)
            for (Message m : r.messages)
                if (m.content != null && m.content.contains("[预算提醒]")) budgetHint = true;
        assertTrue("门禁放行后预算提醒须出现（后移语义）", budgetHint);
    }

    /** ⑤ 无预算 + 无 hook：普通问答一次完成（V68 主路径零回归） */
    @Test public void plainAnswerCompletesInOneRequest() throws Exception {
        llm.addTurn("最终答案：42。已完成全部核对。");
        loop.runUserTurn("计算 6×7");
        assertEquals(1, llm.requests.size());
        assertTrue(loop.lastTurnSucceeded());
    }

    /** 复刻既有通过用例（AgentContinuationV26Test#pendingTodoCannotEnd…）验证 harness 等价 */
    @Test public void replicaPendingTodoContinues() throws Exception {
        loop.session().taskState.begin("完成整表", false);
        loop.session().todos.replace(Collections.singletonList(new TodoList.TodoItem("汇总整表", false)));
        llm.addTurn("阶段结果已返回。" + AgentLoop.TASK_COMPLETE_MARKER);
        llm.addTurnWithTools(Collections.singletonList(call("todo", "TodoWrite",
                "{\"action\":\"mark\",\"index\":0}")), null);
        llm.addTurn("全部分块已核对并汇总交付。" + AgentLoop.TASK_COMPLETE_MARKER);
        loop.runUserTurn("继续执行");
        assertEquals("既有用例在本 harness 中行为一致（3 请求）", 3, llm.requests.size());
        assertTrue(loop.lastTurnSucceeded());
    }

    private ToolCall call(String id, String name, String args) {
        ToolCall c = new ToolCall();
        c.id = id; c.name = name; c.arguments = args;
        return c;
    }
}
