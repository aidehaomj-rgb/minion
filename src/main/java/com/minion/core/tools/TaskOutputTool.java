package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.dev.ProcessTool;

/**
 * TaskOutput（v2.4 §3.2，别名 BashOutput 语义）：取后台任务增量输出；
 * block=true 等待完成或超时。委托会话 ProcessTool（唯一真源：同一账本/输出文件/取消围栏），
 * 也适用于取后台子代理回执所登记的 runtime job。
 */
public final class TaskOutputTool implements Tool {

    public static final long DEFAULT_TIMEOUT_MS = 30_000;
    public static final long MAX_TIMEOUT_MS = 600_000;

    private final ProcessTool process;

    public TaskOutputTool(ProcessTool process) { this.process = process; }

    @Override public String name() { return "TaskOutput"; }

    @Override public String description() {
        return "读取后台任务输出（Bash run_in_background / Process start 的任务 id）；"
                + "block=true 时等待任务结束或超时（默认 true，timeout 毫秒默认 30000、上限 600000）";
    }

    @Override public JsonObject schema() {
        JsonObject schema = SchemaGenerator.objectSchema("读取后台任务增量输出",
                new String[]{"task_id", "block", "timeout"}, new String[]{"task_id"});
        schema.getAsJsonObject("properties").addProperty("block", "boolean");
        return schema;
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String id = args.has("task_id") && !args.get("task_id").isJsonNull()
                ? args.get("task_id").getAsString()
                : (args.has("shell_id") ? args.get("shell_id").getAsString() : "");
        if (id.trim().isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 task_id 参数");
        boolean block = !args.has("block") || args.get("block").isJsonNull()
                || "true".equalsIgnoreCase(args.get("block").getAsString());
        long timeout = DEFAULT_TIMEOUT_MS;
        try {
            if (args.has("timeout") && !args.get("timeout").isJsonNull())
                timeout = Long.parseLong(args.get("timeout").getAsString().trim());
        } catch (NumberFormatException e) {
            return ToolResult.errorWithoutSideEffects("timeout 格式错误: " + e.getMessage());
        }
        timeout = Math.max(0, Math.min(timeout, MAX_TIMEOUT_MS));
        if (block) {
            JsonObject wait = new JsonObject();
            wait.addProperty("action", "wait");
            wait.addProperty("id", id);
            wait.addProperty("timeoutMillis", timeout);
            ToolResult waited = process.execute(wait);
            JsonObject out = new JsonObject();
            out.addProperty("action", "output");
            out.addProperty("id", id);
            ToolResult output = process.execute(out);
            return ToolResult.success(waited.output + "\n" + output.output);
        }
        JsonObject out = new JsonObject();
        out.addProperty("action", "output");
        out.addProperty("id", id);
        return process.execute(out);
    }
}
