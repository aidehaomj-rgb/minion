package com.minion.core.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Win7 兼容滚动诊断日志。全局实例仅保存 jarDir 下日志路径，不持有 GUI 对象。 */
public final class DiagnosticLog {
    private static final long ROTATE_BYTES = 2L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static volatile Path file;

    private DiagnosticLog() { }

    public static void initialize(Path jarDir) {
        if (jarDir == null) return;
        file = jarDir.resolve(".minion").resolve("logs").resolve("minion.log");
    }

    public static Path file() { return file; }

    public static void info(String category, String message) { write("INFO", category, message); }
    public static void warn(String category, String message) { write("WARN", category, message); }
    public static void error(String category, String message) { write("ERROR", category, message); }

    public static void tool(String name, long elapsedMs, boolean ok, String detail) {
        write(ok ? "INFO" : "WARN", "tool",
                name + " elapsedMs=" + elapsedMs + " ok=" + ok + " " + safeOneLine(detail));
    }

    public static List<String> tail(int maxLines) throws IOException {
        Path p = file;
        if (p == null || !Files.exists(p)) return new ArrayList<String>();
        List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
        int from = Math.max(0, all.size() - Math.max(1, Math.min(maxLines, 1000)));
        return new ArrayList<String>(all.subList(from, all.size()));
    }

    public static Path exportTo(Path target) throws IOException {
        Path p = file;
        if (p == null || !Files.exists(p)) throw new IOException("尚无诊断日志");
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(SecretRedactor.redact(line)).append('\n');
        Files.write(target, sb.toString().getBytes(StandardCharsets.UTF_8));
        return target;
    }

    public static void clear() throws IOException {
        Path p = file;
        if (p != null) Files.deleteIfExists(p);
    }

    private static void write(String level, String category, String message) {
        Path p = file;
        if (p == null) return;
        synchronized (LOCK) {
            try {
                Files.createDirectories(p.getParent());
                if (Files.exists(p) && Files.size(p) >= ROTATE_BYTES) {
                    Path old = p.resolveSibling("minion.log.1");
                    Files.move(p, old, StandardCopyOption.REPLACE_EXISTING);
                }
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
                String line = time + " [" + level + "] [" + category + "] "
                        + SecretRedactor.redact(message == null ? "" : message) + System.lineSeparator();
                Files.write(p, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        }
    }

    private static String safeOneLine(String text) {
        if (text == null) return "";
        String one = text.replace('\r', ' ').replace('\n', ' ');
        return one.length() > 500 ? one.substring(0, 500) + "..." : one;
    }
}
