package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;
import static org.junit.Assert.*;

public class ToolExecutionGuardResourceTest {
    @Test public void processWaitDoesNotHoldWorkspaceLock() throws Exception {
        Workspace w=workspace();ToolExecutionGuard waiter=new ToolExecutionGuard(w,false),writer=new ToolExecutionGuard(w,false);
        CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<ToolResult> wait=pool.submit(()->waiter.execute(tool("Process",x->hold(waiting,release)),args("{\"action\":\"wait\",\"id\":\"job\"}")));
            assertTrue(waiting.await(3,TimeUnit.SECONDS));
            Future<ToolResult> write=pool.submit(()->writer.execute(tool("Write",x->ToolResult.success("written")),args("{\"path\":\"independent.txt\"}")));
            assertTrue("external wait must not block an unrelated file write",write.get(2,TimeUnit.SECONDS).ok);
            release.countDown();assertTrue(wait.get(3,TimeUnit.SECONDS).ok);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private interface Body { ToolResult run(JsonObject args) throws Exception; }
    private static Tool tool(final String name,final Body body) {
        return new Tool(){public String name(){return name;}public String description(){return "fixture";}
            public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject args)throws Exception{return body.run(args);}};
    }
    private JsonObject args(String json){return JsonParser.parseString(json).getAsJsonObject();}
    private Workspace workspace()throws Exception{return new Workspace(temp.newFolder().getAbsolutePath());}
    private static ToolResult hold(CountDownLatch started,CountDownLatch release)throws Exception{
        started.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");return ToolResult.success("ok");
    }
    private static void queued(ReentrantLock lock)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!lock.hasQueuedThreads()&&System.nanoTime()<deadline)Thread.sleep(5);
        assertTrue("worker must be waiting on the resource lock",lock.hasQueuedThreads());
    }
    @Test public void independentComplexWritesAcrossProjectsCanOverlap()throws Exception{
        Workspace a=workspace(),b=workspace();ToolExecutionGuard ga=new ToolExecutionGuard(a,false),gb=new ToolExecutionGuard(b,false);
        JsonObject aa=args("{\"action\":\"write\",\"outputPath\":\"a.xlsx\"}");
        JsonObject bb=args("{\"action\":\"docx_report\",\"outputPath\":\"b.docx\"}");
        // Striped locks intentionally allow false collisions; choose non-colliding fixtures.
        for(int i=0;!Collections.disjoint(PathLocks.forPaths(ToolResourceScope.resolve("Excel",aa,a).paths),
                PathLocks.forPaths(ToolResourceScope.resolve("Document",bb,b).paths));i++)bb.addProperty("outputPath","b"+i+".docx");
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            Future<ToolResult> fa=pool.submit(()->ga.execute(tool("Excel",x->hold(first,release)),aa));
            assertTrue(first.await(3,TimeUnit.SECONDS));
            Future<ToolResult> fb=pool.submit(()->gb.execute(tool("Document",x->hold(second,release)),bb));
            assertTrue("independent known outputs must not use global write lock",second.await(3,TimeUnit.SECONDS));
            release.countDown();assertTrue(fa.get(3,TimeUnit.SECONDS).ok);assertTrue(fb.get(3,TimeUnit.SECONDS).ok);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void sameWorkbookWriteAndReadAcrossGuardsStaySerialized()throws Exception{
        Workspace w=workspace();Path file=Paths.get(w.workDir(),"book.xlsx");Files.write(file,new byte[]{1});
        ToolExecutionGuard writer=new ToolExecutionGuard(w,false),reader=new ToolExecutionGuard(w,false);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch started=new CountDownLatch(1),read=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            Future<ToolResult> fw=pool.submit(()->writer.execute(tool("Excel",x->hold(started,release)),args("{\"action\":\"write\",\"outputPath\":\"book.xlsx\"}")));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            Future<ToolResult> fr=pool.submit(()->reader.execute(tool("Read",x->{read.countDown();return ToolResult.success("ok");}),args("{\"path\":\"book.xlsx\"}")));
            assertFalse(read.await(80,TimeUnit.MILLISECONDS));release.countDown();
            assertTrue(fw.get(3,TimeUnit.SECONDS).ok);assertTrue(fr.get(3,TimeUnit.SECONDS).ok);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void directWriteAndEditKeepReentrantSinglePathLock()throws Exception{
        Workspace w=workspace();Path path=Paths.get(w.workDir(),"file.txt");ToolExecutionGuard one=new ToolExecutionGuard(w,false),two=new ToolExecutionGuard(w,false);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch writing=new CountDownLatch(1),editing=new CountDownLatch(1),release=new CountDownLatch(1);
        JsonObject arg=args("{\"path\":\"file.txt\"}");
        try{
            Future<ToolResult> first=pool.submit(()->one.execute(tool("Write",x->{
                ReentrantLock nested=PathLocks.forPath(path);nested.lock();try{return hold(writing,release);}finally{nested.unlock();}
            }),arg));
            assertTrue(writing.await(3,TimeUnit.SECONDS));
            Future<ToolResult> second=pool.submit(()->two.execute(tool("Edit",x->{editing.countDown();return ToolResult.success("ok");}),arg));
            assertFalse(editing.await(80,TimeUnit.MILLISECONDS));release.countDown();
            assertTrue(first.get(3,TimeUnit.SECONDS).ok);assertTrue(second.get(3,TimeUnit.SECONDS).ok);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void mergeOtherInputIsLockedAsWellAsItsOutput()throws Exception{
        Workspace w=workspace();ToolExecutionGuard one=new ToolExecutionGuard(w,false),two=new ToolExecutionGuard(w,false);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch started=new CountDownLatch(1),write=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            Future<ToolResult> merge=pool.submit(()->one.execute(tool("Excel",x->hold(started,release)),args("{\"action\":\"merge\",\"path\":\"a.xlsx\",\"otherPath\":\"b.xlsx\",\"outputPath\":\"result.xlsx\"}")));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            Future<ToolResult> writer=pool.submit(()->two.execute(tool("Write",x->{write.countDown();return ToolResult.success("ok");}),args("{\"path\":\"b.xlsx\"}")));
            assertFalse(write.await(80,TimeUnit.MILLISECONDS));release.countDown();
            assertTrue(merge.get(3,TimeUnit.SECONDS).ok);assertTrue(writer.get(3,TimeUnit.SECONDS).ok);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void unknownShellCannotShrinkScopeByClaimingAnOutputPath()throws Exception{
        Workspace a=workspace(),b=workspace();ToolExecutionGuard ga=new ToolExecutionGuard(a,false),gb=new ToolExecutionGuard(b,false);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch started=new CountDownLatch(1),read=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            Future<ToolResult> shell=pool.submit(()->ga.execute(tool("Bash",x->hold(started,release)),args("{\"command\":\"unknown.exe\",\"outputPath\":\"declared.txt\"}")));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            Future<ToolResult> reader=pool.submit(()->gb.execute(tool("Read",x->{read.countDown();return ToolResult.success("ok");}),args("{\"path\":\"independent.txt\"}")));
            assertFalse(read.await(80,TimeUnit.MILLISECONDS));release.countDown();
            assertTrue(shell.get(3,TimeUnit.SECONDS).ok);assertTrue(reader.get(3,TimeUnit.SECONDS).ok);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void browserInstanceClaimsDoNotBypassSharedBrowserLeaseOrBlockFiles()throws Exception{
        Workspace a=workspace(),b=workspace();ToolExecutionGuard ga=new ToolExecutionGuard(a,false),gb=new ToolExecutionGuard(b,false);
        ExecutorService pool=Executors.newFixedThreadPool(3);CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            Future<ToolResult> browser=pool.submit(()->ga.execute(tool("BrowserAction",x->hold(first,release)),args("{\"instanceId\":\"A\"}")));
            assertTrue(first.await(3,TimeUnit.SECONDS));
            Future<ToolResult> other=pool.submit(()->gb.execute(tool("BrowserScreenshot",x->{second.countDown();return ToolResult.success("ok");}),args("{\"instanceId\":\"B\"}")));
            assertFalse(second.await(80,TimeUnit.MILLISECONDS));
            assertTrue(pool.submit(()->gb.execute(tool("Read",x->ToolResult.success("files free")),args("{\"path\":\"a.txt\"}"))).get(2,TimeUnit.SECONDS).ok);
            release.countDown();assertTrue(browser.get(3,TimeUnit.SECONDS).ok);assertTrue(other.get(3,TimeUnit.SECONDS).ok);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void browserTimeoutHasNoSideEffectsAndReportsWait()throws Exception{
        Workspace w=workspace();ToolExecutionGuard owner=new ToolExecutionGuard(w,false),waiter=new ToolExecutionGuard(w,false);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger executed=new AtomicInteger();
        List<Long> observed=Collections.synchronizedList(new ArrayList<Long>());waiter.waitObserver(observed::add);
        try{
            Future<ToolResult> first=pool.submit(()->owner.execute(tool("BrowserAction",x->hold(started,release)),new JsonObject()));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            ToolResult result=pool.submit(()->waiter.execute(tool("BrowserAction",x->{executed.incrementAndGet();return ToolResult.success("bad");}),new JsonObject())).get(3,TimeUnit.SECONDS);
            assertFalse(result.ok);assertTrue(result.noSideEffect);assertEquals(0,executed.get());assertEquals(1,observed.size());
            assertTrue(observed.get(0)>=1000);assertTrue(waiter.lastLockWaitMillis()>=1000);
            release.countDown();assertTrue(first.get(3,TimeUnit.SECONDS).ok);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void staleReadAndChildMustReadProtectionsArePreserved()throws Exception{
        Workspace w=workspace();Path path=Paths.get(w.workDir(),"file.txt");Files.write(path,"before".getBytes(StandardCharsets.UTF_8));
        ToolExecutionGuard parent=new ToolExecutionGuard(w,false),child=new ToolExecutionGuard(w,true);JsonObject arg=args("{\"path\":\"file.txt\"}");
        AtomicInteger writes=new AtomicInteger();Tool write=tool("Write",x->{writes.incrementAndGet();return ToolResult.success("write");});
        assertFalse(child.execute(write,arg).ok);assertTrue(parent.execute(tool("Read",x->ToolResult.success("before")),arg).ok);
        Files.write(path,"changed by user".getBytes(StandardCharsets.UTF_8));
        assertFalse(parent.execute(write,arg).ok);assertEquals(0,writes.get());
        assertTrue(parent.execute(tool("Read",x->ToolResult.success("updated")),arg).ok);assertTrue(parent.execute(write,arg).ok);
    }
    @Test public void complexResourceLocksNeverSkipExistingMutationAuthorizer()throws Exception{
        Workspace w=workspace();ToolExecutionGuard guard=new ToolExecutionGuard(w,false);AtomicInteger invoked=new AtomicInteger();
        guard.mutationAuthorizer((name,arg)->{throw new IllegalStateException("not authorized");});
        ToolResult result=guard.execute(tool("Excel",x->{invoked.incrementAndGet();return ToolResult.success("bad");}),args("{\"action\":\"write\",\"outputPath\":\"result.xlsx\"}"));
        assertFalse(result.ok);assertTrue(result.noSideEffect);assertEquals(0,invoked.get());
    }
    @Test public void interruptedResourceWaitReleasesEveryAcquiredLockAndReportsOnlyWait()throws Exception{
        Workspace w=workspace();ToolExecutionGuard guard=new ToolExecutionGuard(w,false);
        JsonObject arg=args("{\"action\":\"merge\",\"path\":\"a.xlsx\",\"otherPath\":\"b.xlsx\",\"outputPath\":\"c.xlsx\"}");
        List<ReentrantLock> locks=PathLocks.forPaths(ToolResourceScope.resolve("Excel",arg,w).paths);ReentrantLock held=locks.get(locks.size()-1);
        List<Long> waits=Collections.synchronizedList(new ArrayList<Long>());guard.waitObserver(waits::add);
        ExecutorService pool=Executors.newSingleThreadExecutor();CountDownLatch ended=new CountDownLatch(1);held.lock();
        try{
            Future<?> future=pool.submit(()->{try{guard.execute(tool("Excel",x->{throw new AssertionError("must not execute");}),arg);}catch(InterruptedException expected){}
                catch(Exception error){throw new RuntimeException(error);}finally{ended.countDown();}});
            queued(held);future.cancel(true);assertTrue(ended.await(3,TimeUnit.SECONDS));
        }finally{held.unlock();pool.shutdownNow();}
        assertEquals(1,waits.size());assertEquals(1,guard.lockAttemptCount());
        Lock global=ToolExecutionGuard.exclusiveWorkspaceLock();assertTrue(global.tryLock(1,TimeUnit.SECONDS));global.unlock();
        for(ReentrantLock lock:locks){assertTrue(lock.tryLock(1,TimeUnit.SECONDS));lock.unlock();}
    }
    @Test public void waitObserverRunsBeforeToolAndTelemetryFailureCannotLeakLock()throws Exception{
        Workspace w=workspace();ToolExecutionGuard guard=new ToolExecutionGuard(w,false);AtomicInteger notifications=new AtomicInteger();
        guard.waitObserver(ms->{assertTrue(ms>=0);notifications.incrementAndGet();throw new IllegalStateException("telemetry failed");});
        ToolResult result=guard.execute(tool("Write",x->{assertEquals(1,notifications.get());return ToolResult.success("ok");}),args("{\"path\":\"new.txt\"}"));
        assertTrue(result.ok);assertEquals(1,guard.lockAttemptCount());assertTrue(guard.totalLockWaitMillis()>=0);
        guard.waitObserver(null);assertTrue(guard.execute(tool("Write",x->ToolResult.success("still unlocked")),args("{\"path\":\"new.txt\"}")).ok);
    }
}
