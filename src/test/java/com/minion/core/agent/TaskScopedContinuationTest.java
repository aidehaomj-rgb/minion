package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.minion.core.storage.HistoryArchive;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class TaskScopedContinuationTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();

    private AgentLoop loop(Session session,FakeLlmClient model,ContextManager context) {
        return new AgentLoop(model,new ToolRegistry(),new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(),context,new Workspace(tmp.getRoot().getPath()),session);
    }
    private static String text(List<Message> messages) {
        StringBuilder out=new StringBuilder();for(Message m:messages)out.append(m.content).append('\n');return out.toString();
    }
    private static String longText() {return String.join("",Collections.nCopies(8000,"已完成证据"));}
    private Session session() {return Session.create(tmp.getRoot().getPath(),"fake");}
    private static final class TrackingStore extends SessionStore {
        final List<String> queries=new ArrayList<String>(), scopes=new ArrayList<String>();
        TrackingStore(Path root) {super(root);}
        @Override public List<HistoryArchive.Hit> searchHistory(String session,String query,int limit,String task) throws IOException {
            queries.add(query);scopes.add(task);return super.searchHistory(session,query,limit,task);
        }
    }

    @Test public void instructionChangeInvalidatesOnlyPlanNotEvidenceAndSurvivesRestart() throws Exception {
        Session s=session();s.taskState.begin("处理A表",false);String id=s.taskState.id;
        s.taskState.checkpoint("继续处理A表第20行","A表1-19行",Collections.emptyList());
        s.taskState.addGuidance("改为只处理B表，A表停止");
        assertEquals(2,s.taskState.revision());assertNull(s.taskState.currentNextStep());
        assertEquals("A表1-19行",s.taskState.processedRange);
        assertFalse(s.taskState.render(s.todos).contains("继续处理A表第20行"));
        SessionStore store=new SessionStore(tmp.newFolder("restart").toPath());store.save(s);
        TaskState restored=store.load(s.id).taskState;
        assertEquals(id,restored.id);assertEquals(2,restored.revision());assertNull(restored.currentNextStep());
        restored.checkpoint("核对B表第1行","B表未开始",Collections.emptyList());
        assertEquals("核对B表第1行",restored.currentNextStep());
    }

    @Test public void explicitNewTaskWinsOverBroadContinuePrefix() {
        TaskState s=new TaskState();s.begin("38块旧任务",false);String id=s.id;
        assertTrue(s.begin("继续做一个新任务：检查B表",false));assertNotEquals(id,s.id);
        assertEquals("继续做一个新任务：检查B表",s.objective);
    }

    @Test public void legacyTaskWithCorrectionsDoesNotTrustUnversionedNextStep() {
        TaskState s=new TaskState();s.objective="旧版目标";s.guidance.add("旧版最后纠正：停止A表");s.nextStep="继续读A表";
        assertEquals(2,s.revision());assertNull(s.currentNextStep());
        s.addGuidance("现在只解释B表");assertEquals(3,s.revision());
    }

    @Test public void emergencyCompactionCannotReattachSystemRepairAsLatestUserInstruction() {
        Session s=session();s.taskState.begin("只核对B表，不处理A表",false);
        Message user=Message.user(s.taskState.objective);user.userAuthored=true;user.taskId=s.taskState.id;
        s.messages.add(user);s.messages.add(Message.assistant(longText()));
        s.messages.add(Message.user("[系统提醒] 旧工具失败，请停止调用"));
        FakeLlmClient model=new FakeLlmClient();ContextManager cm=new ContextManager(1000000,.9,0,model,0);
        AgentLoop loop=loop(s,model,cm);
        try {
            loop.compactNow();
            assertTrue(s.messages.stream().anyMatch(m->m.summary));
            assertTrue(s.messages.contains(user));
            assertFalse(s.messages.stream().anyMatch(m->!m.summary&&m.content!=null&&m.content.startsWith("[系统提醒]")));
        } finally {loop.shutdown();}
    }

    @Test public void firstRequestAfterCompactionEndsWithFreshEphemeralHandoff() {
        Session s=session();s.taskState.begin("核对A表",false);
        s.taskState.checkpoint("继续A表第20行","A表前19行",Collections.emptyList());
        s.taskState.addGuidance("改为只核对B表，A表停止");
        Message user=Message.user(s.taskState.latestRequirement());user.userAuthored=true;user.taskId=s.taskState.id;
        s.messages.add(user);s.messages.add(Message.assistant(longText()));
        FakeLlmClient model=new FakeLlmClient();model.addTurn("已回答当前问题");model.stopStatuses.add("completed");
        ContextManager cm=new ContextManager(1000000,.9,0,model,0);AgentLoop loop=loop(s,model,cm);
        try {
            loop.compactNow();loop.runUserTurn("继续执行");
            List<Message> request=model.requests.get(0).messages;Message last=request.get(request.size()-1);
            assertTrue(last.content.startsWith("[系统压缩续接"));assertTrue(last.content.contains("改为只核对B表，A表停止"));
            assertFalse(last.content.contains("继续A表第20行"));assertFalse(last.userAuthored);
            assertFalse(s.messages.stream().anyMatch(m->m.content!=null&&m.content.startsWith("[系统压缩续接")));
            assertEquals(150000,cm.autoCompressAtTokens());
        } finally {loop.shutdown();}
    }

    @Test public void restoredUserAnchorPrecedesRetainedToolReceiptsInsteadOfRequestingReplay() {
        Session s=session();s.taskState.begin("只核对当前B表",false);
        Message user=Message.user(s.taskState.objective);user.userAuthored=true;user.taskId=s.taskState.id;
        s.messages.add(user);s.messages.add(Message.assistant(longText()));
        for(int i=0;i<2;i++) {
            ToolCall call=new ToolCall();call.id="recent-"+i;call.name="Read";call.arguments="{\"path\":\"b.txt\"}";
            Message assistant=Message.assistant(null);assistant.toolCalls=Collections.singletonList(call);
            s.messages.add(assistant);s.messages.add(Message.toolResult(call.id,"Read","近期工具回执"+i));
        }
        FakeLlmClient model=new FakeLlmClient();ContextManager cm=new ContextManager(1000000,.9,0,model,0);
        AgentLoop loop=loop(s,model,cm);
        try {
            loop.compactNow();
            int anchor=s.messages.indexOf(user);assertTrue(anchor>=0);
            for(int i=0;i<s.messages.size();i++)if(s.messages.get(i).role==Message.Role.TOOL)assertTrue(anchor<i);
            assertEquals(2,s.messages.stream().filter(m->m.role==Message.Role.TOOL).count());
            for(int i=0;i<s.messages.size();i++)if(s.messages.get(i).role==Message.Role.TOOL)
                assertEquals(s.messages.get(i).toolCallId,s.messages.get(i-1).toolCalls.get(0).id);
        } finally {loop.shutdown();}
    }

    @Test public void newTaskAutoCompactionDoesNotReenableOldHistorySearch() throws Exception {
        Session s=session();s.taskState.begin("处理旧任务",false);
        for(int i=0;i<6;i++) {
            Message u=Message.user("旧任务第"+i+longText());u.taskId=s.taskState.id;s.messages.add(u);
            Message a=Message.assistant("旧结果"+longText());a.taskId=s.taskState.id;s.messages.add(a);
        }
        FakeLlmClient model=new FakeLlmClient();model.addTurn("已回答当前问题");model.stopStatuses.add("completed");
        ContextManager cm=new ContextManager(1000000,.9,2,model,0);cm.setStableContextTokens(8192);
        TrackingStore store=new TrackingStore(tmp.newFolder("new-task-history").toPath());
        AgentLoop loop=loop(s,model,cm);loop.setSessionStore(store);
        try {
            loop.runUserTurn("新任务：解释B表字段");
            assertTrue("必须真实发生压缩",s.messages.stream().anyMatch(m->m.summary));
            assertTrue("新任务压缩后不应自动检索旧任务: "+store.queries,store.queries.isEmpty());
        } finally {loop.shutdown();}
    }

    @Test public void queuedOlderSupplementCannotOverrideNewerDirectInstruction() throws Exception {
        Session s=session();s.taskState.begin("旧任务",false);String oldTask=s.taskState.id;
        FakeLlmClient model=new FakeLlmClient();model.addTurn("已回答");model.stopStatuses.add("completed");
        ContextManager cm=new ContextManager(1000000,.9,12,model,0);AgentLoop loop=loop(s,model,cm);
        try {
            loop.offerSupplement("旧补充：继续检查A表");
            loop.runUserTurn("新任务：只解释B表");
            assertEquals("新任务：只解释B表",s.taskState.latestRequirement());
            assertTrue(s.taskState.guidance.isEmpty());
            assertTrue(s.messages.stream().anyMatch(m->m.supplement&&oldTask.equals(m.taskId)));
        } finally {loop.shutdown();}
    }

    @Test public void firstTaskRetainsPrequeuedConstraintsButNewestDirectInputHasPriority() {
        Session s=session();FakeLlmClient model=new FakeLlmClient();model.addTurn("已回答");model.stopStatuses.add("completed");
        AgentLoop loop=loop(s,model,new ContextManager(1000000,.9,12,model,0));
        try {
            loop.offerSupplement("列宽保持20");loop.runUserTurn("只解释B表，不做文件修改");
            assertTrue(s.taskState.guidance.contains("列宽保持20"));
            assertEquals("只解释B表，不做文件修改",s.taskState.latestRequirement());
        } finally {loop.shutdown();}
    }

    @Test public void continuationUsesLatestCorrectionRatherThanInvalidOldNextStepAsRecallQuery() throws Exception {
        Session s=session();s.taskState.begin("处理A表",false);
        s.taskState.checkpoint("继续A表第20行","A表前19行",Collections.emptyList());
        s.taskState.addGuidance("改为只核对B表，A表停止");
        FakeLlmClient model=new FakeLlmClient();model.addTurn("已回答");model.stopStatuses.add("completed");
        TrackingStore store=new TrackingStore(tmp.newFolder("correction-recall").toPath());
        AgentLoop loop=loop(s,model,new ContextManager(1000000,.9,12,model,0));loop.setSessionStore(store);
        try {
            loop.runUserTurn("继续执行");
            assertEquals("改为只核对B表，A表停止",store.queries.get(0));
            assertEquals(s.taskState.id,store.scopes.get(0));
        } finally {loop.shutdown();}
    }

    @Test public void automaticRoundContinuationUsesLatestCorrectionWithoutFabricatedUserInstruction() throws Exception {
        Session s=session();s.taskState.begin("处理A表",false);String id=s.taskState.id;
        s.taskState.addGuidance("改为只核对B表，A表停止");s.taskState.finish(false,"轮次边界","round_limit");
        FakeLlmClient model=new FakeLlmClient();model.addTurn("按最新要求回答");model.stopStatuses.add("completed");
        TrackingStore store=new TrackingStore(tmp.newFolder("auto-correction").toPath());
        AgentLoop loop=loop(s,model,new ContextManager(1000000,.9,12,model,0));loop.setSessionStore(store);
        try {
            assertTrue(loop.runContinuationTurn());assertEquals(id,s.taskState.id);
            assertEquals("改为只核对B表，A表停止",store.queries.get(0));
            assertFalse(s.messages.stream().anyMatch(m->m.userAuthored));
        } finally {loop.shutdown();}
    }

    @Test public void specificHistoryMissDoesNotPullUnrelatedOldDecisions() throws Exception {
        Session s=session();FakeLlmClient model=new FakeLlmClient();model.addTurn("未找到原文");model.stopStatuses.add("completed");
        TrackingStore store=new TrackingStore(tmp.newFolder("specific-history").toPath());
        AgentLoop loop=loop(s,model,new ContextManager(1000000,.9,12,model,0));loop.setSessionStore(store);
        try {
            loop.runUserTurn("之前海关证据编号XYZ的原话是什么？");
            assertEquals(1,store.queries.size());assertNull(store.scopes.get(0));
            assertFalse(store.queries.contains("决定"));
        } finally {loop.shutdown();}
    }

    @Test public void syntheticMessagesAreNotUserInstructionsButUserQuotedSystemTextIs() {
        assertFalse(Message.user("[系统续接] 继续旧任务").isUserInstruction());
        assertFalse(Message.user("【系统按需取回的资料】旧要求").isUserInstruction());
        Message actual=Message.user("[系统配置] 请帮我解释这段文字");actual.userAuthored=true;
        assertTrue(actual.isUserInstruction());assertFalse(Message.skill("技能").isUserInstruction());
    }

    @Test public void runtimeCompactionContinuesSameTurnWithoutHistoryRereadOrNewHumanInput() throws Exception {
        Session s=session();s.taskState.begin("核对当前阶段并解释结果",false);String task=s.taskState.id;
        s.todos.replace(java.util.Arrays.asList(new TodoList.TodoItem("阶段一",true),new TodoList.TodoItem("解释结果",false)));
        FakeLlmClient model=new FakeLlmClient();ToolCall call=new ToolCall();call.id="stage-1";call.name="StageStep";call.arguments="{}";
        model.addTurnWithTools(Collections.singletonList(call),"阶段一完成",longText());
        model.addTurn("当前结果解释完成");model.stopStatuses.add("completed");
        ContextManager cm=new ContextManager(1000000,.9,0,model,0) {
            @Override public boolean shouldCompress(List<Message> messages) {
                return messages.stream().anyMatch(m->m.role==Message.Role.TOOL&&"StageStep".equals(m.name))
                        && messages.stream().noneMatch(m->m.summary);
            }
        };
        TrackingStore store=new TrackingStore(tmp.newFolder("runtime-compact").toPath());
        ToolRegistry registry=new ToolRegistry();registry.register(new Tool() {
            public String name(){return "StageStep";}
            public String description(){return "核对阶段结果";}
            public JsonObject schema(){return new JsonObject();}
            public ToolResult execute(JsonObject args){s.todos.markDone(1);return ToolResult.success("阶段回执编号ABCDEFG：阶段一核对已通过，无需重复");}
        });
        AgentLoop loop=new AgentLoop(model,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(),cm,new Workspace(tmp.getRoot().getPath()),s);loop.setSessionStore(store);
        try {
            loop.runUserTurn("继续执行");
            assertTrue(loop.lastTurnSucceeded());assertEquals(task,s.taskState.id);
            assertEquals("压缩后应直接产生第二次模型请求，无需人工继续",2,model.requests.size());
            assertEquals("压缩本身不得发起额外原文检索",1,store.queries.size());
            Message handoff=model.requests.get(1).messages.get(model.requests.get(1).messages.size()-1);
            assertTrue(handoff.content.startsWith("[系统压缩续接"));
            assertTrue(handoff.content.contains("阶段回执编号ABCDEFG"));
            assertTrue(handoff.content.contains("[x] 阶段一"));
            assertEquals(1,s.messages.stream().filter(m->m.userAuthored).count());
            assertEquals(1,s.taskState.evidence.stream().filter(e->"StageStep".equals(e.tool)).count());
        } finally {loop.shutdown();}
    }

    @Test public void newRuntimeSupplementRefreshesRecallAndCheckpointBeforeNextModelRequest() throws Exception {
        Session s=session();s.taskState.begin("核对A表",false);
        s.taskState.checkpoint("继续A表剩余行","A表前19行",Collections.emptyList());
        FakeLlmClient model=new FakeLlmClient();ToolCall call=new ToolCall();call.id="steer";call.name="StageStep";call.arguments="{}";
        model.addTurnWithTools(Collections.singletonList(call),"当前阶段回执");model.addTurn("按新要求回答");model.stopStatuses.add("completed");
        TrackingStore store=new TrackingStore(tmp.newFolder("runtime-steering").toPath());
        AgentLoop[] holder=new AgentLoop[1];ToolRegistry registry=new ToolRegistry();registry.register(new Tool() {
            public String name(){return "StageStep";}
            public String description(){return "阶段结果";}
            public JsonObject schema(){return new JsonObject();}
            public ToolResult execute(JsonObject args){holder[0].offerSupplement("改为只核对B表，A表停止");return ToolResult.success("当前工具已完成");}
        });
        AgentLoop loop=new AgentLoop(model,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(),new ContextManager(1000000,.9,12,model,0),new Workspace(tmp.getRoot().getPath()),s);
        holder[0]=loop;loop.setSessionStore(store);
        try {
            loop.runUserTurn("继续执行");
            assertTrue("有界多查询可以包含当前步骤，但最后一次召回必须响应新纠正",store.queries.size()>=2&&store.queries.size()<=6);
            assertEquals("改为只核对B表，A表停止",store.queries.get(store.queries.size()-1));
            assertTrue(model.requests.get(1).messages.stream().anyMatch(m->m.origin==Message.Origin.TASK_CONTROL
                    &&m.content.contains("改为只核对B表，A表停止")&&!m.content.contains("下一步: 继续A表剩余行")));
            assertNull(s.taskState.currentNextStep());
            assertFalse(model.requests.get(1).messages.get(0).content.contains("继续A表剩余行"));
            assertEquals(2,s.taskState.revision());
            Session restored=store.load(s.id);
            assertEquals("改为只核对B表，A表停止",restored.taskState.latestRequirement());
        } finally {loop.shutdown();}
    }
}
