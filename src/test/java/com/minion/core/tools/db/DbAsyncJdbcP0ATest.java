package com.minion.core.tools.db;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.AgentUi;
import com.minion.core.agent.RuntimeJobCancellation;
import com.minion.core.agent.RuntimeJobRegistry;
import com.minion.core.agent.RuntimeJobRegistry.JobRecord;
import com.minion.core.agent.RuntimeJobRegistry.Status;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.StreamHandler;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import com.minion.core.tools.plugin.DbConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** 用代理 JDBC 执行真实 DbExecutor.query，无网络/新依赖；控制面使用真实磁盘 registry。 */
public class DbAsyncJdbcP0ATest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static JsonObject args(String action, String... pairs) {
        JsonObject args = new JsonObject(); args.addProperty("action", action);
        for (int i = 0; i < pairs.length; i += 2) args.addProperty(pairs[i], pairs[i + 1]);
        return args;
    }

    private static String id(ToolResult result) {
        assertTrue(result.output, result.ok);
        assertTrue(result.output, result.output.startsWith("jobId="));
        return result.output.substring(6, result.output.indexOf('\n'));
    }

    private static void eventually(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue("有界测试等待未收到预期回执", condition.getAsBoolean());
    }

    private final class Fixture implements AutoCloseable {
        final Path root = temporary.newFolder().toPath();
        final Path tmp = Files.createDirectory(root.resolve("authorized-tmp"));
        final RuntimeJobRegistry jobs = new RuntimeJobRegistry(root.resolve("runtime.json"));
        final DataSourceConfig source = new DataSourceConfig("prod", "jdbc:fake://host/db?password=url-secret", "u", "source-secret");
        final DbConfig config = new DbConfig();
        final AtomicReference<String> task = new AtomicReference<String>("task-a");
        final AtomicLong revision = new AtomicLong(1);
        final FakeJdbc jdbc = new FakeJdbc(tmp);
        final DbTool tool;

        Fixture() throws Exception {
            source.queryTimeoutSeconds = 30;
            config.add(source);
            jdbc.beforeConnect = () -> assertFalse("必须先持久登记，再建 JDBC 连接", jobs.list().isEmpty());
            tool = new DbTool(DbType.MYSQL, config, tmp, jdbc);
            tool.bind(jobs, task::get, revision::get);
        }

        String submit() { return id(tool.execute(args("submit", "sql", "SELECT value FROM facts"))); }
        @Override public void close() throws Exception {
            jdbc.release.countDown(); jdbc.cancelRelease.countDown();
            tool.shutdown();
            eventually(() -> jdbc.connections.get() == jdbc.closedConnections.get());
            // shutdown 不拥有 registry；关闭前先让归档线程完成。
            eventually(() -> jobs.list().stream().allMatch(r -> r.status.settled()));
            eventually(() -> jobs.list().stream().allMatch(r -> !tool.execute(args("status", "jobId", r.jobId)).output.contains("liveHandle=true")));
            jobs.close();
        }
    }

    @Test public void submitReturnsIdBeforeRealQueryAndPublishesOnlyAfterResultWritten() throws Exception {
        try (Fixture f = new Fixture()) {
            long before = System.nanoTime(); String id = f.submit();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2000);
            assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            JobRecord running = f.jobs.get(id);
            assertTrue(running.waitForCompletion);
            assertEquals("jdbc:mysql", running.kind);
            assertEquals(1, f.jobs.foregroundJobs("task-a", 1).size());
            assertEquals(Status.RUNNING, running.status);
            assertFalse(Files.exists(Paths.get(running.resultPath)));
            CompletableFuture<RuntimeJobRegistry.WaitResult> wait = CompletableFuture.supplyAsync(() -> {
                try { return f.tool.await(id, 4000, null); } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertFalse(wait.isDone());
            f.jdbc.release.countDown();
            RuntimeJobRegistry.WaitResult result = wait.get(5, TimeUnit.SECONDS);
            assertTrue(result.canResumeTask);
            assertEquals(Status.COMPLETED, result.job.status);
            String contents = new String(Files.readAllBytes(Paths.get(result.job.resultPath)), StandardCharsets.UTF_8);
            assertTrue(contents, contents.contains("真实 JDBC 结果"));
            assertEquals(1, f.jdbc.executions.get());
            assertTrue(f.jdbc.readOnly);
            assertEquals(DbExecutor.MAX_ROWS + 1, f.jdbc.maxRows);
        }
    }

    @Test public void realAgentLoopWaitsForJdbcAndArchivedResultBeforeSecondModelRequestWithoutResubmission() throws Exception {
        Path root = temporary.newFolder("agent-jdbc").toPath();
        Path tmp = Files.createDirectory(root.resolve("authorized-tmp"));
        FakeJdbc jdbc = new FakeJdbc(tmp);
        DbConfig config = new DbConfig();
        config.enabled = true;
        config.add(new DataSourceConfig("scripted", "jdbc:fake://offline/facts", "u", "source-secret"));
        AtomicReference<RuntimeJobRegistry> shared = new AtomicReference<RuntimeJobRegistry>();
        AtomicInteger bindings = new AtomicInteger(), modelCalls = new AtomicInteger(), submits = new AtomicInteger();
        CountDownLatch waiting = new CountDownLatch(1), waitEnded = new CountDownLatch(1), secondRequest = new CountDownLatch(1);
        AtomicReference<String> waitingJob = new AtomicReference<String>();
        // 只观察真实宿主 bind；不替换 submit/query/registry 的实现，也不预绑独立账本。
        DbTool db = new DbTool(DbType.MYSQL, config, tmp, jdbc) {
            @Override public void bind(RuntimeJobRegistry jobs, java.util.function.Supplier<String> taskId,
                                       java.util.function.LongSupplier revision) {
                super.bind(jobs, taskId, revision); shared.set(jobs); bindings.incrementAndGet();
            }
        };
        jdbc.beforeConnect = () -> {
            assertNotNull("共享账本必须由 AgentLoop 首次绑定", shared.get());
            assertEquals("先登记，再提交 JDBC", 1, shared.get().list().size());
            assertEquals(Status.RUNNING, shared.get().list().get(0).status);
        };
        FakeLlmClient model = new FakeLlmClient() {
            @Override public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler) throws LlmException {
                int request = modelCalls.incrementAndGet();
                assertTrue("等待不能消耗模型轮询/重复提交", request <= 2);
                if (request == 2) {
                    assertEquals("第二次请求只能在 JDBC 真的返回并关闭连接后发生", 1, jdbc.closedConnections.get());
                    assertEquals(0, jdbc.release.getCount());
                    assertEquals("先退出外部事件等待，再请求模型", 0, waitEnded.getCount());
                    JobRecord completed = shared.get().get(waitingJob.get());
                    assertEquals(Status.COMPLETED, completed.status);
                    assertTrue(completed.waitForCompletion);
                    Path result = Paths.get(completed.resultPath);
                    assertTrue(result.startsWith(tmp));
                    assertEquals("result.md", result.getFileName().toString());
                    try {
                        assertTrue("通知前真实结果必须已落盘", new String(Files.readAllBytes(result), StandardCharsets.UTF_8).contains("真实 JDBC 结果"));
                    } catch (java.io.IOException e) { throw new AssertionError("模型提前收到尚未落盘的结果", e); }
                    assertEquals("一次完成只注入一份当前 job 的真实结果通知", 1, messages.stream().filter(m ->
                            m.origin == Message.Origin.TOOL_EVIDENCE && m.sourceEventId != null
                            && m.sourceEventId.startsWith("job:" + completed.jobId + ":")
                            && m.content.contains("系统外部作业结果") && m.content.contains("status=COMPLETED")
                            && m.content.contains(completed.resultPath)).count());
                    assertEquals(1, jdbc.executions.get());
                    secondRequest.countDown();
                }
                super.streamChat(messages, tools, handler);
            }
        };
        ToolCall submit = new ToolCall();
        submit.id = "jdbc-submit-once"; submit.name = db.name();
        submit.arguments = args("submit", "sql", "SELECT value FROM facts").toString();
        model.addTurnWithTools(Collections.singletonList(submit), "");
        model.addTurn("只读查询已返回，真实结果已写入授权临时目录。\n[[MINION_TASK_COMPLETE]]");
        model.stopKind = "task"; model.stopStatuses.add("completed");
        ToolRegistry registry = new ToolRegistry();
        registry.register(DbType.MYSQL.id(), db); registry.setGate(id -> config.enabled);
        Session session = Session.create(root.toString(), "scripted-offline");
        AgentUi ui = new AgentUi() {
            @Override public void onToolCall(String name, JsonObject args) {
                if (db.name().equals(name) && "submit".equals(args.get("action").getAsString())) submits.incrementAndGet();
            }
            @Override public void onExternalWaitChanged(boolean active, String jobId) {
                if (active) { waitingJob.set(jobId); waiting.countDown(); }
                else waitEnded.countDown();
            }
        };
        AgentLoop loop = new AgentLoop(model, registry, new SystemPromptBuilder(null, root.toString(), tmp.toString()),
                new ConfirmGate(Config.load(root), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                ui, null, new Workspace(root.toString()), session);
        FutureTask<Void> turn = new FutureTask<Void>(() -> { loop.runUserTurn("只执行一次 DbMysql 只读查询，等待真实结果并报告归档路径"); return null; });
        Thread worker = new Thread(turn, "test-agent-jdbc-turn"); worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(jdbc.started.await(5, TimeUnit.SECONDS));
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            eventually(() -> Arrays.stream(worker.getStackTrace()).anyMatch(frame ->
                    RuntimeJobRegistry.class.getName().equals(frame.getClassName()) && "await".equals(frame.getMethodName())));
            assertEquals("waiting_external", session.taskState.runtimePhase);
            assertEquals(1, modelCalls.get());
            assertEquals(1, secondRequest.getCount());
            JobRecord running = shared.get().get(waitingJob.get());
            assertEquals(Status.RUNNING, running.status);
            assertFalse("JDBC gate 未释放时不应伪造 result.md", Files.exists(Paths.get(running.resultPath)));
            assertEquals(0, jdbc.closedConnections.get());
            jdbc.release.countDown();
            turn.get(10, TimeUnit.SECONDS);
            assertEquals(0, secondRequest.getCount());
            assertEquals(2, modelCalls.get()); assertEquals(2, model.requests.size());
            assertEquals(1, bindings.get()); assertEquals(1, submits.get());
            assertEquals(1, jdbc.connections.get()); assertEquals(1, jdbc.executions.get());
            assertEquals(1, shared.get().list().size());
            assertTrue("实际 AgentLoop 应完成当前脚本回合", loop.lastTurnSucceeded());
            assertTrue(loop.phaseTimings().getAsJsonObject("EXTERNAL_WAIT").get("observations").getAsInt() > 0);
        } finally {
            jdbc.release.countDown(); jdbc.cancelRelease.countDown();
            if (!turn.isDone()) loop.interrupt();
            try { turn.get(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
            loop.shutdown(); db.shutdown();
        }
    }

    @Test public void perSourceTimeoutIsFrozenAndModelCannotRaiseIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.source.queryTimeoutSeconds = 17;
            assertFalse(f.tool.execute(args("submit", "sql", "SELECT 1", "queryTimeoutSeconds", "86400")).ok);
            assertFalse(f.tool.execute(args("query", "sql", "SELECT 1", "timeoutMillis", "86400000")).ok);
            assertTrue(f.jobs.list().isEmpty());
            String id = f.submit();
            assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            f.source.queryTimeoutSeconds = 86400;
            assertEquals(17, f.jdbc.timeout);
            assertFalse(f.tool.description().contains("source-secret"));
            assertFalse(f.tool.description().contains("url-secret"));
            f.jdbc.release.countDown();
            assertEquals(Status.COMPLETED, f.tool.await(id, 4000, null).job.status);
        }
    }

    @Test public void legacyMissingAndInvalidSourceTimeoutsHaveSafeDefaults() {
        DataSourceConfig old = new Gson().fromJson("{\"name\":\"old\"}", DataSourceConfig.class);
        assertEquals(300, old.effectiveQueryTimeoutSeconds());
        old.queryTimeoutSeconds = 0; assertEquals(300, old.effectiveQueryTimeoutSeconds());
        old.queryTimeoutSeconds = -1; assertEquals(300, old.effectiveQueryTimeoutSeconds());
        old.queryTimeoutSeconds = Integer.MAX_VALUE; assertEquals(86400, old.effectiveQueryTimeoutSeconds());
        old.queryTimeoutSeconds = 1; assertEquals(1, old.snapshot().queryTimeoutSeconds);
        assertEquals(1, new Gson().fromJson(new Gson().toJson(old), DataSourceConfig.class).queryTimeoutSeconds);
    }

    @Test public void cancelReturnsWithoutBlockingDriverAndLateResultCannotResume() throws Exception {
        try (Fixture f = new Fixture()) {
            f.jdbc.blockCancel = true;
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            long before = System.nanoTime();
            CompletableFuture<Integer> cancel = f.tool.cancelForeground("task-a", 1, "user stop");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500);
            assertEquals(Integer.valueOf(1), cancel.get(2, TimeUnit.SECONDS));
            assertTrue(f.jdbc.cancelCalled.await(2, TimeUnit.SECONDS));
            assertEquals(Status.CANCELLED, f.jobs.get(id).status);
            assertFalse(f.tool.await(id, 0, null).canResumeTask);
            String status = f.tool.execute(args("status", "jobId", id)).output;
            assertTrue(status, status.contains("physicalCancellationConfirmed=false"));
            f.jdbc.release.countDown();
            eventually(() -> f.jobs.get(id).lateResult);
            assertEquals(Status.CANCELLED, f.jobs.get(id).status);
            assertNotNull(f.jobs.get(id).observedResultPath);
            assertTrue(Files.exists(Paths.get(f.jobs.get(id).observedResultPath)));
        }
    }

    @Test public void explicitIdCanCancelFencedOldRevisionWithoutResubmitting() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            f.revision.set(2); f.jobs.bindTask("task-a", 2);
            assertEquals(Integer.valueOf(0), f.tool.cancelForeground("task-a", 2, "stop new task").get(2, TimeUnit.SECONDS));
            assertTrue(f.tool.execute(args("cancel", "jobId", id)).ok);
            assertTrue(f.jdbc.cancelCalled.await(2, TimeUnit.SECONDS));
            assertTrue(f.jobs.get(id).cancelRequested);
            assertEquals(Status.UNKNOWN, f.jobs.get(id).status);
            f.jdbc.release.countDown(); eventually(() -> f.jobs.get(id).lateResult);
            assertFalse(f.tool.await(id, 0, null).canResumeTask);
            assertEquals(1, f.jdbc.executions.get());
        }
    }

    @Test public void changedTaskWithoutAnotherSubmitStillArchivesOldResultOnly() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            f.task.set("task-b"); f.jobs.bindTask("task-b", 1);
            f.jdbc.release.countDown(); eventually(() -> f.jobs.get(id).lateResult);
            JobRecord old = f.jobs.get(id);
            assertEquals(Status.UNKNOWN, old.status);
            assertEquals(Status.COMPLETED, old.observedStatus);
            assertFalse(f.tool.await(id, 0, null).canResumeTask);
        }
    }

    @Test public void alreadyCompletedOldTaskCannotResumeNewTaskButCanBeInspected() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            f.jdbc.release.countDown();
            assertEquals(Status.COMPLETED, f.tool.await(id, 4000, null).job.status);
            f.task.set("new-task"); f.jobs.bindTask("new-task", 1);
            assertFalse(f.tool.execute(args("wait", "jobId", id, "timeoutMillis", "0")).ok);
            assertTrue(f.tool.execute(args("status", "jobId", id)).ok);
            assertTrue(f.tool.execute(args("cancel", "jobId", id)).ok);
            assertEquals(1, f.jdbc.executions.get());
        }
    }

    @Test public void deadlineSettlesUnknownAndKeepsPhysicalWorkerOccupied() throws Exception {
        try (Fixture f = new Fixture()) {
            f.source.queryTimeoutSeconds = 1;
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            RuntimeJobRegistry.WaitResult result = f.tool.await(id, 4000, null);
            assertEquals(Status.UNKNOWN, result.job.status);
            assertFalse(result.canResumeTask);
            assertTrue(f.jdbc.cancelCalled.await(2, TimeUnit.SECONDS));
            assertEquals(0, f.jdbc.closedConnections.get());
            String timeoutContents = new String(Files.readAllBytes(Paths.get(result.job.resultPath)), StandardCharsets.UTF_8);
            assertTrue(timeoutContents.contains("物理停止未确认"));
            f.jdbc.release.countDown(); eventually(() -> f.jobs.get(id).lateResult);
            assertEquals(timeoutContents, new String(Files.readAllBytes(Paths.get(result.job.resultPath)), StandardCharsets.UTF_8));
            assertFalse(result.job.resultPath.equals(f.jobs.get(id).observedResultPath));
        }
    }

    @Test public void waitsAreBoundedInterruptibleAndCancellationDoesNotResubmit() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            assertEquals(RuntimeJobRegistry.WaitReason.TIMED_OUT, f.tool.await(id, 0, null).reason);
            RuntimeJobCancellation cancellation = new RuntimeJobCancellation();
            CompletableFuture<RuntimeJobRegistry.WaitResult> waiting = CompletableFuture.supplyAsync(() -> {
                try { return f.tool.await(id, 4000, cancellation); } catch (Exception e) { throw new RuntimeException(e); }
            });
            cancellation.cancel();
            assertEquals(RuntimeJobRegistry.WaitReason.WAIT_CANCELLED, waiting.get(2, TimeUnit.SECONDS).reason);
            assertFalse(f.jobs.get(id).cancelRequested);
            assertFalse(f.tool.execute(args("wait", "jobId", id, "timeoutMillis", "86400001")).ok);
            assertFalse(f.tool.execute(args("wait", "jobId", id, "timeoutMillis", "-1")).ok);
            assertFalse(f.tool.execute(args("wait", "jobId", id, "timeoutMillis", "0.5")).ok);
            AtomicReference<ToolResult> interrupted = new AtomicReference<ToolResult>();
            Thread thread = new Thread(() -> { Thread.currentThread().interrupt(); interrupted.set(f.tool.execute(args("wait", "jobId", id))); });
            thread.start(); thread.join(2000);
            assertFalse(thread.isAlive()); assertFalse(interrupted.get().ok);
            assertEquals(1, f.jdbc.executions.get());
        }
    }

    @Test public void twoWorkersAndBoundedQueueQueuedCancelNeverExecutes() throws Exception {
        try (Fixture f = new Fixture()) {
            List<String> ids = new ArrayList<String>();
            for (int i = 0; i < DbAsyncJobs.WORKERS + DbAsyncJobs.QUEUE_SIZE; i++) ids.add(f.submit());
            eventually(() -> f.jdbc.executions.get() == 2);
            assertFalse(f.tool.execute(args("submit", "sql", "SELECT 1")).ok);
            assertEquals(10, f.jobs.list().size());
            String queued = ids.get(ids.size() - 1);
            assertEquals(Status.QUEUED, f.jobs.get(queued).status);
            assertTrue(f.tool.execute(args("cancel", "jobId", queued)).ok);
            assertEquals(Status.CANCELLED, f.jobs.get(queued).status);
            f.jdbc.release.countDown();
            for (String id : ids) f.tool.await(id, 4000, null);
            eventually(() -> f.jdbc.closedConnections.get() == 9);
            assertEquals(9, f.jdbc.executions.get());
            assertTrue(f.jdbc.peak.get() <= 2);
        }
    }

    @Test public void readonlyGuardAndAuthorizedResultRootRejectBeforeConnect() throws Exception {
        try (Fixture f = new Fixture()) {
            assertFalse(f.tool.execute(args("submit", "sql", "DELETE FROM facts")).ok);
            assertFalse(f.tool.execute(args("submit", "sql", "SELECT 1; DROP TABLE facts")).ok);
            assertFalse(f.tool.execute(args("submit", "sql", "SELECT 1", "resultPath", "C:/outside.md")).ok);
            assertTrue(f.jobs.list().isEmpty());
            assertEquals(0, f.jdbc.connections.get());
            try (DbTool unbound = new DbTool(DbType.MYSQL, f.config, f.tmp)) {
                assertFalse(unbound.execute(args("submit", "sql", "SELECT 1")).ok);
            }
            try (DbTool noTmp = new DbTool(DbType.MYSQL, f.config, null)) {
                noTmp.bind(f.jobs, f.task::get, f.revision::get);
                assertFalse(noTmp.execute(args("submit", "sql", "SELECT 1")).ok);
            }
            assertTrue(f.jobs.list().isEmpty());
        }
    }

    @Test public void statusSurvivesSourceDeletionAndNeverSerializesPasswordOrDriverMessage() throws Exception {
        try (Fixture f = new Fixture()) {
            f.jdbc.failQuery = true;
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            f.config.remove("prod"); f.jdbc.release.countDown();
            assertEquals(Status.FAILED, f.tool.await(id, 4000, null).job.status);
            ToolResult status = f.tool.execute(args("status", "id", id));
            assertTrue(status.ok);
            assertFalse(status.output.contains("source-secret"));
            assertFalse(status.output.contains("url-secret"));
            assertFalse(new String(Files.readAllBytes(f.jobs.ledgerPath()), StandardCharsets.UTF_8).contains("source-secret"));
            String error = new String(Files.readAllBytes(Paths.get(f.jobs.get(id).resultPath)), StandardCharsets.UTF_8);
            assertFalse(error, error.contains("source-secret"));
            assertFalse(error, error.contains("url-secret"));
        }
    }

    @Test public void cancelledStatementFailureIsUnconfirmedAndSessionShutdownLeavesRegistryOwnedByCaller() throws Exception {
        try (Fixture f = new Fixture()) {
            f.jdbc.failCancel = true;
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            f.tool.cancelForeground("task-a", 1, "stop").get(2, TimeUnit.SECONDS);
            assertTrue(f.jdbc.cancelCalled.await(2, TimeUnit.SECONDS));
            eventually(() -> f.tool.execute(args("status", "jobId", id)).output.contains("statement_cancel_failed_unconfirmed"));
            long before = System.nanoTime(); f.tool.shutdown(); f.tool.shutdown();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500);
            assertFalse(f.tool.execute(args("query", "sql", "SELECT 1")).ok);
            assertFalse(f.tool.execute(args("submit", "sql", "SELECT 1")).ok);
            // 工具不能关共享 session 的 registry。
            f.jobs.bindTask("another-task", 1);
            f.jdbc.release.countDown(); eventually(() -> f.jobs.get(id).lateResult);
        }
    }

    @Test public void freshSessionRetainsTypeAndConfigButNotBinding() throws Exception {
        try (Fixture f = new Fixture(); DbTool fresh = f.tool.freshForSession()) {
            assertNotSame(f.tool, fresh);
            assertEquals(f.tool.name(), fresh.name());
            f.source.name = "renamed"; f.config.current = "renamed";
            assertTrue(fresh.description().contains("renamed"));
            assertFalse(fresh.execute(args("submit", "sql", "SELECT 1")).ok);
            try (RuntimeJobRegistry other = new RuntimeJobRegistry(f.root.resolve("other.json"))) {
                fresh.bind(other, () -> "new-session", () -> 1L);
            }
        }
    }

    @Test public void failedResultWriteCannotPublishCompleted() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            Files.createDirectory(Paths.get(f.jobs.get(id).resultPath));
            f.jdbc.release.countDown();
            RuntimeJobRegistry.WaitResult result = f.tool.await(id, 4000, null);
            assertEquals(Status.UNKNOWN, result.job.status);
            assertFalse(result.canResumeTask);
            assertNotNull(f.tool.controlError());
        }
    }

    @Test public void synchronousQueryRetainsRealJdbcAndPerSourceTimeout() throws Exception {
        try (Fixture f = new Fixture()) {
            f.jdbc.beforeConnect = () -> { };
            f.source.queryTimeoutSeconds = 23; f.jdbc.release.countDown();
            ToolResult output = f.tool.execute(args("query", "sql", "SELECT value FROM facts"));
            assertTrue(output.output, output.ok);
            assertTrue(output.output.contains("真实 JDBC 结果"));
            assertEquals(23, f.jdbc.timeout);
            assertTrue(f.jobs.list().isEmpty());
        }
    }

    @Test public void explicitSynchronousStopCancelsStatementEvenWhenFutureInterruptIsIgnoredAndToolRemainsOpen() throws Exception {
        try (Fixture f = new Fixture()) {
            f.jdbc.beforeConnect = () -> { };
            f.jdbc.ignoreInterrupts = true; f.jdbc.blockCancel = true;
            AtomicReference<ToolResult> output = new AtomicReference<ToolResult>();
            CountDownLatch returned = new CountDownLatch(1);
            FutureTask<Void> future = new FutureTask<Void>(() -> {
                try { output.set(f.tool.execute(args("query", "sql", "SELECT value FROM facts"))); }
                finally { returned.countDown(); }
                return null;
            });
            Thread worker = new Thread(future, "test-sync-jdbc"); worker.setDaemon(true); worker.start();
            assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            future.cancel(true);
            assertTrue(f.jdbc.interruptIgnored.await(2, TimeUnit.SECONDS));
            assertTrue(worker.isAlive());
            long before = System.nanoTime();
            assertEquals(1, f.tool.cancelSynchronousQueries());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500);
            assertTrue(f.jdbc.cancelCalled.await(2, TimeUnit.SECONDS));
            assertEquals(1, returned.getCount());
            assertEquals(0, f.jdbc.closedConnections.get());
            f.jdbc.release.countDown(); f.jdbc.cancelRelease.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS)); worker.join(2000);
            assertFalse(output.get().ok);
            assertEquals(0, f.tool.cancelSynchronousQueries());
            assertTrue("用户停止不应关闭工具", f.tool.execute(args("query", "sql", "SELECT 1")).ok);
            assertTrue(f.jobs.list().isEmpty());
        }
    }

    @Test public void synchronousStopDoesNotBypassAsyncRegistryCancellationFence() throws Exception {
        try (Fixture f = new Fixture()) {
            String id = f.submit(); assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
            assertEquals(0, f.tool.cancelSynchronousQueries());
            assertFalse(f.jobs.get(id).cancelRequested);
            assertEquals(1, f.jdbc.cancelCalled.getCount());
            f.jdbc.release.countDown();
            assertEquals(Status.COMPLETED, f.tool.await(id, 4000, null).job.status);
        }
    }

    @Test public void synchronousSchemaAndDescribeCanCancelExposedMetadataStatement() throws Exception {
        for (String action : new String[]{"schema", "describe"}) {
            try (Fixture f = new Fixture()) {
                f.jdbc.beforeConnect = () -> { };
                AtomicReference<ToolResult> output = new AtomicReference<ToolResult>();
                Thread worker = new Thread(() -> output.set(f.tool.execute(args(action, "table", "facts"))));
                worker.setDaemon(true); worker.start();
                assertTrue(f.jdbc.started.await(2, TimeUnit.SECONDS));
                assertTrue(f.jdbc.readOnly);
                assertEquals(30, f.jdbc.timeout);
                assertEquals(1, f.tool.cancelSynchronousQueries());
                assertTrue(f.jdbc.cancelCalled.await(2, TimeUnit.SECONDS));
                f.jdbc.release.countDown(); worker.join(2000);
                assertFalse(worker.isAlive()); assertFalse(output.get().ok);
                assertEquals(1, f.jdbc.closedConnections.get());
                assertTrue(f.jobs.list().isEmpty());
            }
        }
    }

    /** 只替换 connect；语句创建、只读、超时、取行、渲染、finally 关闭均由真实 DbExecutor 完成。 */
    private static final class FakeJdbc extends DbExecutor {
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch cancelCalled = new CountDownLatch(1), cancelRelease = new CountDownLatch(1);
        final CountDownLatch interruptIgnored = new CountDownLatch(1);
        final AtomicInteger connections = new AtomicInteger(), closedConnections = new AtomicInteger();
        final AtomicInteger executions = new AtomicInteger(), running = new AtomicInteger(), peak = new AtomicInteger();
        volatile boolean readOnly, blockCancel, failCancel, failQuery, ignoreInterrupts;
        volatile int timeout, maxRows;
        volatile Runnable beforeConnect = () -> { };

        FakeJdbc(Path tmp) { super(tmp); }

        @Override protected Connection connect(DataSourceConfig source, DbType type) {
            beforeConnect.run(); connections.incrementAndGet();
            AtomicInteger connectionClosed = new AtomicInteger();
            Statement statement = proxy(Statement.class, (p, method, args) -> {
                String name = method.getName();
                if ("setQueryTimeout".equals(name)) timeout = (Integer) args[0];
                if ("setMaxRows".equals(name)) maxRows = (Integer) args[0];
                if ("executeQuery".equals(name)) {
                    executions.incrementAndGet();
                    int count = running.incrementAndGet(); peak.accumulateAndGet(count, Math::max); started.countDown();
                    try {
                        awaitQueryGate();
                        if (failQuery) throw new SQLException("source-secret jdbc:fake://host?password=url-secret", "42S02");
                        AtomicInteger row = new AtomicInteger();
                        ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (x, m, a) -> {
                            if ("getColumnCount".equals(m.getName())) return 1;
                            if ("getColumnLabel".equals(m.getName()) || "getColumnName".equals(m.getName())) return "value";
                            return defaultValue(m.getReturnType());
                        });
                        return proxy(ResultSet.class, (x, m, a) -> {
                            if ("getMetaData".equals(m.getName())) return metadata;
                            if ("next".equals(m.getName())) return row.getAndIncrement() == 0;
                            if ("getObject".equals(m.getName())) return "真实 JDBC 结果";
                            return defaultValue(m.getReturnType());
                        });
                    } finally { running.decrementAndGet(); }
                }
                if ("cancel".equals(name)) {
                    cancelCalled.countDown();
                    if (blockCancel) cancelRelease.await(10, TimeUnit.SECONDS);
                    if (failCancel) throw new SQLException("source-secret 取消不支持");
                }
                return defaultValue(method.getReturnType());
            });
            AtomicInteger metadataRow = new AtomicInteger();
            ResultSet metadataResult = proxy(ResultSet.class, (p, method, args) -> {
                if ("getStatement".equals(method.getName())) return statement;
                if ("next".equals(method.getName())) {
                    if (metadataRow.getAndIncrement() != 0) return false;
                    started.countDown(); awaitQueryGate(); return true;
                }
                if ("getString".equals(method.getName())) return "facts";
                if ("getInt".equals(method.getName())) return 1;
                return defaultValue(method.getReturnType());
            });
            DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (p, method, args) -> {
                if ("getTables".equals(method.getName()) || "getColumns".equals(method.getName())) return metadataResult;
                if ("getUserName".equals(method.getName())) return "u";
                return defaultValue(method.getReturnType());
            });
            return proxy(Connection.class, (p, method, args) -> {
                if ("createStatement".equals(method.getName())) return statement;
                if ("getMetaData".equals(method.getName())) return metadata;
                if ("setReadOnly".equals(method.getName())) readOnly = (Boolean) args[0];
                if ("close".equals(method.getName()) && connectionClosed.compareAndSet(0, 1)) closedConnections.incrementAndGet();
                return defaultValue(method.getReturnType());
            });
        }

        private void awaitQueryGate() throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean interrupted = false;
            try {
                for (;;) {
                    long left = end - System.nanoTime();
                    if (left <= 0) throw new SQLException("测试 gate 超时");
                    try { if (release.await(left, TimeUnit.NANOSECONDS)) return; }
                    catch (InterruptedException e) {
                        if (!ignoreInterrupts) throw e;
                        interrupted = true; interruptIgnored.countDown();
                    }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }

        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }
}
