package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import com.minion.core.tools.dev.ProcessTool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Monitor（开发说明 v2.4 §3.2，tools.monitor.enabled 默认 false）：
 * 后台运行命令，stdout 逐行作为事件注入会话——行缓冲 + 限速聚合（10 行或 5s 批量
 * flush，防高频输出撑爆上下文），单监控总事件 ≤500、行去重；退出即结束。
 * 进程生命周期复用会话 {@link ProcessTool}（唯一真源：账本/取消围栏/TaskStop 可停）。
 */
public final class MonitorTool implements Tool {

    private static final int FLUSH_LINES = 10;
    private static final long FLUSH_MILLIS = 5000;
    private static final int MAX_EVENTS = 500;

    private final ProcessTool process;
    private final Session session;

    public MonitorTool(ProcessTool process, Session session) {
        this.process = process;
        this.session = session;
    }

    @Override public String name() { return "Monitor"; }

    @Override public String description() {
        return "后台运行命令并监控 stdout：每行作为事件注入会话（限速聚合）；退出即结束；用 TaskStop 终止";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("监控命令输出",
                new String[]{"command", "description"}, new String[]{"command"});
    }

    @Override public boolean isHighRisk(JsonObject args) { return true; }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String command = args.has("command") && !args.get("command").isJsonNull()
                ? args.get("command").getAsString() : "";
        if (command.trim().isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 command");
        String description = args.has("description") && !args.get("description").isJsonNull()
                ? args.get("description").getAsString() : command;
        // 经 ProcessTool 启动后台进程（waitForCompletion=false）
        JsonObject start = new JsonObject();
        start.addProperty("action", "start");
        start.addProperty("command", command);
        ToolResult started = process.execute(start);
        if (!started.ok) return started;
        final String jobId = started.output.split("\n", 2)[0]
                .replace("已启动后台进程 ", "").trim();
        final CopyOnWriteArrayList<String> buffer = new CopyOnWriteArrayList<String>();
        final long monitorStart = System.currentTimeMillis();
        Thread listener = new Thread(() -> runListener(jobId, description, buffer, monitorStart),
                "minion-monitor-" + jobId);
        listener.setDaemon(true);
        listener.start();
        return ToolResult.success("监控已启动 " + jobId + "（" + description
                + "）；事件按批注入会话；TaskStop " + jobId + " 可终止");
    }

    /** 轮询 tail-follow 读取结果文件并按批注入 session.messages（taskControl，不入 UI 用户行）。
     *  文件异步追加：静态 readLine 会在当前 EOF 提前结束，须按增长轮询；
     *  退出条件 = 进程已结算且无新增字节，或 MAX_EVENTS 上限（TaskStop 随时可终止）。 */
    private void runListener(String jobId, String description, CopyOnWriteArrayList<String> buffer, long start) {
        try {
            java.nio.file.Path result = resultPath(jobId);
            if (result == null) return;
            long lastFlush = System.currentTimeMillis();
            int totalEvents = 0;
            java.util.Set<String> seen = new java.util.HashSet<String>();
            long fileOffset = 0;
            byte[] chunk = new byte[8192];
            java.io.ByteArrayOutputStream pendingLine = new java.io.ByteArrayOutputStream();
            while (totalEvents < MAX_EVENTS) {
                Thread.sleep(400);
                long before = fileOffset;
                long length = java.nio.file.Files.size(result);
                if (length > fileOffset) {
                    try (java.io.InputStream in = java.nio.file.Files.newInputStream(result)) {
                        in.skip(fileOffset);
                        int n;
                        while ((n = in.read(chunk)) > 0) {
                            for (int i = 0; i < n; i++) {
                                byte b = chunk[i];
                                if (b == '\n') {
                                    String line = new String(pendingLine.toByteArray(), StandardCharsets.UTF_8).trim();
                                    pendingLine.reset();
                                    if (!line.isEmpty() && seen.add(line) && totalEvents < MAX_EVENTS) {
                                        buffer.add(line);
                                        totalEvents++;
                                    }
                                } else pendingLine.write(b);
                            }
                            fileOffset += n;
                        }
                    }
                }
                if (buffer.size() >= FLUSH_LINES || System.currentTimeMillis() - lastFlush >= FLUSH_MILLIS) {
                    flush(jobId, description, buffer);
                    lastFlush = System.currentTimeMillis();
                }
                if (fileOffset == before && length == fileOffset && jobSettled(jobId)) break;
            }
            if (!buffer.isEmpty()) flush(jobId, description, buffer);
            session.messages.add(Message.taskControl("[监控结束] " + description + "（" + jobId
                    + "，共 " + totalEvents + " 行事件，耗时 "
                    + (System.currentTimeMillis() - start) / 1000 + "s）"));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 进程被停/文件清理：监听静默结束（账本已有取消围栏）
        }
    }

    /** 账本中该 job 是否已结算（completed/failed/cancelled） */
    private boolean jobSettled(String jobId) {
        try {
            JsonObject status = new JsonObject();
            status.addProperty("action", "status");
            status.addProperty("id", jobId);
            String out = process.execute(status).output;
            return out.contains("COMPLETED") || out.contains("FAILED") || out.contains("CANCELLED");
        } catch (Exception unavailable) {
            return true;
        }
    }

    private void flush(String jobId, String description, CopyOnWriteArrayList<String> buffer) {
        if (buffer.isEmpty()) return;
        StringBuilder sb = new StringBuilder("[监控事件] " + description + "（" + jobId + "）：\n");
        for (String line : buffer) sb.append("  ").append(line).append('\n');
        buffer.clear();
        session.messages.add(Message.taskControl(sb.toString()));
    }

    private java.nio.file.Path resultPath(String jobId) {
        try {
            JsonObject status = new JsonObject();
            status.addProperty("action", "status");
            status.addProperty("id", jobId);
            String out = process.execute(status).output;
            for (String line : out.split("\n")) {
                int at = line.indexOf("resultPath=");
                if (at >= 0)
                    return java.nio.file.Paths.get(line.substring(at + "resultPath=".length()).trim());
            }
        } catch (Exception ignored) { }
        return null;
    }
}
