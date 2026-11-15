package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.minion.core.agent.RuntimeJobCancellation;
import com.minion.core.agent.RuntimeJobRegistry;
import com.minion.core.agent.RuntimeJobRegistry.Handle;
import com.minion.core.agent.RuntimeJobRegistry.JobRecord;
import com.minion.core.agent.RuntimeJobRegistry.Status;
import com.minion.core.agent.RuntimeJobRegistry.WaitReason;
import com.minion.core.agent.RuntimeJobRegistry.WaitResult;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real local-process tests; worker is our own Java fixture, never third-party or network code. */
public class RuntimeProcessP0ATest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path root() throws IOException{return temp.newFolder().toPath();}
    private static JsonObject action(String action,String id){JsonObject a=new JsonObject();a.addProperty("action",action);if(id!=null)a.addProperty("id",id);return a;}
    private static String quote(String value){return "\""+value+"\"";}
    private static String command(String mode) throws Exception {
        boolean win=System.getProperty("os.name","").startsWith("Windows");
        String javaExe=new File(System.getProperty("java.home"),"bin/"+(win?"java.exe":"java")).getAbsolutePath();
        // Worker needs only java.base and its own class directory. Do not pass the full Maven
        // classpath: cmd.exe's 8191-character limit would break a valid offline Win7 test.
        String cp=new File(Worker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        return quote(javaExe)+" -cp "+quote(cp)+" "+quote(Worker.class.getName())+" "+mode;
    }
    private static String start(ProcessTool process,RuntimeJobRegistry jobs,String mode,boolean foreground) throws Exception {
        JsonObject a=action("start",null);a.addProperty("command",command(mode));if(foreground)a.addProperty("waitForCompletion",true);
        ToolResult r=process.execute(a);assertTrue(r.output,r.ok);
        List<JobRecord> list=jobs.list();return list.get(list.size()-1).jobId;
    }
    private static void awaitObservation(RuntimeJobRegistry jobs,String id) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(jobs.get(id).observedExitCode==null&&System.nanoTime()<end)Thread.sleep(10);
        assertNotNull("physical exit must be observed separately from cancel request",jobs.get(id).observedExitCode);
    }

    @Test public void legacyConstructorIsLazyAndSchemasKeepRiskChecks() throws Exception {
        Path root=root();ProcessTool tool=new ProcessTool(new Workspace(root.toString()));
        try {
            assertFalse(Files.exists(root.resolve(".minion")));
            assertTrue(tool.isHighRisk(action("start",null)));assertTrue(tool.isHighRisk(action("stop","x")));
            assertFalse(tool.isHighRisk(action("wait","x")));assertFalse(tool.isHighRisk(action("status","x")));
            assertEquals("boolean",tool.schema().getAsJsonObject("properties").getAsJsonObject("waitForCompletion").get("type").getAsString());
            assertTrue(tool.execute(action("list",null)).ok);assertTrue(Files.exists(tool.runtimeJobs().ledgerPath()));
        } finally {tool.shutdown();}
    }

    @Test public void boundOneShotRegistersNotifiesAndPersistsOutputBeforeCompletion() throws Exception {
        Path root=root();
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            ProcessTool tool=new ProcessTool(new Workspace(root.toString()));AtomicInteger entered=new AtomicInteger(),left=new AtomicInteger();
            tool.bind(jobs,()->"task",()->3L,(job,waiting)->{if(waiting)entered.incrementAndGet();else left.incrementAndGet();});
            try {
                String id=start(tool,jobs,"success",true);WaitResult wait=tool.await(id,20_000,null);
                assertEquals(WaitReason.SETTLED,wait.reason);assertEquals(Status.COMPLETED,wait.job.status);assertEquals(Integer.valueOf(0),wait.job.exitCode);
                assertTrue(wait.canResumeTask);assertEquals("task",wait.job.taskId);assertEquals(3,wait.job.revision);
                assertNotNull(wait.job.externalInstanceId);assertEquals(1,jobs.foregroundJobs("task",3).size());
                assertEquals(1,entered.get());assertEquals(1,left.get());
                ToolResult output=tool.execute(action("output",id));assertTrue(output.output,output.output.contains("worker-ok"));
                assertTrue(tool.execute(action("status",id)).output.contains("COMPLETED"));
                assertTrue(new String(Files.readAllBytes(Paths.get(wait.job.resultPath)),StandardCharsets.UTF_8).contains("worker-ok"));
            } finally {tool.shutdown();}
            assertEquals(Status.COMPLETED,jobs.list().get(0).status); // injected registry not closed by tool
        }
    }

    @Test public void defaultServiceNeverBlocksForegroundAndTimeoutDoesNotResubmit() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            ProcessTool tool=new ProcessTool(new Workspace(root.toString()),jobs);tool.bindTask("task",1);
            try {
                String id=start(tool,jobs,"sleep",false);assertTrue(jobs.foregroundJobs("task",1).isEmpty());
                assertEquals(WaitReason.TIMED_OUT,tool.await(id,1,null).reason);assertEquals(Status.RUNNING,jobs.get(id).status);
                assertFalse(jobs.get(id).cancelRequested);assertEquals(1,jobs.list().size());
                assertTrue(tool.execute(action("stop",id)).ok);awaitObservation(jobs,id);assertEquals(Status.CANCELLED,jobs.get(id).status);
            } finally {tool.shutdown();}
        }
    }

    @Test public void actualNonzeroExitIsFailureNotSuccessAndDoesNotReplay() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            ProcessTool tool=new ProcessTool(new Workspace(root.toString()),jobs);tool.bindTask("task",1,4);
            try {
                String id=start(tool,jobs,"fail",true);WaitResult result=tool.await(id,20_000,null);
                assertEquals(Status.FAILED,result.job.status);assertEquals(Integer.valueOf(7),result.job.exitCode);
                assertTrue("failure receipt may resume repair; it is not success",result.canResumeTask);
                assertEquals(4,result.job.attempt);assertEquals(1,jobs.list().size());
                assertEquals(Status.FAILED,tool.await(id,0,null).job.status);
            } finally {tool.shutdown();}
        }
    }

    @Test public void cancelWaitWakesWithoutStoppingServiceOrAddingSubmissions() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            ProcessTool tool=new ProcessTool(new Workspace(root.toString()),jobs);tool.bindTask("task",1);
            try {
                String id=start(tool,jobs,"sleep",false);RuntimeJobCancellation cancellation=new RuntimeJobCancellation();
                AtomicReference<WaitResult> result=new AtomicReference<WaitResult>();AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
                Thread waiter=new Thread(()->{try{result.set(tool.await(id,60_000,cancellation));}catch(Throwable e){failure.set(e);}});
                waiter.start();cancellation.cancel();waiter.join(2000);assertFalse(waiter.isAlive());assertNull(failure.get());
                assertEquals(WaitReason.WAIT_CANCELLED,result.get().reason);assertEquals(Status.RUNNING,jobs.get(id).status);
                assertFalse(jobs.get(id).cancelRequested);assertEquals(1,jobs.list().size());
            } finally {tool.shutdown();}
        }
    }

    @Test public void concurrentWaitNeverHoldsProcessLockAgainstStop() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            CountDownLatch waiting=new CountDownLatch(1);ProcessTool tool=new ProcessTool(new Workspace(root.toString()));
            tool.bind(jobs,()->"task",()->1L,(job,active)->{if(active)waiting.countDown();});
            try {
                String id=start(tool,jobs,"sleep",true);AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
                AtomicReference<ToolResult> result=new AtomicReference<ToolResult>();
                Thread waiter=new Thread(()->{try{JsonObject a=action("wait",id);a.addProperty("timeoutMillis","60000");result.set(tool.execute(a));}catch(Throwable e){failure.set(e);}});
                waiter.start();assertTrue(waiting.await(2,TimeUnit.SECONDS));
                assertTrue(tool.execute(action("status",id)).ok);assertTrue(tool.execute(action("stop",id)).ok);
                waiter.join(2000);assertFalse(waiter.isAlive());assertNull(failure.get());assertTrue(result.get().output.contains("FENCED"));
                awaitObservation(jobs,id);assertEquals(Status.CANCELLED,jobs.get(id).status);assertTrue(jobs.get(id).lateResult);
            } finally {tool.shutdown();}
        }
    }

    @Test public void frontendStopIsAsynchronousAndLeavesBackgroundServicesAlive() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            ProcessTool tool=new ProcessTool(new Workspace(root.toString()),jobs);tool.bindTask("task",1);
            try {
                String service=start(tool,jobs,"sleep",false),oneShot=start(tool,jobs,"sleep",true);
                long at=System.nanoTime();CompletableFuture<Integer> cancelled=tool.cancelForeground("task",1,"explicit user stop");
                assertTrue("UI caller must not wait for OS killtree",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)<1000);
                assertEquals(Integer.valueOf(1),cancelled.get(15,TimeUnit.SECONDS));
                assertEquals(Status.CANCELLED,jobs.get(oneShot).status);assertEquals("explicit user stop",jobs.get(oneShot).cancelReason);
                assertEquals(Status.RUNNING,jobs.get(service).status);assertFalse(jobs.get(service).cancelRequested);
                awaitObservation(jobs,oneShot);assertEquals(2,jobs.list().size());
            } finally {tool.shutdown();}
        }
    }

    @Test public void supplierRevisionAndExternalFenceAreCapturedAtSubmissionNotExit() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            AtomicLong revision=new AtomicLong(1);ProcessTool tool=new ProcessTool(new Workspace(root.toString()));
            tool.bind(jobs,()->"task",revision::get,null);
            try {
                String old=start(tool,jobs,"sleep",true);revision.set(2);jobs.bindTask("task",2);
                String newer=start(tool,jobs,"success",true);
                assertEquals(2,tool.await(newer,20_000,null).job.revision);
                assertEquals(WaitReason.FENCED,tool.await(old,0,null).reason);assertEquals(1,jobs.get(old).revision);
                assertEquals(Integer.valueOf(1),tool.cancelForeground("task",1,"stop old foreground").get(15,TimeUnit.SECONDS));
                awaitObservation(jobs,old);assertEquals(Status.UNKNOWN,jobs.get(old).status);assertTrue(jobs.get(old).lateResult);
                assertEquals(Status.COMPLETED,jobs.get(newer).status);assertEquals(1,jobs.foregroundJobs("task",2).size());
            } finally {tool.shutdown();}
        }
    }

    @Test public void reopenedProcessIsUnknownAndStopNeverTouchesSavedPidOrRelaunches() throws Exception {
        Path root=root(),path=root.resolve("jobs.json");String id;Path output=root.resolve("partial.log");Files.write(output,"partial".getBytes(StandardCharsets.UTF_8));
        try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(path)) {
            Handle h=jobs.register("task",1,1,"process","old command must NOT run",output.toString(),true);id=h.jobId;
            jobs.publish(h,Status.RUNNING,"local-pid:1",null,null,"old identity is evidence only");
        }
        ProcessTool tool=new ProcessTool(new Workspace(root.toString()),path);
        try {
            assertEquals(Status.UNKNOWN,tool.runtimeJobs().get(id).status);
            assertEquals(WaitReason.FENCED,tool.await(id,0,null).reason);
            ToolResult stopped=tool.execute(action("stop",id));assertFalse(stopped.ok);assertTrue(stopped.noSideEffect);
            assertTrue(tool.execute(action("output",id)).output.contains("partial"));assertEquals(1,tool.runtimeJobs().list().size());
            assertEquals(Integer.valueOf(0),tool.cancelForeground("task",1,"cannot own old PID").get(2,TimeUnit.SECONDS));
        } finally {tool.shutdown();}
    }

    @Test public void giantSingleLineIsBoundedAndStillDrainedToActualExit() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            ProcessTool tool=new ProcessTool(new Workspace(root.toString()),jobs);
            try {
                String id=start(tool,jobs,"large",true);JobRecord r=tool.await(id,20_000,null).job;
                assertEquals(Status.COMPLETED,r.status);
                String all=new String(Files.readAllBytes(Paths.get(r.resultPath)),StandardCharsets.UTF_8);
                assertEquals(120_000,all.length());JsonObject out=action("output",id);out.addProperty("offset",119_990);out.addProperty("limit",1000);
                assertTrue(tool.execute(out).output.contains("119990-120000 / 120000"));
            } finally {tool.shutdown();}
        }
    }

    @Test public void shutdownNeverRestartsAndRejectsNewStarts() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            ProcessTool tool=new ProcessTool(new Workspace(root.toString()),jobs);tool.bindTask("task",1);
            String id=start(tool,jobs,"sleep",true);tool.shutdown();tool.shutdown();
            assertEquals(Status.CANCELLED,jobs.get(id).status);assertTrue(jobs.get(id).cancelRequested);
            try{start(tool,jobs,"success",true);fail("closed window must never relaunch");}catch(IOException expected){}
            assertEquals(1,jobs.list().size());
        }
    }

    @Test public void cancelledTaskCannotLaunchAgainAndInvalidBooleanHasNoSideEffect() throws Exception {
        Path root=root();try(RuntimeJobRegistry jobs=new RuntimeJobRegistry(root.resolve("jobs.json"))) {
            ProcessTool tool=new ProcessTool(new Workspace(root.toString()),jobs);tool.bindTask("task",1);jobs.cancelTask("task",1,"user stop");
            try {
                try{start(tool,jobs,"success",true);fail();}catch(IOException expected){}
                assertTrue(jobs.list().isEmpty());
                JsonObject invalid=action("start",null);invalid.addProperty("command",command("success"));invalid.addProperty("waitForCompletion","maybe");
                try{tool.execute(invalid);fail();}catch(IllegalArgumentException expected){}
                assertTrue(jobs.list().isEmpty());
            } finally {tool.shutdown();}
        }
    }

    public static final class Worker {
        public static void main(String[] args) throws Exception {
            if("sleep".equals(args[0])){System.out.println("worker-started");Thread.sleep(60_000);}
            else if("fail".equals(args[0])){System.out.println("worker-failed");System.exit(7);}
            else if("large".equals(args[0])){char[] data=new char[220_000];java.util.Arrays.fill(data,'x');System.out.print(new String(data));}
            else System.out.println("worker-ok");
        }
    }
}
