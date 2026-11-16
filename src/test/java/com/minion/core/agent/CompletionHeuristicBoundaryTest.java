package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Test;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the boundary between an explanatory sentence and a pending execution plan. */
public class CompletionHeuristicBoundaryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void explanatoryUseOfCheckIsNotAnExecutionPlan() {
        assertFalse(AgentLoop.looksLikeInterimAnswer(
                "让我检查这个表述：这里是在解释操作方法，不执行任务。"));
    }

    @Test public void explicitPlanAtTheEndStillRequiresContinuation() {
        String plan = "第一个子agent完成。让我核对result_0.json文件真实存在且内容正确，然后继续派发。"
                + "由于chunk有38个，逐个派发会很多轮。让我核对result_0后，考虑更高效但保持准确性的方式。"
                + "实际上，让我继续分批派发。先核对第一个结果文件的真实落地，再继续。"
                + "让我检查results目录及result_0.json内容。";
        assertTrue(AgentLoop.looksLikeInterimAnswer(plan));
    }

    @Test public void operationalVerificationGoalIsNotMistakenForAnAnswer() {
        CompletionContract contract = CompletionContract.from(
                "核对38块并汇总", Collections.<String>emptyList());
        assertTrue(contract.action);
        assertFalse(contract.mutation);
    }

    @Test public void pendingPlanContinuesWithoutNullCompletionDecision() throws Exception {
        Fixture f = fixture();
        try {
            f.llm.addTurnWithTools(Collections.singletonList(exampleCall("scan")), null);
            f.llm.addTurn("第一个子agent完成。让我核对result_0.json文件真实存在且内容正确，然后继续派发。"
                    + "由于chunk有38个，逐个派发会很多轮。让我核对result_0后，考虑更高效但保持准确性的方式。"
                    + "实际上，让我继续分批派发。先核对第一个结果文件的真实落地，再继续。"
                    + "让我检查results目录及result_0.json内容。");
            f.loop.runUserTurn("核对38块并汇总");
            // 主流对齐第二轮补丁：有工具回执的普通模式回合，计划性措辞不再拦截——模型 stop 即回合结束
            assertEquals(2,f.llm.requests.size());
            assertTrue(f.loop.lastTurnSucceeded());
            assertEquals("completed",f.loop.session().taskState.stopDecision.status);
        } finally {
            f.loop.shutdown();
        }
    }

    private Fixture fixture() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        Workspace ws = new Workspace(tmp.getRoot().getPath());
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        registry.register(new com.minion.core.tools.ReadTool(ws));
        registry.register(new com.minion.core.tools.WriteTool(ws));
        List<String> warnings = new ArrayList<String>();
        AgentLoop loop = new AgentLoop(llm, registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),
                        new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi() {
                    @Override public void onWarning(String message) { warnings.add(message); }
                    @Override public void onError(String message) { warnings.add(message); }
                },
                new ContextManager(900000, .9, 50, llm, 0), ws,
                Session.create(ws.workDir(), "deepseek-v4-flash-0731"));
        return new Fixture(llm, loop, warnings);
    }

    private static ToolCall exampleCall(String id) {
        ToolCall call = new ToolCall();
        call.id = id;
        call.name = "example";
        call.arguments = "{\"text\":\"核验完成\"}";
        return call;
    }

    private static final class Fixture {
        final FakeLlmClient llm;
        final AgentLoop loop;
        final List<String> warnings;

        Fixture(FakeLlmClient llm, AgentLoop loop, List<String> warnings) {
            this.llm = llm;
            this.loop = loop;
            this.warnings = warnings;
        }
    }
}
