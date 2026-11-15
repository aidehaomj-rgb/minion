package com.minion.core.agent;

import com.google.gson.Gson;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ProgressReceiptLedgerTest {
    @Test public void oldJournalBudgetRoundTripsWithoutInjectingNewDefaults() {
        Gson gson=new Gson();String old="{\"evidenceVersion\":0,\"continuedVersion\":0,\"idleWindows\":0,\"seen\":[]}";
        ContinuationBudget budget=gson.fromJson(old,ContinuationBudget.class);
        assertEquals(com.google.gson.JsonParser.parseString(old),gson.toJsonTree(budget));
        assertEquals(0,budget.observedTokens());
    }
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void moreThan512ReceiptsRemainExactAcrossReload() throws Exception {
        Path root=temp.newFolder().toPath();ContinuationBudget b=new ContinuationBudget();
        b.bindLedger(new ProgressReceiptLedger(root,"session","task"));
        for(int i=0;i<650;i++)b.observe("Read","path="+i,true,"content="+i,null);
        assertEquals(650,b.evidenceVersion);assertTrue(b.seen.size()<=512);
        b.observe("Read","path=0",true,"content=0",null);assertEquals(650,b.evidenceVersion);
        b=new Gson().fromJson(new Gson().toJson(b),ContinuationBudget.class);
        b.bindLedger(new ProgressReceiptLedger(root,"session","task"));
        b.observe("Read","path=1",true,"content=1",null);assertEquals(650,b.evidenceVersion);
        b.observe("Read","path=651",true,"content=651",null);assertEquals(651,b.evidenceVersion);
    }
    @Test public void hostBudgetExtendsProductiveWindowsButNotIdleOrTokenLimit() {
        ContinuationBudget b=new ContinuationBudget();b.configure(96,1000);
        for(int i=0;i<40;i++){b.observe("Write","path="+i,true,"ok",null);assertTrue(b.permits(i));b.continued();}
        assertFalse(b.permits(40));b.observe("Write","next",true,"ok",null);assertTrue(b.permits(40));
        b.usage(1000);assertFalse(b.permits(40));assertTrue(b.describe(40).contains("1000/1000"));
    }
    @Test public void independentTasksDoNotShareDedup() throws Exception {
        Path root=temp.newFolder().toPath();String hash=StopGate.digest("same");
        assertTrue(new ProgressReceiptLedger(root,"s","one").add(hash));
        assertTrue(new ProgressReceiptLedger(root,"s","two").add(hash));
        assertFalse(new ProgressReceiptLedger(root,"s","one").add(hash));
    }
    @Test public void corruptShardNeverGrantsFreshProgress() throws Exception {
        Path root=temp.newFolder().toPath();String hash=StopGate.digest("corruption");
        ProgressReceiptLedger ledger=new ProgressReceiptLedger(root,"s","t");assertTrue(ledger.add(hash));
        Path shard=root.resolve(".minion/progress/s/t/"+hash.substring(0,2)+".txt");
        Files.write(shard,"broken".getBytes("US-ASCII"));
        try{ledger.add(hash);fail("must reject corrupt records");}catch(java.io.IOException expected){}
    }
    @Test public void unboundLegacyBudgetNoLongerFreezesAt512() {
        ContinuationBudget b=new ContinuationBudget();for(int i=0;i<600;i++)b.observe("Read","f="+i,true,"ok",null);
        assertEquals(600,b.evidenceVersion);
    }
    @Test public void processPollingNeverRefreshesUsefulProgress() {
        ContinuationBudget b=new ContinuationBudget();
        for(String action:new String[]{"status","wait","output","list"})b.observe("Process","{\"action\":\""+action+"\"}",true,"changed output "+action,null);
        assertEquals(0,b.evidenceVersion);
    }
    @Test public void jdbcPollingNeverRefreshesUsefulProgressButQueryStillCan() {
        ContinuationBudget b=new ContinuationBudget();
        for(String tool:new String[]{"DbMysql","DbPostgres","DbOracle"}) {
            for(String action:new String[]{"status","wait"})
                b.observe(tool,"{\"action\":\""+action+"\"}",true,"changing job status "+System.nanoTime(),null);
        }
        assertEquals(0,b.evidenceVersion);
        b.observe("DbMysql","{\"action\":\"query\",\"sql\":\"SELECT 1\"}",true,"actual query data",null);
        assertEquals(1,b.evidenceVersion);
    }
}
