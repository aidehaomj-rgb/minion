package com.minion.core.agent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.tools.TaskTool;
import com.minion.core.tools.ToolResult;
import org.junit.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ChildTaskCoordinatorFTest {
    private ChildTaskCoordinator.Assignment task(String id,String...deps){ChildTaskCoordinator.Assignment a=new ChildTaskCoordinator.Assignment();a.stepId=id;a.description="处理"+id;a.acceptance="产出可核对文件";a.scope=Collections.singletonList("pieces/"+id);a.dependsOn=Arrays.asList(deps);return a;}
    private ChildTaskCoordinator.Outcome done(Path root,String id)throws Exception{Path p=root.resolve(id+".txt");Files.write(p,("evidence-"+id).getBytes(StandardCharsets.UTF_8));return ChildTaskCoordinator.Outcome.returned("done","验收逐项报告",Collections.singletonList(p.toString()));}
    private void waitFor(Callable<Boolean> test)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!test.call()){if(System.nanoTime()>end)fail("等待状态超时");Thread.sleep(5);}}
    private void review(ChildTaskCoordinator c,String id)throws Exception{waitFor(()->"returned_needs_review".equals(c.record(id).status));c.reviewFromParent(id,1,"主Agent已核对文件及验收契约",Collections.singletonList("receipt-"+id));}
    @Test public void reverseRegistered38DependenciesRequireParentReview()throws Exception{
        Path root=Files.createTempDirectory("minion-f-38-");List<String> order=Collections.synchronizedList(new ArrayList<String>());
        try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,4,(a,cancel)->{order.add(a.stepId);return done(root,a.stepId);})){
            List<ChildTaskCoordinator.Assignment> tasks=new ArrayList<ChildTaskCoordinator.Assignment>();for(int i=37;i>=0;i--)tasks.add(i==0?task("s0"):task("s"+i,"s"+(i-1)));c.submitBatch(tasks);
            waitFor(()->"returned_needs_review".equals(c.record("s0").status));Thread.sleep(60);assertEquals("queued",c.record("s1").status);
            for(int i=0;i<38;i++)review(c,"s"+i);assertEquals(38,order.size());assertEquals(38,new HashSet<String>(order).size());for(int i=0;i<38;i++)assertEquals("s"+i,order.get(i));
        }
    }
    @Test public void unorderedReturnsBoundConcurrencyAnd38UniqueOutputs()throws Exception{
        Path root=Files.createTempDirectory("minion-f-parallel-");AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();List<String> order=Collections.synchronizedList(new ArrayList<String>());
        try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,3,(a,cancel)->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{Thread.sleep((Integer.parseInt(a.stepId.substring(1))%3==0)?80:5);order.add(a.stepId);return done(root,a.stepId);}finally{active.decrementAndGet();}})){
            List<ChildTaskCoordinator.Assignment> tasks=new ArrayList<ChildTaskCoordinator.Assignment>();for(int i=0;i<38;i++)tasks.add(task("p"+i));c.submitBatch(tasks);waitFor(()->order.size()==38);assertTrue(peak.get()<=3);assertTrue(peak.get()>1);assertNotEquals("p0",order.get(0));assertEquals(38,new HashSet<String>(order).size());
        }
    }
    @Test public void rejectsDuplicateNestedScopeAndCyclesAtomicallyButAllowsReadOverlap()throws Exception{
        Path root=Files.createTempDirectory("minion-f-scope-");try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,2,(a,cancel)->done(root,a.stepId))){c.setCapacity(0);
            ChildTaskCoordinator.Assignment a=task("a"),b=task("b");a.scope=Collections.singletonList("src");b.scope=Collections.singletonList("SRC/A.java");
            try{c.submitBatch(Arrays.asList(a,b));fail();}catch(IllegalArgumentException expected){}assertTrue(c.snapshot().records.isEmpty());
            a=task("a","b");b=task("b","a");try{c.submitBatch(Arrays.asList(a,b));fail();}catch(IllegalArgumentException expected){}assertTrue(c.snapshot().records.isEmpty());
            a=task("a");b=task("b");a.mode=b.mode="explore";a.scope=b.scope=Collections.singletonList("src");c.submitBatch(Arrays.asList(a,b));assertEquals(2,c.snapshot().records.size());
        }
    }
    @Test public void failureAndChangedEvidenceNeverUnlockDependencies()throws Exception{
        Path root=Files.createTempDirectory("minion-f-failure-");try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,2,(a,cancel)->a.stepId.equals("bad")?ChildTaskCoordinator.Outcome.failed("子Agent失败: 429"):done(root,a.stepId))){
            c.submitBatch(Arrays.asList(task("after","bad"),task("bad"),task("independent")));waitFor(()->"cancelled".equals(c.record("after").status));assertEquals("failed",c.record("bad").status);review(c,"independent");
            c.submitBatch(Arrays.asList(task("proof"),task("dependent","proof")));waitFor(()->"returned_needs_review".equals(c.record("proof").status));Files.write(root.resolve("proof.txt"),new byte[]{5});
            try{c.reviewFromParent("proof",1,"looks good",Collections.singletonList("r"));fail();}catch(java.io.IOException expected){}assertEquals("queued",c.record("dependent").status);
        }
    }
    @Test public void cancelDoesNotReleasePhysicalSlotOrGlobalWriteLeaseUntilRunnerExits()throws Exception{
        Path root=Files.createTempDirectory("minion-f-cancel-");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger secondStarted=new AtomicInteger();
        ChildTaskCoordinator first=new ChildTaskCoordinator(root,"one",1,1,(a,cancel)->{entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return done(root,a.stepId);});
        ChildTaskCoordinator second=new ChildTaskCoordinator(root,"two",1,1,(a,cancel)->{secondStarted.incrementAndGet();return done(root,a.stepId);});
        try{first.submitBatch(Collections.singletonList(task("same")));assertTrue(entered.await(2,TimeUnit.SECONDS));second.submitBatch(Collections.singletonList(task("same")));first.cancel("same","用户停止");Thread.sleep(100);assertEquals(1,first.activeExecutions());assertEquals(0,secondStarted.get());assertFalse(first.awaitQuiescence(20));
            try{first.retryFromParent("same",1,"明确重试",true);fail();}catch(java.io.IOException expected){}
            release.countDown();assertTrue(first.awaitQuiescence(1000));assertEquals("cancelled",first.record("same").status);waitFor(()->secondStarted.get()==1);
        }finally{release.countDown();first.close();second.close();}
    }
    @Test public void timeoutIsBoundedAndLateSuccessCannotReplaceIt()throws Exception{
        Path root=Files.createTempDirectory("minion-f-timeout-");CountDownLatch release=new CountDownLatch(1);try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,1,(a,cancel)->{while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return done(root,a.stepId);})){
            ChildTaskCoordinator.Assignment a=task("slow");a.timeoutMillis=1000;c.submitBatch(Collections.singletonList(a));waitFor(()->"timed_out".equals(c.record("slow").status));assertEquals(1,c.activeExecutions());release.countDown();assertTrue(c.awaitQuiescence(1000));assertEquals("timed_out",c.record("slow").status);
        }finally{release.countDown();}
    }
    @Test public void mergeRequiresJointValidationBeforeDependentStarts()throws Exception{
        Path root=Files.createTempDirectory("minion-f-validate-");try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,2,(a,cancel)->done(root,a.stepId))){ChildTaskCoordinator.Assignment a=task("code");a.requiresMerge=true;c.submitBatch(Arrays.asList(a,task("after","code")));review(c,"code");Thread.sleep(50);assertEquals("queued",c.record("after").status);
            c.markMerged("code",1,Collections.singletonList(root.resolve("code.txt").toString()));Thread.sleep(50);assertEquals("queued",c.record("after").status);c.validateFromParent("code",1,true,"联合测试通过",Collections.singletonList("test-id"));review(c,"after");}
    }
    @Test public void restartSnapshotDoesNotReplayRunningAndQueueNeedsQuiescenceConfirmation()throws Exception{
        Path root=Files.createTempDirectory("minion-f-restore-");ChildTaskCoordinator.Snapshot snapshot;
        try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,1,(a,cancel)->done(root,a.stepId))){c.setCapacity(0);c.submitBatch(Arrays.asList(task("one"),task("two")));snapshot=c.snapshot();snapshot.records.get(0).status="running";}
        AtomicInteger runs=new AtomicInteger();try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,1,(a,cancel)->{runs.incrementAndGet();return done(root,a.stepId);})){c.restore(new Gson().fromJson(new Gson().toJson(snapshot),ChildTaskCoordinator.Snapshot.class));Thread.sleep(80);assertEquals(0,runs.get());assertEquals("interrupted_needs_review",c.record("one").status);c.confirmRecoveryQuiescent("旧进程已退出");review(c,"two");assertEquals(1,runs.get());}
    }
    @Test public void taskToolRejectsLegacyUnwiredAndPreservesDispatcherFailure()throws Exception{
        JsonObject args=new JsonObject();args.addProperty("description","task");args.addProperty("stepId","s");args.addProperty("acceptance","验收");JsonArray scope=new JsonArray();scope.add("src");args.add("scope",scope);args.add("dependsOn",new JsonArray());
        assertFalse(new TaskTool((AgentLoop)null).execute(args).ok);assertFalse(new TaskTool(a->ToolResult.error("子Agent失败")).execute(args).ok);args.addProperty("model","override");assertFalse(new TaskTool(a->ToolResult.success("wrong")).execute(args).ok);
    }
    @Test public void runnerErrorCannotBecomeSuccessfulReturnAndActualWriteScopeIsChecked()throws Exception{
        Path root=Files.createTempDirectory("minion-f-error-");AtomicReference<ChildTaskCoordinator> reference=new AtomicReference<ChildTaskCoordinator>();AtomicBoolean allowed=new AtomicBoolean();
        try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,1,(a,cancel)->{if(a.stepId.equals("boom"))throw new AssertionError("not success");ChildTaskCoordinator coordinator=reference.get();int attempt=coordinator.record(a.stepId).attempt;coordinator.authorizeWrite(a.stepId,attempt,root.resolve("pieces/guard/new.txt"));try{coordinator.authorizeWrite(a.stepId,attempt,root.resolve("other.txt"));throw new AssertionError();}catch(java.io.IOException expected){allowed.set(true);}return done(root,a.stepId);})){reference.set(c);c.submitBatch(Arrays.asList(task("boom"),task("guard")));waitFor(()->"failed".equals(c.record("boom").status));review(c,"guard");assertTrue(allowed.get());assertTrue(c.record("boom").error.contains("AssertionError"));}
    }
    @Test public void journalRestoresDataAndNeverOverwritesUnrelatedExistingFile()throws Exception{
        Path root=Files.createTempDirectory("minion-f-journal-");Path journal=root.resolve(".minion/coordinator.json");try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,1,(a,cancel)->done(root,a.stepId))){c.setCapacity(0);c.journal(journal);c.submitBatch(Collections.singletonList(task("pending")));assertTrue(Files.size(journal)>0);}
        byte[] before=Files.readAllBytes(journal);try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,1,(a,cancel)->done(root,a.stepId))){try{c.journal(journal);fail();}catch(java.io.IOException expected){}assertArrayEquals(before,Files.readAllBytes(journal));c.restoreFromFile(journal);assertEquals("cancelled",c.record("pending").status);}
    }
    @Test public void toolPoolOfOneCanEnqueueWhileIndependentRunnerIsBusy()throws Exception{
        Path root=Files.createTempDirectory("minion-f-toolpool-");CountDownLatch release=new CountDownLatch(1);ExecutorService tools=Executors.newSingleThreadExecutor();
        try(ChildTaskCoordinator c=new ChildTaskCoordinator(root,"parent",1,1,(a,cancel)->{release.await();return done(root,a.stepId);})){TaskTool tool=new TaskTool(c);for(int i=0;i<2;i++){final int n=i;ToolResult r=tools.submit(()->{JsonObject a=new Gson().toJsonTree(task("pool"+n)).getAsJsonObject();return tool.execute(a);}).get(1,TimeUnit.SECONDS);assertTrue(r.output,r.ok);}assertEquals(2,c.snapshot().records.size());}
        finally{release.countDown();tools.shutdownNow();}
    }
}
