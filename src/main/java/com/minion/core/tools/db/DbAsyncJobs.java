package com.minion.core.tools.db;

import com.minion.core.agent.RuntimeJobCancellation;
import com.minion.core.agent.RuntimeJobRegistry;
import com.minion.core.agent.RuntimeJobRegistry.Handle;
import com.minion.core.agent.RuntimeJobRegistry.JobRecord;
import com.minion.core.agent.RuntimeJobRegistry.Status;
import com.minion.core.agent.RuntimeJobRegistry.WaitResult;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** JDBC 数据面适配器：共享账本由调用者所有；无自建 session/重放/模型计时轮询。 */
final class DbAsyncJobs {
    static final int WORKERS = 2;
    static final int QUEUE_SIZE = 8;
    private final Object lifecycle = new Object();
    private final DbType type;
    private final DbExecutor executor;
    private final Path tmpDir;
    private final Map<String, Managed> active = new LinkedHashMap<String, Managed>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger controlRequests = new AtomicInteger();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_SIZE), r -> daemon(r, "minion-jdbc-query"),
            new ThreadPoolExecutor.AbortPolicy());
    // 只做有界 deadline/控制事件；绝不在这个线程调用可能阻塞的 JDBC cancel/close。
    private final ScheduledThreadPoolExecutor events = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "minion-jdbc-events"));
    private RuntimeJobRegistry registry;
    private Supplier<String> taskId;
    private LongSupplier revision;
    private boolean used;
    private volatile String controlError;

    DbAsyncJobs(DbType type, DbExecutor executor, Path tmpDir) {
        this.type = type; this.executor = executor;
        this.tmpDir = tmpDir == null ? null : tmpDir.toAbsolutePath().normalize();
        events.setRemoveOnCancelPolicy(true);
        events.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    void bind(RuntimeJobRegistry registry, Supplier<String> taskId, LongSupplier revision) {
        if (registry == null || taskId == null || revision == null) throw new NullPointerException("registry/taskId/revision");
        synchronized (lifecycle) {
            if (closed.get() || used || this.registry != null && this.registry != registry)
                throw new IllegalStateException("bind before JDBC submit");
            this.registry = registry; this.taskId = taskId; this.revision = revision;
        }
    }

    ToolResult submit(DataSourceConfig source, String sql, boolean full) throws IOException {
        synchronized (lifecycle) {
            ensureOpen();
            RuntimeJobRegistry jobs = jobs();
            if (Thread.currentThread().isInterrupted()) return ToolResult.errorWithoutSideEffects("提交已中断，未执行 JDBC");
            if (active.size() >= WORKERS + QUEUE_SIZE)
                return ToolResult.errorWithoutSideEffects("JDBC 查询容量已满（2 执行 + 8 排队）；未提交，请等待现有作业事件");
            DataSourceConfig snapshot = source.snapshot();
            String task = taskId.get(); long rev = revision.getAsLong();
            if (task == null || !task.equals(taskId.get()) || rev != revision.getAsLong())
                return ToolResult.errorWithoutSideEffects("任务绑定正在变化，未执行 JDBC");
            Path directory = newDirectory();
            Path result = directory.resolve("result.md");
            // 持久登记必须先于 JDBC 建连/执行；所有数据库查询都是前台一次性工作。
            Handle handle = jobs.register(task, rev, 1, kind(), type.displayName() + " 只读 JDBC 查询", result.toString(), true);
            used = true;
            Managed managed = new Managed(jobs, handle, snapshot, sql, full, directory, result);
            active.put(handle.jobId, managed);
            try {
                managed.deadline = events.schedule(managed::expire, snapshot.effectiveQueryTimeoutSeconds(), TimeUnit.SECONDS);
                if (closed.get()) throw new RejectedExecutionException("JDBC closed during submission");
                workers.execute(managed);
            } catch (RejectedExecutionException e) {
                managed.finish(ToolResult.error("执行队列已关闭；未执行 JDBC"), Status.FAILED, "submission rejected before JDBC");
                return ToolResult.error("JDBC 未执行；登记保留，jobId=" + handle.jobId);
            }
            return ToolResult.success("jobId=" + handle.jobId + "\nstatus=queued\nwaitForCompletion=true (one-shot)"
                    + "\nqueryTimeoutSeconds=" + snapshot.effectiveQueryTimeoutSeconds()
                    + "\nresultPath=" + result + "\n已登记，非查询完成；等待前台完成事件，不要计时轮询或重复提交。");
        }
    }

    String status(String id) throws IOException {
        RuntimeJobRegistry jobs;
        Managed live;
        synchronized (lifecycle) { jobs = jobs(); live = active.get(id); }
        JobRecord record = record(jobs, id);
        // 白名单投影：不回显 source/url/user/password/SQL/description/detail/任意驱动消息。
        return "jobId=" + record.jobId + "\nstatus=" + record.status.name().toLowerCase(java.util.Locale.ROOT)
                + "\ntaskId=" + record.taskId + "\nrevision=" + record.revision + "\nattempt=" + record.attempt
                + "\nwaitForCompletion=" + record.waitForCompletion + "\ncancelRequested=" + record.cancelRequested
                + "\nfenced=" + record.fenced + "\nlateResult=" + record.lateResult
                + "\nresultPath=" + record.resultPath + "\nobservedStatus=" + record.observedStatus
                + "\nobservedResultPath=" + record.observedResultPath
                + "\nliveHandle=" + (live != null) + "\nphysicalCancellationConfirmed=false"
                + "\ncancellation=" + (live == null ? "no_live_handle" : live.control.cancellationState())
                + (controlError == null ? "" : "\ncontrolError=true（控制/归档失败，不能认定完成）");
    }

    WaitResult await(String id, long timeoutMillis, RuntimeJobCancellation cancellation) throws IOException, InterruptedException {
        RuntimeJobRegistry jobs;
        synchronized (lifecycle) { jobs = jobs(); }
        JobRecord record = record(jobs, id);
        if (!matchesContext(record) && jobs.isCurrent(record) && record.status != Status.UNKNOWN)
            throw new IOException("历史 task/revision 仅允许 status 核对或 cancel 清理，不能用其结果继续当前任务");
        WaitResult result = jobs.awaitTerminal(id, timeoutMillis, cancellation);
        if (result.canResumeTask && !matchesContext(result.job))
            throw new IOException("等待期间 task/revision 已改变；结果只归档，不继续当前任务");
        return result;
    }

    private boolean matchesContext(JobRecord record) {
        return record.taskId.equals(taskId.get()) && record.revision == revision.getAsLong();
    }

    ToolResult cancel(String id, String reason) throws IOException {
        RuntimeJobRegistry jobs; Managed managed;
        synchronized (lifecycle) { jobs = jobs(); managed = active.get(id); }
        JobRecord record = record(jobs, id);
        if (record.status == Status.COMPLETED || record.status == Status.FAILED)
            return ToolResult.success("作业已结算，未重提\n" + status(id));
        if (managed == null)
            return ToolResult.errorWithoutSideEffects("无本次 JDBC 活句柄，物理取消未确认；不会重连或重提\n" + status(id));
        requestCancel(managed, reason);
        return ToolResult.success("已请求取消；账本取消不等于服务端物理停止\n" + status(id));
    }

    /** UI 入口：只入队，不做磁盘 I/O、不等待 Statement.cancel。完成值为请求数而非物理停止数。 */
    CompletableFuture<Integer> cancelForeground(String task, long rev, String reason) {
        CompletableFuture<Integer> result = new CompletableFuture<Integer>();
        if (task == null || rev < 1) {
            result.completeExceptionally(new IllegalArgumentException("taskId/revision")); return result;
        }
        if (controlRequests.incrementAndGet() > 16) {
            controlRequests.decrementAndGet();
            result.completeExceptionally(new RejectedExecutionException("JDBC control queue full")); return result;
        }
        try { events.execute(() -> {
            try {
                int count = 0;
                for (Managed managed : snapshot()) {
                    if (task.equals(managed.handle.taskId) && rev == managed.handle.revision && !managed.finished) {
                        requestCancel(managed, reason); count++;
                    }
                }
                result.complete(count);
            } catch (Exception e) { controlError = "JDBC cancellation persistence failed"; result.completeExceptionally(e); }
            finally { controlRequests.decrementAndGet(); }
        }); } catch (RejectedExecutionException e) {
            controlRequests.decrementAndGet(); result.completeExceptionally(e);
        }
        return result;
    }

    private void requestCancel(Managed managed, String reason) throws IOException {
        IOException failure = null;
        synchronized (managed) {
            if (managed.finished) return;
            // 先持久围栏；即使存储失败，仍尽力取消活语句。
            try { managed.jobs.cancel(managed.handle.jobId, reason == null ? "JDBC cancellation requested" : reason); }
            catch (IOException e) { controlError = "JDBC cancel fence failed"; failure = e; }
            managed.control.cancel();
        }
        if (workers.remove(managed)) managed.finish(ToolResult.error("排队查询已取消，未执行 JDBC"), Status.CANCELLED, "cancelled before JDBC");
        if (failure != null) throw failure;
    }

    /** 永久关闭；不关闭注入的共享 registry，不在 UI 上等待不合作驱动。 */
    void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        try { events.execute(() -> {
            try {
                for (Managed managed : snapshot()) {
                    try { requestCancel(managed, "JDBC session shutdown"); }
                    catch (IOException e) { controlError = "JDBC shutdown fence failed"; }
                    if (managed.deadline != null) managed.deadline.cancel(false);
                }
            } finally { executor.close(); workers.shutdown(); events.shutdown(); }
        }); } catch (RejectedExecutionException e) { executor.close(); workers.shutdown(); }
    }

    String controlError() { return controlError; }

    private List<Managed> snapshot() { synchronized (lifecycle) { return new ArrayList<Managed>(active.values()); } }
    private String kind() { return "jdbc:" + type.id(); }
    private RuntimeJobRegistry jobs() throws IOException {
        if (registry == null) throw new IOException("异步 JDBC 需要先 bind 共享 session RuntimeJobRegistry");
        return registry;
    }
    private JobRecord record(RuntimeJobRegistry jobs, String id) {
        JobRecord record = jobs.get(id);
        if (!kind().equals(record.kind)) throw new IllegalArgumentException("jobId 不属于本数据库工具");
        return record;
    }
    private void ensureOpen() throws IOException { if (closed.get()) throw new IOException("数据库工具已关闭"); }

    private Path newDirectory() throws IOException {
        if (tmpDir == null) throw new IOException("异步结果需要现有授权 tmp 目录；不回退系统临时目录");
        // tmp 根由宿主预先授权/创建，模型没有 resultPath/输出根参数。
        Path root = tmpDir.toRealPath();
        Path parent = root.resolve("db-jobs");
        if (!PathsGuard.inside(root.toString(), parent)) throw new IOException("JDBC 结果目录越界");
        Files.createDirectories(parent);
        if (!PathsGuard.inside(root.toString(), parent)) throw new IOException("JDBC 结果目录越界");
        Path directory = parent.resolve(UUID.randomUUID().toString());
        Files.createDirectory(directory);
        return directory;
    }

    private static Thread daemon(Runnable runnable, String name) { Thread t = new Thread(runnable, name); t.setDaemon(true); return t; }

    private final class Managed implements Runnable {
        final RuntimeJobRegistry jobs;
        final Handle handle;
        final DataSourceConfig source;
        final String sql;
        final boolean full;
        final Path directory, result;
        final DbQueryControl control;
        volatile ScheduledFuture<?> deadline;
        volatile boolean finished;

        Managed(RuntimeJobRegistry jobs, Handle handle, DataSourceConfig source, String sql, boolean full, Path directory, Path result) {
            this.jobs = jobs; this.handle = handle; this.source = source; this.sql = sql; this.full = full;
            this.directory = directory; this.result = result; this.control = executor.control(directory);
        }

        private boolean currentContext() {
            return handle.taskId.equals(taskId.get()) && handle.revision == revision.getAsLong();
        }

        @Override public void run() {
            ToolResult output; Status status;
            try {
                if (closed.get() || !currentContext() || !jobs.isCurrent(jobs.get(handle.jobId)) || jobs.get(handle.jobId).status.settled()) {
                    finish(ToolResult.error("上下文已变化/查询已取消；未执行 JDBC"), Status.UNKNOWN, "not submitted: stale/cancelled context"); return;
                }
                jobs.publish(handle, Status.RUNNING, null, null, null, "JDBC query worker started");
                control.check();
                output = executor.query(source, type, sql, full, control);
                status = output.ok ? Status.COMPLETED : Status.FAILED;
            } catch (Exception | LinkageError e) {
                output = ToolResult.error("JDBC 执行/控制失败: " + DbExecutor.firstLine(DbSecrets.redact(e.getMessage(), source)));
                status = Status.FAILED;
            }
            finish(output, status, "JDBC returned; result archived (not parent task acceptance)");
        }

        void expire() {
            synchronized (this) {
                if (finished) return;
                control.cancel();
                try {
                    Path timeout = write("timeout.md", "查询超过用户数据源上限 " + source.effectiveQueryTimeoutSeconds()
                            + " 秒（含排队/建连）；已请求 Statement.cancel，物理停止未确认。迟到结果仅归档。");
                    // UNKNOWN 终止前台自动等待，但不冒充查询成功/服务端已结束。
                    jobs.publish(handle, Status.UNKNOWN, null, timeout.toString(), null, "source deadline exceeded; physical cancellation unconfirmed");
                } catch (IOException e) {
                    controlError = "JDBC timeout receipt/control write failed";
                    try { jobs.publish(handle, Status.UNKNOWN, null, null, null, "deadline exceeded; archive failed; physical cancellation unconfirmed"); }
                    catch (IOException ignored) { /* registry 自带 storageError 唤醒等待。 */ }
                }
            }
            if (workers.remove(this)) finish(ToolResult.error("排队超时，未执行 JDBC"), Status.UNKNOWN, "queue deadline exceeded before JDBC");
        }

        void finish(ToolResult output, Status observed, String detail) {
            synchronized (this) {
                if (finished) return;
                finished = true;
                if (deadline != null) deadline.cancel(false);
                try {
                    write("result.md", (output.ok ? "查询返回成功\n\n" : "查询返回失败\n\n") + output.output);
                    // 不依赖宿主是否已 bind 新 task：已换 task/revision 时不能以成功唤醒旧任务。
                    if (!currentContext() && !jobs.get(handle.jobId).status.settled())
                        jobs.publish(handle, Status.UNKNOWN, null, null, null, "task context superseded; archive only");
                    jobs.publish(handle, observed, null, result.toString(), null, detail);
                } catch (IOException | RuntimeException e) {
                    controlError = "JDBC result archive/control write failed";
                    try { jobs.publish(handle, Status.UNKNOWN, null, null, null, "result persistence failed; not successful"); }
                    catch (IOException | RuntimeException ignored) { }
                } finally {
                    // 忽略取消的 JDBC 仍占一个 worker/容量，绝不因逻辑超时放大物理并发。
                    synchronized (lifecycle) { active.remove(handle.jobId); }
                }
            }
        }

        Path write(String filename, String contents) throws IOException {
            Path path = directory.resolve(filename);
            if (!PathsGuard.inside(tmpDir.toString(), path)) throw new IOException("JDBC result outside authorized tmp");
            Files.write(path, contents.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return path;
        }
    }
}
