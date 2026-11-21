package com.minion.core.tools.bash;

import com.minion.core.diagnostics.DiagnosticLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shell 快照（开发说明 v2.4 §3.3，bash.snapshot.enabled 默认 false）。
 *
 * <p>会话首次 Bash 前用 Git Bash 捕获四类状态：函数定义（declare -f，base64 传输保真）、
 * shopt -p、set -o、alias（各截断 1000 条，过滤 winpty 别名）+ PATH——不捕获一般环境变量、
 * 不保存 cwd、不保留后台 job。写入 {dir}/shell-snapshot-{sessionId}.sh；每次 Bash 执行
 * 新 shell source 快照（BashTool 接线），会话结束删除。捕获失败（无 Git Bash）降级现状
 * 直接执行并记日志，不影响 GUI/文件工具/对话。
 */
public final class ShellSnapshot {

    private static final int MAX_ENTRIES_PER_KIND = 1000;

    private ShellSnapshot() { }

    /**
     * 捕获快照并写入目标文件；返回文件路径，失败返回 null（调用方降级直接执行）。
     * 每类内容独立采集，单项失败不阻断其余。
     */
    public static Path capture(Path snapshotFile) {
        try {
            StringBuilder script = new StringBuilder();
            script.append("# minion shell snapshot（§3.3）：函数/shopt/set/alias/PATH\n");
            appendDecoded(script, run("declare -f | base64 -w 3200"), "函数定义", MAX_ENTRIES_PER_KIND);
            appendRaw(script, run("shopt -p"), "shopt", MAX_ENTRIES_PER_KIND);
            appendRaw(script, run("set -o"), "set", MAX_ENTRIES_PER_KIND);
            appendFilteredAlias(script, run("alias -p"), MAX_ENTRIES_PER_KIND);
            String path = run("printf '%s' \"$PATH\"");
            if (path != null && !path.trim().isEmpty())
                script.append("export PATH=\"").append(path.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\"\n");
            if (script.length() <= 60) {
                DiagnosticLog.warn("shell-snapshot", "快照内容为空，降级直接执行");
                return null;   // 空快照无意义（无 Git Bash 或全失败）
            }
            Files.createDirectories(snapshotFile.getParent());
            Path tmp = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
            Files.write(tmp, script.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, snapshotFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return snapshotFile;
        } catch (Exception failed) {
            DiagnosticLog.warn("shell-snapshot", "捕获失败（降级直接执行）: " + failed.getMessage());
            return null;
        }
    }

    /** base64 传输的函数定义解码回原文本（跨 GBK 控制台保真） */
    private static void appendDecoded(StringBuilder out, String base64, String label, int cap) {
        if (base64 == null || base64.trim().isEmpty()) return;
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
            String text = new String(decoded, StandardCharsets.UTF_8);
            appendRaw(out, text, label, cap);
        } catch (IllegalArgumentException bad64) {
            DiagnosticLog.warn("shell-snapshot", label + " base64 解码失败，跳过该类");
        }
    }

    private static void appendRaw(StringBuilder out, String text, String label, int cap) {
        if (text == null || text.trim().isEmpty()) return;
        String[] lines = text.split("\n", -1);
        int kept = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            if (kept++ >= cap) {
                DiagnosticLog.warn("shell-snapshot", label + " 超过 " + cap + " 条截断");
                break;
            }
            out.append(line.trim()).append('\n');
        }
    }

    /** alias -p 输出过滤 winpty 前缀别名（winpty 注入的临时别名不跨会话有意义） */
    private static void appendFilteredAlias(StringBuilder out, String aliases, int cap) {
        if (aliases == null || aliases.trim().isEmpty()) return;
        List<String> keep = new ArrayList<String>();
        for (String line : aliases.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("alias winpty")) continue;
            keep.add(t);
            if (keep.size() >= cap) break;
        }
        for (String line : keep) out.append(line).append('\n');
    }

    /** 在 Git Bash 中执行片段取 stdout（UTF-8）；失败返回 null */
    private static String run(String snippet) {
        Process p = null;
        try {
            List<String> cmd = new ArrayList<String>();
            if (isWindows()) {
                cmd.add("bash"); cmd.add("-c"); cmd.add(snippet);
            } else {
                cmd.add("sh"); cmd.add("-c"); cmd.add(snippet);
            }
            p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1 && out.size() < 4 * 1024 * 1024) out.write(buf, 0, n);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out.toByteArray(), StandardCharsets.UTF_8) : null;
        } catch (Exception failed) {
            if (p != null) p.destroy();
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
