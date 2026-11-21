package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.agent.Session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * TaskV2 工具适配层（开发说明 v2.4 §3.4）：TaskCreate/TaskGet/TaskUpdate/TaskList。
 *
 * <p><b>TaskWorkflow/TaskState 是唯一事实源</b>：V2 字段映射 Step（subject→title、
 * description→acceptance、blockedBy→dependsOn）；状态迁移全部经 {@link com.minion.core.agent.TaskWorkflow#transition}
 * ——completed 仍要求真实回执与 note（V68 验收门禁不被新状态机绕过，规格明令）；
 * in_progress 的依赖拦截、deleted 的取消说明均由 transition 强制。本类只做参数适配与
 * 兼容快照导出（{workDir}/.minion/tasks/{sessionId}.json，串行原子写），不建第二状态。
 */
public final class TaskV2Tools {

    private final Session session;
    private final Object ioLock = new Object();

    public TaskV2Tools(Session session) { this.session = session; }

    /** 单动作工具包装（TaskCreate/TaskGet/TaskUpdate/TaskList 共用一个适配实例） */
    public static Tool action(final String name, final TaskV2Tools adapter, final String action) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() {
                if ("create".equals(action)) return "创建持久任务（subject/description/blockedBy 依赖）";
                if ("get".equals(action)) return "按 task_id 查看任务";
                if ("update".equals(action)) return "更新任务状态 pending→in_progress→completed/deleted（completed 需真实回执）";
                return "列出全部任务";
            }
            @Override public JsonObject schema() {
                if ("create".equals(action))
                    return SchemaGenerator.objectSchema("创建任务",
                            new String[]{"subject", "description", "blockedBy", "activeForm"},
                            new String[]{"subject"});
                if ("update".equals(action))
                    return SchemaGenerator.objectSchema("更新任务",
                            new String[]{"task_id", "status", "note", "receipts"},
                            new String[]{"task_id", "status"});
                return SchemaGenerator.objectSchema(name, new String[]{"task_id"}, new String[0]);
            }
            @Override public boolean isHighRisk(JsonObject args) {
                return "update".equals(action);   // 状态迁移含取消/完成判定
            }
            @Override public boolean isConcurrencySafe(JsonObject args) { return false; }   // 同一 store 串行
            @Override public ToolResult execute(JsonObject args) throws Exception {
                return adapter.dispatch(action, args);
            }
        };
    }

    private com.minion.core.agent.TaskWorkflow workflow() {
        return session.taskState.workflow;
    }

    /** 惰性建工作流；任务尚未开始（taskState.id=null）时先绑定当前任务 id */
    private com.minion.core.agent.TaskWorkflow ensureWorkflow() {
        String taskId = session.taskState.id;
        if (taskId == null) {   // V2 先于首轮用户输入使用：开一个任务容器
            taskId = java.util.UUID.randomUUID().toString();
            session.taskState.id = taskId;
        }
        com.minion.core.agent.TaskWorkflow existing = workflow();
        if (existing != null && taskId.equals(existing.taskId)) return existing;
        com.minion.core.agent.TaskWorkflow wf = new com.minion.core.agent.TaskWorkflow();
        wf.taskId = taskId;
        wf.revision = session.taskState.revision();
        session.taskState.workflow = wf;
        return wf;
    }

    public ToolResult dispatch(String action, JsonObject args) throws IOException {
        if ("create".equals(action)) return create(args);
        if ("get".equals(action)) return get(text(args, "task_id"));
        if ("update".equals(action)) return update(args);
        if ("list".equals(action)) return list();
        return ToolResult.errorWithoutSideEffects("未知 action: " + action + "（create/get/update/list）");
    }

    private ToolResult create(JsonObject args) {
        String subject = text(args, "subject");
        if (subject.trim().isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 subject");
        String description = text(args, "description");
        String activeForm = text(args, "activeForm");
        List<String> blockedBy = stringList(args, "blockedBy");
        com.minion.core.agent.TaskWorkflow wf = ensureWorkflow();
        // 依赖存在性 + 环检测（新步骤无人依赖，只需其依赖链无环且存在）
        for (String dep : blockedBy)
            if (find(wf, dep) == null)
                return ToolResult.errorWithoutSideEffects("blockedBy 引用不存在的任务: " + dep);
        com.minion.core.agent.TaskWorkflow.Step s = new com.minion.core.agent.TaskWorkflow.Step();
        s.id = "t" + UUID.randomUUID().toString().substring(0, 8);
        s.title = subject;
        s.scope = "taskv2:" + s.id;   // 写域守卫占位：V68 的 scope 语义（写范围）不适用轻量任务
        s.acceptance = description.isEmpty() ? subject : description;
        s.note = activeForm.isEmpty() ? "" : "activeForm: " + activeForm;
        s.dependsOn = new ArrayList<String>(blockedBy);
        wf.steps.add(s);
        exportSnapshotQuietly();
        return ToolResult.success("已创建任务 " + s.id + ": " + subject
                + (blockedBy.isEmpty() ? "" : "（blockedBy: " + blockedBy + "）"));
    }

    private ToolResult get(String id) {
        com.minion.core.agent.TaskWorkflow.Step s = workflow() == null ? null : find(workflow(), id);
        if (s == null) return ToolResult.errorWithoutSideEffects("任务不存在: " + id);
        return ToolResult.success(render(s));
    }

    private ToolResult update(JsonObject args) {
        com.minion.core.agent.TaskWorkflow wf = workflow();
        if (wf == null) return ToolResult.errorWithoutSideEffects("尚无任务（先 create）");
        String id = text(args, "task_id");
        com.minion.core.agent.TaskWorkflow.Step s = find(wf, id);
        if (s == null) return ToolResult.errorWithoutSideEffects("任务不存在: " + id);
        String status = text(args, "status");
        String action;
        if ("completed".equals(status)) action = "complete";
        else if ("in_progress".equals(status)) action = "start";
        else if ("deleted".equals(status)) action = "cancel";
        else if ("pending".equals(status)) action = "reopen";
        else return ToolResult.errorWithoutSideEffects(
                "status 只支持 pending/in_progress/completed/deleted: " + status);
        List<String> receipts = stringList(args, "receipts");
        String note = text(args, "note");
        try {
            wf.transition(session.taskState, id, action, receipts, new ArrayList<>(), note, null);
        } catch (Exception denied) {
            return ToolResult.errorWithoutSideEffects("状态迁移被拒绝（V68 验收门禁不被绕过）: "
                    + denied.getMessage());
        }
        exportSnapshotQuietly();
        return ToolResult.success("已更新 " + id + " → " + status);
    }

    private ToolResult list() {
        com.minion.core.agent.TaskWorkflow wf = workflow();
        if (wf == null || wf.steps.isEmpty()) return ToolResult.success("无任务");
        StringBuilder sb = new StringBuilder();
        for (com.minion.core.agent.TaskWorkflow.Step s : wf.steps) sb.append(render(s)).append('\n');
        return ToolResult.success(sb.toString().trim());
    }

    // ==================== 辅助 ====================

    private static com.minion.core.agent.TaskWorkflow.Step find(
            com.minion.core.agent.TaskWorkflow wf, String id) {
        for (com.minion.core.agent.TaskWorkflow.Step s : wf.steps)
            if (s.id.equals(id)) return s;
        return null;
    }

    private static String render(com.minion.core.agent.TaskWorkflow.Step s) {
        return s.id + " [" + s.status + "] " + s.title
                + (s.dependsOn.isEmpty() ? "" : "（blockedBy: " + s.dependsOn + "）")
                + (s.note == null || s.note.isEmpty() ? "" : " —— " + s.note);
    }

    /** 兼容快照导出包装：失败仅告警不影响任务状态（事实源在 TaskState） */
    private void exportSnapshotQuietly() {
        try {
            exportSnapshot();
        } catch (java.io.IOException failed) {
            com.minion.core.diagnostics.DiagnosticLog.warn("taskv2",
                    "快照导出失败（不影响任务状态）: " + failed.getMessage());
        }
    }

    /** 兼容格式快照导出（唯一真源仍为 TaskState；ioLock 内串行原子写） */
    private void exportSnapshot() throws IOException {
        synchronized (ioLock) {
            Path file = Paths.get(session.workDir, ".minion", "tasks", session.id + ".json");
            Files.createDirectories(file.getParent());
            JsonObject out = new JsonObject();
            out.addProperty("taskId", session.taskState.id);
            out.addProperty("revision", session.taskState.revision());
            JsonArray arr = new JsonArray();
            com.minion.core.agent.TaskWorkflow wf = workflow();
            if (wf != null) for (com.minion.core.agent.TaskWorkflow.Step s : wf.steps) {
                JsonObject o = new JsonObject();
                o.addProperty("id", s.id);
                o.addProperty("subject", s.title);
                o.addProperty("status", s.status);
                if (s.acceptance != null) o.addProperty("description", s.acceptance);
                if (!s.dependsOn.isEmpty()) {
                    JsonArray deps = new JsonArray();
                    for (String d : s.dependsOn) deps.add(new com.google.gson.JsonPrimitive(d));
                    o.add("blockedBy", deps);
                }
                arr.add(o);
            }
            out.add("tasks", arr);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, out.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static String text(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }

    private static List<String> stringList(JsonObject o, String key) {
        List<String> out = new ArrayList<String>();
        if (o != null && o.has(key) && o.get(key).isJsonArray())
            for (JsonElement e : o.getAsJsonArray(key))
                if (!e.isJsonNull()) out.add(e.getAsString().trim());
        return out;
    }
}
