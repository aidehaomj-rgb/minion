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
    @Test public void validUnknownIsNotRetriedOrTurnedIntoCompletion() {
        FakeLlmClient llm=new FakeLlmClient();llm.stopStatuses.add("unknown");
        assertEquals("unknown",StopGate.evaluate(llm,snapshot(""),null).status);assertEquals(1,llm.stopReviewRequests.size());
    }
    @Test public void overBudgetInputDoesNotIssueAnyReviewOrApprove() {
        char[] chars=new char[48001];Arrays.fill(chars,'x');FakeLlmClient llm=new FakeLlmClient();
        StopGate.Snapshot s=new StopGate.Snapshot("large","核验",Collections.emptyList(),new JsonObject(),Collections.emptyList(),new String(chars),"");
        assertEquals("unknown",StopGate.evaluate(llm,s,null).status);assertTrue(llm.stopReviewRequests.isEmpty());
    }
}
