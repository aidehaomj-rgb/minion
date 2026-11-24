package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.StreamHandler;
import com.minion.core.llm.StopReviewResponse;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.Assert.*;

/**
 * §8.1 后台子 agent 验收（2026-11-23 修复）：task 的 run_in_background 必须真正执行子 agent。
 * 此前只登记不执行——子任务永远停在 running，child_wait 等不到结果，僵尸记录还会挡住后续派发。
 *
 * <p>两个模型桩互相独立：主 agent 用 {@link MainClient}（脚本里可以按运行时状态生成工具调用，
 * 例如复核一个 id 只有派发后才知道的子任务）；子 agent 每个各拿一份 {@link ChildClient}
 * （回复脚本从头出牌，并发在途/请求数记到共享的 {@link ChildProbe}）。
 */
public class BackgroundChildAgentTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 主 agent 模型桩：脚本按序出牌，最后一幕一直重复；非流式请求（结束核验）交给既有夹具 */
    private static final class MainClient implements LlmClient {
        final FakeLlmClient peer = new FakeLlmClient();
        final java.util.ArrayDeque<Object> turns = new java.util.ArrayDeque<Object>();
        final AtomicInteger requests = new AtomicInteger();
        volatile AgentLoop loop;

        void text(String text) { turns.addLast(text); }

        void fixed(List<ToolCall> calls) { turns.addLast(calls); }

        void dynamic(Function<AgentLoop, List<ToolCall>> turn) { turns.addLast(turn); }

        @Override public void streamChat(List<Message> messages, List<JsonObject> tools,
                                         StreamHandler handler) throws LlmException {
            requests.incrementAndGet();
            // 最后一幕一直重复（不轮转：轮转会把开头的派发再演一遍）
            Object turn = turns.peekFirst();
            if (turn == null) throw new LlmException(LlmException.Type.OTHER, "主模型脚本为空", false);
            if (turns.size() > 1) turns.removeFirst();
            Usage usage = new Usage();
            usage.inputTokens = 10;
            usage.outputTokens = 5;
            List<ToolCall> calls = Collections.emptyList();
            if (turn instanceof String) {
                handler.onContent((String) turn);
            } else if (turn instanceof List) {
                calls = (List<ToolCall>) turn;
            } else {
                calls = ((Function<AgentLoop, List<ToolCall>>) turn).apply(loop);
            }
            handler.onFinish(calls.isEmpty() ? "stop" : "tool_calls", usage, calls);
        }

        @Override public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
            return peer.completeChat(messages, systemPrompt);
        }

        @Override public StopReviewResponse reviewStop(String input, String systemPrompt) throws LlmException {
            return peer.reviewStop(input, systemPrompt);
        }
    }

    /** 所有子 agent 共享的观测与控制：并发在途、请求计数、慢速/阻塞/会合 */
    private static final class ChildProbe {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        /** 每次请求的固定耗时（模拟慢模型，让并发/串行关系可观测） */
        volatile long holdMillis;
        /** 非 null：请求先在此会合（验证并发）；超时放行，串行执行时不会卡死 */
        volatile CyclicBarrier rendezvous;
        volatile CountDownLatch blockUntil;
        volatile boolean aborted;
    }

    /** 单个子 agent 的模型桩：自带一份回复脚本，计数记到共享探针 */
    private static final class ChildClient implements LlmClient {
        final ChildProbe probe;
        final FakeLlmClient script;

        ChildClient(ChildProbe probe, FakeLlmClient script) { this.probe = probe; this.script = script; }

        @Override public void streamChat(List<Message> messages, List<JsonObject> tools,
                                         StreamHandler handler) throws LlmException {
            ChildProbe probe = this.probe;
            int inFlight = probe.active.incrementAndGet();
            probe.maxActive.getAndUpdate(m -> Math.max(m, inFlight));
            probe.requests.incrementAndGet();
            try {
                if (probe.aborted) {
                    handler.onError(new LlmException(LlmException.Type.OTHER, "子任务已被取消", false));
                    return;
                }
                CountDownLatch gate = probe.blockUntil;
                if (gate != null) gate.await();
                CyclicBarrier barrier = probe.rendezvous;
                if (barrier != null) {
                    try { barrier.await(10, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException | java.util.concurrent.BrokenBarrierException notConcurrent) {
                        probe.aborted = true;   // 没等到另一个子 agent：后面的请求直接报错，不拖长测试
                        handler.onError(new LlmException(LlmException.Type.OTHER, "子 agent 未能并发执行", false));
                        return;
                    }
                }
                if (probe.holdMillis > 0) Thread.sleep(probe.holdMillis);
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
        final MainClient main = new MainClient();
        final ChildProbe child = new ChildProbe();
        /** 每个子 agent 的回复脚本：派发时（带 childId）对新的夹具重新套用，所以每个子 agent 都从头出牌 */
        BiConsumer<String, FakeLlmClient> childScript = (id, script) -> { };
        final AgentLoop loop;

        final List<String> childUiEvents = Collections.synchronizedList(new ArrayList<String>());

        Rig(TemporaryFolder tmp) {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new com.minion.core.tools.example.ExampleTool());
            Workspace ws = new Workspace(tmp.getRoot().getPath());
            registry.register(new com.minion.core.tools.ReadTool(ws));
            registry.register(new com.minion.core.tools.StructuredOutputTool());
            registry.register(new com.minion.core.tools.WriteTool(ws));
            loop = new AgentLoop(main, registry, new SystemPromptBuilder(null),
                    new ConfirmGate(Config.load(tmp.getRoot().toPath()), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                    new AgentUi() {
                        public void onContent(String s) { }
                        public void onWarning(String s) { }
                        public void onError(String s) { }
                        public void onChildEvent(String id, String kind, String text, Object data) {
                            childUiEvents.add(id + ":" + kind + ":" + (text == null ? "" : text));
                        }
                    }, null, ws, Session.create(tmp.getRoot().getPath(), "test-model"));
            main.loop = loop;
            main.peer.stopKind = "task";
            main.peer.stopStatuses.add("completed");
            loop.setAgentRuntimeFactory((model, id) -> {
                FakeLlmClient script = new FakeLlmClient();
                childScript.accept(id, script);
                return new AgentRuntime(new ChildClient(child, script), "test-model", 60000, 0.8, registry, ws, false);
            });
            loop.roundLimit = 16;
        }

        /** 给之后派发的每个子 agent 追加回复脚本 */
        void childReplies(BiConsumer<String, FakeLlmClient> replies) { childScript = childScript.andThen(replies); }

        ToolCall call(String name, String arguments) {
            ToolCall call = new ToolCall();
            call.id = name.toLowerCase() + "-" + System.nanoTime();
            call.name = name;
            call.arguments = arguments;
            return call;
        }

        /** 主 agent 派发一个后台子任务 */
        void dispatch(String description, String extra) {
            main.fixed(Collections.singletonList(call("task",
                    "{\"description\":\"" + description + "\",\"run_in_background\":true" + extra + "}")));
        }

        /** 主 agent 一次复核全部“已返回待复核”的子任务（childId 只有派发后才知道，所以运行时生成） */
        void reviewAllPending() {
            main.dynamic(l -> {
                List<ToolCall> calls = new ArrayList<ToolCall>();
                for (TaskState.Child c : l.session().taskState.children)
                    if ("returned_needs_review".equals(c.status))
                        calls.add(call("AgentControl",
                                "{\"action\":\"review\",\"childId\":\"" + c.id + "\",\"note\":\"已核对子任务结论\"}"));
                if (calls.isEmpty()) calls.add(call("AgentControl", "{\"action\":\"child_status\"}"));
                return calls;
            });
        }

        ToolCall write(String path) {
            return call("Write", "{\"path\":\"" + path + "\",\"content\":\"主任务写入\"}");
        }

        String firstToolResultContaining(String text) {
            for (Message m : loop.messages())
                if (m.role == Message.Role.TOOL && m.content != null && m.content.contains(text)) return m.content;
            return null;
        }

        /** 某个工具在主对话里的全部结果（按先后顺序） */
        List<Message> toolResults(String tool) {
            List<Message> out = new ArrayList<Message>();
            for (Message m : loop.messages()) if (m.role == Message.Role.TOOL && tool.equals(m.name)) out.add(m);
            return out;
        }

        /** 含指定文字的后台返回通知是否已注入主对话 */
        boolean hasNotice(String text) {
            for (Message m : loop.messages())
                if (m.content != null && m.content.startsWith("[后台子Agent返回]") && m.content.contains(text)) return true;
            return false;
        }

        /** 该子任务的返回通知是否已注入主对话 */
        boolean hasNotice(TaskState.Child child) {
            return hasNotice("childId=" + child.id + " ");
        }

        List<TaskState.Child> children() {
            return new ArrayList<TaskState.Child>(loop.session().taskState.children);
        }

        /** 等全部子任务离开 running（有界轮询） */
        void awaitChildrenSettled(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                boolean running = false;
                for (TaskState.Child c : children()) if ("running".equals(c.status)) running = true;
                if (!running) return;
                Thread.sleep(50);
            }
        }

        /** 失败时的现场：子任务状态 + 主对话里的工具结果 */
        String diagnose() {
            StringBuilder out = new StringBuilder("\n子任务:");
            for (TaskState.Child c : children())
                out.append("\n  ").append(c.id, 0, 8).append(' ').append(c.mode).append(' ').append(c.status)
                        .append(" 分工=").append(c.assignment).append(" 结果=").append(c.result);
            out.append("\n主对话工具结果:");
            for (Message m : loop.messages())
                if (m.role == Message.Role.TOOL)
                    out.append("\n  ").append(m.name).append(": ").append(m.content);
            return out.toString();
        }
    }

    private Rig rig;

    @After public void cleanup() { if (rig != null) rig.loop.shutdown(); }

    private Rig newRig() { rig = new Rig(tmp); return rig; }

    /** 后台子 agent 真正执行：子 agent 发出模型请求、结果注入主对话、复核后回合正常完成 */
    @Test public void runInBackgroundActuallyExecutesAndInjectsResult() throws Exception {
        Rig r = newRig();
        r.childReplies((id, script) -> script.addTurn("子任务结论：共 3 个要点。" + AgentLoop.TASK_COMPLETE_MARKER));
        r.dispatch("调研文档结构", ",\"mode\":\"explore\"");
        r.main.text("已派发，等待结果");
        r.reviewAllPending();
        r.main.text("全部完成。" + AgentLoop.TASK_COMPLETE_MARKER);
        r.loop.runUserTurn("调研");

        assertEquals("后台子 agent 应真正执行（发出 1 次模型请求）" + r.diagnose(), 1, r.child.requests.get());
        TaskState.Child child = r.children().get(0);
        assertEquals("explore", child.mode);
        String launch = r.firstToolResultContaining("childId=");
        assertNotNull(launch);
        assertTrue("登记回执要给出跟踪方式", launch.contains("child_status") && launch.contains("child_wait"));
        assertTrue("返回结果应注入主对话", r.hasNotice("子任务结论：共 3 个要点"));
        assertEquals("主 agent 复核后子任务应记为 reviewed", "reviewed", child.status);
        assertTrue("复核后回合应正常完成", r.loop.lastTurnSucceeded());
    }

    /** 并行：两个只读后台子 agent 同时在跑（在途并发峰值 =2），都返回并注入主对话 */
    @Test public void twoBackgroundExploreChildrenRunConcurrently() throws Exception {
        Rig r = newRig();
        r.child.rendezvous = new CyclicBarrier(2);   // 两个子 agent 必须同时在途才能会合
        r.childReplies((id, script) -> script.addTurn("子任务完成。" + AgentLoop.TASK_COMPLETE_MARKER));
        r.dispatch("调研模块 A", ",\"mode\":\"explore\"");
        r.dispatch("调研模块 B", ",\"mode\":\"explore\"");
        r.main.text("等待两个子任务");
        r.reviewAllPending();
        r.main.text("全部完成。" + AgentLoop.TASK_COMPLETE_MARKER);
        r.loop.runUserTurn("并行调研");

        assertEquals("两个子 agent 各应只发 1 次模型请求" + r.diagnose(), 2, r.child.requests.get());
        assertEquals("两个只读子 agent 应同时在跑，实际并发峰值 " + r.child.maxActive.get(),
                2, r.child.maxActive.get());
        List<TaskState.Child> children = r.children();
        assertEquals(2, children.size());
        for (TaskState.Child c : children) {
            assertEquals("explore", c.mode);
            assertTrue("子任务 " + c.id + " 的结果应注入主对话", r.hasNotice(c));
            assertEquals("复核后应记为 reviewed", "reviewed", c.status);
        }
        assertTrue("复核后回合应正常完成", r.loop.lastTurnSucceeded());
    }

    /** 写入型后台子 agent 没有写范围隔离：彼此串行，执行期间主任务不得写入，返回后恢复 */
    @Test public void backgroundWorkersAreSerializedAndBlockMainWrites() throws Exception {
        Rig r = newRig();
        r.child.holdMillis = 600;
        r.childReplies((id, script) -> {
            ToolCall write = new ToolCall();     // 写入型子 agent 必须有真实操作，只回文字不算交付
            write.id = "child-write";            // 各写各的文件：子 agent 改已有文件必须先 Read
            write.name = "Write";
            write.arguments = "{\"path\":\"child-" + id + ".txt\",\"content\":\"子任务写入\"}";
            script.addTurnWithTools(Collections.singletonList(write), null);
            script.addTurn("写入完成。" + AgentLoop.TASK_COMPLETE_MARKER);
        });
        r.dispatch("整理目录 A", "");     // 省略 mode = worker（可写）
        r.dispatch("整理目录 B", "");
        r.main.fixed(Collections.singletonList(r.write("during.txt")));   // 子任务执行期间
        r.main.fixed(Collections.singletonList(r.call("AgentControl", "{\"action\":\"child_status\"}")));
        r.main.text("等待");
        r.reviewAllPending();
        r.main.fixed(Collections.singletonList(r.write("after.txt")));    // 子任务返回之后
        r.main.text("全部完成。" + AgentLoop.TASK_COMPLETE_MARKER);
        r.loop.runUserTurn("整理");

        assertEquals("每个子 agent 应发 2 次模型请求（写入 + 交付）" + r.diagnose(), 4, r.child.requests.get());
        assertEquals("写入型子 agent 同时只能跑 1 个", 1, r.child.maxActive.get());
        // child_status 的结果保持合法 JSON，并如实列出后台子任务（一个在跑、一个排队）
        JsonObject listed = null;
        for (Message m : r.toolResults("AgentControl"))
            if (m.content.contains("backgroundChildren")) listed = JsonParser.parseString(m.content).getAsJsonObject();
        assertNotNull("child_status 应返回后台子任务情况" + r.diagnose(), listed);
        java.util.Set<String> states = new java.util.HashSet<String>();
        for (com.google.gson.JsonElement row : listed.getAsJsonArray("backgroundChildren"))
            states.add(row.getAsJsonObject().get("state").getAsString());
        assertEquals(new java.util.HashSet<String>(java.util.Arrays.asList("running", "queued")), states);
        List<TaskState.Child> children = r.children();
        assertEquals(2, children.size());
        for (TaskState.Child c : children) {
            assertEquals("worker", c.mode);
            assertTrue("子任务 " + c.id + " 自己的写入应落盘",
                    new File(tmp.getRoot(), "child-" + c.id + ".txt").exists());
            assertTrue("子任务 " + c.id + " 的结果应注入主对话", r.hasNotice(c));
            assertEquals("复核后应记为 reviewed", "reviewed", c.status);
        }
        List<Message> writes = r.toolResults("Write");
        assertEquals("主任务的两次 Write 都应有工具结果", 2, writes.size());
        assertTrue("子任务执行期间主任务写入应被拦下: " + writes.get(0).content,
                writes.get(0).content.contains("后台写入型子任务"));
        assertFalse("执行期间不得落盘", new File(tmp.getRoot(), "during.txt").exists());
        assertTrue("子任务返回后主任务写入应恢复: " + writes.get(1).content,
                new File(tmp.getRoot(), "after.txt").exists());
        assertTrue("复核后回合应正常完成", r.loop.lastTurnSucceeded());
    }

    /** 用户停止：后台子 agent 被中断并落账，不留永远 running 的僵尸记录 */
    @Test public void interruptStopsBackgroundChildrenWithoutZombie() throws Exception {
        Rig r = newRig();
        r.child.blockUntil = new CountDownLatch(1);
        r.childReplies((id, script) -> script.addTurn("不会被用到"));
        r.dispatch("长时间调研", ",\"mode\":\"explore\"");
        r.main.text("等待");
        final Throwable[] failure = new Throwable[1];
        Thread turn = new Thread(() -> {
            try { r.loop.runUserTurn("开始"); } catch (Throwable t) { failure[0] = t; }
        }, "test-main-turn");
        turn.start();
        long deadline = System.currentTimeMillis() + 10000;
        while (r.child.active.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals("子 agent 应已在执行", 1, r.child.active.get());

        r.loop.interrupt();
        turn.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse("中断后主回合应结束", turn.isAlive());
        if (failure[0] != null) throw new AssertionError(failure[0]);
        r.awaitChildrenSettled(10000);

        TaskState.Child child = r.children().get(0);
        assertNotEquals("中断后不能留下 running 的僵尸记录", "running", child.status);
        assertFalse("被取消的子任务不应作为“已返回”注入对话", r.hasNotice("不会被用到"));
        assertTrue(r.loop.wasInterrupted());
    }

    /** 会话关闭：后台池停止，子任务不再占用线程 */
    @Test public void shutdownStopsBackgroundChildren() throws Exception {
        Rig r = newRig();
        r.child.blockUntil = new CountDownLatch(1);
        r.childReplies((id, script) -> script.addTurn("不会被用到"));
        r.dispatch("长时间调研", ",\"mode\":\"explore\"");
        r.main.text("等待");
        Thread turn = new Thread(() -> { try { r.loop.runUserTurn("开始"); } catch (Throwable ignored) { } },
                "test-main-turn-shutdown");
        turn.start();
        long deadline = System.currentTimeMillis() + 10000;
        while (r.child.active.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, r.child.active.get());

        r.loop.shutdown();
        turn.join(TimeUnit.SECONDS.toMillis(10));
        r.loop.awaitToolsTerminated(5000);
        r.awaitChildrenSettled(10000);
        assertNotEquals("running", r.children().get(0).status);
        rig = null;   // 已关闭，cleanup 不再重复 shutdown
    }

    /** 结构化入口自愈：只给 description/prompt/mode/scope，缺的 stepId/dependsOn/acceptance/revision 自动补齐 */
    @Test public void structuredEntrySelfHealsMissingKeys() throws Exception {
        Rig r = newRig();
        r.loop.roundLimit = 6;
        r.childReplies((id, script) -> script.addTurn("结构化子任务完成。" + AgentLoop.TASK_COMPLETE_MARKER));
        r.main.fixed(Collections.singletonList(r.call("task",
                "{\"description\":\"调研模块 A\",\"prompt\":\"返回要点\",\"mode\":\"explore\",\"scope\":[\".\"]}")));
        for (int i = 0; i < 6; i++) r.main.text("等待");
        r.loop.runUserTurn("结构化调研");

        String result = r.firstToolResultContaining("已登记排队");
        assertNotNull("缺省的可选键应自动补齐而不是拒绝；首个工具结果: "
                + r.firstToolResultContaining("子Agent"), result);
        TaskState.Child child = null;
        for (TaskState.Child c : r.children()) if (c.coordinatedStep != null) child = c;
        assertNotNull("应进入协调队列", child);
        assertEquals("explore", child.mode);
    }

    /** 弱模型常见写法：任务说明写在 prompt、mode 用同义词 read——照常派发为只读子 agent */
    @Test public void promptOnlyCallWithModeSynonymIsAccepted() throws Exception {
        Rig r = newRig();
        r.childReplies((id, script) -> script.addTurn("要点已完成并核对。" + AgentLoop.TASK_COMPLETE_MARKER));
        r.main.fixed(Collections.singletonList(r.call("task",
                "{\"prompt\":\"调研并返回要点\",\"mode\":\"read\"}")));
        r.main.text("全部完成。" + AgentLoop.TASK_COMPLETE_MARKER);
        r.loop.runUserTurn("调研");

        assertTrue("同步子 agent 应已执行，实际请求 " + r.child.requests.get(), r.child.requests.get() >= 1);
        TaskState.Child child = r.children().get(0);
        assertEquals("read 应归一为 explore", "explore", child.mode);
        assertTrue(child.assignment.contains("调研并返回要点"));
        assertNotNull(r.firstToolResultContaining("子Agent ID:"));
    }

    /** 实时可视性：后台子任务派发即推送 START（且不与 SubAgentLoop 重复），过程与 DONE 完整送达 AgentUi */
    @Test public void backgroundChildEmitsLiveUiEventsWithoutDuplicateStart() throws Exception {
        Rig r = newRig();
        r.childReplies((id, script) -> script.addTurn("实时提取完毕：23条命中。" + AgentLoop.TASK_COMPLETE_MARKER));
        r.dispatch("提取第1块分类结果", ",\"mode\":\"explore\"");
        r.main.text("等待子任务完成");
        r.reviewAllPending();
        r.main.text("全部交付完成。" + AgentLoop.TASK_COMPLETE_MARKER);
        r.loop.runUserTurn("提取全表");

        TaskState.Child child = r.children().get(0);
        int startCount = 0, contentCount = 0, doneCount = 0;
        synchronized (r.childUiEvents) {
            for (String ev : r.childUiEvents) {
                if (ev.startsWith(child.id + ":START:")) startCount++;
                if (ev.startsWith(child.id + ":CONTENT:")) contentCount++;
                if (ev.startsWith(child.id + ":DONE:")) doneCount++;
            }
        }
        assertEquals("每个后台子Agent应恰好发出 1 次 START 事件: " + r.childUiEvents, 1, startCount);
        assertTrue("后台子Agent流式输出应实时送达 CONTENT 事件: " + r.childUiEvents, contentCount >= 1);
        assertEquals("后台子Agent结束应发出 1 次 DONE 事件: " + r.childUiEvents, 1, doneCount);
        assertTrue(r.loop.lastTurnSucceeded());
    }

    /** 只读子Agent分工文案包含“生成/写入/整理结果”等字样时，不得被完成合同误判为必须写文件而卡死失败 */
    @Test public void exploreChildWithDeliverableWordingCompletesWithoutFileMutation() throws Exception {
        Rig r = newRig();
        java.nio.file.Files.write(tmp.getRoot().toPath().resolve("chunk_0.json"),
                "[{\"id\":0,\"name\":\"货物A\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        r.childReplies((id, script) -> {
            script.stopKind = "task";
            script.stopStatuses.add("completed");
            ToolCall read = new ToolCall();
            read.id = "read-1";
            read.name = "Read";
            read.arguments = "{\"path\":\"chunk_0.json\"}";
            script.addTurnWithTools(Collections.singletonList(read), null);
            // 故意不带 [[MINION_TASK_COMPLETE]]，验证 StopGate 核验只读子Agent不会因“生成结果文件”字样强求写入收据
            script.addTurn("已提取23条分类JSON数据，供主Agent合并生成结果文件。");
        });
        r.dispatch("读取 chunk_0.json 并生成 23 条分类结果用于写入最终汇总表", ",\"mode\":\"explore\"");
        r.main.text("等待子任务");
        r.reviewAllPending();
        r.main.fixed(Collections.singletonList(r.write("summary.json")));
        r.main.text("汇总核验完成。");
        r.loop.runUserTurn("分类汇总生成结果文件");

        TaskState.Child child = r.children().get(0);
        assertEquals("只读子任务不应因分工含‘生成/写入’而被门禁打回重跑: " + r.diagnose(), 2, r.child.requests.get());
        assertEquals("reviewed", child.status);
        assertTrue("主任务无需特殊完成暗号也应正常交付" + r.diagnose(), r.loop.lastTurnSucceeded());
    }

    /** 实机截图回归：后台子Agent停滞时，主Agent调用 child_cancel 放弃并自行产出结果后，不再死锁或误停 */
    @Test public void stalledBackgroundChildCanBeCancelledAndTakenOverByMainAgent() throws Exception {
        Rig r = newRig();
        r.child.blockUntil = new CountDownLatch(1);
        r.childReplies((id, script) -> script.addTurn("停滞不返回"));
        r.dispatch("提取分块数据", ",\"mode\":\"explore\"");
        r.main.dynamic(l -> {
            TaskState.Child stalled = l.session().taskState.children.get(0);
            List<ToolCall> calls = new ArrayList<ToolCall>();
            calls.add(r.call("AgentControl",
                    "{\"action\":\"child_cancel\",\"childId\":\"" + stalled.id
                            + "\",\"note\":\"子agent停滞，放弃子任务改由主Agent直接逐票提取\"}"));
            calls.add(r.write("result_sheet1.json"));
            return calls;
        });
        r.main.text("子agent停滞已解决：改由主Agent直接逐票提取，result_sheet1.json 已全部产出并核验完成。");
        r.loop.runUserTurn("生成分类结果文件");

        r.awaitChildrenSettled(5000);
        TaskState.Child child = r.children().get(0);
        assertEquals("被取消的子任务状态应记为 cancelled" + r.diagnose(), "cancelled", child.status);
        assertTrue("主Agent自行写入的结果文件应存在", new File(tmp.getRoot(), "result_sheet1.json").exists());
        assertTrue("取消停滞子Agent并由主Agent完成交付后，本轮应直接通过完成门禁" + r.diagnose(),
                r.loop.lastTurnSucceeded());
    }

    /** 只读探索模式白名单：允许 StructuredOutput 与只读 Files，严禁可写 Files 与 Bash */
    @Test public void explorePolicyAllowsStructuredOutputAndReadOnlyFilesOnly() {
        AgentToolPolicy policy = new AgentToolPolicy(true, "");
        JsonObject structuredArgs = JsonParser.parseString("{\"data\":{\"rows\":23}}").getAsJsonObject();
        assertTrue(policy.permits("StructuredOutput", structuredArgs));
        assertTrue(policy.permits("Files", JsonParser.parseString("{\"action\":\"read\",\"path\":\"a.txt\"}").getAsJsonObject()));
        assertTrue(policy.permits("Files", JsonParser.parseString("{\"action\":\"read_chunk\",\"path\":\"a.txt\"}").getAsJsonObject()));
        assertFalse(policy.permits("Files", JsonParser.parseString("{\"action\":\"write\",\"path\":\"a.txt\"}").getAsJsonObject()));
        assertFalse(policy.permits("Bash", JsonParser.parseString("{\"command\":\"echo hi\"}").getAsJsonObject()));
    }
}
