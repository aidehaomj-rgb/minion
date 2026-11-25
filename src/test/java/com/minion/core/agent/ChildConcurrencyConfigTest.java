package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * J2 验收：协调队列并行度来自配置 agent.childConcurrency（默认 4，夹 1..16），
 * 运行期 child_capacity 覆盖能力保持不变。
 */
public class ChildConcurrencyConfigTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Config configWith(String value) throws Exception {
        Path dir = tmp.newFolder().toPath();
        if (value != null)
            Files.write(dir.resolve("config.properties"), ("agent.childConcurrency=" + value + "\n").getBytes(StandardCharsets.UTF_8));
        return Config.load(dir);
    }

    @Test public void defaultsToFourWhenUnset() throws Exception {
        assertEquals(4, configWith(null).childConcurrency());
    }

    @Test public void readsConfiguredValue() throws Exception {
        assertEquals(9, configWith("9").childConcurrency());
    }

    @Test public void clampsOutOfRangeAndFallsBackOnGarbage() throws Exception {
        assertEquals(1, configWith("0").childConcurrency());
        assertEquals(1, configWith("-3").childConcurrency());
        assertEquals(16, configWith("99").childConcurrency());
        assertEquals(4, configWith("abc").childConcurrency());
    }

    /** 最小 rig：只验证协调器容量接线，子 agent 用 Read+结论 两段脚本走完完成闸门 */
    private AgentLoop rig(int concurrency) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        Workspace ws = new Workspace(tmp.getRoot().getPath());
        registry.register(new com.minion.core.tools.ReadTool(ws));
        registry.register(new com.minion.core.tools.StructuredOutputTool());
        Path note = tmp.getRoot().toPath().resolve("note.txt");
        Files.write(note, "hello".getBytes(StandardCharsets.UTF_8));
        AgentLoop loop = new AgentLoop(new FakeLlmClient(), registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi() {
                    public void onContent(String s) { }
                    public void onWarning(String s) { }
                    public void onError(String s) { }
                }, null, ws, Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.setAgentRuntimeFactory((model, id) -> {
            FakeLlmClient script = new FakeLlmClient();
            ToolCall read = new ToolCall();
            read.id = "read-" + id;
            read.name = "Read";
            read.arguments = "{\"path\":\"" + note.toString().replace("\\", "\\\\") + "\"}";
            script.addTurnWithTools(Collections.singletonList(read), null);
            script.addTurn("结论-" + id);
            return new AgentRuntime(script, "test-model", 60000, 0.8, registry, ws, false);
        });
        loop.roundLimit = 16;
        loop.session().taskState.begin("J2 并行度验收", true);
        loop.childConcurrency = concurrency;
        return loop;
    }

    private int capacity(AgentLoop loop) {
        JsonObject args = new JsonObject();
        args.addProperty("action", "child_status");
        ToolResult r = loop.controlChildren(args);
        assertTrue(r.output, r.ok);
        return JsonParser.parseString(r.output).getAsJsonObject().get("capacity").getAsInt();
    }

    private JsonObject single(AgentLoop loop) {
        JsonObject a = new JsonObject();
        a.addProperty("stepId", "cap-1");
        a.addProperty("description", "只读调查：读 note.txt 并返回结论");
        a.addProperty("mode", "explore");
        com.google.gson.JsonArray scope = new com.google.gson.JsonArray(); scope.add(".");
        a.add("scope", scope);
        a.add("dependsOn", new com.google.gson.JsonArray());
        a.addProperty("acceptance", "返回结论与证据路径");
        a.addProperty("revision", loop.session().taskState.revision());
        return a;
    }

    @Test public void coordinationUsesConfiguredCapacity() throws Exception {
        AgentLoop loop = rig(3);
        assertTrue(loop.submitCoordinatedChild(single(loop)).ok);
        assertEquals(3, capacity(loop));
    }

    @Test public void defaultCapacityIsFour() throws Exception {
        AgentLoop loop = rig(4);
        loop.childConcurrency = 4;
        assertTrue(loop.submitCoordinatedChild(single(loop)).ok);
        assertEquals(4, capacity(loop));
    }

    @Test public void runtimeChildCapacityOverrideStillWorks() throws Exception {
        AgentLoop loop = rig(3);
        assertTrue(loop.submitCoordinatedChild(single(loop)).ok);
        JsonObject args = new JsonObject();
        args.addProperty("action", "child_capacity");
        args.addProperty("capacity", 2);
        args.addProperty("note", "验收运行期覆盖");
        ToolResult r = loop.controlChildren(args);
        assertTrue(r.output, r.ok);
        assertEquals(2, capacity(loop));
    }

    @Test public void runtimeOverrideAboveConfiguredMaxRejected() throws Exception {
        AgentLoop loop = rig(3);
        assertTrue(loop.submitCoordinatedChild(single(loop)).ok);
        JsonObject args = new JsonObject();
        args.addProperty("action", "child_capacity");
        args.addProperty("capacity", 5);
        args.addProperty("note", "越界覆盖应被拒绝");
        ToolResult r = loop.controlChildren(args);
        assertFalse(r.ok);
        assertEquals(3, capacity(loop));
    }
}