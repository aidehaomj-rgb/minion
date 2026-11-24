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

    @Test public void deliveredReportWithReviewedChildrenAndBulletSummaryCompletesImmediately() throws Exception {
        Fixture f = fixture();
        try {
            f.loop.session().taskState.begin("结合附件评估WCO报告存在哪些风险遗漏，撰写WCO战略环境分析报告评估报告.docx", false);
            TaskState.Child reviewed = new TaskState.Child();
            reviewed.id = "c-att2";
            reviewed.taskId = f.loop.session().taskState.id;
            reviewed.status = "reviewed";
            f.loop.session().taskState.children.add(reviewed);

            f.llm.addTurnWithTools(Collections.singletonList(exampleCall("cp-docx")), null);
            String bulletReport = "交付完成\n\n"
                    + "已基于三份文件（附件2主报告、附件4亚太纪要、附件6欧洲纪要）完成分析，生成了Word报告：\n\n"
                    + "文件：WCO战略环境分析报告评估报告.docx（位于工作目录）\n\n"
                    + String.join("", Collections.nCopies(15, "章节分析与表格校验全部通过，涵盖PESTLE六维度与15项遗漏清单。\n"))
                    + "• 问题4：WCO应最优先投入“平台→海关→海关”风险信息交换机制、数据质量标准、电商框架重塑、廉政\n"
                    + "• 问题5：风险管理是贯穿一切的统一主线，核心架构为“数据驱动→实时交换→画像反哺→规则治理”，提出5条深化建议";
            assertTrue(bulletReport.length() > 650);
            assertFalse("长篇列表总结不应被误判为半截断句", AgentLoop.looksLikeInterruptedReply(bulletReport));
            f.llm.addTurn(bulletReport);

            f.loop.runUserTurn("继续交付最终评估报告");
            assertTrue("已复核子任务且正文明确交付完成时必须直接完成: warnings=" + f.warnings, f.loop.lastTurnSucceeded());
            assertEquals("completed", f.loop.session().taskState.stopDecision.status);
            assertFalse("不应再误报‘尚未给出用户所需回答’", f.warnings.toString().contains("尚未给出用户所需回答"));
        } finally {
            f.loop.shutdown();
        }
    }

    @Test public void midRunHandoffAndReadOnlyBeforeMutationContinueInsteadOfPrematureStop() throws Exception {
        // 1) 明确中途交接短句（“文档已生成。现在登记复核并完成任务。”）不能提前结束
        Fixture f1 = fixture();
        try {
            f1.llm.addTurnWithTools(Collections.singletonList(exampleCall("cp-doc")), null);
            f1.llm.addTurn("文档已生成。现在登记复核并完成任务。");
            f1.llm.addTurn("报告文件 WCO战略环境分析报告评估报告.docx 已生成完毕并保存在工作目录，共10张表格校验通过。");
            f1.loop.runUserTurn("生成并交付WCO评估报告.docx");
            assertEquals("中途过渡短句应自动续接到真正交付总结", 3, f1.llm.requests.size());
            assertTrue(f1.loop.lastTurnSucceeded());
        } finally {
            f1.loop.shutdown();
        }

        // 2) 要求生成文件但仅执行了只读 Read，且模型明确说“接下来开始编写脚本生成报告”时不能半途停机
        Fixture f2 = fixture();
        try {
            java.nio.file.Path src = tmp.getRoot().toPath().resolve("source.txt");
            java.nio.file.Files.write(src, "源数据内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ToolCall readCall = new ToolCall();
            readCall.id = "r1";
            readCall.name = "Read";
            readCall.arguments = "{\"path\":\"source.txt\"}";
            ToolCall writeCall = new ToolCall();
            writeCall.id = "w1";
            writeCall.name = "Write";
            writeCall.arguments = "{\"path\":\"out.md\",\"content\":\"# 评估报告\\n完整结论\"}";

            f2.llm.addTurnWithTools(Collections.singletonList(readCall), null);
            f2.llm.addTurn("已读取全部源材料并梳理出核心问题，接下来我将编写并生成最终评估报告文件。");
            f2.llm.addTurnWithTools(Collections.singletonList(writeCall), null);
            f2.llm.addTurn("评估报告 out.md 已生成完毕。");

            f2.loop.runUserTurn("读取source.txt分析有哪些风险并生成评估报告文档");
            assertEquals("只读探索后声明下一步生成报告时应继续执行写入而非提前结束", 4, f2.llm.requests.size());
            assertTrue(f2.loop.lastTurnSucceeded());
        } finally {
            f2.loop.shutdown();
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
