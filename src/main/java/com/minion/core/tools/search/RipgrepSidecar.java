package com.minion.core.tools.search;

import com.minion.core.config.Config;
import com.minion.core.tools.ProcessCleanup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** ripgrep 旁路探测：jarDir/rg.exe → jarDir/tools/rg.exe → PATH 上的 rg。
 *  进程级缓存一次探测结果；--version 验证可执行（10s 超时）。
 *  找不到/不可用返回 null，Grep/Glob 走 Java 遍历回退，功能不变。
 *  随包分发固定 ripgrep 13.0.0（Win7 兼容，见 offline-package/ripgrep/README.md）。 */
public final class RipgrepSidecar {

    public static final long DEFAULT_SEARCH_TIMEOUT_MS = 120_000L;
    public static final String SEARCH_TIMEOUT_PROPERTY = "minion.ripgrep.timeoutMs";
    private static final String BUNDLED_VERSION = "13.0.0";
    private static final String BUNDLED_SHA256 =
            "ab5595a4f7a6b918cece0e7e22ebc883ead6163948571419a1dd5cd3c7f37972";
    private static volatile boolean probed;
    private static volatile String resolved;
    private static volatile String override; // 测试注入口："" = 强制无 rg

    private RipgrepSidecar() { }

    public static String resolve() {
        String forced = override;
        if (forced != null) return forced.isEmpty() ? null : forced;
        if (probed) return resolved;
        synchronized (RipgrepSidecar.class) {
            if (probed) return resolved;
            resolved = probe();
            probed = true;
            return resolved;
        }
    }

    static void overrideForTests(String path) { override = path; }

    /** 整体搜索截止：可由启动参数覆盖，钳制到 0.5 秒..30 分钟，防止错误配置造成永不超时。 */
    public static long configuredTimeoutMs() {
        long value = Long.getLong(SEARCH_TIMEOUT_PROPERTY, DEFAULT_SEARCH_TIMEOUT_MS);
        return Math.max(500L, Math.min(TimeUnit.MINUTES.toMillis(30), value));
    }

    /** 启动并托管 ripgrep 子进程：超时或调用线程中断都会终止整棵进程树。 */
    public static Guard startGuarded(ProcessBuilder builder, String label) throws IOException {
        return startGuarded(builder, label, configuredTimeoutMs());
    }

    static Guard startGuarded(ProcessBuilder builder, String label, long timeoutMs)
            throws IOException {
        if (builder == null) throw new IllegalArgumentException("ProcessBuilder is required");
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        return new Guard(builder.start(), label, timeoutMs);
    }

    /**
     * ripgrep 进程守卫。 watchdog 每 50ms 检查总截止与调用线程中断，
     * 因此即使主线程阻塞在 BufferedReader.readLine()，也能强杀进程并解除管道等待。
     */
    public static final class Guard implements AutoCloseable {
        private final Process process;
        private final Thread owner;
        private final String label;
        private final long timeoutMs;
        private final long deadlineNanos;
        private final Object monitor = new Object();
        private final Thread watchdog;
        private volatile boolean finished;
        private volatile boolean timedOut;
        private volatile boolean cancelled;

        private Guard(Process process, String label, long timeoutMs) {
            this.process = process;
            this.owner = Thread.currentThread();
            this.label = label == null || label.isEmpty() ? "ripgrep" : label;
            this.timeoutMs = timeoutMs;
            this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            this.watchdog = new Thread(this::watch, "minion-rg-guard");
            this.watchdog.setDaemon(true);
            this.watchdog.start();
        }

        public Process process() { return process; }

        public boolean timedOut() { return timedOut; }

        public boolean cancelled() { return cancelled; }

        public String abortMessage() {
            if (cancelled) return label + " 搜索已取消，已终止 ripgrep";
            return label + " 搜索超过 " + Math.max(1, TimeUnit.MILLISECONDS.toSeconds(timeoutMs))
                    + " 秒，已终止 ripgrep；请缩小 path 或改用更精确的 pattern";
        }

