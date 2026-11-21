package com.minion.core.context;

import com.minion.core.diagnostics.DiagnosticLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动记忆空闲整理（开发说明 v2.4 §6.2，memory.dream.enabled 默认 false）。
 *
 * <p>三重门按成本排序（任一不通过即跳过）：
 * <ol>
 * <li>时间门：距上次整理 ≥{@code memory.dream.minHours}（默认 24h；锁文件 mtime 即上次时间）
 * <li>会话门：mtime 晚于上次整理的会话转录 ≥5 个（排除当前会话；目录扫描 10min 节流）
 * <li>进程锁：.minion/.consolidate-lock 内容为持有者 PID——1h 内活 PID 持锁跳过，
 *     死 PID 可抢占；写后回读验 PID 防双抢；失败回滚 mtime
 * </ol>
 * 通过后执行整理（合并重复描述、删除已空条目、重建 MEMORY.md 索引）。
 * 受限子代理 fork 版随多代理切片接入；本实现为同进程受限执行（只触碰记忆目录）。
 */
public final class MemoryConsolidator {

    public static final int MIN_SESSIONS = 5;
    public static final long LOCK_ALIVE_MILLIS = 3600_000L;

    private final Path memoryDir;
    private final Path lockFile;
    private final String currentSessionId;
    private final int minHours;
    private volatile long lastScanAttempt;

    public MemoryConsolidator(Path projectRoot, String currentSessionId, int minHours) {
        this.memoryDir = projectRoot.resolve("memory");
        this.lockFile = projectRoot.resolve(".minion").resolve(".consolidate-lock");
        this.currentSessionId = currentSessionId;
        this.minHours = Math.max(1, minHours);
    }

    /** 三重门判定结果（供测试与诊断；null=通过） */
    public String blockedBy(Path sessionsDir, long now) {
        String timeGate = timeGate(now);
        if (timeGate != null) return timeGate;
        String sessionGate = sessionGate(sessionsDir, now);
        if (sessionGate != null) return sessionGate;
        return lockGate(now);
    }

    /** 时间门：锁文件 mtime（上次整理时间）距今未满 minHours */
    String timeGate(long now) {
        try {
            if (!Files.isRegularFile(lockFile)) return null;   // 从未整理：通过（首跑）
            long last = Files.getLastModifiedTime(lockFile).toMillis();
            if (now - last < minHours * 3600_000L)
                return "时间门：距上次整理不足 " + minHours + "h";
        } catch (IOException unreadable) {
            return "时间门：锁文件不可读";
        }
        return null;
    }

