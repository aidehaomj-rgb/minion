package com.minion.core.security.permission;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 强制询问敏感路径（开发说明 v2.4 §4.2，参考 filesystem.ts DANGEROUS 语义）：
 * 命中 → safetyCheck ask，任何模式不可跳过（bypass 免疫；dontAsk 下转 deny 由管线承担）。
 *
 * <p>Windows 规避形态全部归一化后再比对：尾点/尾空格剥离、DOS 设备名
 * （CON/PRN/AUX/NUL/COM1-9/LPT1-9）、ADS 流 {@code ::$DATA} 后缀剥离、大小写归一。
 * 目录名（.git/.vscode/.idea/.minion）按<b>路径任一组成段</b>匹配——命中即整个路径敏感。
 */
public final class SensitivePaths {

    private static final Set<String> DANGEROUS_FILES = new HashSet<String>(Arrays.asList(
            ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".zshrc", ".zprofile",
            ".profile", ".ripgreprc", ".mcp.json"));

    private static final Set<String> DANGEROUS_DIRS = new HashSet<String>(Arrays.asList(
            ".git", ".vscode", ".idea", ".minion"));

    private static final Set<String> DOS_DEVICES = buildDevices();

    private static Set<String> buildDevices() {
        Set<String> s = new HashSet<String>(Arrays.asList("con", "prn", "aux", "nul"));
        for (int i = 1; i <= 9; i++) {
            s.add("com" + i);
            s.add("lpt" + i);
        }
        return s;   // 与 normalize 后的小写名比对
    }

    private SensitivePaths() { }

    /** 路径是否命中敏感目标（文件名或任一目录组成段；含 Windows 规避形态归一化） */
    public static boolean hits(Path path) {
        if (path == null) return false;
        for (Path seg = path; seg != null && seg.getFileName() != null; seg = seg.getParent()) {
            String name = normalize(seg.getFileName().toString());
            if (name.isEmpty()) continue;
            if (DANGEROUS_DIRS.contains(name) || DANGEROUS_FILES.contains(name)) return true;
            // DOS 设备名（CON.txt / COM1. 等规避形态：取主名部分比对）
            int dot = name.indexOf('.');
            if (DOS_DEVICES.contains(dot > 0 ? name.substring(0, dot) : name)) return true;
        }
        return false;
    }

    public static boolean hits(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        try { return hits(java.nio.file.Paths.get(path)); }
        catch (RuntimeException invalid) { return true; }   // 畸形路径按敏感处理（fail-closed）
    }

    /** Windows 规避归一：小写 + 剥尾点/尾空格 + 剥 ADS 流（::$DATA 及其后缀形态） */
    private static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        // ADS：name.txt::$DATA / name.txt:stream:$DATA / CON::$DATA 等——首个冒号起剥离
        int colon = n.indexOf(':');
        if (colon > 0) n = n.substring(0, colon);
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1);
        return n;
    }
}