        /** 在流读取循环和 waitFor 后调用；先检查取消，再检查整体截止。 */
        public void throwIfAborted() throws IOException, InterruptedException {
            if (cancelled || owner.isInterrupted()) {
                cancelled = true;
                throw new InterruptedException(abortMessage());
            }
            if (timedOut || System.nanoTime() >= deadlineNanos) {
                timedOut = true;
                ProcessCleanup.terminate(process);
                throw new IOException(abortMessage());
            }
        }

        private void watch() {
            while (!finished) {
                if (owner.isInterrupted()) {
                    cancelled = true;
                    ProcessCleanup.terminate(process);
                    return;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    timedOut = true;
                    ProcessCleanup.terminate(process);
                    return;
                }
                synchronized (monitor) {
                    if (finished) return;
                    try {
                        monitor.wait(Math.max(1L,
                                Math.min(50L, TimeUnit.NANOSECONDS.toMillis(remaining))));
                    } catch (InterruptedException interrupted) {
                        if (finished) return;
                    }
                }
            }
        }

        @Override public void close() {
            finished = true;
            synchronized (monitor) { monitor.notifyAll(); }
            watchdog.interrupt();
            if (process.isAlive()) ProcessCleanup.terminate(process);
        }
    }

    private static String probe() {
        Path jarDir = Config.jarDir();
        List<String> candidates = new ArrayList<String>();
        Path extracted = ensureBundled(jarDir);
        if (extracted != null) candidates.add(extracted.toString());
        candidates.add(jarDir.resolve("rg.exe").toString());
        candidates.add(jarDir.resolve("tools").resolve("rg.exe").toString());
        candidates.add("rg");
        for (String candidate : candidates) {
            if (candidate.indexOf('\\') >= 0 || candidate.indexOf('/') >= 0) {
                if (!Files.isRegularFile(Paths.get(candidate))) continue;
            }
            if (verify(candidate)) return candidate;
        }
        return null;
    }

    /**
     * 从 JAR 的 /ripgrep/rg.exe 自解压到安装目录数据区。
     * 路径包含固定版本和内容哈希前缀，升级 rg 时不会覆盖正在运行的旧进程。
     * 安装目录不可写、资源缺失或校验失败时返回 null，继续探测外部 rg/PATH。
     */
    static Path ensureBundled(Path jarDir) {
        if (jarDir == null) return null;
        Path directory = jarDir.resolve(".session").resolve("tools")
                .resolve("ripgrep-" + BUNDLED_VERSION + "-" + BUNDLED_SHA256.substring(0, 12));
        Path exe = directory.resolve("rg.exe");
        if (BUNDLED_SHA256.equals(hashFile(exe))) return exe;
        InputStream in = RipgrepSidecar.class.getResourceAsStream("/ripgrep/rg.exe");
        if (in == null) return Files.isRegularFile(exe) ? exe : null;
        Path staged = null;
        try {
            Files.createDirectories(directory);
            staged = Files.createTempFile(directory, ".rg-", ".new");
            Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            if (!BUNDLED_SHA256.equals(hashFile(staged))) return null;
            try {
                Files.move(staged, exe, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, exe, StandardCopyOption.REPLACE_EXISTING);
            }
            exe.toFile().setExecutable(true, true);
            return BUNDLED_SHA256.equals(hashFile(exe)) ? exe : null;
        } catch (IOException | RuntimeException unavailable) {
            return BUNDLED_SHA256.equals(hashFile(exe)) ? exe : null;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            if (staged != null) {
                try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
            }
        }
    }

    private static String hashFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) return "";
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            }
            return hex.toString();
        } catch (Exception unavailable) {
            return "";
        }
    }

    private static boolean verify(String exe) {
        Guard guard = null;
        try {
            guard = startGuarded(
                    new ProcessBuilder(exe, "--version").redirectErrorStream(true),
                    "ripgrep 探测", 10_000L);
            InputStream in = guard.process().getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) >= 0) { /* 排空防管道阻塞 */ }
            int exit = guard.process().waitFor();
            guard.throwIfAborted();
            return exit == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (guard != null) guard.close();
        }
    }
}
