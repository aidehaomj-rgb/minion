package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AgentLoopArtifactsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private ToolCall call(String id,String name,String args) {
        ToolCall c=new ToolCall();c.id=id;c.name=name;c.arguments=args;return c;
    }
    private static class ArtifactUi extends RecordingUi {
        final Session session;
        final List<String> paths=new ArrayList<String>(),kinds=new ArrayList<String>();
        String preview;
        SessionStore eventStore;
        ArtifactUi(Session session){this.session=session;}
        @Override public synchronized void onArtifactCreated(String path) {
            paths.add(path);
            GeneratedArtifact receipt=session.artifactsSnapshot().stream().filter(a->path.equals(a.path)).findFirst().orElse(null);
            assertNotNull("callback follows registration",receipt);kinds.add(receipt.kind);
            if(eventStore!=null)try {
                assertTrue("checkpoint precedes event",eventStore.load(session.id).artifactsSnapshot().stream()
                        .anyMatch(a->path.equals(a.path)&&receipt.kind.equals(a.kind)&&receipt.updatedAt==a.updatedAt));
            } catch(Exception e) { throw new AssertionError("artifact was not durable before callback",e); }
            // Mirrors the path-only host contract: classification, not callback arrival, picks the preview.
            preview=session.artifactsSnapshot().stream().filter(a->session.taskState.id.equals(a.taskId))
                    .sorted(java.util.Comparator.comparingInt(a->GeneratedArtifact.DELIVERABLE.equals(a.kind)?0:GeneratedArtifact.REFERENCE.equals(a.kind)?1:2))
                    .map(a->a.path).findFirst().orElse(null);
        }
    }
    private AgentLoop loop(Path root,Workspace ws,Session s,ToolRegistry registry,FakeLlmClient model,ArtifactUi ui)throws Exception {
        return new AgentLoop(model,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(root),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),ui,null,ws,s);
    }

    @Test public void actualWriteEditRegistersBeforeCallbackAndReportLinksSurviveRestore() throws Exception {
        Path root=temp.newFolder().toPath(),reference=root.resolve("已有资料.txt");Files.write(reference,new byte[]{1});
        Workspace ws=new Workspace(root.toString());Session s=Session.create(root.toString(),"fixture");
        ArtifactUi ui=new ArtifactUi(s);ToolRegistry registry=new ToolRegistry();registry.register(new WriteTool(ws));registry.register(new EditTool(ws));
        FakeLlmClient model=new FakeLlmClient();
        model.addTurnWithTools(Collections.singletonList(call("w1","Write","{\"path\":\"交付物 (v1).md\",\"content\":\"first\"}")),"写入文件");
        model.addTurnWithTools(Collections.singletonList(call("w2","Write","{\"path\":\"交付物 (v1).md\",\"content\":\"second\"}")),"更新文件");
        model.addTurnWithTools(Collections.singletonList(call("e1","Edit","{\"path\":\"交付物 (v1).md\",\"oldString\":\"second\",\"newString\":\"final\"}")),"修订文件");
        Path output=root.resolve("交付物 (v1).md");
        model.addTurn("已写入[交付物](<"+output.toUri().toASCIIString()+">)，另附[已有资料](<"+reference.toUri().toASCIIString()+">)。"+AgentLoop.TASK_COMPLETE_MARKER);
        model.stopStatuses.add("completed");model.stopKind="task";
        AgentLoop loop=loop(root,ws,s,registry,model,ui);SessionStore store=new SessionStore(root.resolve("sessions"));loop.setSessionStore(store);ui.eventStore=store;
        try {
            loop.runUserTurn("请生成交付物");
            assertTrue("lastTurnSucceeded=false errors="+ui.errors+" warnings="+ui.warnings
                            +" toolCalls="+ui.toolCalls+" toolResults="+ui.toolResults
                            +" evidence="+s.taskState.evidence.size()+" artifacts="+s.artifactsSnapshot().size()
                            +" fileExists="+Files.isRegularFile(output)
                            +" task="+s.taskState.status+" pause="+s.taskState.pauseCode,
                    loop.lastTurnSucceeded());
            assertEquals("final",new String(Files.readAllBytes(output),StandardCharsets.UTF_8));
            List<GeneratedArtifact> artifacts=s.artifactsSnapshot();assertEquals(3,artifacts.size());
            GeneratedArtifact deliverable=artifacts.stream().filter(a->GeneratedArtifact.DELIVERABLE.equals(a.kind)).findFirst().get();
            assertEquals(output.toRealPath().toString(),deliverable.path);assertEquals("Edit",deliverable.sourceTool);
            assertEquals(Arrays.asList("deliverable","deliverable","deliverable","reference"),ui.kinds);
            assertEquals(3,ui.paths.stream().filter(p->p.equals(deliverable.path)).count());
            GeneratedArtifact record=artifacts.stream().filter(a->GeneratedArtifact.EXECUTION_RECORD.equals(a.kind)).findFirst().get();
            String report=new String(Files.readAllBytes(java.nio.file.Paths.get(record.path)),StandardCharsets.UTF_8);
            assertTrue(report.contains("](<"+output.toUri().toASCIIString()+">)"));
            assertTrue(report.contains("## 已有文件引用（不表示本任务生成）"));assertFalse(ui.paths.contains(record.path));
            assertEquals(deliverable.path,ui.preview);
            assertEquals(1,s.messages.stream().filter(Message::isUserInstruction).count());
            Session restored=new SessionStore(root.resolve("sessions")).load(s.id);assertEquals(3,restored.artifactsSnapshot().size());
            loop.restoreSession(restored);restored.artifacts.get(0).title="external mutation";
            assertNotEquals("external mutation",s.artifactsSnapshot().get(0).title);
            loop.startNewSession();assertNull(s.artifacts);assertTrue(s.artifactsSnapshot().isEmpty());
            Session legacy=Session.create(root.toString(),"legacy");loop.restoreSession(legacy);assertNull(s.artifacts);
        } finally {loop.shutdown();}
    }

    @Test public void failedWriteRetainsOnlyBackendExecutionRecord() throws Exception {
        Path root=temp.newFolder().toPath();Workspace ws=new Workspace(root.toString());
        Session s=Session.create(root.toString(),"fixture");ArtifactUi ui=new ArtifactUi(s);
        ToolRegistry registry=new ToolRegistry();registry.register(new WriteTool(ws));FakeLlmClient model=new FakeLlmClient();
        model.addTurnWithTools(Collections.singletonList(call("bad","Write","{\"path\":\"missing.txt\"}")),"尝试写入");
        AgentLoop loop=loop(root,ws,s,registry,model,ui);loop.roundLimit=1;
        try {
            loop.runUserTurn("生成文件");assertFalse(Files.exists(root.resolve("missing.txt")));
            assertEquals(1,s.artifactsSnapshot().size());assertTrue(ui.kinds.isEmpty());assertTrue(ui.paths.isEmpty());
        } finally {loop.shutdown();}
    }

    @Test public void finalAnswerWithoutToolsCanOnlyRegisterExistingReferences() throws Exception {
        Path root=temp.newFolder().toPath(),file=root.resolve("existing.txt");Files.write(file,new byte[]{1});
        Workspace ws=new Workspace(root.toString());Session s=Session.create(root.toString(),"fixture");ArtifactUi ui=new ArtifactUi(s);
        FakeLlmClient model=new FakeLlmClient();model.addTurn("[已有文件](<"+file.toUri()+">) [缺失](missing.txt) [半截](existing.txt");
        model.stopStatuses.add("completed");
        AgentLoop loop=loop(root,ws,s,new ToolRegistry(),model,ui);
        try {
            loop.runUserTurn("给出已有文件链接");assertEquals(1,s.artifactsSnapshot().size());
            assertEquals(Collections.singletonList("reference"),ui.kinds);
        } finally {loop.shutdown();}
    }

    @Test public void restoreRecoversUserVisibleLinksMissingFromOldArtifactReceipts() throws Exception {
        Path root=temp.newFolder().toPath(),report=root.resolve("历史报告.docx");Files.write(report,new byte[]{1});
        Workspace ws=new Workspace(root.toString());Session s=Session.create(root.toString(),"fixture");
        s.taskState.id="historical";
        Message finalAnswer=Message.assistant("[历史报告](<"+report.toUri().toASCIIString()+">)");
        finalAnswer.taskId="historical";finalAnswer.taskRevision=2L;s.messages.add(finalAnswer);
        AgentLoop loop=loop(root,ws,s,new ToolRegistry(),new FakeLlmClient(),new ArtifactUi(s));
        try {
            loop.restoreSession(s);
            assertEquals(1,s.artifactsSnapshot().size());
            GeneratedArtifact recovered=s.artifactsSnapshot().get(0);
            assertEquals(report.toRealPath().toString(),recovered.path);
            assertEquals(GeneratedArtifact.REFERENCE,recovered.kind);
            assertEquals("historical",recovered.taskId);
            assertEquals(Long.valueOf(2),recovered.revision);
        } finally {loop.shutdown();}
    }

    /** F2 语义翻转（原 lateSuccessfulWriteCannotRegisterInReplacedTaskOrNewRevision 特征化的是 R2/R3 根因）：
     *  同会话内任务替换/插话推进 generation → 迟到产物照常展示登记（late=true）+ UI 事件照发；
     *  门禁证据通道（stopReceipts 的 owner.current 守卫）不受影响，由门禁套件 A/B 特征化保证。 */
    @Test public void lateSuccessfulWriteRegistersAsLateDisplayInSameSession() throws Exception {
        for(boolean replaceTask:new boolean[]{true,false}) {
            Path root=temp.newFolder().toPath();Workspace ws=new Workspace(root.toString());
            Session s=Session.create(root.toString(),"fixture");ArtifactUi ui=new ArtifactUi(s);FakeLlmClient model=new FakeLlmClient();
            ToolRegistry registry=new ToolRegistry();AgentLoop[] holder=new AgentLoop[1];
            registry.register(new Tool() {
                public String name(){return "Write";}public String description(){return "late fixture";}
                public JsonObject schema(){return new JsonObject();}
                public ToolResult execute(JsonObject args)throws Exception {
                    Files.write(root.resolve("late.txt"),new byte[]{1});
                    if(replaceTask)s.taskState.begin("新任务，同会话迟到展示登记",true);
                    else holder[0].offerSupplement("补充：插话推进 generation，旧工具产物仍应展示");
                    return ToolResult.success("written");
                }
            });
            model.addTurnWithTools(Collections.singletonList(call("late","Write","{\"path\":\"late.txt\"}")),"生成旧任务文件");
            AgentLoop loop=holder[0]=loop(root,ws,s,registry,model,ui);loop.roundLimit=1;
            try {
                loop.runUserTurn("旧任务生成文件");assertTrue(Files.isRegularFile(root.resolve("late.txt")));
                String real=root.resolve("late.txt").toRealPath().toString();
                GeneratedArtifact late=s.artifactsSnapshot().stream()
                        .filter(a->GeneratedArtifact.DELIVERABLE.equals(a.kind)).findFirst().orElse(null);
                assertNotNull("同会话迟到产物应展示登记",late);
                assertEquals(real,late.path);assertTrue("迟到打标",late.late);
                assertTrue("UI 事件照发",ui.paths.contains(real));
                if(replaceTask)assertEquals("任务替换后执行记录不跨任务",1,s.artifactsSnapshot().size());
            } finally {loop.shutdown();}
        }
    }

    @Test public void orderedBatchUsesEachToolsExecutionCwdNotLaterWorkspaceCwd() throws Exception {
        Path root=temp.newFolder().toPath(),child=Files.createDirectory(root.resolve("child"));
        Workspace ws=new Workspace(root.toString());Session s=Session.create(root.toString(),"fixture");ArtifactUi ui=new ArtifactUi(s);
        ToolRegistry registry=new ToolRegistry();registry.register(new WriteTool(ws));
        registry.register(new Tool(){public String name(){return "Bash";}public String description(){return "cd fixture";}
            public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject args){ws.cd("child");return ToolResult.success("cwd changed");}});
        FakeLlmClient model=new FakeLlmClient();model.addTurnWithTools(Arrays.asList(
                call("before","Write","{\"path\":\"before.txt\",\"content\":\"real\"}"),
                call("cd","Bash","{\"command\":\"cd child\"}"),
                call("after","Write","{\"path\":\"after.txt\",\"content\":\"real\"}")),"按顺序生成");
        AgentLoop loop=loop(root,ws,s,registry,model,ui);loop.roundLimit=1;
        try {
            loop.runUserTurn("生成两个文件");
            assertTrue(ui.paths.contains(root.resolve("before.txt").toRealPath().toString()));
            assertTrue(ui.paths.contains(child.resolve("after.txt").toRealPath().toString()));
        } finally {loop.shutdown();}
    }
}