    /** 会话门：晚于上次整理的会话转录 ≥5 个（排除当前会话；扫描 10min 节流） */
    String sessionGate(Path sessionsDir, long now) {
        if (sessionsDir == null || !Files.isDirectory(sessionsDir)) return "会话门：无会话目录";
        if (now - lastScanAttempt < 600_000L) return "会话门：扫描节流（10min 一次）";
        lastScanAttempt = now;
        long last = 0;
        try {
            if (Files.isRegularFile(lockFile))
                last = Files.getLastModifiedTime(lockFile).toMillis();
        } catch (IOException ignored) { }
        int fresh = 0;
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(sessionsDir, "*.{json,jsonl}")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (currentSessionId != null && name.startsWith(currentSessionId)) continue;
                try {
                    if (Files.getLastModifiedTime(p).toMillis() > last) fresh++;
                } catch (IOException ignored) { }
            }
        } catch (IOException unreadable) {
            return "会话门：会话目录不可读";
        }
        return fresh >= MIN_SESSIONS ? null
                : "会话门：新会话 " + fresh + " < " + MIN_SESSIONS;
    }

    /** 进程锁：活 PID（1h 内）持锁跳过；死 PID 可抢占；写后回读验 PID */
    String lockGate(long now) {
        try {
            Files.createDirectories(lockFile.getParent());
            if (Files.isRegularFile(lockFile)) {
                long mtime = Files.getLastModifiedTime(lockFile).toMillis();
                String holder = new String(Files.readAllBytes(lockFile), StandardCharsets.UTF_8).trim();
                if (holder.matches("\\d+") && now - mtime < LOCK_ALIVE_MILLIS && pidAlive(holder))
                    return "进程锁：PID " + holder + " 持锁中";
            }
            String mine = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            Path tmp = lockFile.resolveSibling(lockFile.getFileName() + ".tmp");
            Files.write(tmp, mine.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, lockFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // 写后回读验 PID（防双抢：原子替换间隙被他人覆盖）
            String readBack = new String(Files.readAllBytes(lockFile), StandardCharsets.UTF_8).trim();
            if (!mine.equals(readBack)) return "进程锁：双抢失败（他方已持锁）";
            return null;
        } catch (IOException|RuntimeException failed) {
            rollbackLockMtime();
            return "进程锁：获取失败 " + failed.getMessage();
        }
    }

    /** 失败回滚：恢复锁文件 mtime 为获取前值（防止失败路径把「上次整理时间」推新） */
    private void rollbackLockMtime() {
        try {
            if (Files.isRegularFile(lockFile))
                Files.setLastModifiedTime(lockFile,
                        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - minHours * 3600_000L));
        } catch (IOException ignored) { }
    }

    /** 尝试整理：三重门全过才执行；返回 null=完成，否则为阻塞原因 */
    public String tryConsolidate(Path sessionsDir, long now) {
        String blocked = blockedBy(sessionsDir, now);
        if (blocked != null) return blocked;
        try {
            consolidate();
            // 成功：锁 mtime 即本次时间（时间门基准自然前移）
            Files.setLastModifiedTime(lockFile,
                    java.nio.file.attribute.FileTime.fromMillis(now));
            return null;
        } catch (Exception failed) {
            rollbackLockMtime();
            DiagnosticLog.warn("memory-dream", "整理失败（回滚时间门基准）: " + failed.getMessage());
            return "整理失败: " + failed.getMessage();
        }
    }

    /** 受限整理（同进程版：只读+写记忆目录）：合并重复描述、删除空文件、重建 MEMORY.md 索引 */
    void consolidate() throws IOException {
        if (!Files.isDirectory(memoryDir)) return;
        List<String> indexLines = new ArrayList<String>();
        java.util.Map<String, String> byDescription = new java.util.LinkedHashMap<String, String>();
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(memoryDir, "*.md")) {
            for (Path p : ds) {
                if (p.getFileName().toString().equals("MEMORY.md")) continue;
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                if (text.trim().isEmpty()) {
                    Files.deleteIfExists(p);   // 删除已空条目
                    continue;
                }
                String desc = firstDescription(text);
                String key = desc.isEmpty() ? p.getFileName().toString() : desc;
                String prev = byDescription.put(key, p.getFileName().toString());
                if (prev != null) {
                    // 重复描述：保留较早文件，较新的并入（正文追加防丢内容）
                    Path keep = memoryDir.resolve(prev);
                    Path drop = p;
                    Files.write(keep, (new String(Files.readAllBytes(keep), StandardCharsets.UTF_8)
                            + "\n\n---（合并自 " + drop.getFileName() + "）---\n"
                            + text).getBytes(StandardCharsets.UTF_8));
                    Files.deleteIfExists(drop);
                }
            }
        }
        for (java.util.Map.Entry<String, String> e : byDescription.entrySet())
            indexLines.add(MemoryIndex.line("fact", "memory/" + e.getValue(), e.getKey()));
        StringBuilder warning = new StringBuilder();
        String index = MemoryIndex.render(indexLines, warning);
        Files.write(memoryDir.resolve("MEMORY.md"), index.getBytes(StandardCharsets.UTF_8));
    }

    private static String firstDescription(String text) {
        for (String line : text.split("\n", 20)) {
            String t = line.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.startsWith("description:")) return t.substring("description:".length()).trim();
        }
        return text.replaceAll("\\s+", " ").trim().substring(0, Math.min(60, text.length()));
    }

    private static boolean pidAlive(String pid) {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid)
                    .redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            try (java.io.InputStream in = p.getInputStream()) {
                while ((n = in.read(buf)) != -1) all.write(buf, 0, n);
            }
            p.waitFor();
            return all.toString("UTF-8").contains(pid);
        } catch (Exception unavailable) {
            return true;   // 无法判定按活处理（fail-closed 不抢占）
        }
    }

    /** 便捷入口：memory.dream.enabled 开关下由后台守护调用 */
    public static void runIfEnabled(boolean enabled, Path projectRoot, String sessionId,
                                    Path sessionsDir, int minHours) {
        if (!enabled) return;
        String blocked = new MemoryConsolidator(projectRoot, sessionId, minHours)
                .tryConsolidate(sessionsDir, System.currentTimeMillis());
        if (blocked != null)
            DiagnosticLog.info("memory-dream", "跳过整理：" + blocked);
    }
}
