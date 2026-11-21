package com.minion.core.agent;

import com.google.gson.JsonParser;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ordered, bounded read waves separated by side-effect barriers. Does not authorize tools:
 * runner must still call runOneTool/ToolExecutionGuard and the existing confirmation gate.
 * A task/job launch returning success is NOT external completion; runner/TaskWorkflow
 * must still enforce revision/scope/dependency acceptance for subsequent mutations.
 * Does not own an executor, wait on workers from a worker, retry, or change the model.
 */
public final class ToolBatchScheduler {
    public interface Runner { ToolResult run(ToolCall call) throws Exception; }
    private final ToolSchedulingPolicy policy;
    /** §2.2 元数据驱动并行（tools.parallel.metadataDriven）：null/false=legacy 按名判定 */
    private final com.minion.core.tools.ToolRegistry registry;
    private final boolean metadataDriven;

    public ToolBatchScheduler(ToolSchedulingPolicy policy) { this(policy, null, false); }

    public ToolBatchScheduler(ToolSchedulingPolicy policy, com.minion.core.tools.ToolRegistry registry,
                              boolean metadataDriven) {
        this.policy = policy == null ? ToolSchedulingPolicy.conservative() : policy;
        this.registry = registry;
        this.metadataDriven = metadataDriven;
    }

    /** Each stage is contiguous: no later read is moved ahead of an earlier write/confirmation. */
    public List<List<ToolCall>> plan(List<ToolCall> calls) {
        List<List<ToolCall>> stages = new ArrayList<List<ToolCall>>();
        List<ToolCall> reads = new ArrayList<ToolCall>();
        for (ToolCall call : calls) {
            if (!parallel(call)) {
                flush(stages, reads);
                stages.add(Collections.singletonList(call));
            } else {
                reads.add(call);
                if (reads.size() == policy.maxParallelReads) flush(stages, reads);
            }
        }
        flush(stages, reads);
        return Collections.unmodifiableList(stages);
    }

    /**
     * Drop-in replacement for eagerly pool.submit-ing every call. Futures are in original
     * order, including unstarted calls. Register ALL with AgentLoop.inFlight for cancellation.
     * A failed/null/thrown result skips all subsequent stages (no implicit replay); already
     * running peers in the same read-only wave are drained. Cancel ANY future to cancel the
     * entire batch, interrupt running workers, and prevent later writes from starting.
     * Already-started tools may ignore interruption: cancelled is not proof that their
     * side effects stopped, and such operations still require normal reconciliation.
     */
    public List<Future<ToolResult>> submit(List<ToolCall> calls, ExecutorService executor, Runner runner) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(runner, "runner");
        List<List<ToolCall>> stages = plan(calls);
        Batch batch = new Batch();
        List<List<ResultFuture>> waves = new ArrayList<List<ResultFuture>>();
        List<Future<ToolResult>> ordered = new ArrayList<Future<ToolResult>>();
        for (List<ToolCall> stage : stages) {
            List<ResultFuture> wave = new ArrayList<ResultFuture>();
            for (ToolCall call : stage) {
                ResultFuture future = new ResultFuture(batch);
                batch.results.add(future); wave.add(future); ordered.add(future);
            }
            waves.add(wave);
        }
        CompletableFuture<Void> preceding = CompletableFuture.completedFuture(null);
        for (int i = 0; i < stages.size(); i++) {
            final List<ToolCall> stage = stages.get(i);
            final List<ResultFuture> wave = waves.get(i);
            preceding.thenRun(() -> {
                for (int j = 0; j < stage.size(); j++) {
                    final ResultFuture result = wave.get(j);
                    final ToolCall call = stage.get(j);
                    synchronized (batch) {
                        if (batch.cancelled) continue;
                        if (batch.failed.get()) {
                            result.complete(ToolResult.errorWithoutSideEffects("前置工具失败，后续动作未执行；请核对真实结果后重新规划"));
                            continue;
                        }
                        FutureTask<Void> work = new FutureTask<Void>(() -> {
                            // FutureTask.cancel can race between its own state check and
                            // invoking this callable. Recheck at our batch start boundary.
                            synchronized (batch) {
                                if (batch.cancelled || result.isCancelled()) return null;
                            }
                            ToolResult value;
                            try {
                                value = runner.run(call);
                                if (value == null) value = ToolResult.error("工具返回空结果，执行状态待核对");
                            } catch (Throwable error) {
                                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                                value = ToolResult.error("工具执行异常，副作用待核对: " + error.getClass().getSimpleName());
                            }
                            batch.complete(result, value);
                            return null;
                        });
                        result.worker = work;
                        try { executor.execute(work); }
                        catch (RejectedExecutionException rejected) {
                            batch.failed.set(true);
                            result.complete(ToolResult.errorWithoutSideEffects("工具执行器未接受任务，本次未执行"));
                        }
                    }
                }
            });
            preceding = CompletableFuture.allOf(wave.toArray(new CompletableFuture<?>[wave.size()]));
        }
        return Collections.unmodifiableList(ordered);
    }

    private static final class Batch {
        final List<ResultFuture> results = new ArrayList<ResultFuture>();
        final AtomicBoolean failed = new AtomicBoolean();
        boolean cancelled;
        synchronized void complete(ResultFuture result, ToolResult value) {
            // Completion and cancellation must have one linearization boundary. An
            // interrupted worker may return an error OR success after cancellation;
            // neither may replace the batch's published cancelled state.
            if (cancelled || result.isDone()) return;
            if (!value.ok) failed.set(true);
            result.complete(value);
        }
        synchronized boolean cancel(ResultFuture requested, boolean interrupt) {
            if (cancelled || requested.isDone()) return false;
            cancelled = true;
            // Publish EVERY pending result's cancellation before interrupting ANY
            // worker. Otherwise its InterruptedException can complete an outer
            // future before cancelLocal(), leaving a partially cancelled batch.
            for (ResultFuture result : results) result.cancelLocal();
            for (ResultFuture result : results) {
                if (result.worker != null) result.worker.cancel(interrupt);
            }
            return true;
        }
    }
    private static final class ResultFuture extends CompletableFuture<ToolResult> {
        final Batch batch;
        Future<?> worker;
        ResultFuture(Batch batch) { this.batch = batch; }
        @Override public boolean cancel(boolean interrupt) {
            return batch.cancel(this, interrupt);
        }
        void cancelLocal() { super.cancel(false); }
    }
    private static void flush(List<List<ToolCall>> stages, List<ToolCall> reads) {
        if (!reads.isEmpty()) {
            stages.add(Collections.unmodifiableList(new ArrayList<ToolCall>(reads)));
            reads.clear();
        }
    }
    private boolean parallel(ToolCall call) {
        if (call == null) return false;
        if (metadataDriven && registry != null) {
            // §2.2：按工具元数据判定（isConcurrencySafe）；判定异常/未知工具=串行（fail-closed）。
            // ToolResourceScope 的文件锁/资源冲突闸在 runner 内继续生效——元数据通过不绕过资源锁。
            try {
                com.minion.core.tools.Tool tool = registry.get(call.name);
                return tool != null && tool.isEnabled()
                        && tool.isConcurrencySafe(JsonParser.parseString(
                                call.arguments == null ? "{}" : call.arguments).getAsJsonObject());
            } catch (RuntimeException invalid) {
                return false;
            }
        }
        try { return ToolResourceScope.parallelReadOnly(call.name,
                JsonParser.parseString(call.arguments == null ? "{}" : call.arguments).getAsJsonObject()); }
        catch (RuntimeException invalid) { return false; }
    }
}
