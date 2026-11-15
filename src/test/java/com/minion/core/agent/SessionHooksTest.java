package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SessionHooksTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path root(){return temp.getRoot().toPath();}
    private SessionHooks configure(String event,String mode,int timeout)throws Exception {
        JsonObject rule=new JsonObject();rule.addProperty("id","test");rule.addProperty("event",event);rule.addProperty("tool","Write");rule.addProperty("timeoutMs",timeout);
        JsonArray command=new JsonArray();command.add(Paths.get(System.getProperty("java.home"),"bin","java").toString());
        command.add("-cp");command.add(Paths.get("target/test-classes").toAbsolutePath().toString());command.add(Runner.class.getName());command.add(mode);rule.add("command",command);
        JsonObject config=new JsonObject();JsonArray rules=new JsonArray();rules.add(rule);config.add("hooks",rules);
        Files.createDirectories(root().resolve(".minion"));Files.write(root().resolve(".minion/hooks.json"),config.toString().getBytes(StandardCharsets.UTF_8));return new SessionHooks(root());
    }
    private void approve(SessionHooks hooks)throws Exception {hooks.approve(JsonParser.parseString(hooks.configurationPreview()).getAsJsonObject().get("hash").getAsString());}
    @Test public void disabledFastPathDoesNotNeedConfig()throws Exception {
        SessionHooks hooks=new SessionHooks(root());assertFalse(hooks.matches("before_tool","Write",new JsonObject()));
        assertTrue(ToolInvocationPipeline.passed(hooks.run("before_tool","Write",new JsonObject(),null)));
    }
    @Test public void requiresExactPreviewHashAndExplicitApproval()throws Exception {
        SessionHooks h=configure("before_tool","ok",5000);assertFalse(h.status().get("enabled").getAsBoolean());
        try{h.approve("bad");fail();}catch(java.io.IOException expected){}
        approve(h);assertTrue(h.matches("before_tool","Write",new JsonObject()));assertFalse(h.matches("before_tool","Read",new JsonObject()));
        assertTrue(ToolInvocationPipeline.passed(h.run("before_tool","Write",new JsonObject(),null)));
        h.disable();assertFalse(h.status().get("enabled").getAsBoolean());
    }
    @Test public void changedConfigurationCannotExecuteUnderOldApproval()throws Exception {
        SessionHooks h=configure("before_tool","ok",5000);approve(h);
        Files.write(root().resolve(".minion/hooks.json")," ".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
        assertFalse(ToolInvocationPipeline.passed(h.run("before_tool","Write",new JsonObject(),null)));
    }
    @Test public void timeoutIsBoundedAndNeverPassed()throws Exception {
        SessionHooks h=configure("before_tool","sleep",200);approve(h);long start=System.nanoTime();
        assertFalse(ToolInvocationPipeline.passed(h.run("before_tool","Write",new JsonObject(),null)));
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<10);
    }
    @Test public void beforeFailureDoesNotInvokeToolOrClaimNoSideEffects()throws Exception {
        SessionHooks h=configure("before_tool","fail",5000);approve(h);AtomicInteger calls=new AtomicInteger();
        ToolResult r=ToolInvocationPipeline.run("Write",new JsonObject(),h,null,()->true,()->{calls.incrementAndGet();return ToolResult.success("done");});
        assertFalse(r.ok);assertFalse(r.noSideEffect);assertEquals(0,calls.get());
    }
    @Test public void postFailureRetainsActualOperationAndImages()throws Exception {
        SessionHooks h=configure("after_tool","fail",5000);approve(h);AtomicInteger calls=new AtomicInteger();
        ToolResult r=ToolInvocationPipeline.run("Write",new JsonObject(),h,null,()->true,()->{calls.incrementAndGet();return ToolResult.success("created-file");});
        assertFalse(r.ok);assertFalse(r.noSideEffect);assertEquals(1,calls.get());assertTrue(r.output.contains("created-file"));assertTrue(r.output.contains("禁止"));
    }
    @Test public void largeArgumentsAreBoundedWithoutBlockingValidTool()throws Exception {
        SessionHooks h=configure("before_tool","ok",5000);approve(h);JsonObject args=new JsonObject();args.addProperty("content",new String(new char[100000]).replace('\0','x'));
        assertTrue(ToolInvocationPipeline.passed(h.run("before_tool","Write",args,null)));
    }
    @Test public void cancellationFencesCommonPipeline()throws Exception {
        AtomicInteger calls=new AtomicInteger();ToolResult r=ToolInvocationPipeline.run("Read",new JsonObject(),null,null,()->false,()->{calls.incrementAndGet();return ToolResult.success("read");});
        assertFalse(r.ok);assertTrue(r.noSideEffect);assertEquals(0,calls.get());
    }
    @Test public void workspaceChangeInvalidatesOldApproval()throws Exception {
        configure("before_tool","ok",5000);Workspace workspace=new Workspace(root().toString());SessionHooks h=new SessionHooks(workspace);approve(h);
        workspace.setWorkDir(temp.newFolder("new-project").getAbsolutePath());
        assertFalse(h.status().get("enabled").getAsBoolean());assertFalse(h.matches("before_tool","Write",new JsonObject()));
        try{h.configurationPreview();fail();}catch(java.io.IOException expected){}
    }
    @Test public void cancellationAfterBodyCannotStartPostHook()throws Exception {
        SessionHooks h=configure("after_tool","fail",5000);approve(h);java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
        ToolResult r=ToolInvocationPipeline.run("Write",new JsonObject(),h,null,current::get,()->{current.set(false);return ToolResult.success("already executed");});
        assertTrue(r.ok);assertFalse(h.status().has("lastRun"));
    }
    @Test public void cancellationWhileWaitingForSharedGateNeverStartsProcess()throws Exception {
        SessionHooks h=configure("before_complete","ok",5000);approve(h);
        java.util.concurrent.locks.Lock gate=ToolExecutionGuard.exclusiveWorkspaceLock();gate.lock();
        java.util.concurrent.atomic.AtomicReference<JsonObject> result=new java.util.concurrent.atomic.AtomicReference<JsonObject>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker=new Thread(()->{try{result.set(h.run("before_complete","Write",new JsonObject(),null));}catch(Throwable t){error.set(t);}});
        try{
            worker.start();long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(worker.getState()!=Thread.State.WAITING&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals(Thread.State.WAITING,worker.getState());h.cancelRunning();
        }finally{gate.unlock();}
        worker.join(5000);assertFalse(worker.isAlive());assertNull(error.get());assertNotNull(result.get());
        assertFalse(ToolInvocationPipeline.passed(result.get()));assertFalse(h.status().getAsJsonObject("lastRun").has("output"));
    }
    @Test public void scheduledReadCannotExecuteApprovedCommands()throws Exception {
        SessionHooks h=configure("before_tool","fail",5000);
        Path file=root().resolve(".minion/hooks.json");JsonObject config=JsonParser.parseString(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getAsJsonObject();
        config.getAsJsonArray("hooks").get(0).getAsJsonObject().addProperty("tool","Read");Files.write(file,config.toString().getBytes(StandardCharsets.UTF_8));
        approve(h);assertTrue(h.matches("before_tool","Read",new JsonObject()));ToolExecutionGuard guard=new ToolExecutionGuard(new Workspace(root().toString()),false);
        guard.lifecycle(h,null,()->true);guard.scheduledReadOnly(java.util.Collections.singleton("Read"));
        Tool read=new Tool(){public String name(){return "Read";}public String description(){return "fixture";}public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject a){return ToolResult.success("read-only");}};
        assertTrue(guard.execute(read,new JsonObject()).ok);assertFalse(h.status().has("lastRun"));
    }
    @Test public void guardUsesSamePostFailureContractForRootAndChild()throws Exception {
        for(boolean child:new boolean[]{false,true}){
            SessionHooks h=configure("after_tool","fail",5000);approve(h);
            ToolExecutionGuard guard=new ToolExecutionGuard(new Workspace(root().toString()),child);guard.lifecycle(h,null,()->true);
            AtomicInteger calls=new AtomicInteger();Tool tool=new Tool(){
                public String name(){return "Write";}public String description(){return "fixture";}
                public JsonObject schema(){return new JsonObject();}
                public ToolResult execute(JsonObject args){calls.incrementAndGet();return ToolResult.success("side-effect-confirmed");}
            };
            ToolResult r=guard.execute(tool,new JsonObject());assertFalse(r.ok);assertFalse(r.noSideEffect);
            assertTrue(r.output.contains("side-effect-confirmed"));assertEquals(1,calls.get());
        }
    }
    @Test public void interruptedPipelineDoesNotExecute()throws Exception {
        Thread.currentThread().interrupt();try{
            ToolInvocationPipeline.run("Read",new JsonObject(),null,null,()->true,()->{fail();return null;});fail();
        }catch(InterruptedException expected){}finally{Thread.interrupted();}
    }
    public static class Runner {
        public static void main(String[] args)throws Exception {
            if("sleep".equals(args[0])){Thread.sleep(30000);return;}
            while(System.in.read()!=-1){}System.out.println("hook-result");
            if("fail".equals(args[0]))System.exit(2);
        }
    }
}
