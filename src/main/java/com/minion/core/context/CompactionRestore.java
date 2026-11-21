package com.minion.core.context;

import com.minion.core.llm.Message;
import com.minion.core.security.ReadStateStore;
import com.minion.core.tools.TextFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 压缩后恢复（开发说明 v2.4 §5.3，compact.restore.enabled 默认 true）：
 * adoptCompaction 成功后按 {@link ReadStateStore} 时间倒序<b>重读最近 ≤5 个文件</b>，
 * 单文件 ≤5000 token、总预算 50k；已在保留尾部出现同路径 Read 结果的跳过；
 * 重读同时刷新 ReadStateStore。todo/skills 状态再注入由既有 compactionHandoff/
 * WorkingSetProjection 通道承担；transcript 路径由摘要层附带。
 * 子代理压缩不调用本类（不得触碰主线程共享状态）。
 */
public final class CompactionRestore {

    private static final int MAX_FILES = 5;
    private static final int PER_FILE_TOKENS = 5000;
    private static final int TOTAL_TOKEN_BUDGET = 50_000;

    private CompactionRestore() { }

    /**
     * 生成重读注入消息（追加进压缩后历史尾部）。返回空列表 = 无可重读/全部不可读。
     *
     * @param store 会话读状态（倒序取最近文件；重读成功后回写刷新）
     * @param retainedTail 保留尾部（当前实现不做内容级去重：Read 回执不含路径，
     *                     以路径集合内去重 + 预算上限兜底；重复注入一次无害且有界）
     */
    public static List<Message> restoreRecentFiles(ReadStateStore store, List<Message> retainedTail) {
        List<Message> out = new ArrayList<Message>();
        if (store == null || store.size() == 0) return out;
        List<ReadStateStore.Entry> entries = store.recentEntries();
        Set<String> injected = new HashSet<String>();
        int usedTokens = 0;
        List<String> sections = new ArrayList<String>();
        for (ReadStateStore.Entry e : entries) {
            if (sections.size() >= MAX_FILES || usedTokens >= TOTAL_TOKEN_BUDGET) break;
            if (e.path == null || !injected.add(normalize(e.path))) continue;
            String body = readFileBounded(Paths.get(e.path), PER_FILE_TOKENS);
            if (body == null) continue;
            int tokens = TokenCounter.estimate(body);
            if (usedTokens + tokens > TOTAL_TOKEN_BUDGET) continue;   // 装不下整文件则跳过，不注入半截
            usedTokens += tokens;
            sections.add("=== " + e.path + "（压缩后重读） ===\n" + body);
            refresh(store, Paths.get(e.path), body);
        }
        if (sections.isEmpty()) return out;
        StringBuilder sb = new StringBuilder("[压缩后文件重读] 以下最近工作文件的当前内容已重新注入"
                + "（历史 Read 结果已被摘要压缩，需要更多内容请用 Read 工具）：\n\n");
        for (String s : sections) sb.append(s).append("\n\n");
        out.add(Message.taskControl(sb.toString()));
        return out;
    }

    private static String normalize(String path) {
        try { return Paths.get(path).toAbsolutePath().normalize().toString(); }
        catch (RuntimeException invalid) { return path; }
    }

    private static void refresh(ReadStateStore store, Path file, String body) {
        try {
            store.record(ReadStateStore.keyOf(file), body, ReadStateStore.sha256(file), null,
                    Files.getLastModifiedTime(file).toMillis(), 0, 0, false, false);
        } catch (Exception refreshFailure) { /* 刷新失败不阻断恢复注入 */ }
    }

    private static String readFileBounded(Path file, int maxTokens) {
        try {
            if (!Files.isRegularFile(file)) return null;
            if (Files.size(file) > 4L * 1024 * 1024) return null;   // >4MB 不整读
            TextFiles.Lines lines = TextFiles.readAllLines(file);
            StringBuilder sb = new StringBuilder();
            for (String line : lines.lines) {
                sb.append(line).append('\n');
                if (TokenCounter.estimate(sb.toString()) > maxTokens)
                    return sb.substring(0, Math.max(0, sb.length() - line.length() - 1))
                            + "\n…[单文件预算 " + maxTokens + " token 截断]";
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception unreadable) {
            return null;
        }
    }
}
