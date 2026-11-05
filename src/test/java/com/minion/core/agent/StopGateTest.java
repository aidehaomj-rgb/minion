package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.llm.*;
import com.minion.core.tools.ToolResult;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StopGateTest {
    private StopGate.Snapshot snapshot(String blocking) {
        return new StopGate.Snapshot("task-1","生成并核验文件",Collections.singletonList("保留全部行"),new JsonObject(),
                Arrays.asList(new StopGate.Receipt("tool:ok","Write","{}",ToolResult.success("文件已写入")),
                        new StopGate.Receipt("tool:failed","Bash","{}",ToolResult.error("失败"))),"某种从未登记过的表述",blocking);
    }
    private JsonObject result(StopGate.Snapshot s,String status,String kind,String ref) {
        JsonObject o=new JsonObject();o.addProperty("taskId",s.id);o.addProperty("ticket",s.ticket);o.addProperty("version",s.version);
        o.addProperty("status",status);o.addProperty("kind",kind);o.addProperty("reason","目标核验结果");o.addProperty("nextStep","核对文件");
        JsonArray evidence=new JsonArray();if(ref!=null)evidence.add(ref);o.add("evidence",evidence);return o;
    }
    @Test public void unknownWordingCanContinueWithoutDictionary() {
        StopGate.Snapshot s=snapshot("");assertEquals("continue",StopGate.parse(s,result(s,"continue","task","reply").toString()).status);
    }
    @Test public void taskCompletionRequiresActualSuccessfulReceipt() {
        StopGate.Snapshot s=snapshot("");assertEquals("completed",StopGate.parse(s,result(s,"completed","task","tool:ok").toString()).status);
        assertEquals("unknown",StopGate.parse(s,result(s,"completed","task","reply").toString()).status);
        assertEquals("unknown",StopGate.parse(s,result(s,"completed","task","tool:failed").toString()).status);
    }
    @Test public void deterministicPendingStateCannotBeOverruled() {
        StopGate.Snapshot s=snapshot("待办尚未完成");assertEquals("continue",StopGate.parse(s,result(s,"completed","task","tool:ok").toString()).status);
        assertEquals("task",StopGate.parse(s,result(s,"completed","answer","reply").toString()).kind);
    }
    @Test public void answerCanFinishWithoutToolsOrMarker() {
        StopGate.Snapshot s=new StopGate.Snapshot("q","解释CSV",Collections.emptyList(),new JsonObject(),Collections.emptyList(),"CSV是逗号分隔格式","");
        assertEquals("completed",StopGate.parse(s,result(s,"completed","answer","reply").toString()).status);
    }
    @Test public void operationalGoalCannotBeDowngradedToReplyOnlyAnswer() {
        StopGate.Snapshot s=new StopGate.Snapshot("task","生成并保存报告.xlsx",Collections.emptyList(),new JsonObject(),Collections.emptyList(),"报告已完成。","");
        StopGate.Decision decision=StopGate.parse(s,result(s,"completed","answer","reply").toString());
        assertEquals("continue",decision.status);assertEquals("task",decision.kind);
        assertTrue(decision.reason.contains("操作工具收据"));
    }
    @Test public void solveThisBugRequiresARealMutationNotAReadReceipt() {
        StopGate.Receipt read=new StopGate.Receipt("tool:read","Read","{\"path\":\"AgentLoop.java\"}",ToolResult.success("source inspected"));
        StopGate.Snapshot task=new StopGate.Snapshot("task","你再参考主流Agent，把这个错误彻底解决完",Collections.emptyList(),new JsonObject(),Collections.singletonList(read),"问题已解决。","");
        StopGate.Decision result=StopGate.parse(task,result(task,"completed","answer","tool:read").toString());
        assertEquals("continue",result.status);assertTrue(result.reason.contains("写入/构建"));
        StopGate.Snapshot question=new StopGate.Snapshot("q","如何解决这个错误？",Collections.emptyList(),new JsonObject(),Collections.emptyList(),"可先检查日志。","");
        assertEquals("completed",StopGate.parse(question,result(question,"completed","answer","reply").toString()).status);
        StopGate.Snapshot proposal=new StopGate.Snapshot("p","给我一个解决方案",Collections.emptyList(),new JsonObject(),Collections.emptyList(),"建议先诊断。","");
        assertEquals("completed",StopGate.parse(proposal,result(proposal,"completed","answer","reply").toString()).status);
    }
    @Test public void readOnlyReceiptCannotProveGeneratedArtifact() {
        StopGate.Receipt read=new StopGate.Receipt("tool:read","Read","{\"path\":\"report.xlsx\"}",ToolResult.success("old file"));
        StopGate.Snapshot s=new StopGate.Snapshot("task","生成并保存报告.xlsx",Collections.emptyList(),new JsonObject(),Collections.singletonList(read),"报告已完成。","");
        assertEquals("continue",StopGate.parse(s,result(s,"completed","answer","reply").toString()).status);
        StopGate.Decision withRead=StopGate.parse(s,result(s,"completed","answer","tool:read").toString());
        assertEquals("continue",withRead.status);assertTrue(withRead.reason.contains("写入/构建"));
    }
    @Test public void historicalShellReceiptMustCarryVerifiedMutationBit() {
        for(Boolean mutation:new Boolean[]{Boolean.FALSE,null}) {
            StopGate.Receipt bash=new StopGate.Receipt("evidence:1","Bash","",ToolResult.success("listed files"),mutation);
            StopGate.Snapshot s=new StopGate.Snapshot("task","修好生成报告的问题",Collections.emptyList(),new JsonObject(),Collections.singletonList(bash),"已完成。","");
            assertEquals("continue",StopGate.parse(s,result(s,"completed","task","evidence:1").toString()).status);
        }
        StopGate.Receipt write=new StopGate.Receipt("evidence:2","Write","",ToolResult.success("written"),Boolean.TRUE);
        StopGate.Snapshot s=new StopGate.Snapshot("task","修好生成报告的问题",Collections.emptyList(),new JsonObject(),Collections.singletonList(write),"已完成。","");
        assertEquals("completed",StopGate.parse(s,result(s,"completed","task","evidence:2").toString()).status);
    }
    @Test public void readOnlyDatabaseAndStatusCallsCannotProveWrittenReport() {
        String[][] reads={{"Database","query"},{"SQLite","schema"},{"Python","status"},
                {"Build","artifacts"},{"Git","diff"},{"DbMysql","query"}};
        for(String[] item:reads) {
            JsonObject args=new JsonObject();args.addProperty("action",item[1]);
            assertFalse(item[0]+"/"+item[1],StopGate.mutationEvidence(item[0],args));
            StopGate.Receipt receipt=new StopGate.Receipt("tool:read",item[0],args.toString(),ToolResult.success("read-only result"));
            StopGate.Snapshot s=new StopGate.Snapshot("task","生成报告",Collections.emptyList(),new JsonObject(),Collections.singletonList(receipt),"报告已生成。","");
            assertEquals(item[0],"continue",StopGate.parse(s,result(s,"completed","task","tool:read").toString()).status);
        }
        JsonObject write=new JsonObject();write.addProperty("action","execute");
        assertTrue(StopGate.mutationEvidence("SQLite",write));
    }
    @Test public void realWriteReceiptCanBackstopReviewerAnswerClassification() {
        StopGate.Snapshot s=snapshot("");
        StopGate.Decision decision=StopGate.parse(s,result(s,"completed","answer","reply").toString());
        assertEquals("completed",decision.status);assertEquals("task",decision.kind);
        assertTrue(decision.evidence.contains("tool:ok"));
    }
    @Test public void howToQuestionDoesNotRequireToolExecution() {
        StopGate.Snapshot s=new StopGate.Snapshot("q","如何生成报告.xlsx？",Collections.emptyList(),new JsonObject(),Collections.emptyList(),"可以用Excel导出。","");
        assertEquals("completed",StopGate.parse(s,result(s,"completed","answer","reply").toString()).status);
    }
    @Test public void negativeConstraintDoesNotCancelPositiveDeliverable() {
        StopGate.Snapshot work=new StopGate.Snapshot("task","生成报告，不要修改原始数据",Collections.emptyList(),new JsonObject(),Collections.emptyList(),"报告已完成。","");
        assertEquals("continue",StopGate.parse(work,result(work,"completed","answer","reply").toString()).status);
        StopGate.Snapshot answer=new StopGate.Snapshot("q","分三项解释，不要修改文件",Collections.emptyList(),new JsonObject(),Collections.emptyList(),"三项说明已经给出。","");
        assertEquals("completed",StopGate.parse(answer,result(answer,"completed","answer","reply").toString()).status);
    }
    @Test public void explicitAgentNextStepCannotBeApprovedEvenWithCompletionMarkerAndReceipt() {
        StopGate.Receipt write=new StopGate.Receipt("tool:write","Write","{\"path\":\"report.txt\"}",ToolResult.success("draft written"));
        StopGate.Snapshot s=new StopGate.Snapshot("task","生成并核验报告",Collections.emptyList(),new JsonObject(),Collections.singletonList(write),
                "初稿已写入。接下来我会检查内容。"+AgentLoop.TASK_COMPLETE_MARKER,"");
        StopGate.Decision d=StopGate.parse(s,result(s,"completed","answer","reply").toString());
        assertEquals("continue",d.status);assertTrue(d.reason.contains("阶段进度"));
    }
    @Test public void successfulWriteCannotCertifyMissingOrChangedOutputAtStopTime() {
        StopGate.Receipt write=new StopGate.Receipt("tool:write","Write","{\"path\":\"report.txt\"}",ToolResult.success("written"));
        for(String status:new String[]{"missing","present"}) {
            JsonObject facts=new JsonObject(),file=new JsonObject();file.addProperty("path","report.txt");file.addProperty("status",status);
            file.addProperty("recordedSha256","old");file.addProperty("actualSha256","new");
            JsonArray outputs=new JsonArray();outputs.add(file);facts.add("outputFiles",outputs);
            StopGate.Snapshot s=new StopGate.Snapshot("task","生成报告",Collections.emptyList(),facts,Collections.singletonList(write),"报告已生成。","");
            StopGate.Decision d=StopGate.parse(s,result(s,"completed","task","tool:write").toString());
            assertEquals("continue",d.status);assertTrue(d.reason.contains("输出文件"));
        }
    }
    @Test public void mismatchedTaskTicketVersionAreRejected() {
        StopGate.Snapshot s=snapshot("");for(String field:new String[]{"taskId","ticket","version"}) {
            JsonObject o=result(s,"completed","task","tool:ok");o.addProperty(field,"old");assertEquals(field,"unknown",StopGate.parse(s,o.toString()).status);
        }
    }
    @Test public void malformedMissingFieldsUnknownStateAndUnknownEvidenceAreRejected() {
        StopGate.Snapshot s=snapshot("");assertEquals("unknown",StopGate.parse(s,"{broken").status);
        for(String field:new String[]{"status","kind","reason","nextStep","evidence"}) {
            JsonObject o=result(s,"completed","task","tool:ok");o.remove(field);assertEquals(field,"unknown",StopGate.parse(s,o.toString()).status);
        }
        assertEquals("unknown",StopGate.parse(s,result(s,"done","task","tool:ok").toString()).status);
        assertEquals("unknown",StopGate.parse(s,result(s,"completed","task","invented").toString()).status);
    }
    @Test public void pendingHumanOrUnknownNeverBecomeCompleted() {
        StopGate.Snapshot s=snapshot("");for(String status:new String[]{"waiting_user","unknown"})
            assertEquals(status,StopGate.parse(s,result(s,status,"task","reply").toString()).status);
    }
    @Test public void newGuidanceOrEvidenceInvalidatesApprovalVersion() {
        StopGate.Snapshot a=snapshot("");StopGate.Snapshot b=snapshot("");assertTrue(a.sameFacts(b));assertNotEquals(a.ticket,b.ticket);
        StopGate.Snapshot changed=snapshot("文件已改变");assertFalse(a.sameFacts(changed));
    }
    @Test public void onlyNewSuccessfulFactsResetNoProgressBudget() {
        StopGate.Progress p=new StopGate.Progress();assertEquals(1,p.deny());assertEquals(2,p.deny());
        p.observe(new StopGate.Receipt("1","AgentControl","{\"action\":\"status\"}",ToolResult.success("不断变动的状态")));assertEquals(3,p.deny());
        p.observe(new StopGate.Receipt("2","Bash","{}",ToolResult.error("新错误")));assertEquals(4,p.deny());
        p.observe(new StopGate.Receipt("3","Read","{\"path\":\"a\"}",ToolResult.success("证据A")));assertEquals(1,p.deny());
        p.observe(new StopGate.Receipt("4","Read","{\"path\":\"a\"}",ToolResult.success("证据A")));assertEquals(2,p.deny());
        p.observe(new StopGate.Receipt("5","Read","{\"path\":\"a\"}",ToolResult.success("证据B")));assertEquals(1,p.deny());
    }
    @Test public void malformedReviewHasOnlyOneRetryAndCanRecover() {
        StopGate.Snapshot s=snapshot("");FakeLlmClient llm=new FakeLlmClient();llm.stopKind="task";
        llm.onStopReview=new java.util.function.Consumer<String>(){int count;public void accept(String v){llm.stopReviewRaw=count++==0?"{broken":null;}};
        assertEquals("completed",StopGate.evaluate(llm,s,null).status);assertEquals(2,llm.stopReviewRequests.size());
    }
    @Test public void fencedJsonIsAcceptedButFreeTextIsRecoverableNotApproved() {
        StopGate.Snapshot s=snapshot("");
        assertEquals("completed",StopGate.parse(s,"```json\n"+result(s,"completed","task","tool:ok")+"\n```").status);
        StopGate.Decision invalid=StopGate.parse(s,"我认为任务完成了");
        assertEquals("unknown",invalid.status);assertFalse(invalid.valid);assertTrue(invalid.recoverableReviewFailure);
        JsonObject wrongTicket=result(s,"completed","task","tool:ok");wrongTicket.addProperty("ticket","other");
        assertFalse(StopGate.parse(s,wrongTicket.toString()).recoverableReviewFailure);
    }
    @Test public void validUnknownIsNotRetriedOrTurnedIntoCompletion() {
        FakeLlmClient llm=new FakeLlmClient();llm.stopStatuses.add("unknown");
        assertEquals("unknown",StopGate.evaluate(llm,snapshot(""),null).status);assertEquals(1,llm.stopReviewRequests.size());
    }
    @Test public void longCandidateIsReviewedFromBoundedPreviewWithoutLosingVersionSafety() {
        char[] chars=new char[48001];Arrays.fill(chars,'x');FakeLlmClient llm=new FakeLlmClient();
        StopGate.Snapshot s=new StopGate.Snapshot("large","核验",Collections.emptyList(),new JsonObject(),Collections.emptyList(),new String(chars),"");
        assertTrue(s.input.length()<48000);
        assertTrue(JsonParser.parseString(s.input).getAsJsonObject().get("candidateTruncated").getAsBoolean());
        llm.stopStatuses.add("continue");
        assertEquals("continue",StopGate.evaluate(llm,s,null).status);assertEquals(1,llm.stopReviewRequests.size());
        chars[20000]='y';
        StopGate.Snapshot changed=new StopGate.Snapshot("large","核验",Collections.emptyList(),new JsonObject(),Collections.emptyList(),new String(chars),"");
        assertFalse(s.sameFacts(changed));
    }
    @Test public void largeFactsAndReceiptsRemainReviewableWithinBudget() {
        char[] chars=new char[100000];Arrays.fill(chars,'z');
        JsonObject facts=new JsonObject();facts.addProperty("todos",new String(chars));
        List<StopGate.Receipt> receipts=new ArrayList<StopGate.Receipt>();
        for(int i=0;i<30;i++)receipts.add(new StopGate.Receipt("tool:"+i,"Read","{}",ToolResult.success(new String(chars))));
        StopGate.Snapshot s=new StopGate.Snapshot("large","核验",Collections.emptyList(),facts,receipts,new String(chars),"");
        assertTrue(s.input.length()<48000);
        assertTrue(JsonParser.parseString(s.input).getAsJsonObject().get("factsTruncated").getAsBoolean());
    }
}
