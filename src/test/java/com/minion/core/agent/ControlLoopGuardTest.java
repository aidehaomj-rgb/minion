package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ReadTool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 子 agent 相关的空转保护与输出契约（2026-11-23 弱模型派发修复的配套）：
 * 没有可等的子任务还反复 child_wait 要被拦下并指回复核；child_status 必须保持合法 JSON。
 * 来回交替的控制循环保护见同一提交之后的 ControlLoopGuardTest 扩展。
 */
public class ControlLoopGuardTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private FakeLlmClient llm;
    private AgentLoop loop;
    private final List<String> notices = new ArrayList<String>();

    @Before public void setup() {
        llm = new FakeLlmClient();
        Workspace ws = new Workspace(tmp.getRoot().getPath());
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        registry.register(new ReadTool(ws));
        loop = new AgentLoop(llm, registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi() {
                    public void onWarning(String s) { notices.add(s); }
                    public void onError(String s) { notices.add(s); }
                }, null, ws, Session.create(ws.workDir(), "fake"));
        loop.requestExecutionMode(ExecutionMode.GOAL);
        loop.roundLimit = 60;   // 保证停下来的原因是控制循环保护，不是轮数上限
    }

    @After public void cleanup() { loop.shutdown(); }

    private void tool(String id, String name, String arguments) {
        ToolCall call = new ToolCall();
        call.id = id;
        call.name = name;
        call.arguments = arguments;
        llm.addTurnWithTools(Collections.singletonList(call), null);
    }

    private void control(String id, String action) { tool(id, "AgentControl", "{\"action\":\"" + action + "\"}"); }

    /** 纠偏提示只进下一轮请求、不进会话历史，所以到模型实际收到的请求里找 */
    private boolean requestContains(String text) {
        for (com.minion.core.llm.FakeLlmClient.RequestRecord request : llm.requests)
            for (com.minion.core.llm.Message m : request.messages)
                if (m.content != null && m.content.contains(text)) return true;
        return false;
    }

    /** 没有可等的子任务还反复 child_wait（每次立即返回）：拦下并指回去复核已返回的结果 */
    @Test public void repeatedIdleChildWaitStopsWithPointerToReturnedResults() {
        llm.stopKind = "task";
        for (int i = 0; i < 12; i++) control("wait-" + i, "child_wait");
        loop.runUserTurn("等子任务返回");
        assertFalse(loop.lastTurnSucceeded());
        assertEquals("control_loop，实际请求 " + llm.requests.size() + "，首个结果: " + firstControlResult(),
                "control_loop", loop.session().taskState.pauseCode);
        assertTrue("空等的 child_wait 应在几轮内停下，实际请求 " + llm.requests.size(), llm.requests.size() <= 8);
        assertTrue("要指回去复核已返回的结果", requestContains("不要再调用 child_wait"));
    }

    private String firstControlResult() {
        for (com.minion.core.llm.Message m : loop.messages())
            if (m.role == com.minion.core.llm.Message.Role.TOOL && "AgentControl".equals(m.name))
                return String.valueOf(m.content).substring(0, Math.min(300, String.valueOf(m.content).length()));
        return "(无)";
    }

    /** 合法推进：每次参数都不同（逐个复核不同子任务），交替多少次都不算循环 */
    @Test public void distinctControlArgumentsNeverTriggerControlLoop() {
        llm.stopKind = "task";
        llm.stopStatuses.add("completed");
        for (int i = 0; i < 6; i++)
            tool("review-" + i, "AgentControl",
                    "{\"action\":\"review\",\"childId\":\"child-" + i + "\",\"note\":\"已核对\"}");
        llm.addTurn("全部复核完成。");
        loop.runUserTurn("复核子任务");
        assertNotEquals("control_loop", loop.session().taskState.pauseCode);
        assertEquals("6 次复核都应实际执行", 7, llm.requests.size());
    }

    /** 中间夹着真实工作的同名控制调用：计数被清零，不会累积成误判 */
    @Test public void realWorkBetweenControlCallsResetsTheCounter() {
        llm.stopKind = "task";
        llm.stopStatuses.add("completed");
        for (int i = 0; i < 6; i++) {
            control("status-" + i, "status");
            tool("work-" + i, "example", "{\"text\":\"核验产物\"}");
        }
        llm.addTurn("完成。");
        loop.runUserTurn("处理");
        assertNotEquals("control_loop", loop.session().taskState.pauseCode);
        assertEquals(13, llm.requests.size());
        assertTrue(loop.lastTurnSucceeded());
    }

    /** child_status 的结果是给程序解析的 JSON：后台子任务情况只能加字段，不能在后面拼文字 */
    @Test public void childStatusOutputStaysValidJson() {
        loop.session().taskState.begin("核对子任务状态", true);
        ToolResult status = loop.controlChildren(JsonParser.parseString("{\"action\":\"child_status\"}").getAsJsonObject());
        assertTrue(status.output, status.ok);
        JsonObject parsed = JsonParser.parseString(status.output).getAsJsonObject();
        assertTrue(parsed.has("backgroundChildren") && parsed.get("backgroundChildren").isJsonArray());
        assertEquals(0, parsed.getAsJsonArray("backgroundChildren").size());
        assertTrue(parsed.get("note").getAsString().contains("没有排队或运行中的子任务"));
    }
}
