package com.minion.core.agent;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProgressContinuationTest {
    private TaskState state(){TaskState s=new TaskState();s.begin("持续处理当前任务",true);return s;}
    private boolean next(TaskState s){s.finish(false,"轮次边界","round_limit");return s.beginAutomaticContinuation();}
    @Test public void noEvidenceStillStopsAtOriginalThreeWindows(){TaskState s=state();for(int i=0;i<3;i++)assertTrue(next(s));assertFalse(next(s));}
    @Test public void newActualEvidenceExtendsButNeverRemovesHardCap(){TaskState s=state();for(int i=0;i<ContinuationBudget.MAX_WINDOWS;i++){s.observeToolProgress("Read","file="+i,true,"content="+i,null);assertTrue("window="+i,next(s));}s.observeToolProgress("Read","new",true,"new",null);assertFalse(next(s));}
    @Test public void RepeatedReadsControlsAndFailuresCannotRefreshBudget(){TaskState s=state();for(int i=0;i<3;i++){s.observeToolProgress("Read","same",true,"same",null);assertTrue(next(s));}s.observeToolProgress("Read","same",true,"same",null);s.observeToolProgress("TodoWrite","different",true,"done",null);s.observeToolProgress("AgentControl","checkpoint",true,"changed",null);s.observeToolProgress("Write","file",false,"failed",null);assertFalse(next(s));}
    @Test public void freshEvidenceIsRequiredForEveryExtendedWindow(){TaskState s=state();for(int i=0;i<3;i++)assertTrue(next(s));s.observeToolProgress("Write","A",true,"created",null);assertTrue(next(s));assertFalse(next(s));}
    @Test public void explicitHumanResumeResetsBudgetWithoutCreatingNewTask(){TaskState s=state();String id=s.id;for(int i=0;i<3;i++)assertTrue(next(s));assertFalse(next(s));s.begin("继续",false);assertEquals(id,s.id);assertEquals(0,s.automaticContinuations);assertTrue(next(s));}
    @Test public void serializationRetainsDedupAndCancelledTurnsDoNotAutoResume(){TaskState s=state();s.observeToolProgress("Read","same",true,"same",null);assertTrue(next(s));s=new Gson().fromJson(new Gson().toJson(s),TaskState.class);int version=s.continuationBudget.evidenceVersion;s.observeToolProgress("Read","same",true,"same",null);assertEquals(version,s.continuationBudget.evidenceVersion);s.finish(false,"用户停止","interrupted");assertFalse(s.canAutomaticallyContinue());assertEquals("interrupted",s.executionPhase());}
    @Test public void newReportIdsAndTimingsDoNotMakeRepeatedTestsNewProgress(){TaskState s=state();for(int i=0;i<3;i++){s.observeToolProgress("Build","test",true,"{\"type\":\"minion.validation.v1\",\"kind\":\"test\",\"status\":\"passed\",\"sourceDigest\":\"same\",\"tests\":1,\"runId\":\""+i+"\",\"durationMillis\":"+i+"}",null);assertTrue(next(s));}s.observeToolProgress("Build","test",true,"{\"type\":\"minion.validation.v1\",\"kind\":\"test\",\"status\":\"passed\",\"sourceDigest\":\"same\",\"tests\":1,\"runId\":\"new\"}",null);assertFalse(next(s));}
}
