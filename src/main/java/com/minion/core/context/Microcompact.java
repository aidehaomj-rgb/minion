package com.minion.core.context;

import com.minion.core.llm.Message;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Microcompact（开发说明 v2.4 §5.1，microcompact.enabled 默认 false）：可再生工具结果清理。
 *
 * <p>白名单（结果可从磁盘/执行再生）：Read、Bash、Grep、Glob、Edit、Write、NotebookEdit、
 * WebFetch(内网版)、TaskOutput。除最近 {@code keepRecent}（下限强制 ≥1，防保留全部的
 * 边界 bug）条白名单结果外，正文替换为占位串。非白名单工具、用户消息、assistant、
 * summary/pinned 消息一律不动。仅主线程调用——子代理压缩不得动主线程状态（由调用方保证）。
 * token 口径由既有估算器统一承担（4/3 保守系数）。
 */
public final class Microcompact {

    public static final String PLACEHOLDER = "[旧工具结果已清理，可用工具重新获取]";

    private static final Set<String> REGENERABLE = new HashSet<String>(Arrays.asList(
            "read", "bash", "grep", "glob", "edit", "write", "notebookedit", "webfetch", "taskoutput"));

    private Microcompact() { }

    /** 清理会话历史中的旧可再生工具结果；返回替换条数（原地修改 messages 的 content） */
    public static int sweep(List<Message> messages, int keepRecent) {
        if (messages == null || messages.isEmpty()) return 0;
        int keep = Math.max(1, keepRecent);   // 下限 ≥1：slice(-0) 保留全部的边界防御
        // 最近 keep 条白名单结果的下标（从尾向头收集）
        Set<Integer> recent = new HashSet<Integer>();
        for (int i = messages.size() - 1; i >= 0 && recent.size() < keep; i--) {
            Message m = messages.get(i);
            if (isRegenerableTool(m)) recent.add(i);
        }
        int replaced = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (recent.contains(i)) continue;
            Message m = messages.get(i);
            if (!isRegenerableTool(m)) continue;
            if (m.content != null && !m.content.isEmpty()) {
                m.content = PLACEHOLDER;
                replaced++;
            }
        }
        return replaced;
    }

    private static boolean isRegenerableTool(Message m) {
        if (m == null || m.role != Message.Role.TOOL || m.name == null) return false;
        if (m.summary || m.pinned) return false;
        return REGENERABLE.contains(m.name.toLowerCase(Locale.ROOT));
    }
}
