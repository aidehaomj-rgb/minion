package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import com.minion.core.tools.dev.ArtifactVerificationTool;
import com.minion.core.tools.dev.BuildTool;
import com.minion.core.tools.python.PythonRuntime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.Assert.*;

public class EvidenceBasedCompletionTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private static final String FILE="交付.docx",GOAL="生成并交付核验后的 Word 文件";
    private Path root;
    private Workspace workspace;
    private ToolRegistry registry;
    private AgentLoop loop;
    private FakeLlmClient llm;
    private final Map<Integer,Runnable> beforeRequest=new HashMap<Integer,Runnable>();
    private int id,writes;

    @Before public void setup() throws Exception {
        root=tmp.newFolder().toPath();
        workspace=new Workspace(root.toString());
        llm=new FakeLlmClient() {
            @Override public void streamChat(List<Message> messages,List<JsonObject> tools,StreamHandler handler)throws LlmException {
                Runnable before=beforeRequest.remove(requests.size());
                if(before!=null)before.run();
                super.streamChat(messages,tools,handler);
            }
        };
        registry=new ToolRegistry();
        registry.register(new ReadTool(workspace));
        registry.register(new WriteTool(workspace));
        registry.register(new ArtifactVerificationTool(workspace));
        registry.register(new Tool() {
            public String name(){return "Document";}
            public String description(){return "";}
            public JsonObject schema(){return new JsonObject();}
            public ToolResult execute(JsonObject args)throws Exception {
                writes++;docx(root.resolve(args.get("outputPath").getAsString()),"已核验标记");return ToolResult.success("输出成功");
            }
        });
        loop=new AgentLoop(llm,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(root),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new AgentUi(){},null,
                workspace,Session.create(workspace.workDir(),"fake"));
        loop.requestExecutionMode(ExecutionMode.GOAL);
    }

    @After public void cleanup(){loop.shutdown();}

    private void tool(String name,String arguments) {tool(name,arguments,null);}
    private void tool(String name,String arguments,Runnable before) {
        ToolCall call=new ToolCall();call.id="test"+(++id);call.name=name;call.arguments=arguments;
        if(before!=null)beforeRequest.put(id-1,before);
        llm.addTurnWithTools(Collections.singletonList(call),null);
    }
    private void create(){tool("Document","{\"action\":\"docx_report\",\"outputPath\":\""+FILE+"\"}");}
    private void check(){tool("ArtifactVerification","{\"action\":\"docx\",\"path\":\""+FILE+"\",\"stableMs\":100,\"officeChecks\":[{\"expectedText\":\"已核验标记\"}]}");}
    private void checkpoint(String next){tool("AgentControl","{\"action\":\"checkpoint\",\"processedRange\":\"全部\",\"files\":\""+FILE+"\",\"nextStep\":\""+next+"\"}");}
    private void finalReply(){llm.addTurn(FILE+" 已核验并交付。\n"+AgentLoop.TASK_COMPLETE_MARKER);}

    @Test public void actualOfficeAssertionAndCurrentCheckpointEndWithoutReviewerOrExtraWork() {
        create();check();checkpoint("");finalReply();
        llm.stopReviewRaw="should not be called";
        loop.runUserTurn(GOAL);
        assertTrue(result(),loop.lastTurnSucceeded());
        assertEquals("local_evidence",loop.session().taskState.stopDecision.source);
        assertEquals(1,writes);assertEquals(4,llm.requests.size());assertTrue(llm.stopReviewRequests.isEmpty());
        assertTrue(loop.messages().get(loop.messages().size()-1).content.contains("完成时间："));
    }

    @Test public void mereFileExistenceAndMarkerStillRequireSemanticReview() {
        create();checkpoint("");finalReply();
        llm.stopStatuses.add("completed");
        loop.runUserTurn(GOAL);
        assertTrue(result(),loop.lastTurnSucceeded());
        assertEquals("reviewer",loop.session().taskState.stopDecision.source);
        assertEquals(1,llm.stopReviewRequests.size());assertEquals(3,llm.requests.size());
    }

    @Test public void filteredFinalResponseIsNotAnApprovedDelivery() {
        create();check();checkpoint("");
        llm.addTurnWithFinish(Collections.emptyList(),FILE+" 已核验。"+AgentLoop.TASK_COMPLETE_MARKER,"content_filter");
        loop.runUserTurn(GOAL);
        assertFalse(loop.lastTurnSucceeded());assertEquals("content_filter",loop.session().taskState.pauseCode);
        assertTrue(llm.stopReviewRequests.isEmpty());assertEquals(1,writes);
    }

    @Test public void aCheckpointWithNextActionMustNotSkipTheReviewer() {
        create();check();checkpoint("仍需核验用户要求的格式");
        finalReply();llm.stopStatuses.add("unknown");loop.runUserTurn(GOAL);
        assertFalse(loop.lastTurnSucceeded());assertEquals(1,llm.stopReviewRequests.size());
    }

    @Test public void changedArtifactDoesNotCompleteEvenWithAnOlderSuccessfulAssertion() {
        create();check();checkpoint("");
        tool("Read","{\"path\":\"not-required.txt\"}",()->overwriteDoc("已经更改"));
        for(int i=0;i<4;i++) finalReply();
        loop.runUserTurn(GOAL);
        assertFalse(loop.lastTurnSucceeded());assertEquals("no_progress",loop.session().taskState.pauseCode);
        assertEquals("local_block",loop.session().taskState.stopDecision.source);
        assertTrue(result(),result().contains("断点证据文件已变更"));
        assertEquals(1,writes);assertTrue(llm.stopReviewRequests.isEmpty());
    }

    @Test public void realRereadAndRefreshRetireObsoleteWriteHashWithoutBlindRewrite() throws Exception {
        String file="report.txt";String goal="生成并交付文件";
        tool("Write","{\"path\":\""+file+"\",\"content\":\"old\"}");
        tool("AgentControl","{\"action\":\"checkpoint\",\"processedRange\":\"全篇\",\"files\":\""+file+"\"}");
        tool("Read","{\"path\":\""+file+"\"}",()->{
            try{Files.write(root.resolve(file),"reviewed revision".getBytes(StandardCharsets.UTF_8));}
            catch(IOException e){throw new AssertionError(e);}
        });
        tool("AgentControl","{\"action\":\"refresh_checkpoint\",\"note\":\"读取了合法更新\"}");
        llm.addTurn(file+" 已核对最新内容并交付。"+AgentLoop.TASK_COMPLETE_MARKER);
        llm.stopStatuses.add("completed");loop.runUserTurn(goal);
        assertTrue(result(),loop.lastTurnSucceeded());assertEquals(5,llm.requests.size());assertEquals(1,llm.stopReviewRequests.size());
        assertEquals("reviewed revision",new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8));
    }

    @Test public void freshGuidanceInvalidatesPreviouslyVerifiedCheckpoint() {
        create();check();checkpoint("");
        beforeRequest.put(3,()->loop.offerSupplement("另补充：加上附件目录。"));
        finalReply();finalReply();llm.stopStatuses.add("unknown");
        loop.runUserTurn(GOAL);
        assertFalse(loop.lastTurnSucceeded());
        assertEquals(2,loop.session().taskState.revision());
        assertTrue(llm.stopReviewRequests.get(0).contains("附件目录"));
    }

    @Test public void identicalDenialIsNotSentForReviewAgainButDoesNotApprove() {
        llm.stopStatuses.add("continue");
        for(int i=0;i<4;i++)llm.addTurn("尚缺用户指定的范围结果。");
        loop.runUserTurn("说明分析结论");
        assertFalse(loop.lastTurnSucceeded());
        assertEquals("unchanged_review",loop.session().taskState.stopDecision.source);
        assertEquals("no_progress",loop.session().taskState.pauseCode);
        assertEquals(1,llm.stopReviewRequests.size());
    }

    @Test public void strongEvidenceDoesNotBypassApprovedCompletionHook() throws Exception {
        JsonObject rule=new JsonObject();rule.addProperty("id","stop-test");rule.addProperty("event","before_complete");rule.addProperty("timeoutMs",5000);
        JsonArray command=new JsonArray();command.add(Paths.get(System.getProperty("java.home"),"bin","java").toString());
        command.add("-cp");command.add(Paths.get("target/test-classes").toAbsolutePath().toString());
        command.add(SessionHooksTest.Runner.class.getName());command.add("fail");rule.add("command",command);
        JsonArray hooks=new JsonArray();hooks.add(rule);JsonObject config=new JsonObject();config.add("hooks",hooks);
        Path hookFile=root.resolve(".minion/hooks.json");
        Files.createDirectories(hookFile.getParent());Files.write(hookFile,config.toString().getBytes(StandardCharsets.UTF_8));
        loop.hooks().approve(JsonParser.parseString(loop.hooks().configurationPreview()).getAsJsonObject().get("hash").getAsString());
        create();check();checkpoint("");for(int i=0;i<3;i++)finalReply();
        loop.runUserTurn(GOAL);
        assertFalse(loop.lastTurnSucceeded());assertEquals("hook_failed",loop.session().taskState.pauseCode);
        assertEquals("unknown",loop.session().taskState.stopDecision.status);
        assertTrue(llm.stopReviewRequests.isEmpty());assertEquals(1,writes);
    }

    @Test public void realTestBoundToACompletedWorkflowStopsWithoutARetest() throws Exception {
        String python=System.getProperty("minion.test.python","");
        Assume.assumeTrue(Files.isRegularFile(Paths.get(python)));
        registry.register(new BuildTool(workspace,new PythonRuntime(python)));
        Files.write(root.resolve("test_logic.py"),("import unittest\nclass ResultTest(unittest.TestCase):\n"
                +"    def test_result(self):\n        with open('result.txt') as f: self.assertEqual(f.read(), 'expected')\n").getBytes(StandardCharsets.UTF_8));
        tool("AgentControl","{\"action\":\"plan_steps\",\"revision\":1,\"steps\":[{\"id\":\"verify\",\"title\":\"交付并验证\",\"scope\":\".\",\"acceptance\":\"测试通过\",\"validationKind\":\"test\"}]}");
        tool("AgentControl","{\"action\":\"step_change\",\"stepId\":\"verify\",\"revision\":1,\"stepAction\":\"start\"}");
        tool("Write","{\"path\":\"result.txt\",\"content\":\"expected\"}");
        tool("Build","{\"action\":\"test\",\"timeoutSeconds\":20}");
        ToolCall complete=new ToolCall();
        llm.addTurnWithTools(Collections.singletonList(complete),null);
        // Supply the real host-issued receipt ID after the Build invocation, as a model would.
        beforeRequest.put(4,()-> {
            TaskState.Evidence e=loop.session().taskState.evidence.stream().filter(x->"Build".equals(x.tool)).findFirst().get();
            assertTrue(e.result,e.success);
            complete.id="actual-test-receipt";complete.name="AgentControl";
            complete.arguments="{\"action\":\"step_change\",\"stepAction\":\"complete\",\"stepId\":\"verify\",\"revision\":1,"
                    +"\"note\":\"真实测试通过\",\"receiptIds\":\""+e.id+"\"}";
        });
        llm.addTurn("结果文件 result.txt 已写入，实际测试通过。"+AgentLoop.TASK_COMPLETE_MARKER);
        loop.runUserTurn("生成 result.txt 并运行测试");
        assertTrue(result(),loop.lastTurnSucceeded());
        assertEquals("local_evidence",loop.session().taskState.stopDecision.source);
        assertTrue(llm.stopReviewRequests.isEmpty());assertEquals(6,llm.requests.size());
    }

    private String result(){return loop.session().taskState.stopDecision==null?loop.session().taskState.pauseReason:loop.session().taskState.stopDecision.reason;}
    private void overwriteDoc(String text){try{docx(root.resolve(FILE),text);}catch(IOException e){throw new AssertionError(e);}}
    private static void docx(Path path,String content)throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>"
                    +content+"</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
