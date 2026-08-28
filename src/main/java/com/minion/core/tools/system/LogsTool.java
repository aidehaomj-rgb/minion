package com.minion.core.tools.system;

import com.google.gson.JsonObject;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 诊断日志查看与脱敏导出。 */
public final class LogsTool implements Tool {
    private final Workspace workspace;
    public LogsTool(Workspace workspace) { this.workspace = workspace; }
    @Override public String name() { return "Logs"; }
    @Override public String description() { return "查看、导出或清理 Minion 脱敏诊断日志"; }
    @Override public JsonObject schema() { return SchemaGenerator.objectSchema("诊断日志", new String[]{"action", "lines", "path"}, new String[]{"action"}); }
    @Override public boolean isHighRisk(JsonObject args) { return "clear".equalsIgnoreCase(str(args, "action")); }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = str(args, "action").toLowerCase(Locale.ROOT);
        if ("tail".equals(action) || "status".equals(action)) {
            int count = integer(args, "lines", 100);
            List<String> lines = DiagnosticLog.tail(count);
            if (lines.isEmpty()) return ToolResult.success("暂无日志；路径=" + DiagnosticLog.file());
            StringBuilder sb = new StringBuilder();
            for (String line : lines) sb.append(line).append('\n');
            return ToolResult.success(sb.toString().trim());
        }
        if ("export".equals(action)) {
            String value = str(args, "path");
            Path target = value.isEmpty() ? workspace.cwd().resolve("minion-support.log")
                    : workspace.cwd().resolve(value).normalize().toAbsolutePath();
            if (!target.startsWith(workspace.cwd().toAbsolutePath().normalize())) return ToolResult.error("日志只能导出到当前工作区");
            return ToolResult.success("日志已导出: " + DiagnosticLog.exportTo(target));
        }
        if ("clear".equals(action)) { DiagnosticLog.clear(); return ToolResult.success("日志已清理"); }
        return ToolResult.error("未知 action: " + action + "（支持 status/tail/export/clear）");
    }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static int integer(JsonObject o, String k, int d) { try { return o.has(k) ? o.get(k).getAsInt() : d; } catch (Exception e) { return d; } }
}
