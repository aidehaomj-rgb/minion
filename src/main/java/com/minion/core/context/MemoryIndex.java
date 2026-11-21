package com.minion.core.context;

import java.util.List;

/**
 * 记忆索引 MEMORY.md（开发说明 v2.4 §6.2）：由已验证记忆渲染索引行。
 *
 * <p>双上限：≤200 行且 ≤25000 字节——<b>先按行截断再在限内最后换行处截字节</b>
 * （防超长单行绕过行数上限后仍以字节截断保证上限）。超限返回警告由调用方展示。
 * 召回经 {@link MemoryRecall}（SideQuery 小模型选择）或关键词回退。
 */
public final class MemoryIndex {

    public static final int MAX_LINES = 200;
    public static final int MAX_BYTES = 25000;

    /** §6.2 使用前验证文案（写入系统提示词，自撰） */
    public static final String USAGE_GUIDANCE =
            "项目记忆是『某时刻为真』的快照：依据记忆回答或行动前，必须先读取当前文件核实；"
                    + "与现状冲突时以现状为准，并更新或删除过期记忆；用户将依你的建议行动时，必先完成该验证。";

    private MemoryIndex() { }

    /** 单条索引行（清单口径：类型/路径/描述） */
    public static String line(String type, String path, String description) {
        String desc = description == null ? "" : description.replace("\n", " ").trim();
        if (desc.length() > 120) desc = desc.substring(0, 120) + "…";
        return "- " + (type == null ? "" : type) + " | " + (path == null ? "" : path)
                + (desc.isEmpty() ? "" : " | " + desc);
    }

    /**
     * 渲染 MEMORY.md 全文；超限（行或字节）按序截断并在警告缓冲追加提示。
     */
    public static String render(List<String> lines, StringBuilder warning) {
        StringBuilder sb = new StringBuilder("# 项目记忆索引\n\n");
        int linesUsed = 0;
        for (String line : lines) {
            if (linesUsed >= MAX_LINES) {
                warn(warning, "记忆索引超过 " + MAX_LINES + " 行，已截断（请整理合并旧记忆）");
                break;
            }
            byte[] candidate;
            String withLine = sb.toString() + line + "\n";
            candidate = withLine.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (candidate.length > MAX_BYTES) {
                // 先按行截断已触发字节上限：在限内最后换行处截断（不撕开多字节字符）
                String fitted = fitBytes(withLine, MAX_BYTES);
                if (fitted != null) sb = new StringBuilder(fitted);
                warn(warning, "记忆索引超过 " + MAX_BYTES + " 字节，已截断（请整理合并旧记忆）");
                break;
            }
            sb.append(line).append('\n');
            linesUsed++;
        }
        return sb.toString();
    }

    private static void warn(StringBuilder warning, String text) {
        if (warning != null) warning.append(text).append('\n');
    }

    /** 限内最后换行处截字节（防长行绕过与多字节撕裂）；装不下头部返回 null */
    private static String fitBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        // 退到不超限的换行处（按字节倒查 0x0A）
        for (int i = maxBytes - 1; i > 0; i--) {
            if (bytes[i] == '\n') {
                // 校验该切片可完整解码（落在多字节中间则继续退）
                String part = new String(bytes, 0, i, java.nio.charset.StandardCharsets.UTF_8);
                if (!part.isEmpty() && !part.endsWith("\uFFFD")) return part;
            }
        }
        return null;
    }
}
