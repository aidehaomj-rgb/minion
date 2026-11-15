package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import com.minion.core.tools.dev.CodeIndexTool;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class RuntimeWorkbenchWiringTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void rootRegistersToolsWithoutOpeningRuntimeLedger()throws Exception {
        Path root=temp.getRoot().toPath();ToolRegistry registry=new ToolRegistry();Session s=Session.create(root.toString(),"fake");
        AgentLoop loop=new AgentLoop(new FakeLlmClient(),registry,new SystemPromptBuilder(null),new ConfirmGate(Config.load(root),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new RecordingUi(),null,new Workspace(root.toString()),s);
        try{
            assertNotNull(registry.get("CodeIndex"));assertNotNull(registry.get("RuntimeJobs"));registry.schemas();
            assertFalse(Files.exists(root.resolve(".minion/runtime-jobs/"+s.id+".json")));
            assertFalse(loop.hooks().status().get("enabled").getAsBoolean());
        }finally{loop.shutdown();}
    }
    @Test public void runtimeObservationCannotBuyContinuationBudget(){
        ContinuationBudget b=new ContinuationBudget();StopGate.Progress p=new StopGate.Progress();
        for(String action:Arrays.asList("list","inspect","reconcile")){
            String args="{\"action\":\""+action+"\"}";
            b.observe("RuntimeJobs",args,true,"changing-version-"+action,null);
            p.observe(new StopGate.Receipt(action,"RuntimeJobs",args,ToolResult.success("changing-observation")));
        }
        assertEquals(0,b.evidenceVersion);assertEquals(1,p.deny());
    }
    @Test public void recoveryCannotRebaseOldLedgerToAnotherWorkspace()throws Exception {
        Path root=temp.newFolder("original").toPath();ToolRegistry registry=new ToolRegistry();Session s=Session.create(root.toString(),"fake");s.taskState.begin("核对原始作业",true);
        Workspace workspace=new Workspace(root.toString());
        AgentLoop loop=new AgentLoop(new FakeLlmClient(),registry,new SystemPromptBuilder(null),new ConfirmGate(Config.load(root),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new RecordingUi(),null,workspace,s);
        try{
            JsonObject args=new JsonObject();args.addProperty("action","list");assertTrue(registry.get("RuntimeJobs").execute(args).ok);
            Path next=temp.newFolder("another").toPath();workspace.setWorkDir(next.toString());
            assertFalse(registry.get("RuntimeJobs").execute(args).ok);
            assertFalse(Files.exists(next.resolve(".minion/runtime-jobs/"+s.id+".json")));
        }finally{loop.shutdown();}
    }
    @Test public void childCannotInspectRootJobsButCanUseReadOnlyIndex(){
        AgentToolPolicy policy=new AgentToolPolicy(true,"");assertFalse(policy.visible("RuntimeJobs"));
        JsonObject args=new JsonObject();args.addProperty("action","symbols");assertTrue(policy.permits("CodeIndex",args));
        assertTrue(ToolExecutionGuard.readOnly("CodeIndex",args));
    }
    @Test public void indexSchemaIsLoadedOnlyWhenRelevantOrDiscovered(){
        ToolRegistry registry=new ToolRegistry();registry.register(new CodeIndexTool(new Workspace(temp.getRoot().toString())));
        ToolSchemaRouter router=new ToolSchemaRouter();assertEquals(0,router.select(registry.schemas(),"整理采购表格").size());
        assertEquals(1,router.select(registry.schemas(),"修改Java代码，查找函数引用").size());
        router.discover(registry.schemas(),"","CodeIndex",0,10);assertEquals(1,router.select(registry.schemas(),"整理采购表格").size());
    }
    @Test public void browserVerificationScreenshotAppearsAsEvidenceNotDeliverable()throws Exception {
        Path root=temp.getRoot().toPath(),png=root.resolve("evidence.png");Files.write(png,new byte[]{1});
        ToolCall call=new ToolCall();call.name="ArtifactVerification";call.arguments="{\"action\":\"webpage\",\"screenshotPath\":\"evidence.png\"}";
        List<GeneratedArtifact> files=new GeneratedArtifactCollector(p->PathsGuard.inside(root.toString(),p)).fromTool(call,ToolResult.success("passed"),root,"task",1);
        assertEquals(1,files.size());assertEquals(GeneratedArtifact.REFERENCE,files.get(0).kind);
        assertEquals(png.toRealPath().toString(),files.get(0).path);
    }
}
