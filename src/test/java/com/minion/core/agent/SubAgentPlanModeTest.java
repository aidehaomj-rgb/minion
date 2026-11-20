package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.TaskTool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

import static org.junit.Assert.*;

/**
 * §8.1 子代理增强验收：plan 模式（只读规划）、run_in_background 登记即返回、mode 校验。
 */
public class SubAgentPlanModeTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final class Rig {
        final FakeLlmClient llm = new FakeLlmClient();
        final AgentLoop loop;
        final Config config;

        Rig(TemporaryFolder tmp) {
            config = Config.load(tmp.getRoot().toPath());
            ToolRegistry registry = new ToolRegistry();
            registry.register(new com.minion.core.tools.example.ExampleTool());
            loop = new AgentLoop(llm, registry,
                    new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                    new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                    new RecordingUi(), null,
                    new Workspace(tmp.getRoot().getPath()),
                    Session.create(tmp.getRoot().getPath(), "test-model"));
            loop.roundLimit = 10;
        }

        ToolCall taskCall(String argumentsJson) {
            ToolCall tc = new ToolCall();
            tc.id = "t1";
            tc.name = "task";
            tc.arguments = argumentsJson;
            return tc;
        }
    }

    /** plan 模式与 explore 同级只读：example（非只读白名单工具）不进子 agent 工具面 */
    @Test public void planModeRunsReadOnlyToolSurface() throws Exception {
        Rig rig = new Rig(tmp);
        rig.llm.addTurnWithTools(Collections.singletonList(rig.taskCall(
                "{\"description\":\"规划重构\",\"mode\":\"plan\"}")), null);
        rig.llm.addTurn("规划完成");
        rig.loop.runUserTurn("派规划");
        // 子 agent 请求（requests[1]）工具面不含 example（worker 模式下含，见 taskTool_endToEnd_defaultRunner）
        assertTrue(rig.llm.requests.size() >= 2);
        assertTrue(rig.llm.requests.get(1).tools.stream()
                .noneMatch(t -> "example".equals(t.getAsJsonObject("function").get("name").getAsString())));
        // 只读白名单内已注册工具（CodeIndex）保持可见
        assertTrue(rig.llm.requests.get(1).tools.stream()
                .anyMatch(t -> "codeindex".equalsIgnoreCase(t.getAsJsonObject("function").get("name").getAsString())));
    }

    /** run_in_background：登记即返回 childId，不消耗子 agent LLM 请求，child 进入 taskState */
    @Test public void runInBackgroundRegistersWithoutExecuting() throws Exception {
        Rig rig = new Rig(tmp);
        rig.llm.addTurnWithTools(Collections.singletonList(rig.taskCall(
                "{\"description\":\"后台调研\",\"mode\":\"plan\",\"run_in_background\":true}")), null);
        rig.llm.addTurn("已登记后台任务");
        rig.loop.runUserTurn("派后台");
        // 只有主 agent 请求（1 轮派发 + 后续），没有任何子 agent 请求
        assertTrue(rig.llm.requests.size() >= 1);
        for (int i = 1; i < rig.llm.requests.size(); i++)
            assertFalse("run_in_background 不应执行子 agent", rig.llm.requests.get(i).messages.size() == 2
                    && rig.llm.requests.get(i).messages.get(1).content.contains("后台调研"));
        // tool 结果包含 childId 与跟踪指引
        Message toolMsg = null;
        for (Message m : rig.loop.messages())
            if (Message.Role.TOOL == m.role) { toolMsg = m; break; }
        assertNotNull(toolMsg);
        assertTrue(toolMsg.content.contains("childId="));
        assertTrue(toolMsg.content.contains("child_status"));
        // taskState 中登记了 plan 模式 child
        TaskState.Child registered = null;
        for (TaskState.Child c : rig.loop.session().taskState.children)
            if (c.assignment.contains("后台调研")) registered = c;
        assertNotNull(registered);
        assertEquals("plan", registered.mode);
    }

    /** 非法 mode 拒绝（fail-closed，不静默当 worker） */
    @Test public void invalidModeRejected() throws Exception {
        Rig rig = new Rig(tmp);
        rig.llm.addTurnWithTools(Collections.singletonList(rig.taskCall(
                "{\"description\":\"x\",\"mode\":\"admin\"}")), null);
        rig.llm.addTurn("被拒");
        rig.loop.runUserTurn("派");
        Message toolMsg = null;
        for (Message m : rig.loop.messages())
            if (Message.Role.TOOL == m.role) { toolMsg = m; break; }
        assertNotNull(toolMsg);
        assertTrue(toolMsg.content.contains("mode只支持worker/explore/plan"));
    }
}
