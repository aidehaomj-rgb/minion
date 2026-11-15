package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import com.minion.core.tools.dev.ProcessTool;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real Java child, scripted model. Not an intranet provider/MaxCompute certification. */
public class ZcodeRuntimeIntegrationTest {
    @Test public void cancelledLateToolCannotBecomeTheNewTasksEvidence() throws Exception {
        CountDownLatch oldStarted=new CountDownLatch(1),releaseOld=new CountDownLatch(1),newUser=new CountDownLatch(1),oldReturned=new CountDownLatch(1);
        ToolRegistry registry=new ToolRegistry();
        registry.register(new Tool(){public String name(){return "SlowOld";}public String description(){return "old";}
            public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject args){
                oldStarted.countDown();boolean released=false;while(!released)try{releaseOld.await();released=true;}catch(InterruptedException ignored){}
                oldReturned.countDown();return ToolResult.success("old result must stay with A");}});
        registry.register(new Tool(){public String name(){return "NewStep";}public String description(){return "new";}
            public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject args){return ToolResult.success("B actual step");}});
        FakeLlmClient llm=new FakeLlmClient();llm.addTurnWithTools(Collections.singletonList(named("old-call","SlowOld")),"");
        llm.addTurnWithTools(Collections.singletonList(named("new-call","NewStep")),"");llm.addTurn("任务B执行完毕。\n[[MINION_TASK_COMPLETE]]");llm.stopKind="task";
        Session s=Session.create(temp.getRoot().getPath(),"fake");AgentLoop loop=loop(llm,registry,s,new AgentUi(){
            public void onUserMessage(String text){if(text.contains("任务B"))newUser.countDown();}});
        ExecutorService turns=Executors.newSingleThreadExecutor();
        try {
            Future<?> a=turns.submit(()->loop.runUserTurn("执行任务A"));assertTrue(oldStarted.await(5,TimeUnit.SECONDS));loop.interrupt();a.get(5,TimeUnit.SECONDS);
            String oldTask=s.taskState.id;Future<?> b=turns.submit(()->loop.runUserTurn("新任务，执行任务B"));assertTrue(newUser.await(5,TimeUnit.SECONDS));
            assertNotEquals(oldTask,s.taskState.id);releaseOld.countDown();assertTrue(oldReturned.await(5,TimeUnit.SECONDS));b.get(10,TimeUnit.SECONDS);
            assertFalse(s.taskState.evidence.stream().anyMatch(e->"SlowOld".equals(e.tool)&&s.taskState.id.equals(e.taskId)));
            assertTrue(s.taskState.evidence.stream().anyMatch(e->"NewStep".equals(e.tool)&&s.taskState.id.equals(e.taskId)));
        } finally {releaseOld.countDown();loop.shutdown();turns.shutdownNow();}
    }
    private ToolCall named(String id,String name){ToolCall t=new ToolCall();t.id=id;t.name=name;t.type="function";t.arguments="{}";return t;}
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    public static class Child {
        public static void main(String[] args) throws Exception {
            Thread.sleep(500);Files.write(Paths.get("done.txt"),"done".getBytes("UTF-8"));
        }
    }
    private AgentLoop loop(FakeLlmClient llm,ToolRegistry registry,Session session,AgentUi ui) {
        return new AgentLoop(llm,registry,new SystemPromptBuilder(null,temp.getRoot().getPath()),
                new ConfirmGate(Config.load(temp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                ui,null,new Workspace(temp.getRoot().getPath()),session);
    }
    @Test public void oneShotWaitDoesNotPollModelAndStablePrefixExcludesTask() throws Exception {
        Workspace w=new Workspace(temp.getRoot().getPath());ProcessTool process=new ProcessTool(w);ToolRegistry registry=new ToolRegistry();registry.register(process);
        String java=Paths.get(System.getProperty("java.home"),"bin","java.exe").toString();
        String classes=Paths.get(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String command="\""+java+"\" -cp \""+classes+"\" "+Child.class.getName();
        JsonObject args=new JsonObject();args.addProperty("action","start");args.addProperty("command",command);args.addProperty("waitForCompletion",true);
        ToolCall call=new ToolCall();call.id="start-once";call.name="Process";call.type="function";call.arguments=args.toString();
        AtomicBoolean waited=new AtomicBoolean();FakeLlmClient llm=new FakeLlmClient();
        llm.addTurnWithTools(Collections.singletonList(call), "");llm.addTurn("命令已执行结束，结果已核对。\n[[MINION_TASK_COMPLETE]]");llm.stopKind="task";
        Session session=Session.create(w.workDir(),"scripted");AgentLoop loop=loop(llm,registry,session,new AgentUi(){
            @Override public void onExternalWaitChanged(boolean wait,String id){if(wait)waited.set(true);}
        });
        try {
            loop.runUserTurn("运行一次指定命令，等待结束后给我结果");
            assertTrue(Files.isRegularFile(temp.getRoot().toPath().resolve("done.txt")));
            assertTrue(waited.get());assertEquals("only two model turns",2,llm.requests.size());
            assertEquals(1,process.runtimeJobs().list().size());assertTrue(loop.lastTurnSucceeded());
            assertFalse(llm.requests.get(0).messages.get(0).content.contains("运行一次指定命令"));
            assertTrue(llm.requests.get(1).messages.stream().anyMatch(m->m.origin==Message.Origin.TOOL_EVIDENCE&&m.content.contains("系统外部作业结果")));
            assertTrue(loop.phaseTimings().getAsJsonObject("EXTERNAL_WAIT").get("observations").getAsInt()>0);
            String oldSession=session.id;
            loop.startNewSession();
            assertNotEquals(oldSession,session.id);assertNotSame(process,registry.get("Process"));
        } finally {process.shutdown();loop.shutdown();}
    }
    @Test public void tokenBoundaryPausesWithoutClaimingCompletion() {
        FakeLlmClient llm=new FakeLlmClient();llm.addTurnWithTools(Collections.singletonList(readCall()), "");
        ToolRegistry registry=new ToolRegistry();registry.register(new Tool(){
            public String name(){return "Read";}public String description(){return "test";}
            public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject args){return ToolResult.success("read");}
        });
        Session session=Session.create(temp.getRoot().getPath(),"scripted");AgentLoop loop=loop(llm,registry,session,new AgentUi(){});loop.configureTaskBudget(96,1);
        try {loop.runUserTurn("读取资料后继续分析");assertFalse(loop.lastTurnSucceeded());assertEquals("task_budget",session.taskState.pauseCode);
            assertEquals(1,llm.requests.size());assertFalse(session.taskState.canAutomaticallyContinue());}
        finally {loop.shutdown();}
    }
    @Test public void unlimitedDesktopBudgetResumesLegacyTokenPausedTask() {
        FakeLlmClient llm=new FakeLlmClient();llm.addTurnWithTools(Collections.singletonList(readCall()),"");llm.addTurn("资料读取完成");
        ToolRegistry registry=new ToolRegistry();registry.register(new Tool(){
            public String name(){return "Read";}public String description(){return "test";}
            public com.google.gson.JsonObject schema(){return new com.google.gson.JsonObject();}
            public ToolResult execute(com.google.gson.JsonObject args){return ToolResult.success("read");}
        });
        Session session=Session.create(temp.getRoot().getPath(),"scripted");
        session.taskState.begin("读取资料后继续分析",false);
        session.taskState.continuationBudget.configure(96,1);
        session.taskState.continuationBudget.usage(200000000L);
        session.taskState.pauseCode="task_budget";session.taskState.status="paused";
        AgentLoop loop=loop(llm,registry,session,new AgentUi(){});
        try{
            loop.configureTaskBudget(96,0);
            assertNull(session.taskState.continuationBudget.tokenLimit);
            loop.runUserTurn("继续");
            assertNull(session.taskState.continuationBudget.tokenLimit);
            assertEquals(2,llm.requests.size());
            assertNotEquals("task_budget",session.taskState.pauseCode);
        }finally{loop.shutdown();}
    }
    private ToolCall readCall(){ToolCall t=new ToolCall();t.id="read";t.type="function";t.name="Read";t.arguments="{\"path\":\"unused\"}";return t;}
}
