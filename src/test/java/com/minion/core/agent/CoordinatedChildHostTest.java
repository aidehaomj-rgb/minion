package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.context.TokenCounter;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import com.minion.core.tools.dev.BuildTool;
import com.minion.core.tools.python.PythonRuntime;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Exercises the registered task -> actual AgentLoop/SubAgentLoop -> scoped tools -> parent receipts path. */
public class CoordinatedChildHostTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private final List<AgentLoop> loops=new ArrayList<AgentLoop>();
    private AgentLoop loop;private Workspace workspace;private ToolRegistry tools;private ConfirmGate confirm;private FakeLlmClient parent;
    private JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private AgentLoop create(Path root)throws Exception{
        workspace=new Workspace(root.toString());tools=new ToolRegistry();tools.register(new ReadTool(workspace));tools.register(new WriteTool(workspace));
        confirm=new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE));parent=new FakeLlmClient();
        AgentLoop result=new AgentLoop(parent,tools,new SystemPromptBuilder(root.resolve("project.md").toString()),confirm,new AgentUi(){},null,workspace,Session.create(root.toString(),"same-model"));
        result.session().taskState.begin("处理多个分块，仅完成当前任务",true);loops.add(result);loop=result;return result;
    }
    @After public void close(){for(AgentLoop l:loops){l.interrupt();l.shutdown();}}
    private JsonObject task(String step,String path,String...deps){
        JsonObject a=new JsonObject();a.addProperty("description","处理 "+step);a.addProperty("stepId",step);a.addProperty("revision",loop.session().taskState.revision());
        a.addProperty("mode","worker");a.addProperty("acceptance","指定范围文件内容为done，并提供实际路径");
        a.add("scope",new Gson().toJsonTree(Collections.singletonList(path)));a.add("dependsOn",new Gson().toJsonTree(Arrays.asList(deps)));return a;
    }
    private ToolCall call(String name,JsonObject args){ToolCall call=new ToolCall();call.id=UUID.randomUUID().toString();call.name=name;call.arguments=args.toString();return call;}
    private ToolResult runRoot(String name,JsonObject args)throws Exception{
        java.lang.reflect.Method method=AgentLoop.class.getDeclaredMethod("runOneTool",ToolCall.class);method.setAccessible(true);
        return (ToolResult)method.invoke(loop,call(name,args));
    }
    private ToolResult submit(JsonObject args)throws Exception{return tools.get("task").execute(args);}
    private JsonObject record(String step)throws Exception{
        ToolResult result=loop.controlChildren(json("{\"action\":\"child_status\",\"limit\":16}"));assertTrue(result.output,result.ok);
        for(JsonElement e:json(result.output).getAsJsonArray("records"))if(step.equals(e.getAsJsonObject().get("stepId").getAsString()))return e.getAsJsonObject();return null;
    }
    private JsonObject await(String step,String status)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);JsonObject r;
        while((r=record(step))==null||!status.equals(r.get("status").getAsString())){
            if(System.nanoTime()>until)fail("未达到 "+status+": "+r);Thread.sleep(25);
        }return r;
    }
    private String parentRead(Path file)throws Exception{
        JsonObject a=new JsonObject();a.addProperty("path",file.toString());ToolResult result=runRoot("Read",a);assertTrue(result.output,result.ok);
        List<TaskState.Evidence> evidence=loop.session().taskState.evidence;return evidence.get(evidence.size()-1).id;
    }
    private ToolResult review(String step,String ref){
        JsonObject a=json("{\"action\":\"child_review\",\"attempt\":1,\"note\":\"实际读取产物并核对验收约定\"}");a.addProperty("stepId",step);a.addProperty("receiptIds",ref);return loop.controlChildren(a);
    }
    private FakeLlmClient writer(Path target){FakeLlmClient child=new FakeLlmClient();JsonObject a=new JsonObject();a.addProperty("path",target.toString());a.addProperty("content","done");
        child.addTurnWithTools(Collections.singletonList(call("Write",a)),null);child.addTurn("已完成分工，输出文件为 "+target);return child;}
    private void rootedFactory(java.util.function.Function<Path,FakeLlmClient> client){
        loop.setRootedRuntimeFactory((model,id,root)->{assertNull("子模型必须继承，不接受另选模型",model);Workspace w=new Workspace(root==null?workspace.workDir():root);
            ToolRegistry registry=new ToolRegistry();registry.register(new ReadTool(w));registry.register(new WriteTool(w));
            return new AgentRuntime(client.apply(Paths.get(w.workDir())),"same-model",1000000,.9,8192,150000,registry,w,true);});
    }
    @Test public void startupIsLazyAndModelOverrideCannotCreateAQueue()throws Exception{
        Path root=tmp.newFolder("lazy").toPath();create(root);assertFalse(Files.exists(root.resolve(".minion/child-coordination")));
        JsonObject a=task("p","p.txt");a.addProperty("model","different");assertFalse(submit(a).ok);assertFalse(Files.exists(root.resolve(".minion/child-coordination")));
    }
    @Test public void actualWritesAndParentReadReceiptsGateDependencyRelease()throws Exception{
        Path root=tmp.newFolder("receipts").toPath();create(root);AtomicInteger number=new AtomicInteger();
        rootedFactory(r->writer(r.resolve(number.incrementAndGet()==1?"a.txt":"b.txt")));
        assertTrue(submit(task("a","a.txt")).ok);assertTrue(submit(task("b","b.txt","a")).ok);
        JsonObject a=await("a","returned_needs_review");assertEquals("queued",record("b").get("status").getAsString());
        assertEquals("done",new String(Files.readAllBytes(root.resolve("a.txt")),"UTF-8"));
        assertFalse(review("a","").ok);assertFalse(review("a",loop.session().taskState.evidence.get(0).id).ok);
        String ref=parentRead(root.resolve("a.txt"));ToolResult review=review("a",ref);assertTrue(review.output,review.ok);
        await("b","returned_needs_review");assertEquals(2,number.get());
        assertTrue(review("b",parentRead(root.resolve("b.txt"))).ok);
        assertEquals("same-model",loop.session().taskState.child(a.get("childId").getAsString()).model);
    }
    @Test public void scopedWorkerCannotWriteAnotherWorkersFile()throws Exception{
        Path root=tmp.newFolder("scope").toPath();create(root);
        rootedFactory(r->{FakeLlmClient child=writer(r.resolve("not-allowed.txt"));JsonObject good=new JsonObject();good.addProperty("path",r.resolve("allowed.txt").toString());good.addProperty("content","done");child.addTurnWithTools(Collections.singletonList(call("Write",good)),null);child.addTurn("只完成allowed范围");return child;});
        // The scripted terminal candidate after a rejected write is not proof; inspect the actual filesystem/transcript.
        assertTrue(submit(task("a","allowed.txt")).ok);JsonObject r=await("a","returned_needs_review");assertFalse(Files.exists(root.resolve("not-allowed.txt")));
        Path transcript=Paths.get(loop.session().taskState.child(r.get("childId").getAsString()).transcript);
        assertTrue(new String(Files.readAllBytes(transcript),"UTF-8").contains("未执行"));
    }
    @Test public void rootScopeCannotRewriteCoordinatorOrGitMetadata()throws Exception{
        Path root=tmp.newFolder("metadata").toPath();create(root);rootedFactory(r->writer(r.resolve(".minion/forged-record.txt")));
        assertTrue(submit(task("wide",".")).ok);JsonObject r=await("wide","failed");
        assertFalse(Files.exists(root.resolve(".minion/forged-record.txt")));
        String transcript=new String(Files.readAllBytes(Paths.get(loop.session().taskState.child(r.get("childId").getAsString()).transcript)),"UTF-8");assertTrue(transcript.contains("协调元数据"));
    }
    private FakeLlmClient waiting(CountDownLatch entered,CountDownLatch release){return new FakeLlmClient(){
        @Override public void streamChat(List<Message> messages,List<JsonObject> schemas,StreamHandler handler)throws LlmException{
            entered.countDown();try{release.await(20,TimeUnit.SECONDS);handler.onContent("仅返回观察结果");handler.onFinish("stop",new Usage(),null);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }};}
    @Test public void cancellationReachesIndependentConnectionAndParentMutationWaits()throws Exception{
        Path root=tmp.newFolder("cancel").toPath();create(root);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);rootedFactory(r->waiting(entered,release));
        try{assertTrue(submit(task("a","a.txt")).ok);assertTrue(entered.await(3,TimeUnit.SECONDS));
            JsonObject write=json("{\"content\":\"must not run\"}");write.addProperty("path",root.resolve("root.txt").toString());ToolResult denied=runRoot("Write",write);assertFalse(denied.ok);assertFalse(Files.exists(root.resolve("root.txt")));
            loop.interrupt();await("a","cancelled");
        }finally{release.countDown();}
    }
    @Test public void latestRevisionRetiresOldChildrenInsteadOfRevivingOldInstructions()throws Exception{
        Path root=tmp.newFolder("revision").toPath();create(root);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);rootedFactory(r->waiting(entered,release));
        try{assertTrue(submit(task("old","old.txt")).ok);assertTrue(entered.await(3,TimeUnit.SECONDS));
            TaskState state=loop.session().taskState;String id=state.id;state.addGuidance("改为只分析，不再生成旧文件");
            ToolResult current=loop.controlChildren(json("{\"action\":\"child_status\"}"));assertTrue(current.output,current.ok);
            assertEquals(id,state.id);assertTrue(state.childSnapshot().isEmpty());assertFalse(Files.exists(root.resolve("old.txt")));
            TaskState.Child old=state.children.get(0);state.begin("全新的另一个任务",true);state.recordEvidence(old.id,"Read",true,"late result",null,false);
            assertEquals(id,state.evidence.get(state.evidence.size()-1).taskId);
        }finally{release.countDown();}
    }
    @Test public void worktreeUsesActualIsolatedToolsAndRequiresJointValidation()throws Exception{
        Path root=FGitFixture.create();create(root);
        rootedFactory(r->{if(r.equals(root.toAbsolutePath()))return writer(r.resolve("b.txt"));FakeLlmClient child=new FakeLlmClient();JsonObject read=new JsonObject();read.addProperty("path",r.resolve("a.txt").toString());
            JsonObject write=read.deepCopy();write.addProperty("content","done");child.addTurnWithTools(Collections.singletonList(call("Read",read)),null);child.addTurnWithTools(Collections.singletonList(call("Write",write)),null);child.addTurn("a.txt已更新，等待主Agent合并验收");return child;});
        JsonObject request=task("code","a.txt");request.addProperty("requiresMerge",true);assertTrue(submit(request).ok);
        assertTrue(submit(task("after-code","b.txt","code")).ok);
        JsonObject returned=await("code","returned_needs_review");Path execution=Paths.get(returned.get("executionRoot").getAsString());
        assertNotEquals(root.toRealPath(),execution.toRealPath());assertEquals("base\n",new String(Files.readAllBytes(root.resolve("a.txt")),"UTF-8"));
        assertTrue(review("code",parentRead(execution.resolve("a.txt"))).ok);
        TaskState.Child child=loop.session().taskState.child(returned.get("childId").getAsString());
        com.minion.core.checkpoint.CheckpointStore checkpoints=new com.minion.core.checkpoint.CheckpointStore(root,root.resolve(".minion/checkpoints/test"));
        OutputMerge merge=new OutputMerge(workspace,checkpoints);OutputMerge.Plan plan=merge.inspect(child,"a.txt","a.txt");merge.apply(plan,loop.session().taskState);
        assertEquals("done",new String(Files.readAllBytes(root.resolve("a.txt")),"UTF-8"));assertEquals("reviewed",record("code").get("status").getAsString());
        assertEquals("merged_needs_validation",loop.session().taskState.child(child.id).status);
        String executable=System.getProperty("minion.validation.python","");Assume.assumeTrue(Files.isRegularFile(Paths.get(executable)));
        tools.register(new BuildTool(workspace,new PythonRuntime(executable)));JsonObject command=json("{\"action\":\"command\",\"timeoutSeconds\":30}");
        command.addProperty("command","\""+executable+"\" -c \"assert open('a.txt').read() == 'done'\"");ToolResult check=runRoot("Build",command);assertTrue(check.output,check.ok);
        String ref=loop.session().taskState.evidence.get(loop.session().taskState.evidence.size()-1).id;
        JsonObject validation=json("{\"action\":\"validate_merge\",\"validationKind\":\"command\",\"note\":\"实际Python断言检查合并后的文件内容，非单元测试\"}");validation.addProperty("childId",child.id);validation.addProperty("receiptIds",ref);
        ToolResult validated=tools.get("AgentControl").execute(validation);assertTrue(validated.output,validated.ok);assertEquals("validated",record("code").get("status").getAsString());
        await("after-code","returned_needs_review");assertEquals("done",new String(Files.readAllBytes(root.resolve("b.txt")),"UTF-8"));
    }
    @Test public void explicitRetryKeepsOldAttemptButOnlyCurrentChildBlocksCompletion()throws Exception{
        Path root=tmp.newFolder("retry").toPath();create(root);AtomicInteger attempts=new AtomicInteger();
        rootedFactory(r->{if(attempts.incrementAndGet()==1)throw new IllegalStateException("injected factory failure");return writer(r.resolve("a.txt"));});
        assertTrue(submit(task("a","a.txt")).ok);JsonObject old=await("a","failed");
        JsonObject retry=json("{\"action\":\"child_retry\",\"stepId\":\"a\",\"attempt\":1,\"note\":\"核对目录未写入，连接创建失败，可重新派发\"}");
        assertFalse(loop.controlChildren(retry).ok);retry.addProperty("sideEffectsChecked",true);assertTrue(loop.controlChildren(retry).ok);
        JsonObject current=await("a","returned_needs_review");assertEquals(2,current.get("attempt").getAsInt());assertNotEquals(old.get("childId"),current.get("childId"));
        assertEquals(2,loop.session().taskState.children.size());assertEquals(1,loop.session().taskState.childSnapshot().size());
        String id=parentRead(root.resolve("a.txt"));JsonObject review=json("{\"action\":\"child_review\",\"stepId\":\"a\",\"attempt\":2,\"note\":\"实际核对新尝试输出\"}");review.addProperty("receiptIds",id);assertTrue(loop.controlChildren(review).ok);
    }
    @Test public void restoredQueueDoesNotRunUntilExplicitRecoveryAndNeverReplaysAnUncertainWorker()throws Exception{
        Path root=tmp.newFolder("recovery").toPath();TaskState state=new TaskState();state.begin("只测试协调记录恢复",true);AtomicInteger executions=new AtomicInteger();
        ChildTaskCoordinator.Runner runner=(a,c)->{executions.incrementAndGet();Path proof=root.resolve("proof.txt");Files.write(proof,"proof".getBytes("UTF-8"));return ChildTaskCoordinator.Outcome.returned("返回","仅测试fixture",Collections.singletonList(proof.toString()));};
        ChildTaskHost first=new ChildTaskHost(root,"recovery-session",state,1,runner,()->{});first.coordinator().setCapacity(0);
        JsonObject assignment=json("{\"stepId\":\"one\",\"description\":\"读取测试资料\",\"acceptance\":\"记录证据\",\"scope\":[\".\"],\"dependsOn\":[],\"mode\":\"explore\",\"revision\":1}");assertTrue(first.submit(assignment).ok);
        ChildTaskCoordinator.Snapshot persisted=first.coordinator().snapshot();first.close();
        ChildTaskCoordinator.Record uncertain=new Gson().fromJson(new Gson().toJson(persisted.records.get(0)),ChildTaskCoordinator.Record.class);
        uncertain.id=UUID.randomUUID().toString();uncertain.assignment.stepId="uncertain";uncertain.status="running";persisted.records.add(uncertain);
        Path journal=root.resolve(".minion/child-coordination/recovery-session").resolve(state.id+"-r1.json");Files.write(journal,new Gson().toJson(persisted).getBytes("UTF-8"));
        try(ChildTaskHost restored=new ChildTaskHost(root,"recovery-session",state,1,runner,()->{})){
            Thread.sleep(150);assertEquals(0,executions.get());assertTrue(restored.status(0,8).get("recoveryHold").getAsBoolean());
            assertTrue(restored.control(json("{\"action\":\"child_resume_queue\",\"note\":\"测试旧协调器已关闭，队列从未执行\"}")).ok);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(executions.get()==0&&System.nanoTime()<end)Thread.sleep(20);assertEquals(1,executions.get());
            Thread.sleep(100);assertEquals(1,executions.get());assertEquals("interrupted_needs_review",restored.coordinator().record("uncertain").status);
        }
    }
    @Test public void parentWaitsWithoutModelPollingOrSpendingAnotherToolRound()throws Exception{
        Path root=tmp.newFolder("no-spin").toPath();create(root);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);rootedFactory(r->waiting(entered,release));
        JsonObject assignment=task("one","one.txt");assignment.addProperty("mode","explore");parent.addTurnWithTools(Collections.singletonList(call("task",assignment)),null);loop.roundLimit=1;
        ExecutorService worker=Executors.newSingleThreadExecutor();try{
            Future<?> job=worker.submit(()->loop.runUserTurn("按当前要求继续处理",null,true));assertTrue(entered.await(3,TimeUnit.SECONDS));
            Thread.sleep(150);assertFalse("等待子结果不应错误停止主任务",job.isDone());assertEquals(1,parent.requests.size());
            release.countDown();job.get(10,TimeUnit.SECONDS);assertEquals(1,parent.requests.size());assertEquals("paused_budget",loop.session().taskState.executionPhase());
        }finally{release.countDown();worker.shutdownNow();}
    }
}
