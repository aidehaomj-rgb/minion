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
        ToolRegistry registry=new ToolRegistry();registry.register(new com.minion.core.tools.example.ExampleTool());
        loop=new AgentLoop(llm,registry,new SystemPromptBuilder(null),new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi(){public void onWarning(String s){notices.add(s);}public void onError(String s){notices.add(s);}},null,ws,Session.create(ws.workDir(),"fake"));
    }
    @After public void cleanup(){loop.shutdown();}
    private void tool(String id){ToolCall c=new ToolCall();c.id=id;c.name="example";c.arguments="{\"text\":\"核验产物\"}";llm.addTurnWithTools(Collections.singletonList(c),null);}
    @Test public void novelUnknownPhrasesAndLanguagesAreReviewedAndContinue() {
        llm.stopStatuses.add("continue");llm.stopStatuses.add("completed");
        tool("read");llm.addTurn("Phase omega reached; synthesis remains outside this response.");tool("deliver");llm.addTurn("全部交付。");
        loop.runUserTurn("核对并交付结果");assertEquals(4,llm.requests.size());assertEquals(2,llm.stopReviewRequests.size());assertTrue(loop.lastTurnSucceeded());
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
        assertFalse(loop.lastTurnSucceeded());com.google.gson.JsonObject data=com.google.gson.JsonParser.parseString(llm.stopReviewRequests.get(0)).getAsJsonObject();
        assertTrue(data.get("blocking").getAsString().contains("超出当前权限"));
        com.google.gson.JsonObject known=data.getAsJsonObject("facts").getAsJsonArray("knownFiles").get(0).getAsJsonObject();
        assertEquals("permission_denied",known.get("status").getAsString());assertFalse(known.has("actualSha256"));
    }
    @Test public void varyingStatusRepliesDoNotResetNoProgressBudget() {
        llm.stopKind="task";
        for(int i=0;i<13;i++) {
            ToolCall status=new ToolCall();status.id="status-"+i;status.name="AgentControl";status.arguments="{\"action\":\"status\"}";
            llm.addTurnWithTools(Collections.singletonList(status),null);llm.addTurn("Opaque phase "+i);llm.stopStatuses.add("continue");
        }
        loop.runUserTurn("交付文件");assertFalse(loop.lastTurnSucceeded());assertEquals(13,llm.stopReviewRequests.size());
        assertEquals(13,loop.session().taskState.stopDecision.noProgress);
        assertTrue(notices.stream().anyMatch(s->s.contains("无实质进展的收尾预算已用尽")));
    }
    @Test public void humanInputAndInvalidReviewerPauseWithoutQueueSuccess() {
        llm.stopStatuses.add("waiting_user");llm.addTurn("缺少证据材料。");loop.runUserTurn("核对资料");
        assertFalse(loop.lastTurnSucceeded());assertEquals("waiting_user",loop.session().taskState.stopDecision.status);
        llm.stopReviewRaw="not JSON";llm.addTurn("回复看起来已经完成。");loop.runUserTurn("新任务核对");
        assertFalse(loop.lastTurnSucceeded());assertEquals("unknown",loop.session().taskState.stopDecision.status);
    }
    @Test public void supplementDuringReviewInvalidatesOldApproval() {
        llm.stopStatuses.add("completed");llm.stopStatuses.add("completed");
        llm.onStopReview=new java.util.function.Consumer<String>(){int calls;public void accept(String s){if(calls++==0)loop.offerSupplement("还要保留原编号");}};
        llm.addTurn("第一版交付。");llm.addTurn("已处理最新补充。");loop.runUserTurn("生成结果");
        assertEquals(2,llm.requests.size());assertTrue(loop.lastTurnSucceeded());
        assertTrue(llm.stopReviewRequests.get(1).contains("保留原编号"));
        assertTrue(loop.messages().stream().anyMatch(m->m.supplement));
    }
    @Test public void explicitStopDuringReviewNeverAutoResumes() {
        llm.onStopReview=s->loop.interrupt();llm.addTurn("已完成。");loop.runUserTurn("当前任务");
        assertFalse(loop.lastTurnSucceeded());assertEquals(1,llm.requests.size());assertEquals("paused",loop.session().taskState.status);
    }
    @Test public void childUsesOnlyAssignmentAndCannotApproveParent() {
        FakeLlmClient childClient=new FakeLlmClient();childClient.stopStatuses.add("continue");childClient.stopStatuses.add("completed");
        childClient.addTurn("Stage zeta, deliverable absent.");childClient.addTurn("分工结果已完成。");
        SubAgentLoop child=new SubAgentLoop("主任务是全表交付","只核对第1行",tmp.getRoot().toString(),childClient,new ToolRegistry(),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new AgentUi(){});
        assertEquals("分工结果已完成。",child.run());assertTrue(child.succeeded());assertEquals(2,childClient.stopReviewRequests.size());
        assertTrue(childClient.stopReviewRequests.get(0).contains("只核对第1行"));assertFalse(childClient.stopReviewRequests.get(0).contains("主任务是全表交付"));
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
        llm.addTurn("方案已实施。");loop.runUserTurn("按照这个修改");assertTrue(loop.lastTurnSucceeded());
        com.google.gson.JsonObject data=com.google.gson.JsonParser.parseString(llm.stopReviewRequests.get(0)).getAsJsonObject();
        assertEquals("按照这个修改",data.get("goal").getAsString());assertTrue(data.getAsJsonObject("facts").get("referenceContext").toString().contains("版本核验"));
    }
    @Test public void newCheckpointBytesDuringReviewInvalidateOldApproval() throws Exception {
        java.nio.file.Path file=tmp.getRoot().toPath().resolve("proof.txt");java.nio.file.Files.write(file,new byte[]{1});
        loop.session().taskState.begin("核对文件",false);loop.session().taskState.checkpoint("核验","全部",Collections.singletonList(FileEvidence.capture(file)));
        llm.onStopReview=new java.util.function.Consumer<String>(){int calls;public void accept(String s){if(calls++==0)try{java.nio.file.Files.write(file,new byte[]{2});}catch(Exception e){throw new RuntimeException(e);}}};
        llm.addTurn("已核验完成。");ToolCall refresh=new ToolCall();refresh.id="refresh";refresh.name="AgentControl";
        refresh.arguments="{\"action\":\"refresh_checkpoint\",\"note\":\"已复核合法更新\"}";
        llm.addTurnWithTools(Collections.singletonList(refresh),null);llm.addTurn("新版本核验完成。");loop.runUserTurn("继续执行");
        assertEquals(3,llm.requests.size());assertEquals(2,llm.stopReviewRequests.size());assertTrue(loop.lastTurnSucceeded());
        assertTrue(loop.session().taskState.checkpointFilesValid());
    }
}
