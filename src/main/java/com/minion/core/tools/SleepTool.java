package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Sleep（v2.4 §3.2）：可中断等待原语——不产生进程、不占 shell；用户中断（Esc/停止）经
 * cancellation 供应者立即打断。AgentLoop 内的等待语义（轮询间隔、限流退避）应使用本原语。
 */
public final class SleepTool implements Tool {

    private static final int MAX_SECONDS = 600;
    private final BooleanSupplier cancelled;

    public SleepTool(BooleanSupplier cancelled) {
        this.cancelled = cancelled == null ? () -> false : cancelled;
    }

    @Override public String name() { return "Sleep"; }

    @Override public String description() {
        return "等待指定秒数（可被用户中断立即打断；不产生进程、不占用 shell）；上限 " + MAX_SECONDS + "s";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("可中断等待",
                new String[]{"seconds"}, new String[]{"seconds"});
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        double seconds;
        try {
            seconds = args.has("seconds") ? args.get("seconds").getAsDouble() : -1;
        } catch (NumberFormatException e) {
            return ToolResult.errorWithoutSideEffects("seconds 格式错误: " + e.getMessage());
        }
        if (seconds <= 0) return ToolResult.errorWithoutSideEffects("seconds 必须为正数（≤" + MAX_SECONDS + "）");
        if (seconds > MAX_SECONDS)
            return ToolResult.errorWithoutSideEffects("seconds 超过上限 " + MAX_SECONDS + "s；更长的间隔请拆分多次");
        long deadline = System.nanoTime() + (long) (seconds * 1_000_000_000L);
        while (System.nanoTime() < deadline) {
            if (cancelled.getAsBoolean())
                return ToolResult.errorWithoutSideEffects("等待已被用户中断");
            long left = deadline - System.nanoTime();
            TimeUnit.NANOSECONDS.sleep(Math.min(left, 200_000_000L));   // 200ms 检查点：中断尽快生效
            if (Thread.currentThread().isInterrupted())
                return ToolResult.errorWithoutSideEffects("等待被中断");
        }
        return ToolResult.success("已等待 " + seconds + "s");
    }
}
