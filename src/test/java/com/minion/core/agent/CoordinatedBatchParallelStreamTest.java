package com.minion.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.StreamHandler;
import com.minion.core.llm.StopReviewResponse;
import com.minion.core.tools.TaskTool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * J 批验收：task.assignments 一次登记多条分工，依赖就绪即并行执行，且每个子 agent 各自流式输出。
 * 并发用会合屏障观测（串行执行时屏障超时→子任务报错→断言失败）；流式用主 UI 捕获的
 * 【子Agent childId】CONTENT 事件观测（两个不同 childId 都要有增量）。
 * 守卫与单条 submit 完全一致：过期修订/空批/重复 stepId 整批拒绝，不登记任何一条。
 */
public class CoordinatedBatchParallelStreamTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 共享探针：并发在途峰值 + 会合屏障（验证真并行） */
    private static final class Probe {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        volatile CyclicBarrier rendezvous;
        volatile boolean aborted;
    }

    /** 子 agent 模型桩：首次请求先会合（证明并发），再走自带回复脚本 */
    private static final class ChildClient implements LlmClient {
        final Probe probe;
        final FakeLlmClient script;
        final AtomicInteger calls = new AtomicInteger();
        ChildClient(Probe probe, FakeLlmClient script) { this.probe = probe; this.script = script; }

        @Override public void streamChat(List<Message> messages, List<JsonObject> tools,
                                         StreamHandler handler) throws LlmException {
            int inFlight = probe.active.incrementAndGet();
            probe.maxActive.getAndUpdate(m -> Math.max(m, inFlight));
            try {
                if (probe.aborted) {
                    handler.onError(new LlmException(LlmException.Type.OTHER, "子任务已被取消", false));
                    return;
                }
                CyclicBarrier barrier = probe.rendezvous;
                if (barrier != null && calls.incrementAndGet() == 1) {
                    try { barrier.await(10, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException | java.util.concurrent.BrokenBarrierException notConcurrent) {
                        probe.aborted = true;
                        handler.onError(new LlmException(LlmException.Type.OTHER, "子 agent 未能并发执行", false));
                        return;
                    }
                }
                script.streamChat(messages, tools, handler);
            } catch (InterruptedException interrupted) {
                probe.aborted = true;
                Thread.currentThread().interrupt();
                handler.onError(new LlmException(LlmException.Type.OTHER, "子任务被中断", false));
            } finally {
                probe.active.decrementAndGet();
            }
        }

        @Override public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
            return script.completeChat(messages, systemPrompt);
        }

        @Override public StopReviewResponse reviewStop(String input, String systemPrompt) throws LlmException {
            return script.reviewStop(input, systemPrompt);
        }
    }

    private static final class Rig {
        final Probe probe = new Probe();
        final AgentLoop loop;
        final java.nio.file.Path note;
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final List<String> contentIds = Collections.synchronizedList(new ArrayList<String>());

        Rig(TemporaryFolder tmp) throws Exception {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new com.minion.core.tools.example.ExampleTool());
            Workspace ws = new Workspace(tmp.getRoot().getPath());
            note = tmp.getRoot().toPath().resolve("note.txt");
            java.nio.file.Files.write(note, "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            registry.register(new com.minion.core.tools.ReadTool(ws));
            registry.register(new com.minion.core.tools.StructuredOutputTool());
            registry.register(new com.minion.core.tools.WriteTool(ws));
            loop = new AgentLoop(new FakeLlmClient(), registry, new SystemPromptBuilder(null),
                    new ConfirmGate(Config.load(tmp.getRoot().toPath()), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                    new AgentUi() {
                        public void onContent(String s) { }
                        public void onWarning(String s) { }
                        public void onError(String s) { }
                        public void onChildEvent(String id, String kind, String text, Object data) {
                            events.add(id + ":" + kind);
                            if ("CONTENT".equals(kind)) contentIds.add(id);
                        }
                    }, null, ws, Session.create(tmp.getRoot().getPath(), "test-model"));
            loop.setAgentRuntimeFactory((model, id) -> {
                FakeLlmClient script = new FakeLlmClient();
                // 子任务完成闸门要求真实操作收据：先 Read 工作区真实文件，再回结论
                com.minion.core.llm.ToolCall read = new com.minion.core.llm.ToolCall();
                read.id = "read-" + id;
                read.name = "Read";
                read.arguments = "{\"path\":\"" + note.toString().replace("\\", "\\\\") + "\"}";
                script.addTurnWithTools(Collections.singletonList(read), null);
                script.addTurn("结论-" + id);
                return new AgentRuntime(new ChildClient(probe, script), "test-model", 60000, 0.8, registry, ws, false);
            });
            loop.roundLimit = 16;
            loop.session().taskState.begin("J 批并行验收测试", true);
        }

        long revision() { return loop.session().taskState.revision(); }

        JsonObject assignment(String stepId, String mode) {
            JsonObject a = new JsonObject();
            a.addProperty("stepId", stepId);
            a.addProperty("description", "只读调查 " + stepId + "：列出工作区文件并返回结论");
            a.addProperty("mode", mode);
            JsonArray scope = new JsonArray(); scope.add(".");
            a.add("scope", scope);
            a.add("dependsOn", new JsonArray());
            a.addProperty("acceptance", "返回结论与证据路径");
            return a;
        }

        JsonObject batch(long revision, JsonObject... items) {
            JsonObject args = new JsonObject();
            args.addProperty("revision", revision);
            JsonArray arr = new JsonArray();
            for (JsonObject i : items) arr.add(i);
            args.add("assignments", arr);
            return args;
        }

        boolean awaitReturned(int n, long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < end) {
                int done = 0;
                synchronized (loop.session().taskState) {
                    for (TaskState.Child c : loop.session().taskState.children)
                        if ("returned_needs_review".equals(c.status)) done++;
                }
                if (done >= n) return true;
                Thread.sleep(50);
            }
            return false;
        }

        int childCount() {
            synchronized (loop.session().taskState) { return loop.session().taskState.children.size(); }
        }

        String childStatuses() {
            StringBuilder sb = new StringBuilder();
            synchronized (loop.session().taskState) {
                for (TaskState.Child c : loop.session().taskState.children)
                    sb.append(c.id).append('=').append(c.status).append('(')
                      .append(c.result == null ? "" : c.result.substring(0, Math.min(200, c.result.length()))).append(") ");
            }
            return sb.append(" events=").append(events)
                    .append(" coord=").append(loop.controlChildren(statusArgs()).output).toString();
        }

        JsonObject statusArgs() {
            JsonObject a = new JsonObject();
            a.addProperty("action", "child_status");
            return a;
        }
    }

    @Test public void batchIndependentChildrenRunConcurrentlyWithPerChildStreams() throws Exception {
        Rig rig = new Rig(tmp);
        rig.probe.rendezvous = new CyclicBarrier(2);
        ToolResult r = rig.loop.submitCoordinatedChildBatch(
                rig.batch(rig.revision(), rig.assignment("p1", "explore"), rig.assignment("p2", "explore")));
        assertTrue(r.output, r.ok);
        assertTrue("两个子 agent 都应返回待复核: " + rig.childStatuses(), rig.awaitReturned(2, 20000));
        assertFalse("会合屏障超时说明是串行执行", rig.probe.aborted);
        assertEquals("两个子 agent 必须同时在途", 2, rig.probe.maxActive.get());
        Set<String> streamed = new HashSet<String>(rig.contentIds);
        assertEquals("每个子 agent 都要有自己的流式增量", 2, streamed.size());
        List<String> ev = new ArrayList<String>(rig.events);
        int firstDone = -1;
        for (int i = 0; i < ev.size(); i++) if (ev.get(i).endsWith(":DONE")) { firstDone = i; break; }
        assertTrue("应有 DONE 事件", firstDone >= 0);
        int starts = 0;
        for (int i = 0; i < firstDone; i++) if (ev.get(i).endsWith(":START")) starts++;
        assertEquals("两个 START 都应在任一 DONE 之前（并行登记并行执行）", 2, starts);
    }

    @Test public void staleRevisionRejectsWholeBatch() throws Exception {
        Rig rig = new Rig(tmp);
        ToolResult r = rig.loop.submitCoordinatedChildBatch(
                rig.batch(rig.revision() + 1, rig.assignment("p1", "explore"), rig.assignment("p2", "explore")));
        assertFalse(r.ok);
        assertTrue(r.output.contains("revision"));
        assertEquals("整批拒绝：一条都不能登记", 0, rig.childCount());
        assertEquals(0, rig.probe.maxActive.get());
    }

    @Test public void emptyAssignmentsRejected() throws Exception {
        Rig rig = new Rig(tmp);
        ToolResult r = rig.loop.submitCoordinatedChildBatch(rig.batch(rig.revision()));
        assertFalse(r.ok);
        assertEquals(0, rig.childCount());
    }

    @Test public void duplicateStepIdRejectsWholeBatch() throws Exception {
        Rig rig = new Rig(tmp);
        ToolResult r = rig.loop.submitCoordinatedChildBatch(
                rig.batch(rig.revision(), rig.assignment("same", "explore"), rig.assignment("same", "explore")));
        assertFalse(r.ok);
        assertTrue(r.output.contains("stepId"));
        assertEquals("整批原子：重复 stepId 时一条都不登记", 0, rig.childCount());
    }

    @Test public void nonObjectElementRejected() throws Exception {
        Rig rig = new Rig(tmp);
        JsonObject args = rig.batch(rig.revision());
        args.getAsJsonArray("assignments").add("不是对象");
        ToolResult r = rig.loop.submitCoordinatedChildBatch(args);
        assertFalse(r.ok);
        assertEquals(0, rig.childCount());
    }

    @Test public void oversizedBatchRejected() throws Exception {
        Rig rig = new Rig(tmp);
        JsonObject[] items = new JsonObject[65];
        for (int i = 0; i < items.length; i++) items[i] = rig.assignment("s" + i, "explore");
        ToolResult r = rig.loop.submitCoordinatedChildBatch(rig.batch(rig.revision(), items));
        assertFalse(r.ok);
        assertEquals(0, rig.childCount());
    }

    @Test public void singleStructuredSubmitStillWorks() throws Exception {
        Rig rig = new Rig(tmp);
        JsonObject single = rig.assignment("solo", "explore");
        single.addProperty("revision", rig.revision());
        ToolResult r = rig.loop.submitCoordinatedChild(single);
        assertTrue(r.output, r.ok);
        assertTrue(rig.childStatuses(), rig.awaitReturned(1, 20000));
        assertEquals(1, rig.probe.maxActive.get());
    }

    @Test public void taskToolBatchSelfHealsWeakModelVariants() throws Exception {
        Rig rig = new Rig(tmp);
        // 弱模型常见变体：任务说明写在 prompt、mode 同义词、scope 传字符串、stepId 含空格、
        // 省略 acceptance/dependsOn/顶层 revision —— 与单条结构化入口同一套自愈
        JsonObject element = new JsonObject();
        element.addProperty("stepId", "extract 1");
        element.addProperty("prompt", "调查工作区文件并返回结论");
        element.addProperty("mode", "调查");
        element.addProperty("scope", ".");
        JsonObject args = new JsonObject();
        JsonArray arr = new JsonArray(); arr.add(element);
        args.add("assignments", arr);
        ToolResult r = new TaskTool(rig.loop).execute(args);
        assertTrue(r.output, r.ok);
        assertTrue(rig.childStatuses(), rig.awaitReturned(1, 20000));
        synchronized (rig.loop.session().taskState) {
            assertEquals("extract-1", rig.loop.session().taskState.children.get(0).coordinatedStep);
        }
    }

    @Test public void dispatcherBatchRoutesToSubmitBatch() {
        final List<JsonObject> seen = new ArrayList<JsonObject>();
        TaskTool tool = new TaskTool(new TaskTool.Dispatcher() {
            @Override public ToolResult submit(JsonObject args) { return ToolResult.error("单条入口不应被调用"); }
            @Override public ToolResult submitBatch(JsonObject args) { seen.add(args); return ToolResult.success("batch-ok"); }
        });
        JsonObject args = new JsonObject();
        JsonArray arr = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("stepId", "d1");
        a.addProperty("description", "只读调查");
        arr.add(a);
        args.add("assignments", arr);
        ToolResult r = tool.execute(args);
        assertTrue(r.output, r.ok);
        assertEquals("batch-ok", r.output);
        assertEquals(1, seen.size());
    }

    @Test public void dispatcherWithoutBatchSupportFailsLoudly() {
        TaskTool tool = new TaskTool(args -> ToolResult.success("单条"));
        JsonObject args = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(new JsonObject());
        args.add("assignments", arr);
        ToolResult r = tool.execute(args);
        assertFalse(r.ok);
        assertTrue(r.output.contains("不支持批量登记"));
    }
}