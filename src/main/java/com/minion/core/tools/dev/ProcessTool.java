package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 会话级后台进程管理。所有进程均限制在工作区目录，应用退出时统一回收。 */
public final class ProcessTool implements Tool {
    private static final int MAX_OUTPUT = 120_000;
    private final Workspace workspace;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, Managed> processes = new LinkedHashMap<String, Managed>();

    public ProcessTool(Workspace workspace) { this.workspace = workspace; }
    @Override public String name() { return "Process"; }
    @Override public String description() { return "后台进程：start/list/output/stop/clean；适合本地服务、构建和长任务，启动/停止需确认"; }
    @Override public JsonObject schema() { return SchemaGenerator.objectSchema("后台进程管理",
            new String[]{"action","command","cwd","id","offset","limit"}, new String[]{"action"}); }
    @Override public boolean isHighRisk(JsonObject args) {
        String a = text(args, "action").toLowerCase(Locale.ROOT);
        return "start".equals(a) || "stop".equals(a) || "clean".equals(a);
    }

    @Override public synchronized ToolResult execute(JsonObject args) throws Exception {
        String action = text(args, "action").toLowerCase(Locale.ROOT);
        if ("list".equals(action)) return list();
        if ("output".equals(action)) return output(text(args, "id"), integer(args, "offset", 0), integer(args, "limit", 30_000));
        if ("start".equals(action)) return start(text(args, "command"), text(args, "cwd"));
        if ("stop".equals(action)) return stop(text(args, "id"));
        if ("clean".equals(action)) return clean();
        return ToolResult.error("未知 action: " + action + "（支持 start/list/output/stop/clean）");
    }

    private ToolResult start(String command, String cwdText) throws Exception {
        if (command.trim().isEmpty()) return ToolResult.error("start 缺少 command");
        Path cwd = cwdText.trim().isEmpty() ? workspace.cwd() : workspace.cwd().resolve(cwdText).normalize().toAbsolutePath();
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), cwd);
        if (guard != null) return guard;
        if (!Files.isDirectory(cwd)) return ToolResult.error("工作目录不存在: " + cwd);
        List<String> invocation = new ArrayList<String>();
        if (isWindows()) { invocation.add("cmd.exe"); invocation.add("/d"); invocation.add("/s"); invocation.add("/c"); invocation.add(command); }
        else { invocation.add("sh"); invocation.add("-lc"); invocation.add(command); }
        ProcessBuilder pb = new ProcessBuilder(invocation).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = pb.start();
        String id = "p" + sequence.incrementAndGet();
        Managed managed = new Managed(id, command, cwd, process);
        processes.put(id, managed);
        managed.reader.start();
        return ToolResult.success("已启动后台进程 " + id + "\n命令: " + command + "\n目录: " + cwd);
    }

    private ToolResult list() {
        if (processes.isEmpty()) return ToolResult.success("没有后台进程");
        StringBuilder out = new StringBuilder();
        for (Managed m : processes.values()) out.append(m.id).append("  ").append(m.running() ? "RUNNING" : "EXIT " + m.exitCode())
                .append("  ").append(m.command).append("\n");
        return ToolResult.success(out.toString().trim());
    }

    private ToolResult output(String id, int offset, int limit) {
        Managed m = processes.get(id);
        if (m == null) return ToolResult.error("进程不存在: " + id);
        String all = m.text();
        int from = Math.max(0, Math.min(offset, all.length()));
        int to = Math.min(all.length(), from + Math.max(1, Math.min(limit, 60_000)));
        return ToolResult.success("进程 " + id + " " + (m.running() ? "RUNNING" : "EXIT " + m.exitCode())
                + "\n输出范围: " + from + "-" + to + " / " + all.length() + "\n" + all.substring(from, to));
    }

    private ToolResult stop(String id) {
        Managed m = processes.get(id);
        if (m == null) return ToolResult.error("进程不存在: " + id);
        kill(m.process);
        return ToolResult.success("已终止进程 " + id);
    }

    private ToolResult clean() {
        int stopped = 0;
        for (Managed m : processes.values()) if (m.running()) { kill(m.process); stopped++; }
        int total = processes.size();
        processes.clear();
        return ToolResult.success("已清理 " + total + " 个记录，终止 " + stopped + " 个运行中进程");
    }

    public synchronized void shutdown() { for (Managed m : processes.values()) if (m.running()) kill(m.process); processes.clear(); }

    private static void kill(Process p) {
        try {
            long pid = pidOf(p);
            if (pid > 0 && isWindows()) new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F").start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        p.destroy();
        try { p.destroyForcibly(); } catch (Throwable ignored) { }
    }

    private static long pidOf(Process p) {
        try {
            java.lang.reflect.Field field = p.getClass().getDeclaredField("handle"); field.setAccessible(true);
            long handle = field.getLong(p);
            java.lang.reflect.Method method = Class.forName("java.lang.ProcessImpl").getDeclaredMethod("getProcessId", long.class); method.setAccessible(true);
            return ((Number) method.invoke(null, handle)).longValue();
        } catch (Exception ignored) { return -1; }
    }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    private static String text(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static int integer(JsonObject o, String k, int d) { try { return o.has(k) ? o.get(k).getAsInt() : d; } catch (Exception e) { return d; } }

    private static final class Managed {
        final String id, command; final Path cwd; final Process process; final StringBuilder output = new StringBuilder(); final Thread reader;
        Managed(String id, String command, Path cwd, Process process) {
            this.id=id; this.command=command; this.cwd=cwd; this.process=process;
            this.reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line=br.readLine()) != null) synchronized (output) { if (output.length() < MAX_OUTPUT) output.append(line).append('\n'); }
                } catch (Exception ignored) { }
            }, "minion-process-" + id); this.reader.setDaemon(true);
        }
        boolean running() { try { process.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; } }
        int exitCode() { try { return process.exitValue(); } catch (IllegalThreadStateException e) { return -1; } }
        String text() { synchronized (output) { return output.toString(); } }
    }
}
