package com.minion.core.agent;

import com.minion.core.tools.*;
import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class RuntimeJobRecoveryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path root,ledger,result;
    private String id;
    @Before public void setup() throws Exception {
        root=temp.newFolder().toPath().toRealPath();ledger=root.resolve("jobs.json");result=root.resolve("result.csv");
        Files.write(result,Arrays.asList("real saved result"));
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            RuntimeJobRegistry.Handle h=jobs.register("task",2,3,"sql","existing job",result.toString(),true);id=h.jobId;
            jobs.publish(h,RuntimeJobRegistry.Status.RUNNING,"external-42",null,null,"accepted not completed");
        }
    }
    private RuntimeJobRecovery recovery(RuntimeJobRegistry jobs) throws Exception{return new RuntimeJobRecovery(jobs,Arrays.asList(root));}
    private interface Checked {void run() throws Exception;}
    private void rejected(Checked action) throws Exception {try{action.run();fail("must reject");}catch(IllegalArgumentException expected){}}

    @Test public void restartEvidenceNeverCompletesAndReadOnlyDoesNotWrite() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            byte[] bytes=Files.readAllBytes(ledger);RuntimeJobRecovery recovery=recovery(jobs);
            RuntimeJobRecovery.Report report=recovery.reconcile(id,null,"external-42");
            assertTrue(report.job.fenced);assertTrue(report.humanReviewEligible);assertFalse(report.completionProven);
            assertEquals(RuntimeJobRegistry.Status.UNKNOWN,report.job.status);
            assertEquals("stable_observation",report.file.state);assertEquals(64,report.file.sha256.length());
            assertEquals("matches_ledger_only_not_provider_verified",report.externalIdCheck);
            assertTrue(recovery.reconcile(id,report.file.sha256,null).file.sameAsPrevious);
            assertArrayEquals(bytes,Files.readAllBytes(ledger));
            assertEquals(RuntimeJobRegistry.Status.RUNNING,report.job.history.get(report.job.history.size()-1).status);
            assertTrue(report.job.history.get(report.job.history.size()-1).stateJson.contains("accepted not completed"));
        }
    }
    @Test public void versionsDetectReplacementAndReviewRejectsStaleFile() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            RuntimeJobRecovery recovery=recovery(jobs);RuntimeJobRecovery.Report old=recovery.reconcile(id,null,null);
            Files.write(result,Arrays.asList("new version"));
            RuntimeJobRecovery.Report next=recovery.reconcile(id,old.file.sha256,null);
            assertFalse(next.file.sameAsPrevious);assertNotEquals(old.file.sha256,next.file.sha256);
            rejected(()->recovery.acknowledge(old,"human","checked"));
        }
    }
    @Test public void manualReviewPersistsButDoesNotResumeAndCannotReplay() throws Exception {
        long version;
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            RuntimeJobRecovery recovery=recovery(jobs);RuntimeJobRecovery.Report old=recovery.reconcile(id,null,null);
            RuntimeJobRegistry.JobRecord reviewed=recovery.acknowledge(old,"authenticated-user","read evidence, execution unresolved");
            assertEquals(RuntimeJobRegistry.Status.UNKNOWN,reviewed.status);assertTrue(reviewed.fenced);
            assertFalse(jobs.isCurrent(reviewed));assertEquals(1,reviewed.recoveryReviews.size());version=reviewed.version;
            assertEquals(old.file.sha256,reviewed.recoveryReviews.get(0).fileEvidence.sha256);
            assertTrue(reviewed.recoveryReviews.get(0).fileEvidence.stableDuringRead);
            rejected(()->recovery.acknowledge(old,"authenticated-user","duplicate stale review"));
        }
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            assertEquals(version,jobs.get(id).version);assertEquals(1,jobs.get(id).recoveryReviews.size());
            assertTrue(jobs.get(id).fenced);
        }
    }
    @Test public void allRecoveryIdentityFieldsAreRequired() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            RuntimeJobRegistry.JobRecord r=jobs.get(id);
            rejected(()->jobs.acknowledgeRecovery(id,"other",r.revision,r.attempt,r.ownerEpoch,r.version,"user","note",null,r.externalInstanceId));
            rejected(()->jobs.acknowledgeRecovery(id,r.taskId,1,r.attempt,r.ownerEpoch,r.version,"user","note",null,r.externalInstanceId));
            rejected(()->jobs.acknowledgeRecovery(id,r.taskId,r.revision,4,r.ownerEpoch,r.version,"user","note",null,r.externalInstanceId));
            rejected(()->jobs.acknowledgeRecovery(id,r.taskId,r.revision,r.attempt,"wrong",r.version,"user","note",null,r.externalInstanceId));
            rejected(()->jobs.acknowledgeRecovery(id,r.taskId,r.revision,r.attempt,r.ownerEpoch,r.version,"user","note",null,"wrong"));
            jobs.bindTask("task",3);assertFalse(recovery(jobs).inspect(id).humanReviewEligible);
            rejected(()->recovery(jobs).acknowledge(recovery(jobs).inspect(id),"user","old revision"));
        }
    }
    @Test public void permissionMissingDirectoryAndExternalIdBoundaries() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            assertEquals("not_authorized",new RuntimeJobRecovery(jobs,Collections.<Path>emptyList()).reconcile(id,null,null).file.state);
            RuntimeJobRecovery recovery=recovery(jobs);
            assertEquals("mismatch",recovery.reconcile(id,null,"wrong").externalIdCheck);
            Files.delete(result);assertEquals("missing",recovery.reconcile(id,null,null).file.state);
            Files.createDirectory(result);assertEquals("not_regular_file",recovery.reconcile(id,null,null).file.state);
            jobs.cancel(id,"user stop");assertFalse(recovery.inspect(id).humanReviewEligible);
            assertEquals(RuntimeJobRegistry.Status.UNKNOWN,jobs.get(id).status);
        }
    }
    @Test public void modelToolOnlyAllowsReadsAndRegistrationIsExplicit() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            ToolRegistry registry=new ToolRegistry();RuntimeJobsTool.registerForMainAgent(registry,recovery(jobs));
            Tool tool=registry.get("RuntimeJobs");byte[] before=Files.readAllBytes(ledger);
            for(String action:Arrays.asList("list","inspect","reconcile")){
                JsonObject args=new JsonObject();args.addProperty("action",action);args.addProperty("jobId",id);
                assertTrue(tool.execute(args).ok);
            }
            for(String action:Arrays.asList("cancel","complete","acknowledge","retry")){
                JsonObject args=new JsonObject();args.addProperty("action",action);assertFalse(tool.execute(args).ok);
            }
            JsonObject args=new JsonObject();args.addProperty("action","reconcile");args.addProperty("jobId",id);args.addProperty("resultPath",ledger.toString());
            assertFalse(tool.execute(args).ok);assertArrayEquals(before,Files.readAllBytes(ledger));
        }
    }
    @Test public void lazyFactoryIsOnlyInvokedByValidExecution() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger)){
            RuntimeJobsTool.Factory factory=()->{calls.incrementAndGet();return recovery(jobs);};
            ToolRegistry registry=new ToolRegistry();RuntimeJobsTool.registerForMainAgent(registry,factory);
            Tool tool=registry.get("RuntimeJobs");tool.name();tool.description();tool.schema();registry.schemas();
            assertEquals(0,calls.get());
            JsonObject invalid=new JsonObject();invalid.addProperty("action","retry");
            assertFalse(tool.execute(invalid).ok);assertEquals(0,calls.get());
            invalid.addProperty("action","inspect");assertFalse(tool.execute(invalid).ok);assertEquals(0,calls.get());
            JsonObject valid=new JsonObject();valid.addProperty("action","list");
            assertTrue(tool.execute(valid).ok);assertEquals(1,calls.get());
        }
    }
    @Test public void factoryExceptionIsReportedOnlyAtExecution() {
        RuntimeJobsTool tool=new RuntimeJobsTool(()->{throw new java.io.IOException("offline ledger unavailable");});
        tool.schema();tool.description();
        JsonObject args=new JsonObject();args.addProperty("action","list");
        ToolResult result=tool.execute(args);assertFalse(result.ok);assertFalse(result.noSideEffect);
        assertTrue(result.output.contains("offline ledger unavailable"));
    }
    @Test public void discoveryDoesNotCreateLazyLedger() throws Exception {
        Path lazyPath=root.resolve("never-opened-by-discovery.json");
        final RuntimeJobRegistry[] owned=new RuntimeJobRegistry[1];
        RuntimeJobsTool tool=new RuntimeJobsTool(()->{
            owned[0]=new RuntimeJobRegistry(lazyPath);return recovery(owned[0]);
        });
        tool.schema();tool.description();assertFalse(Files.exists(lazyPath));
        assertFalse(Files.exists(lazyPath.resolveSibling(lazyPath.getFileName()+".lock")));
        JsonObject args=new JsonObject();args.addProperty("action","list");
        try{assertTrue(tool.execute(args).ok);assertTrue(Files.exists(lazyPath));}
        finally{if(owned[0]!=null)owned[0].close();}
    }
    @Test public void relativePathsRequireExplicitOriginalWorkspace() throws Exception {
        Path other=root.resolve("relative-jobs.json");String relativeId;
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(other)){
            relativeId=jobs.register("relative-task",1,1,"sql","old workspace result","result.csv",true).jobId;
        }
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(other)){
            assertEquals("relative_path_requires_host_resolution",recovery(jobs).reconcile(relativeId,null,null).file.state);
            assertEquals("stable_observation",new RuntimeJobRecovery(jobs,Arrays.asList(root),root).reconcile(relativeId,null,null).file.state);
        }
    }
}
