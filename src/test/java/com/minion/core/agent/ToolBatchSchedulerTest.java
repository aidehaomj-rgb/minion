package com.minion.core.agent;

import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ToolBatchSchedulerTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static ToolCall call(String id,String name) { return ToolSchedulingPolicyTest.call(id,name,"{}"); }
    private static void await(List<Future<ToolResult>> futures) throws Exception {
        for(Future<ToolResult> future:futures) assertTrue(future.get(5,TimeUnit.SECONDS).output,future.get().ok);
    }
    @Test public void wavesAreContiguousAndNeverReorderAcrossSideEffects() {
        List<ToolCall> calls=Arrays.asList(call("r1","Read"),call("r2","Read"),call("w1","Write"),
                call("w2","Edit"),call("b","BrowserScreenshot"),call("r3","Read"),call("r4","Read"));
        List<List<ToolCall>> stages=new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(4)).plan(calls);
        assertEquals(5,stages.size()); assertEquals(2,stages.get(0).size());
        assertEquals("w1",stages.get(1).get(0).id);assertEquals("w2",stages.get(2).get(0).id);
        assertEquals("b",stages.get(3).get(0).id);assertEquals(2,stages.get(4).size());
        for(List<ToolCall> stage:new ToolBatchScheduler(null).plan(calls)) assertEquals(1,stage.size());
    }
    @Test public void independentReadsRunTogetherAndWritesWaitForEntireWave() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        CountDownLatch readers=new CountDownLatch(2),release=new CountDownLatch(1),write=new CountDownLatch(1);
        List<String> order=Collections.synchronizedList(new ArrayList<String>());
        List<ToolCall> calls=Arrays.asList(call("1","Read"),call("2","Read"),call("3","Write"),call("4","Edit"));
        try {
            List<Future<ToolResult>> futures=new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(2)).submit(calls,pool,c->{
                if("Read".equals(c.name)){readers.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}
                else {assertEquals(0,readers.getCount());order.add(c.id);write.countDown();}
                return ToolResult.success(c.id);
            });
            assertTrue(readers.await(3,TimeUnit.SECONDS));assertFalse(write.await(80,TimeUnit.MILLISECONDS));
            release.countDown();await(futures);
            assertEquals(Arrays.asList("3","4"),order);
            for(int i=0;i<4;i++)assertEquals(calls.get(i).id,futures.get(i).get().output);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void configuredWaveWidthCapsEvenWideExecutor() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8);
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        List<ToolCall> calls=new ArrayList<ToolCall>();for(int i=0;i<12;i++)calls.add(call(""+i,"Read"));
        try {
            await(new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(2)).submit(calls,pool,c->{
                int current=active.incrementAndGet();peak.updateAndGet(p->Math.max(p,current));
                try {Thread.sleep(15);return ToolResult.success(c.id);}finally{active.decrementAndGet();}
            }));
            assertTrue(peak.get()<=2);
        } finally {pool.shutdownNow();}
    }
    @Test public void singleWorkerCannotDeadlockWaitingForQueuedPredecessor() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {await(new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(4)).submit(Arrays.asList(
                call("1","Read"),call("2","Read"),call("3","Write"),call("4","Read")),pool,c->ToolResult.success(c.id)));}
        finally {pool.shutdownNow();}
    }
    @Test public void thirtyEightWriteChunksAndFinalReadKeepActualFileOrder() throws Exception {
        Path file=temp.newFile("chunks.txt").toPath();List<ToolCall> calls=new ArrayList<ToolCall>();
        StringBuilder expected=new StringBuilder();
        for(int i=0;i<38;i++){calls.add(call("chunk-"+i,"Write"));expected.append("chunk-").append(i).append('\n');}
        calls.add(call("verify","Read"));ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<ToolResult>> futures=new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(4)).submit(calls,pool,c->{
                if("Read".equals(c.name))return ToolResult.success(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
                Files.write(file,(c.id+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
                return ToolResult.success(c.id);
            });
            await(futures);assertEquals(expected.toString(),futures.get(38).get().output);
            assertEquals(expected.toString(),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        }finally{pool.shutdownNow();}
    }
    @Test public void failedThrownOrUnknownResultSkipsDependentMutationWithoutReplay() throws Exception {
        for(int mode=0;mode<3;mode++) {
            final int outcome=mode;AtomicInteger writes=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                List<Future<ToolResult>> futures=new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(2)).submit(
                        Arrays.asList(call("r","Read"),call("w","Write")),pool,c->{
                    if("Write".equals(c.name)){writes.incrementAndGet();return ToolResult.success("unexpected");}
                    if(outcome==0)return ToolResult.error("failed");if(outcome==1)return null;throw new IllegalStateException("unknown");
                });
                assertFalse(futures.get(0).get(3,TimeUnit.SECONDS).ok);
                ToolResult skipped=futures.get(1).get(3,TimeUnit.SECONDS);assertFalse(skipped.ok);assertTrue(skipped.noSideEffect);
                assertEquals(0,writes.get());
            }finally{pool.shutdownNow();}
        }
    }
    @Test public void cancellingUnstartedWriteInterruptsReadsAndPreventsLaterSideEffects() throws Exception {
        AtomicReference<List<Future<ToolResult>>> published=new AtomicReference<List<Future<ToolResult>>>();
        AtomicBoolean interruptObserved=new AtomicBoolean(),cancelledBeforeInterrupt=new AtomicBoolean();
        ExecutorService pool=Executors.newFixedThreadPool(2,r->new Thread(r,"scheduler-cancel-test"){
            @Override public void interrupt(){
                List<Future<ToolResult>> results=published.get();
                if(results!=null&&interruptObserved.compareAndSet(false,true))
                    cancelledBeforeInterrupt.set(results.stream().allMatch(Future::isCancelled));
                super.interrupt();
            }
        });
        CountDownLatch started=new CountDownLatch(1),stopped=new CountDownLatch(1);
        AtomicInteger writes=new AtomicInteger();
        try {
            List<Future<ToolResult>> futures=new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(2)).submit(
                    Arrays.asList(call("r","Read"),call("w","Write")),pool,c->{
                if("Write".equals(c.name)){writes.incrementAndGet();return ToolResult.success("bad");}
                started.countDown();try{new CountDownLatch(1).await();}finally{stopped.countDown();}return ToolResult.success("r");
            });
            published.set(futures);
            assertTrue(started.await(3,TimeUnit.SECONDS));assertTrue(futures.get(1).cancel(true));
            assertTrue("worker must be interrupted",interruptObserved.get());
            assertTrue("all pending results must be cancelled BEFORE worker interruption",cancelledBeforeInterrupt.get());
            assertTrue(stopped.await(3,TimeUnit.SECONDS));assertTrue(futures.get(0).isCancelled());assertTrue(futures.get(1).isCancelled());assertEquals(0,writes.get());
        }finally{pool.shutdownNow();}
    }
    @Test public void cancellationStopsQueuedPeersAndLateSuccessfulReadCannotReviveWrite() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        CountDownLatch started=new CountDownLatch(1),interrupted=new CountDownLatch(1),returnAfterCancel=new CountDownLatch(1);
        AtomicInteger queuedReads=new AtomicInteger(),writes=new AtomicInteger();
        try {
            List<Future<ToolResult>> futures=new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(2)).submit(
                    Arrays.asList(call("running","Read"),call("queued","Read"),call("w","Write")),pool,c->{
                if("Write".equals(c.name)){writes.incrementAndGet();return ToolResult.success("unexpected write");}
                if("queued".equals(c.id)){queuedReads.incrementAndGet();return ToolResult.success("unexpected queued read");}
                started.countDown();
                try{new CountDownLatch(1).await();}catch(InterruptedException expected){interrupted.countDown();}
                if(!returnAfterCancel.await(3,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");
                return ToolResult.success("late read success");
            });
            assertTrue(started.await(3,TimeUnit.SECONDS));assertTrue(futures.get(2).cancel(true));
            assertTrue(interrupted.await(3,TimeUnit.SECONDS));
            for(Future<ToolResult> future:futures)assertTrue(future.isCancelled());
            returnAfterCancel.countDown();pool.shutdown();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));
            for(Future<ToolResult> future:futures){
                assertTrue(future.isCancelled());try{future.get();fail("cancelled results cannot become success");}catch(CancellationException expected){}
            }
            assertEquals(0,queuedReads.get());assertEquals(0,writes.get());
        }finally{returnAfterCancel.countDown();pool.shutdownNow();}
    }
    @Test public void cancellingAnAlreadyCompletedReadDoesNotCancelFollowingWrite() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            List<Future<ToolResult>> futures=new ToolBatchScheduler(null).submit(
                    Arrays.asList(call("r","Read"),call("w","Write")),pool,c->{
                if("Write".equals(c.name)){writing.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}
                return ToolResult.success(c.id);
            });
            assertTrue(writing.await(3,TimeUnit.SECONDS));assertTrue(futures.get(0).get(3,TimeUnit.SECONDS).ok);
            assertFalse(futures.get(0).cancel(true));assertFalse(futures.get(1).isCancelled());
            release.countDown();assertTrue(futures.get(1).get(3,TimeUnit.SECONDS).ok);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void rejectedExecutorProducesNonSideEffectResultsForEveryCall() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();pool.shutdown();
        List<Future<ToolResult>> futures=new ToolBatchScheduler(null).submit(
                Arrays.asList(call("1","Read"),call("2","Write")),pool,c->{throw new AssertionError("must not run");});
        for(Future<ToolResult> future:futures){ToolResult result=future.get(3,TimeUnit.SECONDS);assertFalse(result.ok);assertTrue(result.noSideEffect);}
    }
    @Test public void malformedArgumentsShellAndConfirmationAreAlwaysBarriers() {
        ToolCall malformed=call("1","Read");malformed.arguments="{";
        List<ToolCall> calls=Arrays.asList(malformed,call("2","Bash"),call("3","AskUserQuestion"),call("4","UnknownPlugin"));
        for(List<ToolCall> stage:new ToolBatchScheduler(ToolSchedulingPolicyTest.parallel(4)).plan(calls))assertEquals(1,stage.size());
    }
}
