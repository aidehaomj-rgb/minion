package com.minion.core.agent;
import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskWorkingSetTest {
    @Test public void revisionChangeDropsOldNextStepAndOldFileReceipts() {
        TaskState s=new TaskState();s.begin("生成旧报告",true);s.latestUserEventId="event-current";
        FileEvidence file=new FileEvidence();file.path="old.xlsx";file.sha256="old";
        s.recordEvidence("root","Write",true,"旧产物",file,true);
        s.checkpoint("执行旧导出","old",java.util.Collections.singletonList(file));
        s.addGuidance("改成分析新的数据，不要导出旧报告");
        JsonObject set=s.workingSet();assertTrue(set.get("nextStep").isJsonNull());
        assertEquals(0,set.getAsJsonArray("observedFiles").size());
        assertFalse(s.compactionHandoff(new TodoList()).contains("最近工具回执 Write"));
    }
    @Test public void activeLastStepIsNotLostBehind38CompletedSteps() {
        TaskState s=new TaskState();s.begin("处理38分块",true);s.workflow=new TaskWorkflow();
        s.workflow.taskId=s.id;s.workflow.revision=s.revision();
        for(int i=0;i<38;i++){TaskWorkflow.Step step=new TaskWorkflow.Step();step.id="chunk"+i;
            step.scope="part"+i;step.acceptance="核对总数";step.status=i==37?"running":"completed";s.workflow.steps.add(step);}
        JsonArray active=s.workingSet().getAsJsonArray("activeSteps");assertEquals(1,active.size());
        assertEquals("chunk37",active.get(0).getAsJsonObject().get("stepId").getAsString());
    }
    @Test public void handoffCarriesVersionReferenceWithoutRereadingDisk() {
        TaskState s=new TaskState();s.begin("继续分析",true);s.latestUserEventId="ev1";
        FileEvidence file=new FileEvidence();file.path="not-yet-readable.xlsx";file.sha256="observedhash";
        s.recordEvidence("root","Read",true,"读到数据",file,false);
        JsonObject set=s.workingSet();assertEquals("ev1",set.get("latestUserEventId").getAsString());
        assertEquals("observedhash",set.getAsJsonArray("observedFiles").get(0).getAsJsonObject().get("observedSha256").getAsString());
    }
    @Test public void externalWaitAndVerificationAreNotCompletion() {
        TaskState s=new TaskState();s.begin("运行作业",true);s.runtimePhase="waiting_external";
        assertEquals("waiting_external",s.executionPhase());s.runtimePhase="verifying";assertEquals("verifying",s.executionPhase());
        assertEquals("running",s.status);
    }
}
