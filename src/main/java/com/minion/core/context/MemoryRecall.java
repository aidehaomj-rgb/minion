package com.minion.core.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.SideQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 记忆召回选择器（开发说明 v2.4 §6.2）：记忆文件头清单经 {@link SideQuery} 让小模型
 * 选 ≤5 个（max_tokens 256、JSON 输出）；排除 MEMORY.md 与已展示路径；SideQuery 不可用/
 * 失败/输出畸形时回退关键词匹配（现状口径）。返回路径清单供主模型按需读取。
 */
public final class MemoryRecall {

    public static final int MAX_SELECT = 5;

    private MemoryRecall() { }

    /**
     * @param llm           侧路客户端（null=直接关键词回退）
     * @param headers       候选文件头（文件名+描述，一行一个）
     * @param query         当前任务/问题
     * @param alreadyShown  已在上下文展示、无需再选的路径
     */
    public static List<String> select(LlmClient llm, List<String> headers, String query,
                                      List<String> alreadyShown) {
        List<String> candidates = filterCandidates(headers, alreadyShown);
        if (candidates.isEmpty()) return candidates;
        if (llm != null) {
            List<String> picked = sideQuerySelect(llm, candidates, query);
            if (picked != null) return picked;
        }
        return keywordFallback(candidates, query);
    }

    private static List<String> filterCandidates(List<String> headers, List<String> alreadyShown) {
        List<String> out = new ArrayList<String>();
        for (String h : headers) {
            if (h == null || h.trim().isEmpty()) continue;
            String path = pathOf(h);
            if (path.isEmpty() || path.endsWith("MEMORY.md")) continue;   // 索引自身不参与召回
            if (alreadyShown != null && alreadyShown.contains(path)) continue;
            out.add(h);
        }
        return out;
    }

    /** SideQuery 选择；null=失败需回退 */
    private static List<String> sideQuerySelect(LlmClient llm, List<String> candidates, String query) {
        StringBuilder sb = new StringBuilder("从以下记忆文件清单中选出与任务最相关的至多 " + MAX_SELECT
                + " 个，输出 JSON 字符串数组（元素为完整清单行原文），不要其他文字：\n");
        for (String c : candidates) sb.append(c).append('\n');
        sb.append("\n任务：").append(query == null ? "" : query);
        String answer = SideQuery.complete(llm,
                new ArrayList<Message>(Arrays.asList(Message.user(sb.toString()))),
                "你是记忆检索助手。只输出 JSON 数组。", "memory-recall");
        if (answer == null) return null;
        try {
            String json = answer.trim();
            int l = json.indexOf('['), r = json.lastIndexOf(']');
            if (l < 0 || r <= l) return null;
            JsonArray arr = JsonParser.parseString(json.substring(l, r + 1)).getAsJsonArray();
            List<String> picked = new ArrayList<String>();
            for (JsonElement e : arr) {
                if (picked.size() >= MAX_SELECT) break;
                String item = e.getAsString();
                for (String c : candidates)
                    if (c.equals(item) && !picked.contains(item)) { picked.add(item); break; }
            }
            return picked;   // 可能为空：模型判断均不相关
        } catch (RuntimeException malformed) {
            DiagnosticLog.warn("memory", "召回选择输出畸形（回退关键词）: " + answer.substring(0, Math.min(120, answer.length())));
            return null;
        }
    }

    /** 关键词回退（现状口径）：query 分词，含任一词的候选按命中数排序取前 5 */
    static List<String> keywordFallback(List<String> candidates, String query) {
        List<String> tokens = tokens(query);
        List<int[]> scored = new ArrayList<int[]>();   // [index, score]
        for (int i = 0; i < candidates.size(); i++) {
            String lower = candidates.get(i).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String t : tokens) if (lower.contains(t)) score++;
            if (score > 0) scored.add(new int[]{i, score});
        }
        scored.sort((a, b) -> Integer.compare(b[1], a[1]));
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < scored.size() && out.size() < MAX_SELECT; i++)
            out.add(candidates.get(scored.get(i)[0]));
        return out;
    }

    private static List<String> tokens(String query) {
        List<String> out = new ArrayList<String>();
        if (query == null) return out;
        for (String t : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) {
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    /** 头行 "type | path | desc" → path */
    private static String pathOf(String header) {
        String[] parts = header.split("\\|");
        return parts.length >= 2 ? parts[1].trim() : "";
    }
}
