package com.minion.core.tools.python;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 面向内网 Anaconda 的 Python 3 工具；不依赖 OpenAI 或联网安装。 */
public class PythonTool implements Tool {
    private final Workspace workspace;
    private final PythonRuntime runtime;

    public PythonTool(Workspace workspace, PythonRuntime runtime) { this.workspace = workspace; this.runtime = runtime; }
    @Override public String name() { return "Python"; }
    @Override public String description() { return "调用本机 Python/Anaconda：status 检查环境，run_code 执行代码，run_file 执行工作区脚本；适合 pandas、数据分析、构建与自动化"; }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("Python 3 执行工具",
                new String[]{"action", "code", "path", "arguments", "timeoutSeconds"}, new String[]{"action"});
    }
    @Override public boolean isHighRisk(JsonObject args) {
        String action = str(args, "action").toLowerCase(Locale.ROOT);
        if ("run_file".equals(action)) return true;
        String code = str(args, "code").toLowerCase(Locale.ROOT);
        return code.contains("os.remove") || code.contains("os.unlink") || code.contains("shutil.rmtree")
                || code.contains("subprocess") || code.contains("winreg") || code.contains("requests.")
                || code.contains("urllib.") || code.contains("socket.");
    }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = str(args, "action").toLowerCase(Locale.ROOT);
        if ("status".equals(action)) return result(runtime.status(workspace.cwd()));
        int timeout = integer(args, "timeoutSeconds", 120);
        if ("run_code".equals(action)) {
            String code = str(args, "code");
            if (code.isEmpty()) return ToolResult.error("run_code 缺少 code 参数");
            return result(runtime.runCode(code, workspace.cwd(), timeout));
        }
        if ("run_file".equals(action)) {
            String value = str(args, "path");
            if (value.isEmpty()) return ToolResult.error("run_file 缺少 path 参数");
            Path path = PathsGuard.resolve(workspace.cwd().toString(), value).toAbsolutePath().normalize();
            ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), path);
            if (guard != null) return guard;
            return result(runtime.runFile(path, arguments(str(args, "arguments")), workspace.cwd(), timeout));
        }
        return ToolResult.error("未知 action: " + action + "（支持 status/run_code/run_file）");
    }
    private static ToolResult result(PythonRuntime.RunResult r) {
        String output = r.output == null || r.output.trim().isEmpty() ? "(无输出)" : r.output.trim();
        return r.ok() ? ToolResult.success(output) : ToolResult.error("Python 退出码 " + r.exitCode + "\n" + output);
    }
    private static List<String> arguments(String json) {
        List<String> out = new ArrayList<String>();
        if (json == null || json.trim().isEmpty()) return out;
        try { for (com.google.gson.JsonElement e : new Gson().fromJson(json, JsonArray.class)) out.add(e.getAsString()); }
        catch (Exception e) { out.add(json); }
        return out;
    }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static int integer(JsonObject o, String k, int def) { try { return o.has(k) ? Integer.parseInt(o.get(k).getAsString()) : def; } catch (Exception e) { return def; } }
}
