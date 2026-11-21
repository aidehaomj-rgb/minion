package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.dev.ProcessTool;

/**
 * TaskStop（v2.4 §3.2，别名 KillShell 语义）：终止后台任务（杀进程树复用 Process/ProcessCleanup；
 * durable cancel 围栏先行——竞态中的成功回执按归档处理）。委托会话 ProcessTool。
 */
public final class TaskStopTool implements Tool {

    private final ProcessTool process;

    public TaskStopTool(ProcessTool process) { this.process = process; }

    @Override public String name() { return "TaskStop"; }

    @Override public String description() { return "终止后台任务（task_id 兼容 shell_id；进程树终止，退出以回执为准）"; }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("终止后台任务",
                new String[]{"task_id"}, new String[]{"task_id"});
    }

    @Override public boolean isHighRisk(JsonObject args) { return true; }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String id = args.has("task_id") && !args.get("task_id").isJsonNull()
                ? args.get("task_id").getAsString()
                : (args.has("shell_id") ? args.get("shell_id").getAsString() : "");
        if (id.trim().isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 task_id 参数");
        JsonObject stop = new JsonObject();
        stop.addProperty("action", "stop");
        stop.addProperty("id", id);
        return process.execute(stop);
    }
}
