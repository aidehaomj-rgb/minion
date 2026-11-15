package com.minion.core.agent;

import com.minion.core.tools.ToolResult;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProgressEvidencePolicyTest {
    @Test public void observationOnlyActionsCannotResetEitherProgressCounter() {
        String[][] actions={{"Process","list"},{"Process","status"},{"Process","wait"},{"Process","output"},
                {"DbMysql","status"},{"DbMysql","wait"},{"DbPostgres","status"},{"DbOracle","wait"},
                {"Logs","tail"},{"Logs","status"},{"Permission","audit"},{"Permission","status"},
                {"BrowserAction","status"},{"BrowserAction","wait"},{"BrowserAction","wait_selector"},
                {"BrowserAction","network_idle"},{"BrowserAction","download_status"},{"AgentControl","verify"},
                {"TodoWrite","update"},{"task","finish_step"},{"DiscoverTools","search"}};
        ContinuationBudget budget=new ContinuationBudget();StopGate.Progress gate=new StopGate.Progress();
        assertEquals(1,gate.deny());int denies=1;
        for(String[] action:actions)for(int i=0;i<2;i++) {
            String args="{\"action\":\""+action[1]+"\"}",output="changing log/time/DOM version "+i;
            budget.observe(action[0],args,true,output,null);
            gate.observe(new StopGate.Receipt("receipt-"+denies,action[0],args,ToolResult.success(output)));
            assertEquals(action[0]+"/"+action[1],++denies,gate.deny());
        }
        assertEquals(0,budget.evidenceVersion);
    }
    @Test public void diagnosticsTimestampAndAutoExportNameAreNotNewFacts() {
        ContinuationBudget budget=new ContinuationBudget();StopGate.Progress gate=new StopGate.Progress();
        String args="{\"action\":\"export\"}";
        for(int i=0;i<2;i++) {
            String output="Minion 离线诊断\n时间: 2026-11-06 15:00:0"+i+"\nJava: ok\nPython: ok\n\n报告已导出: D:/report-"+i+".txt";
            budget.observe("Diagnostics",args,true,output,null);
            gate.observe(new StopGate.Receipt("r"+i,"Diagnostics",args,ToolResult.success(output)));
            assertEquals(i+1,gate.deny());
        }
        assertEquals(1,budget.evidenceVersion);
        String changed="Minion 离线诊断\n时间: 2026-11-06 15:01:00\nJava: ok\nPython: missing";
        budget.observe("Diagnostics",args,true,changed,null);
        gate.observe(new StopGate.Receipt("changed","Diagnostics",args,ToolResult.success(changed)));
        assertEquals(2,budget.evidenceVersion);assertEquals(1,gate.deny());
    }
    @Test public void businessDataAndRealBrowserActionsStillCount() {
        ContinuationBudget budget=new ContinuationBudget();StopGate.Progress gate=new StopGate.Progress();
        for(String[] call:new String[][]{{"DbMysql","query"},{"BrowserAction","text"},{"BrowserAction","click"},{"Read","read"}}) {
            String args="{\"action\":\""+call[1]+"\"}";
            budget.observe(call[0],args,true,"real data",null);
            gate.observe(new StopGate.Receipt(call[0]+call[1],call[0],args,ToolResult.success("real data")));
            assertEquals(1,gate.deny());
        }
        assertEquals(4,budget.evidenceVersion);
    }
    @Test public void repeatedBuildUuidCannotResetStopGateEither() {
        StopGate.Progress gate=new StopGate.Progress();
        for(int i=0;i<2;i++) {
            String output="{\"status\":\"passed\",\"sourceDigest\":\"same\",\"tests\":10,\"id\":\"uuid-"+i+"\",\"elapsedMs\":"+i+"}";
            gate.observe(new StopGate.Receipt("r"+i,"Build","{\"action\":\"test\"}",ToolResult.success(output)));
            assertEquals(i+1,gate.deny());
        }
    }
    @Test public void artifactNestedContentChangeStillCountsButObservationTimeDoesNot() {
        ContinuationBudget budget=new ContinuationBudget();StopGate.Progress gate=new StopGate.Progress();
        String args="{\"action\":\"file\",\"path\":\"report.txt\"}";
        for(int i=0;i<3;i++) {
            String sha=i<2?"old-content":"new-content";
            String output="{\"action\":\"file\",\"status\":\"passed\",\"observedAt\":"+i
                    +",\"facts\":{\"path\":\"report.txt\",\"sha256\":\""+sha+"\",\"size\":10,\"modifiedAt\":"+i+",\"stableMs\":"+i+"}}";
            budget.observe("ArtifactVerification",args,true,output,null);
            gate.observe(new StopGate.Receipt("r"+i,"ArtifactVerification",args,ToolResult.success(output)));
            assertEquals(i==1?2:1,gate.deny());
            assertEquals(i<2?1:2,budget.evidenceVersion);
        }
    }
    @Test public void nestedBrowserHttpContentUsesStableHashNotPageVersion() {
        String one="{\"status\":\"passed\",\"facts\":{\"http\":{\"sha256\":\"one\",\"url\":\"http://localhost\"},\"checks\":[{\"text\":\"业务结果\",\"pageVersion\":1}]}}";
        String same=one.replace("\"pageVersion\":1","\"pageVersion\":2");
        String changed=same.replace("\"sha256\":\"one\"","\"sha256\":\"two\"");
        assertEquals(ProgressEvidencePolicy.stableOutput("ArtifactVerification",one),ProgressEvidencePolicy.stableOutput("ArtifactVerification",same));
        assertNotEquals(ProgressEvidencePolicy.stableOutput("ArtifactVerification",same),ProgressEvidencePolicy.stableOutput("ArtifactVerification",changed));
    }
}
