package com.minion.core.agent;
import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class IndependentReviewPolicyTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void explorerCanInspectGitButCannotWriteOrUseNetworkVerification(){AgentToolPolicy p=new AgentToolPolicy(true,"Git,ArtifactVerification");assertTrue(p.visible("Git"));assertTrue(p.permits("Git",args("diff")));assertTrue(p.permits("Git",args("review_snapshot")));assertFalse(p.permits("Git",args("push")));assertFalse(p.permits("Git",args("worktree_create")));assertTrue(p.permits("ArtifactVerification",args("image")));assertFalse(p.permits("ArtifactVerification",args("http")));assertFalse(StopGate.mutationEvidence("ArtifactVerification",args("webpage")));}
    @Test public void reviewUsesActualDiffAndReadOnlyChildWithoutClaimingTests()throws Exception{
        Path root=tmp.newFolder("review").toPath();Process git=new ProcessBuilder("git","init",root.toString()).redirectErrorStream(true).start();Assume.assumeTrue(git.waitFor(10,TimeUnit.SECONDS)&&git.exitValue()==0);
        Files.write(root.resolve("changed.py"),"print('untracked real diff')\n".getBytes("UTF-8"));Workspace ws=new Workspace(root.toString());Session session=Session.create(root.toString(),"fake");
        AgentLoop loop=new AgentLoop(new FakeLlmClient(),new ToolRegistry(),new SystemPromptBuilder(null),new ConfirmGate(Config.load(root),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new RecordingUi(),null,ws,session);
        AtomicReference<JsonObject> dispatched=new AtomicReference<JsonObject>();loop.setSubAgentRunner(a->{dispatched.set(a);return "当前差异未发现确定缺陷，未运行测试";});
        try{session.taskState.begin("只读审查代码",true);ToolResult r=loop.reviewChanges(new JsonObject());assertTrue(r.output,r.ok);JsonObject out=JsonParser.parseString(r.output).getAsJsonObject();assertFalse(out.get("testsRun").getAsBoolean());assertNotNull(dispatched.get());assertEquals("explore",dispatched.get().get("mode").getAsString());assertTrue(dispatched.get().get("prompt").getAsString().contains("untracked real diff"));assertEquals("Read,Grep,Glob,Git",dispatched.get().get("allowedTools").getAsString());assertFalse(dispatched.get().has("model"));assertEquals("print('untracked real diff')\n",new String(Files.readAllBytes(root.resolve("changed.py")),"UTF-8"));}
        finally{loop.shutdown();}
    }
    private static JsonObject args(String action){JsonObject a=new JsonObject();a.addProperty("action",action);return a;}
}
