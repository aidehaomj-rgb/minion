package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * v2.4 §12.2 can_use_tool 内嵌链路验收：PreToolUse/权限管线照常先跑 → 宿主回调 →
 * updatedInput 重校验（只收紧不放宽）→ deny/超时 fail-closed；低危已放行工具默认
 * 不打扰宿主（requireForAllTools=false）。GUI/legacy 不装回调零影响。
 */
public class CanUseToolCallbackTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 高危记录工具（执行即留痕） */
    private static final class ProbeTool implements Tool {
        final java.util.List<String> executed = new java.util.ArrayList<String>();
        @Override public String name() { return "Probe"; }
        @Override public String description() { return "探针"; }
        @Override public JsonObject schema() { return new JsonObject(); }
        @Override public boolean isHighRisk(JsonObject args) { return true; }
        @Override public ToolResult execute(JsonObject args) {
            executed.add(args.toString());
            return ToolResult.success("ran:" + args);
        }
    }

    private FakeLlmClient llm;
    private AgentLoop loop;
    private ProbeTool probe;

    @Before public void setup() {
        llm = new FakeLlmClient();
        probe = new ProbeTool();
        Workspace ws = new Workspace(tmp.getRoot().getPath());
        ToolRegistry registry = new ToolRegistry();
        registry.register(probe);
        loop = new AgentLoop(llm, registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),
                        new com.minion.core.tools.confirm.FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi() {
                    public void onContent(String s) { }
                    public void onWarning(String s) { }
                    public void onError(String s) { }
                }, new ContextManager(900000, .9, 50, llm, 0), ws,
                Session.create(ws.workDir(), "deepseek-v4-flash-0731"));
    }

    @After public void cleanup() { loop.shutdown(); }

    private void turnWithProbeCall() {
        ToolCall call = new ToolCall();
        call.id = "p1";
        call.name = "Probe";
        call.arguments = "{\"x\":1}";
        llm.addTurnWithTools(Collections.singletonList(call), null);
        llm.addTurn("探针执行完毕，结果已核对。" + AgentLoop.TASK_COMPLETE_MARKER);
        loop.runUserTurn("跑探针");
    }

    /** 宿主 allow → 工具执行原参；回调收到完整输入 */
    @Test public void hostAllowExecutesWithOriginalInput() {
        final AtomicInteger checks = new AtomicInteger();
        loop.setCanUseToolCallback(new AgentLoop.CanUseToolCallback() {
            @Override public boolean requireForAllTools() { return true; }
            @Override public AgentLoop.CanUseToolCallback.Decision check(
                    String toolUseId, Tool tool, JsonObject input) {
                checks.incrementAndGet();
                assertEquals("Probe", tool.name());
                assertTrue(input.has("x"));
                return AgentLoop.CanUseToolCallback.Decision.allow(null, null);
            }
        });
        turnWithProbeCall();
        assertEquals(1, checks.get());
        assertEquals(1, probe.executed.size());
        assertTrue(loop.lastTurnSucceeded());
    }

    /** 宿主 deny → 工具不执行；宿主超时/断连（返回 null Decision 模拟由 HeadlessRunner 桥负责） */
    @Test public void hostDenyBlocksExecution() {
        loop.setCanUseToolCallback(new AgentLoop.CanUseToolCallback() {
            @Override public boolean requireForAllTools() { return true; }
            @Override public AgentLoop.CanUseToolCallback.Decision check(
                    String toolUseId, Tool tool, JsonObject input) {
                return AgentLoop.CanUseToolCallback.Decision.deny("策略禁止");
            }
        });
        turnWithProbeCall();
        assertTrue("deny 后工具不得执行", probe.executed.isEmpty());
    }

    /** updatedInput：宿主改参 → 复检通过后按改后参数执行（updatedInput 全链路生效） */
    @Test public void updatedInputExecutesWithUpdatedArgs() {
        final JsonObject updated = new JsonObject();
        updated.addProperty("x", 2);
        loop.setCanUseToolCallback(new AgentLoop.CanUseToolCallback() {
            @Override public boolean requireForAllTools() { return true; }
            @Override public AgentLoop.CanUseToolCallback.Decision check(
                    String toolUseId, Tool tool, JsonObject input) {
                return AgentLoop.CanUseToolCallback.Decision.allow(updated, null);
            }
        });
        turnWithProbeCall();
        assertEquals(1, probe.executed.size());
        assertTrue("按 updatedInput 执行", probe.executed.get(0).contains("\"x\":2"));
    }

    /** 策略 deny 前置：宿主回调不可放宽（sessionDeny 挡在宿主之前，check 不被调用） */
    @Test public void sessionDenyBlocksBeforeHost() {
        final AtomicInteger checks = new AtomicInteger();
        loop.setCanUseToolCallback(new AgentLoop.CanUseToolCallback() {
            @Override public boolean requireForAllTools() { return true; }
            @Override public AgentLoop.CanUseToolCallback.Decision check(
                    String toolUseId, Tool tool, JsonObject input) {
                checks.incrementAndGet();
                return AgentLoop.CanUseToolCallback.Decision.allow(null, null);
            }
        });
        loop.confirmGate().applySessionPermissionUpdates(com.google.gson.JsonParser.parseString(
                "[{\"behavior\":\"deny\",\"tool\":\"Probe\"}]").getAsJsonArray());
        turnWithProbeCall();
        assertTrue("策略 deny → 不执行", probe.executed.isEmpty());
        assertEquals("宿主回调不得被咨询（策略优先于宿主）", 0, checks.get());
    }

    /** 未装回调（GUI/legacy）：链路零影响，工具照常执行 */
    @Test public void noCallbackLegacyUnchanged() {
        turnWithProbeCall();
        assertEquals(1, probe.executed.size());
    }
}
