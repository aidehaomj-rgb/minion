package com.minion.core.agent;

import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class TaskStateTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void continuationAndGuidanceNeverReplaceOriginalGoal() {
        TaskState state=new TaskState();
        state.begin("核查A表全部行并生成Excel",false);
        String id=state.id;
        TaskState.Child c=state.startChild("仅核查1-20行");
        state.addGuidance("输出列宽20"); state.progress("已核查前20行");
        state.finishChild(c.id,"命中2行",true,"/tmp/child.json");
        state.begin("继续执行",false);
        assertEquals(id,state.id); assertEquals("核查A表全部行并生成Excel",state.objective);
        String anchor=state.render(new TodoList());
        assertTrue(anchor.contains("输出列宽20")); assertTrue(anchor.contains("命中2行"));
        assertTrue(anchor.contains("returned_needs_review"));
        state.begin("继续执行",true);
        assertNotEquals(id,state.id); assertEquals("继续执行",state.objective);
        assertFalse(state.render(new TodoList()).contains("命中2行"));
    }
    @Test public void migrationSkipsSteeringAndInternalMessages() {
        TaskState state=new TaskState();
        state.restoreFromHistory(Arrays.asList(Message.user("生成报表"),Message.userSupplement("加一列"),
                Message.skill("旧技能"),Message.user("[系统提醒] 继续"),Message.user("继续执行")));
        assertEquals("生成报表",state.objective);
        assertTrue(state.guidance.contains("加一列"));
    }
    @Test public void repeatedResumeDoesNotEvictRealRequirements() {
        TaskState state=new TaskState(); state.begin("生成报表",false);
        state.addGuidance("列宽20，保留原始编号");
        for(int i=0;i<20;i++) state.begin("继续执行",false);
        assertEquals(1,state.guidance.size());
        assertTrue(state.render(new TodoList()).contains("保留原始编号"));
    }
    @Test public void stateAndQueuedImagesSurviveRestart() throws Exception {
        Session s=Session.create(tmp.getRoot().toString(),"fake");
        s.taskState.begin("当前目标",false);
        s.taskState.addGuidance("新的约束");
        SessionStore store=new SessionStore(tmp.newFolder("sessions").toPath()); store.save(s);
        Session.QueuedTask q=new Session.QueuedTask(); q.text="下个任务"; q.guidance=true;
        com.minion.core.llm.ImagePart image=new com.minion.core.llm.ImagePart();
        image.mime="image/png"; image.base64="abc"; q.images.add(image);
        s.queuedTasks.add(q); store.saveQueue(s);
        Session loaded=store.load(s.id);
        assertEquals("当前目标",loaded.taskState.objective);
        assertEquals("下个任务",loaded.queuedTasks.get(0).text);
        assertEquals(1,loaded.queuedTasks.get(0).images.size());
        s.queuedTasks.clear(); store.saveQueue(s);
        assertTrue(store.load(s.id).queuedTasks.isEmpty());
    }
    @Test public void largeOutputStoredWithoutLosingEvidence() throws Exception {
        StringBuilder b=new StringBuilder(); for(int i=0;i<2000;i++) b.append("证据行").append(i).append('\n');
        ToolOutputStore store=new ToolOutputStore(tmp.getRoot().toString(),"safe-scope");
        String preview=store.forContext(b.toString());
        assertTrue(preview.length()<6500); assertTrue(preview.contains("完整内容已保存"));
        java.nio.file.Path dir=tmp.getRoot().toPath().resolve(".minion/tool-results/safe-scope");
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.list(dir)) {
            java.nio.file.Path file=files.findFirst().get();
            assertEquals(b.toString(),new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            assertTrue(preview.contains(file.toString()));
        }
        assertEquals("短结果",store.forContext("短结果"));
    }
    @Test public void stopDecisionAndReferenceContextSurviveRestart() throws Exception {
        Session s=Session.create(tmp.getRoot().toString(),"fake");s.taskState.begin("按照这个修改",false);
        s.taskState.bindReferenceHistory(Arrays.asList(Message.user("设计结束门禁"),Message.assistant("隔离审核当前目标和实际证据")));
        StopGate.Decision decision=new StopGate.Decision();decision.taskId=s.taskState.id;decision.status="continue";decision.kind="task";
        decision.reason="缺少最终验收";decision.version="version";decision.noProgress=2;s.taskState.recordStop(decision);
        TaskState.Child child=s.taskState.startChild("局部核验");s.taskState.childStop(child.id,decision);
        SessionStore store=new SessionStore(tmp.newFolder("stop-sessions").toPath());store.save(s);Session loaded=store.load(s.id);
        assertEquals("continue",loaded.taskState.stopDecision.status);assertEquals(2,loaded.taskState.stopDecision.noProgress);
        assertTrue(loaded.taskState.referenceContext.get(1).contains("隔离审核"));
        assertEquals("continue",loaded.taskState.child(child.id).stopDecision.status);
        loaded.taskState.recoverAfterRestart();
        assertEquals("paused",loaded.taskState.status);
        loaded.taskState.begin("新的独立任务",true);assertNull(loaded.taskState.stopDecision);assertTrue(loaded.taskState.referenceContext.isEmpty());
    }
    @Test public void concurrentChildLedgerKeepsAllAssignments() throws Exception {
        TaskState s=new TaskState(); s.begin("主任务",false);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<?>> jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<20;i++) { final int n=i; jobs.add(pool.submit(() -> {
                TaskState.Child c=s.startChild("分工"+n); s.finishChild(c.id,"结果"+n,true,"record"+n);
            })); }
            for(java.util.concurrent.Future<?> job:jobs) job.get();
            assertEquals(20,s.children.size());
            java.util.Set<String> ids=new java.util.HashSet<String>();
            for(TaskState.Child c:s.children) { ids.add(c.id); assertEquals("returned_needs_review",c.status); }
            assertEquals(20,ids.size());
        } finally { pool.shutdownNow(); }
    }
}
