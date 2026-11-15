package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.context.*;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AgentWorkflowV24Test {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private JsonObject json(String raw) { return JsonParser.parseString(raw).getAsJsonObject(); }
    private void write(Path file,String value) throws Exception { Files.write(file,value.getBytes(StandardCharsets.UTF_8)); }
    private ConfirmGate gate() { return new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)); }
    private ToolCall call(String id,String name,String args) { ToolCall c=new ToolCall(); c.id=id;c.name=name;c.arguments=args;return c; }
    private AgentLoop loop(FakeLlmClient llm) {
        Workspace ws=new Workspace(tmp.getRoot().toString());
        return new AgentLoop(llm,new ToolRegistry(),new SystemPromptBuilder(null),gate(),new AgentUi(){},null,ws,Session.create(ws.workDir(),"parent"));
    }
    @Test public void concurrentWriterCannotOverwriteVersionReadByOtherAgent() throws Exception {
        Path f=tmp.getRoot().toPath().resolve("shared.md"); write(f,"base");
        Workspace ws=new Workspace(tmp.getRoot().toString()); ToolExecutionGuard a=new ToolExecutionGuard(ws,true),b=new ToolExecutionGuard(ws,true);
        JsonObject path=json("{\"path\":\"shared.md\"}");
        assertTrue(a.execute(new ReadTool(ws),path).ok); assertTrue(b.execute(new ReadTool(ws),path).ok);
        assertTrue(b.execute(new WriteTool(ws),json("{\"path\":\"shared.md\",\"content\":\"B\"}")).ok);
        ToolResult stale=a.execute(new WriteTool(ws),json("{\"path\":\"shared.md\",\"content\":\"A\"}"));
        assertFalse(stale.ok); assertTrue(stale.output.contains("并行文件冲突"));
        assertEquals("B",new String(Files.readAllBytes(f),StandardCharsets.UTF_8));
        assertTrue(a.execute(new ReadTool(ws),path).ok);
        assertTrue(a.execute(new WriteTool(ws),json("{\"path\":\"shared.md\",\"content\":\"merged\"}")).ok);
    }
    @Test public void childMustReadExistingFileButCanCreateNewFile() throws Exception {
        write(tmp.getRoot().toPath().resolve("old.md"),"keep"); Workspace ws=new Workspace(tmp.getRoot().toString());
        ToolExecutionGuard guard=new ToolExecutionGuard(ws,true);
        assertFalse(guard.execute(new WriteTool(ws),json("{\"path\":\"old.md\",\"content\":\"bad\"}")).ok);
        assertTrue(guard.execute(new WriteTool(ws),json("{\"path\":\"new.md\",\"content\":\"new\"}")).ok);
    }
    @Test public void differentProjectsSerializeMutationsToSharedResources() throws Exception {
        Workspace a=new Workspace(tmp.newFolder("a").toString()),b=new Workspace(tmp.newFolder("b").toString());
        AtomicInteger concurrent=new AtomicInteger(),peak=new AtomicInteger();
        Tool mutation=new Tool() { public String name(){return "Python";} public String description(){return "test";} public JsonObject schema(){return new JsonObject();}
            public ToolResult execute(JsonObject args) throws Exception { int n=concurrent.incrementAndGet(); peak.accumulateAndGet(n,Math::max); try { Thread.sleep(40);return ToolResult.success("ok"); }finally { concurrent.decrementAndGet(); } } };
        ExecutorService pool=Executors.newFixedThreadPool(2); try {
            Future<?> one=pool.submit(() -> { try {new ToolExecutionGuard(a,false).execute(mutation,new JsonObject());}catch(Exception e){throw new RuntimeException(e);} });
            Future<?> two=pool.submit(() -> { try {new ToolExecutionGuard(b,false).execute(mutation,new JsonObject());}catch(Exception e){throw new RuntimeException(e);} });
            one.get(3,TimeUnit.SECONDS); two.get(3,TimeUnit.SECONDS); assertEquals(1,peak.get());
        } finally {pool.shutdownNow();}
    }
    @Test public void stalledBrowserDoesNotBlockOtherSessionsFileTools() throws Exception {
        Workspace ws=new Workspace(tmp.getRoot().toString());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Tool browser=new Tool(){public String name(){return "BrowserAction";}public String description(){return "test";}
            public JsonObject schema(){return new JsonObject();}
            public ToolResult execute(JsonObject args)throws Exception {entered.countDown();release.await(10,TimeUnit.SECONDS);return ToolResult.success("done");}};
        Tool file=new Tool(){public String name(){return "Python";}public String description(){return "test";}
            public JsonObject schema(){return new JsonObject();}
            public ToolResult execute(JsonObject args){return ToolResult.success("independent");}};
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {
            Future<ToolResult> waiting=pool.submit(()->new ToolExecutionGuard(ws,false).execute(browser,new JsonObject()));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            Future<ToolResult> other=pool.submit(()->new ToolExecutionGuard(ws,false).execute(file,new JsonObject()));
            assertEquals("independent",other.get(1,TimeUnit.SECONDS).output);
            Future<ToolResult> competingBrowser=pool.submit(()->new ToolExecutionGuard(ws,false).execute(browser,new JsonObject()));
            ToolResult busy=competingBrowser.get(4,TimeUnit.SECONDS);
            assertFalse(busy.ok);assertTrue(busy.noSideEffect);
            release.countDown();assertTrue(waiting.get(3,TimeUnit.SECONDS).ok);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void readOnlyPolicyFailsClosedForShellPluginsAndKnowledgeWrites() {
        AgentToolPolicy p=new AgentToolPolicy(true,"");
        for(String tool:Arrays.asList("Write","Edit","Bash","Python","mcp_tool","Secrets","task")) assertFalse(tool,p.visible(tool));
        assertTrue(p.permits("Excel",json("{\"action\":\"validate\"}")));
        assertFalse(p.permits("Excel",json("{\"action\":\"patch_cells\"}")));
        assertFalse(p.permits("Excel",json("{\"action\":\"read\",\"filter\":\"x>1\"}")));
        assertTrue(p.permits("Document",json("{\"action\":\"pdf_view\"}")));
        assertFalse(p.permits("Document",json("{\"action\":\"docx_report\"}")));
        assertTrue(p.permits("Knowledge",json("{\"action\":\"search\"}")));
        assertFalse(p.permits("Knowledge",json("{\"action\":\"save\"}")));
        AgentToolPolicy worker = new AgentToolPolicy(false, "");
        assertFalse(worker.visible("Memory"));
        assertTrue(worker.permits("Knowledge", json("{\"action\":\"search\"}")));
        assertFalse(worker.permits("Knowledge", json("{\"action\":\"save\"}")));
        AgentToolPolicy white=new AgentToolPolicy(false,"Read, Glob"); assertTrue(white.visible("Read")); assertFalse(white.visible("Write"));
    }
    @Test public void checkpointAndLedgerPersistEvidenceWithoutPromotingOldGoal() throws Exception {
        Path file=tmp.getRoot().toPath().resolve("result.md"); write(file,"verified");
        TaskState state=new TaskState(); state.begin("生成报表",false);
        state.checkpoint("检查余下21-30行","1-20行",Collections.singletonList(FileEvidence.capture(file)));
        TaskState.Child child=state.startChild("1-20行"); state.configureChild(child.id,"explore","Read","child-model","record");
        Usage usage=new Usage(); usage.inputTokens=123; usage.outputTokens=45; state.childStats(child.id,300,60000,usage);
        state.finishChild(child.id,"证据",true,"record"); assertTrue(state.reviewChild(child.id));
        TaskState restored=new Gson().fromJson(new Gson().toJson(state),TaskState.class);
        assertTrue(restored.checkpointFilesValid()); assertEquals(123,restored.child(child.id).inputTokens);
        write(file,"changed"); assertFalse(restored.checkpointFilesValid()); assertTrue(restored.checkpointStatus().contains("changed"));
        restored.begin("新任务",true); assertTrue(restored.childSnapshot().isEmpty()); assertNull(restored.nextStep);
    }
    @Test public void childIsLockedToParentButSideQuestionRemainsSeparate() throws Exception {
        FakeLlmClient parent=new FakeLlmClient(),child=new FakeLlmClient(); child.addTurn("已完成子范围"); child.compressResult="旁支回答";
        AgentLoop loop=loop(parent); loop.session().taskState.begin("原任务",false);
        loop.session().messages.add(Message.user("保留原文")); final List<String> chosen=new ArrayList<String>();
        loop.setAgentRuntimeFactory((name,id) -> { chosen.add(name); return new AgentRuntime(child,name==null?"parent":name,60000,.8,new ToolRegistry(),loop.workspace(),true); });
        assertTrue(loop.runSubAgent(json("{\"description\":\"只查1行\",\"model\":\"configured-child\"}"))
                .contains("不能单独指定model"));
        assertTrue(chosen.isEmpty());
        String result=loop.runSubAgent(json("{\"description\":\"只查1行\",\"mode\":\"explore\"}"));
        assertTrue(result.contains("已返回")); assertTrue(parent.requests.isEmpty()); assertNull(chosen.get(0));
        assertEquals("parent",loop.session().taskState.childSnapshot().get(0).model);
        String before=new Gson().toJson(loop.session());
        assertEquals("旁支回答",loop.askSideQuestion("说明术语","other-model","任务背景"));
        assertEquals(before,new Gson().toJson(loop.session())); assertEquals("other-model",chosen.get(1)); loop.shutdown();
    }
    @Test public void taskToolDoesNotAdvertiseOrAcceptChildModelOverride() {
        AgentLoop loop=loop(new FakeLlmClient());
        TaskTool tool=new TaskTool(loop);
        assertFalse(tool.schema().getAsJsonObject("properties").has("model"));
        ToolResult denied=tool.execute(json("{\"description\":\"查一行\",\"model\":\"other-model\"}"));
        assertFalse(denied.ok); assertTrue(denied.output.contains("主会话模型"));
        loop.shutdown();
    }
    @Test public void legacyChildCannotResumeOnDifferentParentModel() throws Exception {
        FakeLlmClient llm=new FakeLlmClient();AgentLoop loop=loop(llm);
        loop.session().taskState.begin("主任务",false);
        TaskState.Child old=loop.session().taskState.startChild("旧模型分工");
        loop.session().taskState.configureChild(old.id,"explore","Read","other-model","old-record.json");
        String result=loop.resumeSubAgent(old.id,false);
        assertTrue(result.contains("请核对文件后选择重试"));
        assertTrue(llm.requests.isEmpty());
        loop.shutdown();
    }
    @Test public void childResumePreservesCompletedActionsAndRemovesBrokenToolPairs() throws Exception {
        Path record=tmp.getRoot().toPath().resolve("child.json"); FakeLlmClient llm=new FakeLlmClient(); llm.addTurn("只补缺失已完成");
        Message valid=Message.assistant(null); valid.toolCalls=Collections.singletonList(call("done","Read","{\"path\":\"result.md\"}"));
        Message broken=Message.assistant("写入未完成"); broken.toolCalls=Collections.singletonList(call("bad","Write","{\"path\":"));
        JsonObject data=new JsonObject(); data.addProperty("assignment","仅补21-30行");
        Path cwd=tmp.newFolder("child-cwd").toPath(); data.addProperty("cwd",cwd.toString());
        data.add("messages",new Gson().toJsonTree(Arrays.asList(Message.system("旧系统"),Message.user("任务分工"),valid,Message.toolResult("done","Read","已完成1-20行"),broken)));
        data.add("todos",new Gson().toJsonTree(new TodoList())); write(record,data.toString());
        SubAgentLoop sub=new SubAgentLoop("当前系统","仅补21-30行",tmp.getRoot().toString(),llm,new ToolRegistry(),gate(),new AgentUi(){});
        sub.setRecordPath(record); sub.restoreRecord(record); sub.run();
        assertTrue(sub.succeeded()); assertEquals("当前系统",llm.lastRequestMessages.get(0).content.substring(0,4));
        assertTrue(llm.lastRequestMessages.stream().anyMatch(m -> m.role==Message.Role.TOOL&&"done".equals(m.toolCallId)));
        for(Message m:llm.lastRequestMessages) if(m.toolCalls!=null) for(ToolCall c:m.toolCalls) assertNotEquals("bad",c.id);
        assertTrue(llm.lastRequestMessages.get(llm.lastRequestMessages.size()-1).content.contains("只完成尚未解决"));
        assertTrue(llm.lastRequestMessages.get(llm.lastRequestMessages.size()-1).content.contains(cwd.toString()));
        try(java.util.stream.Stream<Path> paths=Files.list(record.getParent())) { assertTrue(paths.anyMatch(p -> p.getFileName().toString().contains(".retry-"))); }
    }
    @Test public void childMalformedArgumentsNeverPoisonNextApiRequest() {
        FakeLlmClient llm=new FakeLlmClient(); llm.addTurnWithTools(Collections.singletonList(call("bad","Read","{\"path\":")),null); llm.addTurn("恢复成功");
        SubAgentLoop sub=new SubAgentLoop("系统","仅核查",tmp.getRoot().toString(),llm,new ToolRegistry(),gate(),new AgentUi(){});
        assertEquals("恢复成功",sub.run()); assertTrue(llm.requests.get(1).messages.stream().allMatch(m -> m.toolCalls==null||m.toolCalls.isEmpty()));
    }
    @Test public void compactProfileDeduplicatesToolsButKeepsGoalsAndSkills() {
        ContextManager cm=new ContextManager(131072,.8,50,new FakeLlmClient(),0);
        String large=String.join("",Collections.nCopies(20000,"证据"));
        List<Message> messages=Arrays.asList(Message.user("当前目标必须保留"),Message.skill("<skill name=\"audit\">\n技能必须保留\n</skill>","audit","SKILL.md","v1"),Message.toolResult("1","Read",large),Message.toolResult("2","Read",large));
        List<Message> balanced=cm.prepareForRequest(messages); cm.setRequestProfile("compact"); List<Message> compact=cm.prepareForRequest(messages);
        assertTrue(TokenCounter.estimateMessages(compact)<TokenCounter.estimateMessages(balanced));
        assertTrue(compact.get(2).content.contains("重复")); assertEquals("1",compact.get(2).toolCallId);
        assertEquals("当前目标必须保留",compact.get(0).content); assertTrue(compact.get(1).content.contains("技能必须保留")); assertEquals(Message.Origin.SKILL,compact.get(1).origin);
        assertEquals("<skill name=\"audit\">\n技能必须保留\n</skill>",messages.get(1).content);
        assertEquals(large,messages.get(3).content);
        String budget=ContextBudget.describe(compact,Collections.emptyList(),131072,60000,48000,"compact");
        assertTrue(budget.contains("工具结果:")); assertTrue(budget.contains("安全窗口: 60000"));
    }
    @Test public void checkpointToolRejectsOutsideAndMissingButAcceptsRealEvidence() throws Exception {
        AgentLoop loop=loop(new FakeLlmClient()); loop.session().taskState.begin("目标",false); Tool checkpoint=loop.registry().get("AgentControl");
        assertFalse(checkpoint.execute(json("{\"action\":\"checkpoint\",\"files\":\"missing.md\"}")).ok);
        write(tmp.getRoot().toPath().resolve("result.md"),"actual");
        assertTrue(checkpoint.execute(json("{\"action\":\"checkpoint\",\"files\":\"result.md\",\"nextStep\":\"最后验证\",\"processedRange\":\"1-30\"}")).ok);
        assertEquals("1-30",loop.session().taskState.processedRange); assertTrue(loop.session().taskState.checkpointFilesValid()); loop.shutdown();
    }
    @Test public void rootMustReviewReturnedChildBeforeSuccessfulCompletion() {
        FakeLlmClient llm=new FakeLlmClient(); AgentLoop loop=loop(llm); loop.session().taskState.begin("原任务",false);
        TaskState.Child child=loop.session().taskState.startChild("核查一行"); loop.session().taskState.finishChild(child.id,"实际结果",true,"record");
        llm.addTurn("全部完成 [[MINION_TASK_COMPLETE]]");
        llm.addTurnWithTools(Collections.singletonList(call("review","AgentControl","{\"action\":\"review\",\"childId\":\""+child.id+"\",\"note\":\"已读取结果并核对一行证据\"}")),null);
        llm.addTurn("核对完成 [[MINION_TASK_COMPLETE]]"); loop.runUserTurn("继续执行");
        assertTrue(loop.lastTurnSucceeded()); assertEquals("reviewed",loop.session().taskState.child(child.id).status); assertEquals(3,llm.requests.size()); loop.shutdown();
    }
}
