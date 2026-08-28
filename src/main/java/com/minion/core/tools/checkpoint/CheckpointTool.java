package com.minion.core.tools.checkpoint;

import com.google.gson.JsonObject;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 查看和恢复 Write/Edit 自动创建的文件检查点。 */
public final class CheckpointTool implements Tool {
    private final CheckpointStore store;

    public CheckpointTool(CheckpointStore store) { this.store = store; }
    @Override public String name() { return "Checkpoint"; }
    @Override public String description() { return "文件检查点：list 查看，restore 恢复，clear 清理；Write/Edit 修改前自动创建"; }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("文件修改检查点",
                new String[]{"action", "id", "limit"}, new String[]{"action"});
    }
    @Override public boolean isHighRisk(JsonObject args) {
        String action = str(args, "action").toLowerCase(Locale.ROOT);
        return "restore".equals(action) || "clear".equals(action);
    }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = str(args, "action").toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            int limit = integer(args, "limit", 20);
            List<CheckpointStore.Entry> entries = store.list(Math.min(100, Math.max(1, limit)));
            if (entries.isEmpty()) return ToolResult.success("暂无检查点");
            StringBuilder sb = new StringBuilder("检查点（").append(entries.size()).append("）：");
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (CheckpointStore.Entry e : entries) {
                sb.append('\n').append(e.id).append(" | ").append(fmt.format(new Date(e.createdAt)))
                        .append(" | ").append(e.action).append(" | ")
                        .append(e.existed ? "还原原内容" : "还原为不存在").append(" | ").append(e.target);
            }
            return ToolResult.success(sb.toString());
        }
        if ("restore".equals(action)) {
            String id = str(args, "id");
            if (id.isEmpty()) return ToolResult.error("restore 缺少 id");
            return ToolResult.success("已恢复: " + store.restore(id));
        }
        if ("clear".equals(action)) return ToolResult.success("已清理检查点文件 " + store.clear() + " 个");
        if ("status".equals(action)) return ToolResult.success("检查点目录: " + store.root());
        return ToolResult.error("未知 action: " + action + "（支持 status/list/restore/clear）");
    }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static int integer(JsonObject o, String k, int d) { try { return o.has(k) ? o.get(k).getAsInt() : d; } catch (Exception e) { return d; } }
}
