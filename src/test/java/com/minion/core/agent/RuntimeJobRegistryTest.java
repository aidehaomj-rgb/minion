package com.minion.core.agent;

import com.minion.core.agent.RuntimeJobRegistry.Handle;
import com.minion.core.agent.RuntimeJobRegistry.JobRecord;
import com.minion.core.agent.RuntimeJobRegistry.Status;
import com.minion.core.agent.RuntimeJobRegistry.WaitReason;
import com.minion.core.agent.RuntimeJobRegistry.WaitResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Offline provider simulation: no cloud SDK, model call, network, or 20-minute sleep. */
public class RuntimeJobRegistryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path ledger() throws IOException{return temp.newFolder().toPath().resolve("jobs.json");}
    private Handle submit(RuntimeJobRegistry jobs) throws IOException{return jobs.register("task-A",1,2,"simulated","queued for 20 minutes","result.csv",true);}

    @Test public void identityIsDurableAndSnapshotsAreDetached() throws Exception {
        Path path=ledger();String id;
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            Handle h=submit(jobs);id=h.jobId;
            jobs.publish(h,Status.RUNNING,"instance-123","result.csv",null,"submitted once");
            JobRecord r=jobs.get(id);assertEquals("task-A",r.taskId);assertEquals(1,r.revision);assertEquals(2,r.attempt);
            assertEquals("instance-123",r.externalInstanceId);assertTrue(r.waitForCompletion);
            r.status=Status.COMPLETED;r.taskId="changed";
            assertEquals(Status.RUNNING,jobs.get(id).status);assertEquals("task-A",jobs.get(id).taskId);
            String json=new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
            assertTrue(json.contains("\"status\":\"running\""));assertTrue(json.contains("instance-123"));
            assertEquals(1,jobs.foregroundJobs("task-A",1).size());assertTrue(jobs.foregroundJobs("task-B",1).isEmpty());
        }
        try(RuntimeJobRegistry reopened=new RuntimeJobRegistry(path)) {
            assertEquals(Status.UNKNOWN,reopened.get(id).status);assertEquals("instance-123",reopened.get(id).externalInstanceId);
            assertTrue(reopened.get(id).fenced);assertTrue(reopened.get(id).recovered);assertEquals(1,reopened.list().size());
        }
    }

    @Test public void everyUnfinishedStateBecomesUnknownWithoutSubmittingAgain() throws Exception {
        Path path=ledger();
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            for(Status state:Arrays.asList(Status.QUEUED,Status.RUNNING,Status.WAITING_EXTERNAL,Status.WAITING_USER,Status.VERIFYING)) {
                Handle h=submit(jobs);if(state!=Status.QUEUED)jobs.publish(h,state,"instance-"+state,null,null,null);
            }
        }
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            assertEquals(5,jobs.list().size());
            for(JobRecord r:jobs.list()){assertEquals(Status.UNKNOWN,r.status);assertTrue(r.fenced);assertTrue(r.recovered);}
        }
    }

    @Test public void completedFailedCancelledSurviveRestartAndUnknownIsNotSuccess() throws Exception {
        Path path=ledger();String completed,failed,cancelled,unknown;
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            Handle a=submit(jobs),b=submit(jobs),c=submit(jobs),d=submit(jobs);
            completed=a.jobId;failed=b.jobId;cancelled=c.jobId;unknown=d.jobId;
            jobs.publish(a,Status.COMPLETED,null,"done.txt",0,null);jobs.publish(b,Status.FAILED,null,null,7,null);
            jobs.cancel(c.jobId,"user stop");jobs.publish(d,Status.UNKNOWN,null,null,null,"network response indeterminate");
            assertFalse(jobs.awaitTerminal(unknown,0,null).canResumeTask);
        }
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            assertEquals(Status.COMPLETED,jobs.get(completed).status);assertEquals(Status.FAILED,jobs.get(failed).status);
            assertEquals(Status.CANCELLED,jobs.get(cancelled).status);assertEquals(Status.UNKNOWN,jobs.get(unknown).status);
            assertTrue(jobs.get(cancelled).cancelRequested);assertEquals("user stop",jobs.get(cancelled).cancelReason);
        }
    }

    @Test public void oldRevisionCompletionOnlyArchivesAndCannotWakeNewTask() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger())) {
            Handle old=submit(jobs);jobs.publish(old,Status.WAITING_EXTERNAL,"old-instance",null,null,"network disconnected; instance still running");
            jobs.bindTask("task-A",2);
            Handle current=jobs.register("task-A",2,1,"simulated","new goal",null,true);
            jobs.publish(old,Status.COMPLETED,"old-instance","late.csv",0,"late success");
            JobRecord archived=jobs.get(old.jobId);
            assertEquals(Status.UNKNOWN,archived.status);assertTrue(archived.lateResult);assertEquals(Status.COMPLETED,archived.observedStatus);
            assertEquals("late.csv",archived.observedResultPath);assertEquals(Integer.valueOf(0),archived.observedExitCode);
            assertEquals(WaitReason.FENCED,jobs.awaitTerminal(old.jobId,0,null).reason);assertFalse(jobs.awaitTerminal(old.jobId,0,null).canResumeTask);
            assertEquals(Status.QUEUED,jobs.get(current.jobId).status);
            assertEquals(1,jobs.foregroundJobs("task-A",2).size());
            try{jobs.bindTask("task-A",1);fail("must not roll revision back");}catch(IllegalArgumentException expected){}
            try{jobs.register("task-A",1,3,"simulated","stale",null,false);fail("stale submission");}catch(IllegalArgumentException expected){}
            assertEquals(2,jobs.list().size());
        }
    }

    @Test public void cancelThenLateSuccessCannotResurrectJob() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger())) {
            Handle h=submit(jobs);jobs.publish(h,Status.RUNNING,"remote-one",null,null,null);
            jobs.cancel(h.jobId,"explicit cancel");jobs.publish(h,Status.COMPLETED,"remote-one","late.txt",0,"too late");
            JobRecord r=jobs.get(h.jobId);assertEquals(Status.CANCELLED,r.status);assertTrue(r.fenced);assertTrue(r.cancelRequested);
            assertEquals(Status.COMPLETED,r.observedStatus);assertEquals("late.txt",r.observedResultPath);
            assertFalse(jobs.awaitTerminal(h.jobId,0,null).canResumeTask);assertEquals(1,jobs.list().size());
        }
    }

    @Test public void taskStopFenceRejectsNewSubmissionUntilExplicitNewRevision() throws Exception {
        Path path=ledger();
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            Handle h=submit(jobs);jobs.cancelTask("task-A",1,"window stop");
            assertEquals(Status.CANCELLED,jobs.get(h.jobId).status);
            try{submit(jobs);fail("same stopped task cannot relaunch");}catch(IOException expected){}
        }
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            try{submit(jobs);fail("cancel fence must survive restart");}catch(IOException expected){}
            Handle next=jobs.register("task-A",2,1,"simulated","user explicitly changed request",null,false);
            assertEquals(Status.QUEUED,jobs.get(next.jobId).status);assertEquals(2,jobs.list().size());
        }
    }

    @Test public void staleOwnerCannotPromoteRecoveredUnknownEvenWithSameJobId() throws Exception {
        Path path=ledger();Handle old;
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)){old=submit(jobs);jobs.publish(old,Status.RUNNING,"local-pid:123",null,null,null);}
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            jobs.publish(old,Status.COMPLETED,null,"old-result",0,"old callback");
            assertEquals(Status.UNKNOWN,jobs.get(old.jobId).status);assertEquals(Status.COMPLETED,jobs.get(old.jobId).observedStatus);
            assertFalse(jobs.awaitTerminal(old.jobId,0,null).canResumeTask);
        }
    }

    @Test public void completionEventWakesTwentyMinuteWaitWithoutPolling() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger())) {
            Handle h=submit(jobs);AtomicReference<WaitResult> result=new AtomicReference<WaitResult>();
            AtomicReference<Throwable> error=new AtomicReference<Throwable>();CountDownLatch started=new CountDownLatch(1);
            Thread waiter=new Thread(()->{try{started.countDown();result.set(jobs.awaitTerminal(h.jobId,TimeUnit.MINUTES.toMillis(20),null));}catch(Throwable e){error.set(e);}});
            waiter.start();assertTrue(started.await(2,TimeUnit.SECONDS));
            jobs.publish(h,Status.COMPLETED,"instance-one","result.csv",0,"done");
            waiter.join(2000);assertFalse("event must wake, not wait 20 minutes",waiter.isAlive());assertNull(error.get());
            assertEquals(WaitReason.SETTLED,result.get().reason);assertTrue(result.get().canResumeTask);assertEquals(1,jobs.list().size());
        }
    }

    @Test public void waitTimeoutDoesNotCancelOrResubmitAndChangeWaitReturnsNewVersion() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger())) {
            Handle h=submit(jobs);long version=jobs.get(h.jobId).version;
            assertEquals(WaitReason.TIMED_OUT,jobs.awaitTerminal(h.jobId,5,null).reason);
            assertEquals(Status.QUEUED,jobs.get(h.jobId).status);assertFalse(jobs.get(h.jobId).cancelRequested);
            jobs.publish(h,Status.WAITING_EXTERNAL,"slow-instance",null,null,"not a failure");
            assertEquals(WaitReason.CHANGED,jobs.awaitChange(h.jobId,version,0,null).reason);
            assertEquals(1,jobs.list().size());
            try{jobs.awaitTerminal(h.jobId,RuntimeJobRegistry.MAX_WAIT_MILLIS+1,null);fail();}catch(IllegalArgumentException expected){}
        }
    }

    @Test public void cancellationWakesAllWaitersWithoutCancellingExternalExecution() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger())) {
            Handle h=submit(jobs);RuntimeJobCancellation cancel=new RuntimeJobCancellation();
            AtomicReference<WaitResult> result=new AtomicReference<WaitResult>();
            Thread waiter=new Thread(()->{try{result.set(jobs.awaitTerminal(h.jobId,60_000,cancel));}catch(InterruptedException e){throw new AssertionError(e);}});
            waiter.start();cancel.cancel();waiter.join(2000);assertFalse(waiter.isAlive());
            assertEquals(WaitReason.WAIT_CANCELLED,result.get().reason);assertEquals(Status.QUEUED,jobs.get(h.jobId).status);
            assertFalse(jobs.get(h.jobId).cancelRequested);assertEquals(WaitReason.WAIT_CANCELLED,jobs.awaitTerminal(h.jobId,0,cancel).reason);
        }
    }

    @Test public void interruptedWaitDoesNotConsumeJobOrPreventOtherWaits() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger())) {
            Handle h=submit(jobs);CountDownLatch interrupted=new CountDownLatch(1);
            Thread waiter=new Thread(()->{try{jobs.awaitTerminal(h.jobId,60_000,null);}catch(InterruptedException e){interrupted.countDown();}});
            waiter.start();waiter.interrupt();waiter.join(2000);assertTrue(interrupted.await(1,TimeUnit.SECONDS));assertFalse(waiter.isAlive());
            assertEquals(Status.QUEUED,jobs.get(h.jobId).status);assertEquals(WaitReason.TIMED_OUT,jobs.awaitTerminal(h.jobId,0,null).reason);
        }
    }

    @Test public void exclusiveLedgerOwnerAndCorruptionFailClosed() throws Exception {
        Path path=ledger();
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            Handle h=submit(jobs);
            try{new RuntimeJobRegistry(path);fail("must refuse second owner");}catch(IOException expected){}
            assertEquals(Status.QUEUED,jobs.get(h.jobId).status);
        }
        byte[] corrupt="{not-json".getBytes(StandardCharsets.UTF_8);Files.write(path,corrupt);
        try{new RuntimeJobRegistry(path);fail("must not reset corrupt ledger");}catch(IOException expected){}
        assertArrayEquals(corrupt,Files.readAllBytes(path));
        // Failed constructor released its lock; still fails on data, not an orphaned ownership lock.
        try{new RuntimeJobRegistry(path);fail();}catch(IOException expected){assertFalse(expected.getMessage().contains("already owned"));}
    }

    @Test public void diskFailureCannotPublishFakeCompletionAndWakesWaiters() throws Exception {
        Path path=ledger();
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            Handle h=submit(jobs);Files.delete(path);Files.createDirectory(path);Files.write(path.resolve("keep"),new byte[]{1});
            try{jobs.publish(h,Status.COMPLETED,null,null,0,"not durable");fail();}catch(IOException expected){}
            assertEquals(Status.QUEUED,jobs.get(h.jobId).status);assertNotNull(jobs.storageError());
            assertEquals(WaitReason.STORAGE_ERROR,jobs.awaitTerminal(h.jobId,60_000,null).reason);
            try{submit(jobs);fail("no new side effects after persistence failure");}catch(IOException expected){}
            Files.delete(path.resolve("keep"));Files.delete(path);
        }
    }

    @Test public void duplicateTerminalEventIsIdempotentAndConflictingOneIsArchival() throws Exception {
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger())) {
            Handle h=submit(jobs);jobs.publish(h,Status.COMPLETED,null,"done",0,null);long v=jobs.get(h.jobId).version;
            jobs.publish(h,Status.COMPLETED,null,"done",0,null);assertEquals(v,jobs.get(h.jobId).version);
            jobs.publish(h,Status.FAILED,null,"later",9,"late conflict");
            assertEquals(Status.COMPLETED,jobs.get(h.jobId).status);assertEquals(Status.FAILED,jobs.get(h.jobId).observedStatus);
            assertEquals("done",jobs.get(h.jobId).resultPath);
        }
    }

    @Test public void closingRegistryWakesWaitAndNeverSubmitsOrCancels() throws Exception {
        RuntimeJobRegistry jobs=new RuntimeJobRegistry(ledger());Handle h=submit(jobs);jobs.close();jobs.close();
        assertEquals(WaitReason.CLOSED,jobs.awaitTerminal(h.jobId,60_000,null).reason);
        assertEquals(Status.QUEUED,jobs.get(h.jobId).status);
        try{submit(jobs);fail();}catch(IOException expected){}
    }
}
