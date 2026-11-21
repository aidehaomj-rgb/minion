package com.minion.core.ui.statusline;

import com.google.gson.JsonObject;
import com.minion.core.security.TrustGate;

import java.util.concurrent.TimeUnit;

/**
 * v2.4 §10.4 自定义 statusLine 执行器：settings statusLine:{type:"command",command}；
 * 每次把 JSON 经 stdin 传入（session_id/model/workspace/cost/context_window 百分比/
 * output_style/vim.mode），取 stdout <b>首行</b>渲染；5s 超时；exit≠0/无输出忽略（返回 null）。
 * 启动前经 §10.9 信任复核（statusline 类型；gate 可空=不复核，仅单测）。
 * 单实例串行：新请求到来时取消旧进程（interrupt 旧线程由调用方管理；本类同步执行）。
 */
public final class StatusLineRunner {

    public static final long TIMEOUT_MS = 5000;

    /** 同步执行：null=忽略（未配置/信任拒绝/超时/非零退出/空输出） */
    public static String run(String command, JsonObject payload, TrustGate gate, String workspaceRoot) {
        if (command == null || command.trim().isEmpty()) return null;
        if (gate != null) {
            com.minion.core.security.TrustedExecutable spec = com.minion.core.security.TrustedExecutable.of(
                    com.minion.core.security.TrustedExecutable.TYPE_STATUSLINE,
                    com.minion.core.security.TrustedExecutable.shortId(command),
                    "user", command, java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String, String>emptyMap(), workspaceRoot,
                    com.minion.core.security.TrustedExecutable.referencedScripts(command,
                            java.nio.file.Paths.get(workspaceRoot == null ? "." : workspaceRoot)));
            if (!gate.ensureTrusted(spec)) return null;   // §10.9 拒绝 → 不执行子进程
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? new String[]{"cmd.exe", "/c", command}
                            : new String[]{"sh", "-c", command});
            p = pb.start();
            p.getOutputStream().write(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            p.getOutputStream().close();
            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String first = reader.readLine();
            return first == null || first.trim().isEmpty() ? null : first.trim();
        } catch (Exception failed) {
            if (p != null) p.destroyForcibly();
            return null;
        }
    }

    /** 组装 stdin payload（两层状态栏字段口径） */
    public static JsonObject payload(String sessionId, String model, String workspace,
            String costSummary, int contextWindowUsedPercent, String outputStyle, String vimMode) {
        JsonObject o = new JsonObject();
        o.addProperty("session_id", sessionId == null ? "" : sessionId);
        o.addProperty("model", model == null ? "" : model);
        o.addProperty("workspace", workspace == null ? "" : workspace);
        o.addProperty("cost", costSummary == null ? "" : costSummary);
        o.addProperty("context_window", contextWindowUsedPercent);
        o.addProperty("output_style", outputStyle == null ? "default" : outputStyle);
        o.addProperty("vim.mode", vimMode == null ? "" : vimMode);
        return o;
    }

    private StatusLineRunner() { }
}
