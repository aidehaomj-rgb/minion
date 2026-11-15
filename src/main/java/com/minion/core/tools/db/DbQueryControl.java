package com.minion.core.tools.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 本次 JDBC 活句柄；从不按历史 jobId 重连。cancel 返回也不等于服务端物理停止。 */
final class DbQueryControl {
    private final Executor cancellations;
    final Path outputDir;
    final boolean synchronous;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean dispatched = new AtomicBoolean();
    private volatile Statement statement;
    private volatile Connection connection;
    private volatile boolean finished;
    private volatile String cancellation = "not_requested";

    DbQueryControl(Executor cancellations, Path outputDir) {
        this(cancellations, outputDir, false);
    }

    DbQueryControl(Executor cancellations, Path outputDir, boolean synchronous) {
        this.cancellations = cancellations;
        this.outputDir = outputDir;
        this.synchronous = synchronous;
    }

    void attach(Connection connection) throws SQLException {
        this.connection = connection;
        check();
    }

    void attach(Statement statement) throws SQLException {
        this.statement = statement;
        if (requested.get()) dispatch();
        check();
    }

    void check() throws SQLException {
        if (requested.get() || Thread.currentThread().isInterrupted())
            throw new SQLException("JDBC 查询已请求取消；物理停止未确认");
    }

    void cancel() {
        if (requested.compareAndSet(false, true)) cancellation = "requested_no_statement_yet";
        dispatch();
    }

    private void dispatch() {
        final Statement live = statement;
        if (live == null || finished || !dispatched.compareAndSet(false, true)) return;
        cancellation = "statement_cancel_queued";
        try {
            cancellations.execute(() -> {
                try {
                    if (!finished) {
                        live.cancel();
                        cancellation = "statement_cancel_returned_unconfirmed";
                    } else cancellation = "query_returned_before_cancel";
                } catch (Exception | LinkageError e) {
                    cancellation = "statement_cancel_failed_unconfirmed";
                }
            });
        } catch (RejectedExecutionException e) {
            cancellation = "cancel_dispatch_rejected_unconfirmed";
        }
    }

    /** 关闭会话时独立尝试释放连接；不能让驱动 close/cancel 阻塞 UI 线程。 */
    void closeConnection() {
        final Connection live = connection;
        if (live == null || finished) return;
        try { cancellations.execute(() -> { try { live.close(); } catch (Exception ignored) { } }); }
        catch (RejectedExecutionException ignored) { /* 原查询返回后 finally 仍负责关闭。 */ }
    }

    String cancellationState() { return cancellation; }
    void finished() { finished = true; statement = null; connection = null; }
}
