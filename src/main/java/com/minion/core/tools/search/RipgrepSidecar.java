package com.minion.core.tools.search;

import com.minion.core.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** ripgrep 旁路探测：jarDir/rg.exe → jarDir/tools/rg.exe → PATH 上的 rg。
 *  进程级缓存一次探测结果；--version 验证可执行（10s 超时）。
 *  找不到/不可用返回 null，Grep/Glob 走 Java 遍历回退，功能不变。
 *  随包分发固定 ripgrep 13.0.0（Win7 兼容，见 offline-package/ripgrep/README.md）。 */
public final class RipgrepSidecar {

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

    private static String probe() {
        Path jarDir = Config.jarDir();
        String[] candidates = {
                jarDir.resolve("rg.exe").toString(),
                jarDir.resolve("tools").resolve("rg.exe").toString(),
                "rg",
        };
        for (String candidate : candidates) {
            if (candidate.indexOf('\\') >= 0 || candidate.indexOf('/') >= 0) {
                if (!Files.isRegularFile(Paths.get(candidate))) continue;
            }
            if (verify(candidate)) return candidate;
        }
        return null;
    }

    private static boolean verify(String exe) {
        Process p = null;
        try {
            p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) >= 0) { /* 排空防管道阻塞 */ }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            if (p != null) p.destroyForcibly();
            return false;
        }
    }
}