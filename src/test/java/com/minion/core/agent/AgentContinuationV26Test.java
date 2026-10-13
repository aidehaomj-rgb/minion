package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

/** 回归 0731 正常 stop、短进度、已返回子Agent与待办不能导致主任务提前退出。 */
public class AgentContinuationV26Test {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private FakeLlmClient llm;
    private AgentLoop loop;
    private List<String> warnings;
    private List<String> displayed;
    @Before public void setup() {
        llm = new FakeLlmClient(); warnings = new ArrayList<String>(); displayed = new ArrayList<String>();
        Workspace ws = new Workspace(tmp.getRoot().getPath());
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        loop = new AgentLoop(llm, registry, new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi() {
                    public void onContent(String s) { displayed.add(s); }
                    public void onWarning(String s) { warnings.add(s); }
                    public void onError(String s) { warnings.add(s); }
                }, new ContextManager(900000, .9, 50, llm, 0), ws,
                Session.create(ws.workDir(), "deepseek-v4-flash-0731"));
        registry.register(new TodoWriteTool(loop.session().todos));
    }
    @After public void cleanup() { loop.shutdown(); }
    private ToolCall call(String id, String name, String args) {
        ToolCall c = new ToolCall(); c.id=id; c.name=name; c.arguments=args; return c;
    }
    private void scan(String id) {
        llm.addTurnWithTools(Collections.singletonList(call(id,"example","{\"text\":\"核验完成\"}")),null);
    }
    private void finish() { llm.addTurn("全部分块已核对并汇总交付。" + AgentLoop.TASK_COMPLETE_MARKER); }
    private String lastHint(int request) {
        List<Message> messages=llm.requests.get(request).messages;
        return messages.get(messages.size()-1).content;
    }
    @Test public void shortDeepSeekProgressContinuesAtLargeWindowWithoutCompaction() {
        scan("scan");
        llm.addTurn("继续推进。先验证chunk文件内容完整性，并确认3个无匹配单的情况。");
        scan("verify"); finish();
        loop.runUserTurn("核对38个分块并生成完整结果");
        assertEquals(4,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
        assertTrue(warnings.toString().contains("阶段性回复"));
        assertTrue(lastHint(2).contains("立即调用"));
        assertTrue(llm.completeChatRequests.isEmpty());
        assertEquals(900000,loop.contextManager().maxTokens());
        assertEquals(2,loop.messages().stream().filter(m -> m.role==Message.Role.TOOL).count());
        assertFalse(loop.messages().stream().anyMatch(m -> m.role==Message.Role.USER && m.content.contains("系统完成性检查")));
    }
    @Test public void longChinesePlanWithLetMeCheckTailContinues() {
        scan("scan");
        String plan="第一个子agent（chunk_0）完成。让我核对result_0.json文件真实存在且内容正确，然后继续派发。为验证落地，我需要确认文件写入成功。"
                + "由于chunk有38个，逐个派发会很多轮。让我核对result_0后，考虑更高效但保持准确性的方式。让我核对result_0.json落地。"
                + "实际上，让我继续分批派发。先核对第一个结果文件的真实落地，再继续。让我核对result_0.json。"
                + "由于需要逐块核对子agent，且38块数量大，我将持续派发和核对。让我先验证result_0文件，然后继续。让我检查results目录及result_0.json内容。";
        assertTrue(plan.length()>240); llm.addTurn(plan); scan("verify"); finish();
        loop.runUserTurn("核对38块并汇总");
        assertEquals(4,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
    }
    @Test public void pendingTodoCannotEndOnUnrecognizedShortReplyOrCompletionMarker() {
        loop.session().taskState.begin("完成整表",false);
        loop.session().todos.replace(Collections.singletonList(new TodoList.TodoItem("汇总整表",false)));
        llm.addTurn("阶段结果已返回。" + AgentLoop.TASK_COMPLETE_MARKER);
        llm.addTurnWithTools(Collections.singletonList(call("todo","TodoWrite",
                "{\"action\":\"mark\",\"index\":0}")),null);
        finish(); loop.runUserTurn("继续执行");
        assertEquals(3,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
        assertTrue(lastHint(1).contains("待办"));
    }
    @Test public void returnedChildWithoutTodoOrPriorToolHistoryStillRequiresReview() {
        loop.session().taskState.begin("完成38块",false);
        TaskState.Child c=loop.session().taskState.startChild("chunk_0");
        loop.session().taskState.finishChild(c.id,"result_0已写入",true,"child.json");
        llm.addTurn("收到结果。");
        llm.addTurnWithTools(Collections.singletonList(call("review","AgentControl",
                "{\"action\":\"review\",\"childId\":\""+c.id+"\",\"note\":\"已核对实际证据\"}")),null);
        finish(); loop.runUserTurn("继续执行");
        assertEquals(3,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
        assertEquals("reviewed",loop.session().taskState.child(c.id).status);
    }
    @Test public void reviewedChildRequiresRootDeliveryHandshakeEvenForShortReply() {
        loop.session().taskState.begin("完成38块",false);
        TaskState.Child c=loop.session().taskState.startChild("chunk_0");
        loop.session().taskState.finishChild(c.id,"已返回",true,"child.json");
        assertTrue(loop.session().taskState.reviewChild(c.id));
        llm.addTurn("第一块核对完毕。"); finish(); loop.runUserTurn("继续执行");
        assertEquals(2,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
    }
    @Test public void unresolvedChildCannotBeBypassedByCompletionMarkerAndRecoveryIsBounded() {
        loop.session().taskState.begin("完成38块",false);
        TaskState.Child c=loop.session().taskState.startChild("chunk_1");
        loop.session().taskState.finishChild(c.id,"失败",false,"child.json");
        finish(); loop.runUserTurn("继续执行");
        assertEquals(4,llm.requests.size()); assertFalse(loop.lastTurnSucceeded());
        assertEquals("paused",loop.session().taskState.status);
        assertTrue(warnings.toString().contains("尚未完成"));
        assertTrue(warnings.toString().contains("停止自动续接"));
        assertTrue(llm.completeChatRequests.isEmpty());
    }
    @Test public void explicitMissingInputPausesWithoutRetryAndDoesNotMarkComplete() {
        loop.session().taskState.begin("整表核对",false);
        loop.session().todos.replace(Collections.singletonList(new TodoList.TodoItem("核对缺失表",false)));
        llm.addTurn("缺少第四批文件，请上传后才能继续。"); loop.runUserTurn("继续执行");
        assertEquals(1,llm.requests.size()); assertFalse(loop.lastTurnSucceeded());
        assertEquals("paused",loop.session().taskState.status);
        assertTrue(warnings.toString().contains("等待用户"));
    }
    @Test public void ordinaryQuestionWithNoToolWorkDoesNotGetForcedContinuation() {
        llm.addTurn("让我检查这个表述：这里是在解释操作方法，不执行任务。");
        loop.runUserTurn("解释这句话是什么意思");
        assertEquals(1,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
    }
    @Test public void explicitApprovalIsAUserBlockButFinalSuggestionsAreNot() {
        assertTrue(AgentLoop.looksLikeUserBlock("请确认方案后才能继续。"));
        assertTrue(AgentLoop.looksLikeUserBlock("需要您授权访问此目录后再继续。"));
        assertFalse(AgentLoop.looksLikeUserBlock("全部文件已验证。建议后续上传备份。"));
        assertFalse(AgentLoop.looksLikeUserBlock("文件已交付，请确认结果。" + AgentLoop.TASK_COMPLETE_MARKER));
    }
    @Test public void simpleToolAnswerCanFinishWithoutComplexTaskMarker() {
        scan("scan"); llm.addTurn("共有两个文件，分别为A和B。");
        loop.runUserTurn("列出文件");
        assertEquals(2,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
    }
    @Test public void repeatedPlansStopExplicitlyAndNeverCompactHealthyContext() {
        scan("scan"); llm.addTurn("让我检查result_0.json。"); loop.runUserTurn("汇总38块");
        assertEquals(14,llm.requests.size()); assertFalse(loop.lastTurnSucceeded());
        assertTrue(warnings.toString().contains("停止自动续接"));
        assertTrue(llm.completeChatRequests.isEmpty());
    }
    @Test public void oldTaskChildDoesNotBlockNewQuestion() {
        loop.session().taskState.begin("旧的38块任务",false);
        TaskState.Child child=loop.session().taskState.startChild("旧分工");
        loop.session().taskState.finishChild(child.id,"旧结果",true,"old.json");
        llm.addTurn("这是新问题的直接回答。"); loop.runUserTurn("解释CSV与JSON的区别");
        assertEquals(1,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
        assertTrue(loop.session().taskState.childSnapshot().isEmpty());
    }
    @Test public void changedCheckpointCannotBeBypassedByMarker() throws Exception {
        java.nio.file.Path file=tmp.getRoot().toPath().resolve("evidence.json");
        java.nio.file.Files.write(file,"old".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        loop.session().taskState.begin("核对整表",false);
        loop.session().taskState.checkpoint("最后验证","1-38",Collections.singletonList(FileEvidence.capture(file)));
        java.nio.file.Files.write(file,"changed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        finish();
        llm.addTurnWithTools(Collections.singletonList(call("checkpoint","AgentControl",
                "{\"action\":\"checkpoint\",\"files\":\"evidence.json\",\"nextStep\":\"\",\"processedRange\":\"1-38\"}")),null);
        finish(); loop.runUserTurn("继续执行");
        assertEquals(3,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
        assertTrue(lastHint(1).contains("断点证据文件"));
    }
    @Test public void longMissingHandshakeRecoveryIsBoundedEvenWithoutInterimPhrase() {
        scan("scan"); llm.addTurn(String.join("",Collections.nCopies(160,"已核对一项事实。")));
        loop.runUserTurn("完成整表");
        assertEquals(14,llm.requests.size()); assertFalse(loop.lastTurnSucceeded());
        assertTrue(warnings.toString().contains("交付尚未确认"));
        assertTrue(warnings.toString().contains("停止自动续接"));
    }

    private java.nio.file.Path changedCheckpoint() throws Exception {
        java.nio.file.Path file=tmp.getRoot().toPath().resolve("final.xlsx");
        java.nio.file.Files.write(file,"stage".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        loop.session().taskState.begin("生成最终交付",false);
        loop.session().taskState.checkpoint("生成最终表","1-267",Collections.singletonList(FileEvidence.capture(file)));
        java.nio.file.Files.write(file,"verified final".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return file;
    }
    @Test public void completedOutputRefreshesOldCheckpointAndStops() throws Exception {
        changedCheckpoint(); finish();
        llm.addTurnWithTools(Collections.singletonList(call("refresh","AgentControl",
                "{\"action\":\"refresh_checkpoint\",\"note\":\"已核对267行、完整描述及分工；最终表按新要求重生成\"}")),null);
        finish(); loop.runUserTurn("继续执行");
        assertEquals(3,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
        assertEquals("completed",loop.session().taskState.status);
        assertTrue(loop.session().taskState.checkpointFilesValid());
        assertEquals("",loop.session().taskState.nextStep);
        assertEquals("1-267",loop.session().taskState.processedRange);
        assertTrue(lastHint(1).contains("refresh_checkpoint"));
    }
    @Test public void alternatingStatusAndCompletionDoesNotResetRecoveryBudget() throws Exception {
        changedCheckpoint();
        for(int i=0;i<3;i++) {
            finish();
            llm.addTurnWithTools(Collections.singletonList(call("status"+i,"AgentControl","{\"action\":\"status\"}")),null);
        }
        finish(); loop.runUserTurn("继续执行");
        assertEquals(7,llm.requests.size()); assertFalse(loop.lastTurnSucceeded());
        assertEquals("paused",loop.session().taskState.status);
        assertTrue(warnings.toString().contains("预算已用尽"));
        assertFalse(loop.session().taskState.checkpointFilesValid());
        assertTrue(loop.messages().stream().anyMatch(m -> m.role==Message.Role.TOOL && m.content.contains("[changed]")));
    }
    @Test public void refreshRejectsMissingFileAndPreservesWholeSnapshot() throws Exception {
        java.nio.file.Path file=changedCheckpoint();
        java.nio.file.Path missing=tmp.getRoot().toPath().resolve("missing.json");
        java.nio.file.Files.write(missing,new byte[]{1});
        List<FileEvidence> baseline=new ArrayList<FileEvidence>(loop.session().taskState.checkpointFiles);
        baseline.add(FileEvidence.capture(missing));
        loop.session().taskState.checkpoint("核对","1-267",baseline);
        java.nio.file.Files.delete(missing);
        ToolResult result=loop.registry().get("AgentControl").execute(com.google.gson.JsonParser.parseString(
                "{\"action\":\"refresh_checkpoint\",\"note\":\"核对完成\"}").getAsJsonObject());
        assertFalse(result.ok); assertTrue(result.output.contains("原记录保留"));
        assertEquals(baseline.get(0).sha256,loop.session().taskState.checkpointFiles.get(0).sha256);
        assertEquals(2,loop.session().taskState.checkpointFiles.size());
        assertEquals("核对",loop.session().taskState.nextStep);
    }
    @Test public void refreshRequiresReviewNoteAndKeepsPendingTodos() throws Exception {
        changedCheckpoint();
        Tool tool=loop.registry().get("AgentControl");
        assertFalse(tool.execute(com.google.gson.JsonParser.parseString("{\"action\":\"refresh_checkpoint\"}").getAsJsonObject()).ok);
        assertFalse(loop.session().taskState.checkpointFilesValid());
        loop.session().todos.replace(Collections.singletonList(new TodoList.TodoItem("尚未汇总",false)));
        assertTrue(tool.execute(com.google.gson.JsonParser.parseString("{\"action\":\"refresh_checkpoint\",\"note\":\"文件已核对\"}").getAsJsonObject()).ok);
        finish(); loop.runUserTurn("继续执行");
        assertEquals(4,llm.requests.size()); assertFalse(loop.lastTurnSucceeded());
        assertTrue(warnings.toString().contains("待办"));
    }
    @Test public void pendingStateBudgetIsBoundedAcrossToolsWithoutCompletionMarker() throws Exception {
        changedCheckpoint();
        for(int i=0;i<12;i++) {
            llm.addTurn("核对状态如下。");
            llm.addTurnWithTools(Collections.singletonList(call("status"+i,"AgentControl","{\"action\":\"status\"}")),null);
        }
        llm.addTurn("核对状态如下。"); loop.runUserTurn("继续执行");
        assertEquals(25,llm.requests.size()); assertFalse(loop.lastTurnSucceeded());
        assertTrue(warnings.toString().contains("预算已用尽"));
    }

    @Test public void completionTimeUsesActualClockAndExplicitBeijingZone() {
        java.time.Clock fixed=java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T01:02:03Z"),java.time.ZoneId.of("America/New_York"));
        assertEquals("完成时间：2026-10-12 09:02:03（北京时间）",AgentLoop.completionTimeLine(fixed));
    }
    @Test public void completedReplyContainsOneTimeLineInUiAndHistory() {
        finish(); loop.runUserTurn("生成最终交付");
        assertTrue(loop.lastTurnSucceeded());
        String reply=loop.messages().get(loop.messages().size()-1).content;
        assertTrue(reply.matches("(?s).*完成时间：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}（北京时间）.*"));
        assertTrue(AgentLoop.hasTaskCompleteMarker(reply));
        assertEquals(1,displayed.stream().filter(s -> s.contains("完成时间：")).count());
        assertTrue(reply.contains(displayed.get(displayed.size()-1).trim()));
    }
    @Test public void rejectedCompletionAndOrdinaryAnswerHaveNoCompletionTime() throws Exception {
        changedCheckpoint(); finish(); loop.runUserTurn("继续执行");
        assertFalse(loop.lastTurnSucceeded());
        assertFalse(displayed.toString().contains("完成时间："));
        assertFalse(loop.messages().stream().anyMatch(m -> m.content!=null && m.content.contains("完成时间：")));
        displayed.clear(); llm.addTurn("CSV是逗号分隔格式。"); loop.runUserTurn("解释CSV格式");
        assertTrue(loop.lastTurnSucceeded());
        assertFalse(displayed.toString().contains("完成时间："));
    }

    @Test public void screenshotComparisonPlanWithoutTodosContinuesToActualAction() {
        scan("copy");
        String reply="数据已复制。现在编写比对脚本，检查：\n"
                + "- 4家越南企业名称在进口侧各字段（发货人、收货人、货描、境外收发货人）\n"
                + "- 4家香港贸易商名称（Ocean Beauty、Hairunda、Xinxin、Oceanic Fishery）\n"
                + "- 原产国=越南/香港、启运港/装货地=越南/香港的记录\n"
                + "国家代码映射：250=法国、643=俄罗斯、036=澳大利亚、392=日本、124=加拿大、360=印尼、410=韩国、764=泰国、604=秘鲁。无越南、无香港，但要核对数字代码是否对应。\n让我做全面比对。";
        assertTrue(reply.length()>240 && reply.length()<1000);
        llm.addTurn(reply); scan("compare"); finish();
        loop.runUserTurn("将这4家企业与03072进口表比对");
        assertTrue(loop.session().todos.items.isEmpty());
        assertEquals(4,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
        assertTrue(warnings.toString().contains("阶段性回复"));
        assertEquals(2,loop.messages().stream().filter(m -> m.role==Message.Role.TOOL).count());
        assertTrue(llm.completeChatRequests.isEmpty());
    }
    @Test public void activeComparisonAndScriptPlansAreRecognizedAcrossLengths() {
        String[] plans={"让我做全面比对。","让我先进行逐行匹配。","现在开始编写比对脚本。",
                "接下来我会进一步对比这些字段。","我将制作比对结果表。","让我做一次完整核验。"};
        for(String plan:plans) {
            assertTrue(plan,AgentLoop.looksLikeInterimAnswer(plan));
            assertTrue(plan,AgentLoop.looksLikeInterimAnswer(String.join("",Collections.nCopies(30,"已读取各列样本。"))+plan));
        }
    }
    @Test public void completedComparisonsAndQuotedPlansDoNotTriggerContinuation() {
        assertFalse(AgentLoop.looksLikeInterimAnswer("已完成全面比对，没有发现匹配记录。"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("结果已交付。建议后续进行全面比对。"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("日志中引用的是“让我做全面比对。”"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("文档示例：让我做全面比对。"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("对比结论是：商品描述不同，没有匹配记录。"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("现在比对结果如下：4家企业均无匹配记录。"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("现在比对已完成，没有发现匹配记录。"));
        scan("compare"); llm.addTurn("已完成全面比对，4家企业均无匹配记录。"+AgentLoop.TASK_COMPLETE_MARKER);
        loop.runUserTurn("比对企业名称");
        assertEquals(2,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
        assertEquals(1,displayed.stream().filter(s -> s.contains("完成时间：")).count());
    }
    @Test public void repeatedComparisonPlansRemainBounded() {
        scan("copy"); llm.addTurn("让我做全面比对。"); loop.runUserTurn("比对进口表");
        assertEquals(14,llm.requests.size()); assertFalse(loop.lastTurnSucceeded());
        assertTrue(warnings.toString().contains("停止自动续接"));
        assertEquals("paused",loop.session().taskState.status);
        assertFalse(displayed.toString().contains("完成时间："));
    }
    @Test public void planInOrdinaryExplanationWithoutToolsDoesNotForceExecution() {
        llm.addTurn("让我做全面比对。"); loop.runUserTurn("解释这个句子的含义");
        assertEquals(1,llm.requests.size()); assertTrue(loop.lastTurnSucceeded());
    }
}
