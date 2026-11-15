package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

public class UnifiedStopLoopTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private FakeLlmClient llm;private AgentLoop loop;private List<String> notices;
    @Before public void setup() {
        llm=new FakeLlmClient();notices=new ArrayList<String>();Workspace ws=new Workspace(tmp.getRoot().getPath());
        ToolRegistry registry=new ToolRegistry();registry.register(new com.minion.core.tools.example.ExampleTool());registry.register(new ReadTool(ws));
        loop=new AgentLoop(llm,registry,new SystemPromptBuilder(null),new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi(){public void onWarning(String s){notices.add(s);}public void onError(String s){notices.add(s);}},null,ws,Session.create(ws.workDir(),"fake"));
        loop.requestExecutionMode(ExecutionMode.GOAL);
    }
    @After public void cleanup(){loop.shutdown();}
    private void tool(String id){ToolCall c=new ToolCall();c.id=id;c.name="example";c.arguments="{\"text\":\"核验产物\"}";llm.addTurnWithTools(Collections.singletonList(c),null);}
    @Test public void longPlanningReplyIsNotTreatedAsDeliveredWork() {
        String prefix=String.join("",Collections.nCopies(250,"背景"));
        assertTrue(AgentLoop.looksLikeInterimAnswer(prefix+"先确认分工，然后生成chunk。"));
        assertTrue(AgentLoop.looksLikeInterimAnswer("这一步完成后，下一步需要做代码修改和回归测试。"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("结果已交付；建议下一步做更深入的人工复核。"));
    }
    @Test public void failedShellStepDoesNotStopAnExplicitNextStepPlan() {
        loop.shutdown();Workspace ws=new Workspace(tmp.getRoot().getPath());ToolRegistry registry=new ToolRegistry();
        registry.register(new Tool(){public String name(){return "Bash";}public String description(){return "test";}
            public com.google.gson.JsonObject schema(){return new com.google.gson.JsonObject();}
            public ToolResult execute(com.google.gson.JsonObject args){return ToolResult.error("exit code 1");}});
        registry.register(new com.minion.core.tools.example.ExampleTool());
        loop=new AgentLoop(llm,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi(){public void onWarning(String s){notices.add(s);}public void onError(String s){notices.add(s);}},
                null,ws,Session.create(ws.workDir(),"failed-shell"));
        loop.requestExecutionMode(ExecutionMode.GOAL);
        ToolCall failed=new ToolCall();failed.id="failed";failed.name="Bash";failed.arguments="{\"command\":\"python -c broken\"}";
        llm.stopStatuses.add("waiting_user");llm.stopStatuses.add("waiting_user");
        llm.addTurnWithTools(Collections.singletonList(failed),null);
        llm.addTurn("继续追加四（七）指标体系全生命周期管理功能，然后完成五、六、七部分及生成逻辑。"
                +String.join("",Collections.nCopies(300,"基础版本和历史材料说明。")));
        ToolCall followup=new ToolCall();followup.id="followup";followup.name="example";followup.arguments="{\"text\":\"读取最新结果\"}";
        llm.addTurnWithTools(Collections.singletonList(followup),null);
        llm.addTurn("后续核对已执行，失败的旧步骤仍需核实。");
        loop.runUserTurn("核对并交付结果");
        assertEquals(notices.toString()+" / stop="+loop.session().taskState.stopDecision.reason,
                4,llm.requests.size());assertTrue(llm.actionRecoveryCalls>0);
        assertTrue(llm.stopReviewRequests.size()>=2);
        assertEquals(1,loop.reliability().unresolved(loop.session().taskState.id).size());
        assertFalse(loop.lastTurnSucceeded());
        assertEquals("waiting_user",loop.session().taskState.stopDecision.status);
        String stepId=loop.reliability().unresolved(loop.session().taskState.id).get(0).getAsJsonObject().get("id").getAsString();
        assertTrue(llm.stopReviewRequests.get(1).contains(stepId));
    }
    @Test public void failedReceiptDoesNotBlockLaterVerifiedDelivery() {
        loop.shutdown();Workspace ws=new Workspace(tmp.getRoot().getPath());ToolRegistry registry=new ToolRegistry();
        registry.register(new Tool(){public String name(){return "Bash";}public String description(){return "test";}
            public com.google.gson.JsonObject schema(){return new com.google.gson.JsonObject();}
            public ToolResult execute(com.google.gson.JsonObject args){return ToolResult.error("exit code 1");}});
        registry.register(new com.minion.core.tools.example.ExampleTool());
        loop=new AgentLoop(llm,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi(){public void onWarning(String s){notices.add(s);}public void onError(String s){notices.add(s);}},
                null,ws,Session.create(ws.workDir(),"failed-then-delivered"));
        loop.requestExecutionMode(ExecutionMode.GOAL);
        ToolCall failed=new ToolCall();failed.id="failed";failed.name="Bash";failed.arguments="{\"command\":\"python -c broken\"}";
        llm.addTurnWithTools(Collections.singletonList(failed),null);
        tool("verified");llm.addTurn("替代步骤已成功，最终结果已核验交付。");
        llm.stopKind="task";llm.stopStatuses.add("completed");
        loop.runUserTurn("核对并交付结果");
        assertTrue(loop.lastTurnSucceeded());
        assertEquals("completed",loop.session().taskState.stopDecision.status);
        assertEquals(1,loop.reliability().unresolved(loop.session().taskState.id).size());
        com.google.gson.JsonObject review=com.google.gson.JsonParser.parseString(llm.stopReviewRequests.get(0)).getAsJsonObject();
        assertEquals("",review.get("blocking").getAsString());
        assertEquals(1,review.getAsJsonObject("facts").getAsJsonArray("uncertainSteps").size());
    }
    @Test public void novelUnknownPhrasesAndLanguagesAreReviewedAndContinue() {
        llm.stopStatuses.add("continue");llm.stopStatuses.add("completed");
        tool("read");llm.addTurn("Phase omega reached; synthesis remains outside this response.");tool("deliver");llm.addTurn("全部交付。");
        loop.runUserTurn("核对并交付结果");assertEquals(4,llm.requests.size());assertEquals(2,llm.stopReviewRequests.size());assertTrue(loop.lastTurnSucceeded());
    }
    @Test public void reviewerUnknownCannotPauseAnExplicitExecutablePlan() {
        llm.stopStatuses.add("unknown");llm.stopStatuses.add("completed");
        llm.addTurn("继续追加第五节，然后调用现有工具核对产物。");
        tool("after-unknown");llm.addTurn("第五节已核验交付。");
        loop.runUserTurn("追加并核对第五节");
        assertEquals(3,llm.requests.size());
        assertTrue(llm.stopReviewRequests.size()>=2);
        assertTrue(loop.lastTurnSucceeded());
    }
    @Test public void reviewerCannotInventUserWaitWithoutConcreteDependency() {
        llm.stopStatuses.add("waiting_user");llm.stopStatuses.add("completed");
        llm.addTurn("阶段结果已记录。");tool("after-false-wait");llm.addTurn("结果已实际核对并交付。");
        loop.runUserTurn("核对并交付结果");
        assertTrue(loop.lastTurnSucceeded());assertEquals(3,llm.requests.size());
        assertTrue(notices.stream().anyMatch(s->s.contains("未指出具体需要用户提供")));
    }
    @Test public void promisingToAskUserIsNotTheQuestionAndCannotEndTurn() {
        llm.stopStatuses.add("waiting_user");llm.stopStatuses.add("waiting_user");
        llm.addTurn("我需要向用户提问，确认数据文件路径。接下来向用户提问。");
        llm.addTurn("请提供需要分析的 Excel 文件完整路径，以便继续处理。");
        loop.runUserTurn("分析指定的 Excel 文件");
        assertEquals(2,llm.requests.size());
        assertEquals("waiting_user",loop.session().taskState.pauseCode);
        assertTrue(loop.messages().get(loop.messages().size()-1).content.contains("请提供"));
        assertTrue(llm.actionRecoveryCalls>0);
    }
    @Test public void markerCannotBypassSemanticRejectionWithoutTodos() {
        llm.stopStatuses.add("continue");llm.stopStatuses.add("completed");
        llm.addTurn("其实还没执行。"+AgentLoop.TASK_COMPLETE_MARKER);tool("execute");llm.addTurn("最终已核验。");
        loop.runUserTurn("执行核对");assertEquals(3,llm.requests.size());assertTrue(loop.lastTurnSucceeded());assertEquals(2,llm.stopReviewRequests.size());
    }
    @Test public void verifiedTaskCanCompleteWithoutMarkerAndReceivesCompletionTime() {
        llm.stopKind="task";tool("output");llm.addTurn("文件已生成并核验，不输出旧标记。");
        loop.runUserTurn("交付文件");assertTrue(loop.lastTurnSucceeded());
        assertEquals("completed",loop.session().taskState.stopDecision.status);
        assertTrue(loop.messages().get(loop.messages().size()-1).content.contains("完成时间："));
    }
    @Test public void excelOutputIsCapturedAndVerifiedAsArtifactEvidence() throws Exception {
        loop.shutdown();Workspace ws=new Workspace(tmp.getRoot().getPath());ToolRegistry registry=new ToolRegistry();
        registry.register(new Tool(){public String name(){return "Excel";}public String description(){return "fake Excel export";}
            public com.google.gson.JsonObject schema(){return new com.google.gson.JsonObject();}
            public ToolResult execute(com.google.gson.JsonObject args) throws Exception {
                java.nio.file.Files.write(ws.cwd().resolve(args.get("outputPath").getAsString()),new byte[]{1,2,3});
                return ToolResult.success("已输出表格");
            }});
        loop=new AgentLoop(llm,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi(){},null,ws,Session.create(ws.workDir(),"excel-proof"));
        loop.requestExecutionMode(ExecutionMode.GOAL);
        ToolCall call=new ToolCall();call.id="excel-write";call.name="Excel";
        call.arguments="{\"action\":\"write\",\"outputPath\":\"report.xlsx\"}";
        llm.stopKind="task";llm.addTurnWithTools(Collections.singletonList(call),null);llm.addTurn("report.xlsx 已生成并交付。");
        loop.runUserTurn("生成Excel报告");
        assertTrue(loop.lastTurnSucceeded());
        TaskState.Evidence proof=loop.session().taskState.evidence.get(0);
        assertEquals(Boolean.TRUE,proof.mutation);assertNotNull(proof.file);
        assertEquals("present",proof.file.status);assertEquals("unchanged",proof.file.verify());
    }
    @Test public void multipartAnswerIncludesDeliveredPartsAndDoesNotForceTools() {
        llm.stopKind="answer";llm.stopStatuses.add("continue");llm.stopStatuses.add("continue");llm.stopStatuses.add("completed");
        llm.addTurn("第一项：解释机制。");llm.addTurn("第二项：说明风险。");llm.addTurn("第三项：给出边界。");
        loop.runUserTurn("分三项解释，不要修改文件");
        assertTrue(loop.lastTurnSucceeded());assertEquals(0,llm.actionRecoveryCalls);assertEquals(1,llm.withoutThinkingCalls);
        com.google.gson.JsonObject data=com.google.gson.JsonParser.parseString(llm.stopReviewRequests.get(2)).getAsJsonObject();
        String prior=data.getAsJsonObject("facts").get("earlierDeliveredReplies").toString();
        assertTrue(prior.contains("第一项"));assertTrue(prior.contains("第二项"));
        assertTrue(llm.requests.get(2).messages.stream().anyMatch(m->m.content!=null&&m.content.contains("不能为了结束检查增加文件操作")));
    }
    @Test public void priorTaskToolEvidenceCannotCertifyCurrentTask() {
        loop.session().taskState.begin("旧目标",false);
        loop.session().taskState.recordEvidence("root","Write",true,"旧文件已交付",null);
        ToolCall old=new ToolCall();old.id="old";old.name="Write";old.arguments="{}";
        Message oldReply=Message.assistant(null);oldReply.toolCalls=Collections.singletonList(old);loop.session().messages.add(oldReply);
        loop.session().messages.add(Message.toolResult("old","Write","旧文件已交付"));
        llm.stopKind="task";llm.addTurn("新文件已交付。");loop.runUserTurn("另一个新文件任务");
        assertFalse(loop.lastTurnSucceeded());
        com.google.gson.JsonObject data=com.google.gson.JsonParser.parseString(llm.stopReviewRequests.get(0)).getAsJsonObject();
        assertEquals(0,data.getAsJsonArray("receipts").size());assertFalse(data.getAsJsonObject("facts").get("toolsUsed").getAsBoolean());
    }
    @Test public void outsideCheckpointCannotBeHashedOrApproved() throws Exception {
        java.io.File outside=tmp.newFolder("outside");java.io.File scoped=tmp.newFolder("workspace");
        java.nio.file.Path file=outside.toPath().resolve("evidence.txt");java.nio.file.Files.write(file,new byte[]{1});
        loop.shutdown();Workspace ws=new Workspace(scoped.getPath());
        loop=new AgentLoop(llm,new ToolRegistry(),new SystemPromptBuilder(null),new ConfirmGate(Config.load(scoped.toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new AgentUi(){},null,ws,Session.create(ws.workDir(),"fake")){
            @Override public boolean evidencePathAllowed(java.nio.file.Path p){assertFalse("File checks must not hold task ledger lock",Thread.holdsLock(session().taskState));return p.toAbsolutePath().normalize().startsWith(scoped.toPath());}
        };
        loop.session().taskState.begin("核对受限断点",false);
        FileEvidence recorded=FileEvidence.capture(file);loop.session().taskState.checkpoint("交付","全部",Collections.singletonList(recorded));
        java.nio.file.Files.write(file,new byte[]{2});
        String state=loop.authorizedCheckpointStatus();assertTrue(state.contains("permission_denied"));assertFalse(state.contains("changed"));
        llm.stopStatuses.add("waiting_user");llm.addTurn("权限不足。");loop.runUserTurn("继续执行");
        assertFalse(loop.lastTurnSucceeded());
        assertTrue(llm.stopReviewRequests.isEmpty());
        assertTrue(loop.session().taskState.pauseReason!=null||loop.session().taskState.pauseCode!=null);
    }
    @Test public void repeatedStatusControlStopsWithoutDrainingNoProgressBudget() {
        llm.stopKind="task";
        for(int i=0;i<13;i++) {
            ToolCall status=new ToolCall();status.id="status-"+i;status.name="AgentControl";status.arguments="{\"action\":\"status\"}";
            llm.addTurnWithTools(Collections.singletonList(status),null);llm.addTurn("Opaque phase "+i);llm.stopStatuses.add("continue");
        }
        loop.runUserTurn("交付文件");
        assertFalse(loop.lastTurnSucceeded());
        assertTrue(llm.requests.size()<13);
        assertTrue(notices.stream().anyMatch(s->s.contains("同一内部状态操作连续重复")));
        assertEquals("control_loop",loop.session().taskState.pauseCode);
    }
    @Test public void humanInputAndInvalidReviewerPauseWithoutQueueSuccess() {
        llm.stopStatuses.add("waiting_user");llm.addTurn("请上传需要核对的证据材料文件。");loop.runUserTurn("核对资料");
        assertFalse(loop.lastTurnSucceeded());assertEquals("waiting_user",loop.session().taskState.stopDecision.status);
        assertTrue(loop.session().taskState.pauseReason.contains("等待用户"));
        assertEquals("waiting_user",loop.session().taskState.pauseCode);
        llm.stopReviewRaw="not JSON";llm.addTurn("回复看起来已经完成。");loop.runUserTurn("继续总结当前结论");
        assertFalse(loop.lastTurnSucceeded());
        assertNotNull(loop.session().taskState.stopDecision);
        assertFalse("completed".equals(loop.session().taskState.stopDecision.status));
        assertTrue(llm.stopReviewRequests.size()>=2);
    }
    @Test public void malformedReviewResumesAnUnfinishedTaskAndRequiresRealToolEvidence() {
        llm.stopKind="task";
        tool("inspect");
        llm.addTurn("我先确认分工，再生成结果。");
        tool("deliver");
        llm.addTurn("文件已实际生成并核验。");
        final int[] reviews={0};
        llm.onStopReview=input->llm.stopReviewRaw=++reviews[0]<=2?"核验器意外输出了普通文字":null;
        loop.runUserTurn("生成并核验文件");
        assertTrue(loop.lastTurnSucceeded());
        assertEquals(4,llm.requests.size());
        assertTrue(llm.actionRecoveryCalls>0);
        assertTrue(notices.stream().anyMatch(s->s.contains("结束核验格式故障")));
        assertEquals("completed",loop.session().taskState.stopDecision.status);
    }
    @Test public void permanentlyMalformedReviewPausesAfterBoundedRetriesWithoutFalseCompletion() {
        llm.stopReviewRaw="not JSON";
        llm.addTurn("我先检查，再生成结果。");
        loop.runUserTurn("生成文件");
        assertFalse(loop.lastTurnSucceeded());
        assertEquals("unknown",loop.session().taskState.stopDecision.status);
        assertEquals(3,llm.requests.size());
        assertEquals(6,llm.stopReviewRequests.size());
        assertTrue(notices.stream().anyMatch(s->s.contains("结束核验无法确认")));
    }
    @Test public void supplementDuringReviewInvalidatesOldApproval() {
        llm.stopStatuses.add("completed");llm.stopStatuses.add("completed");
        llm.onStopReview=new java.util.function.Consumer<String>(){int calls;public void accept(String s){if(calls++==0)loop.offerSupplement("还要保留原编号");}};
        llm.addTurn("第一版交付。");tool("supplement-delivery");llm.addTurn("已处理最新补充并实际交付。");loop.runUserTurn("生成结果");
        assertEquals(3,llm.requests.size());assertTrue(loop.lastTurnSucceeded());
        assertTrue(llm.stopReviewRequests.get(1).contains("保留原编号"));
        assertTrue(loop.messages().stream().anyMatch(m->m.supplement));
    }
    @Test public void explicitStopDuringReviewNeverAutoResumes() {
        llm.onStopReview=s->loop.interrupt();llm.addTurn("已完成。");loop.runUserTurn("当前任务");
        assertFalse(loop.lastTurnSucceeded());assertEquals(1,llm.requests.size());assertEquals("paused",loop.session().taskState.status);
    }
    @Test public void childUsesOnlyAssignmentAndCannotApproveParent() {
        FakeLlmClient childClient=new FakeLlmClient();childClient.stopStatuses.add("continue");childClient.stopStatuses.add("completed");
        childClient.addTurn("Stage zeta, deliverable absent.");
        ToolCall childProof=new ToolCall();childProof.id="child-proof";childProof.name="example";childProof.arguments="{\"text\":\"核对第1行\"}";
        childClient.addTurnWithTools(Collections.singletonList(childProof),null);childClient.addTurn("分工结果已完成。");
        ToolRegistry childTools=new ToolRegistry();childTools.register(new com.minion.core.tools.example.ExampleTool());
        SubAgentLoop child=new SubAgentLoop("主任务是全表交付","只核对第1行",tmp.getRoot().toString(),childClient,childTools,
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new AgentUi(){});
        assertEquals("分工结果已完成。",child.run());assertTrue(child.succeeded());assertEquals(2,childClient.stopReviewRequests.size());
        assertTrue(childClient.stopReviewRequests.get(0).contains("只核对第1行"));assertFalse(childClient.stopReviewRequests.get(0).contains("主任务是全表交付"));
    }
    @Test public void childMalformedReviewGetsOneRecoveryWithoutFalseCompletion() {
        FakeLlmClient childClient=new FakeLlmClient();childClient.stopKind="task";
        final int[] reviews={0};
        childClient.onStopReview=input->childClient.stopReviewRaw=++reviews[0]<=2?"bad json":null;
        childClient.addTurn("我先核对，再执行。");
        ToolCall action=new ToolCall();action.id="child-action";action.name="example";action.arguments="{\"text\":\"子任务证据\"}";
        childClient.addTurnWithTools(Collections.singletonList(action),null);
        childClient.addTurn("子任务已交付并核对。");
        ToolRegistry tools=new ToolRegistry();tools.register(new com.minion.core.tools.example.ExampleTool());
        SubAgentLoop child=new SubAgentLoop("主任务背景","只执行子任务",tmp.getRoot().toString(),childClient,tools,
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new AgentUi(){});
        assertEquals("子任务已交付并核对。",child.run());assertTrue(child.succeeded());
        assertEquals(3,childClient.requests.size());
    }
    @Test public void childContinuedReplyPreservesThinkingAndPersistsDecision() throws Exception {
        FakeLlmClient childClient=new FakeLlmClient();childClient.stopKind="answer";childClient.stopStatuses.add("continue");childClient.stopStatuses.add("completed");
        childClient.addTurnWithTools(null,"第一部分结果。","原始reasoning");childClient.addTurn("第二部分结果。");
        SubAgentLoop child=new SubAgentLoop("主系统","解释两部分",tmp.getRoot().toString(),childClient,new ToolRegistry(),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new AgentUi(){});
        java.nio.file.Path record=tmp.getRoot().toPath().resolve("child-stop.json");child.setRecordPath(record);child.run();assertTrue(child.succeeded());
        assertTrue(childClient.requests.get(1).messages.stream().anyMatch(m->"原始reasoning".equals(m.reasoningContent)));
        com.google.gson.JsonObject data=com.google.gson.JsonParser.parseString(childClient.stopReviewRequests.get(1)).getAsJsonObject();
        assertTrue(data.getAsJsonObject("facts").get("earlierDeliveredReplies").toString().contains("第一部分结果"));
        com.google.gson.JsonObject saved=com.google.gson.JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(record),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("completed",saved.getAsJsonObject("stopDecision").get("status").getAsString());
    }
    @Test public void referenceContextExplainsDeicticGoalWithoutRevivingOldTask() {
        loop.session().messages.add(Message.user("设计一个统一结束门禁"));
        loop.session().messages.add(Message.assistant("方案：按当前目标、实际工具证据和版本核验完成。"));
        tool("deictic-edit");llm.addTurn("方案已实施。");loop.runUserTurn("按照这个修改");assertTrue(loop.lastTurnSucceeded());
        com.google.gson.JsonObject data=com.google.gson.JsonParser.parseString(llm.stopReviewRequests.get(0)).getAsJsonObject();
        assertEquals("按照这个修改",data.get("goal").getAsString());assertTrue(data.getAsJsonObject("facts").get("referenceContext").toString().contains("版本核验"));
    }
    @Test public void newCheckpointBytesDuringReviewInvalidateOldApproval() throws Exception {
        java.nio.file.Path file=tmp.getRoot().toPath().resolve("proof.txt");java.nio.file.Files.write(file,new byte[]{1});
        loop.session().taskState.begin("核对文件",false);loop.session().taskState.checkpoint("核验","全部",Collections.singletonList(FileEvidence.capture(file)));
        llm.onStopReview=new java.util.function.Consumer<String>(){int calls;public void accept(String s){if(calls++==0)try{java.nio.file.Files.write(file,new byte[]{2});}catch(Exception e){throw new RuntimeException(e);}}};
        llm.addTurn("已核验完成。");ToolCall read=new ToolCall();read.id="read-proof";read.name="Read";
        read.arguments="{\"path\":\"proof.txt\"}";llm.addTurnWithTools(Collections.singletonList(read),null);
        ToolCall refresh=new ToolCall();refresh.id="refresh";refresh.name="AgentControl";
        refresh.arguments="{\"action\":\"refresh_checkpoint\",\"note\":\"已复核合法更新\"}";
        llm.addTurnWithTools(Collections.singletonList(refresh),null);llm.addTurn("新版本核验完成。");loop.runUserTurn("继续执行");
        assertEquals(4,llm.requests.size());assertEquals(2,llm.stopReviewRequests.size());assertTrue(loop.lastTurnSucceeded());
        assertTrue(loop.session().taskState.checkpointFilesValid());
    }
}
