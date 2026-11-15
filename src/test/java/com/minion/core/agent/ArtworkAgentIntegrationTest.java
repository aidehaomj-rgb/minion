package com.minion.core.agent;
import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.artwork.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.Assert.*;

public class ArtworkAgentIntegrationTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void textOnlyModelCanProduceRealArtworkAndEvidenceWithoutReceivingPixels()throws Exception {
        Path root=tmp.getRoot().toPath();Files.write(root.resolve("design.json"),ArtworkTemplates.create("process","业务流程图","本地绘图，无需额外模型").toString().getBytes(StandardCharsets.UTF_8));
        Workspace ws=new Workspace(root.toString());ToolRegistry registry=new ToolRegistry();registry.register(new ArtworkTool(ws,null));
        FakeLlmClient model=new FakeLlmClient();ToolCall call=new ToolCall();call.id="art-1";call.name="Artwork";call.arguments="{\"action\":\"render\",\"path\":\"design.json\",\"outputPath\":\"flow.png\"}";
        model.addTurnWithTools(Collections.singletonList(call),"开始绘制");model.addTurn("已导出业务流程图、SVG与源稿；尚未视觉核验。"+AgentLoop.TASK_COMPLETE_MARKER);model.stopStatuses.add("completed");
        Session session=Session.create(root.toString(),"deepseek-v4-pro");AgentLoop loop=new AgentLoop(model,registry,new SystemPromptBuilder(null),new ConfirmGate(Config.load(root),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new RecordingUi(),null,ws,session);
        try {
            loop.runUserTurn("绘制业务流程图");assertTrue(loop.lastTurnSucceeded());
            assertTrue(Files.size(root.resolve("flow.png"))>1000);assertTrue(Files.isRegularFile(root.resolve("flow.svg")));
            assertTrue(session.taskState.evidence.stream().anyMatch(e->"Artwork".equals(e.tool)&&e.file!=null&&e.file.path.endsWith("flow.png")));
            assertTrue(model.requests.stream().allMatch(r->r.messages.stream().allMatch(m->m.images==null||m.images.isEmpty())));
        } finally {loop.shutdown();}
    }
    @Test public void drawingRequiresRealMutationButCapabilityQuestionsDoNot() {
        assertTrue(CompletionContract.from("帮我绘制流程图",Collections.emptyList()).mutation);
        assertFalse(CompletionContract.from("这些模型能否生图",Collections.emptyList()).mutation);
        assertFalse(CompletionContract.from("介绍如何绘制流程图",Collections.emptyList()).mutation);
    }
}
