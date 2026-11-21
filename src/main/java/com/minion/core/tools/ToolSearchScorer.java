package com.minion.core.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ToolSearch 检索打分（开发说明 v2.4 §5.4，toolsearch.enabled 默认 false；纯词法非 BM25）。
 *
 * <p>{@code select:名} 直选（逗号多选，优先级最高）；精确名快速路径：MCP 12/普通 10；
 * 子串：MCP 6/普通 5；全名兜底 3；searchHint 命中 4；描述词边界 2。取 top N（默认 5）。
 * 自动启用阈值：延迟工具 schema token（2.5 字符/token 估）> 上下文窗口×10%。
 */
public final class ToolSearchScorer {

    public static final int DEFAULT_MAX_RESULTS = 5;

    /** 候选工具条目 */
    public static final class Candidate {
        public final String name;         // 完整名（MCP 为 mcp__server__tool）
        public final String searchHint;   // 服务端/工具声明的检索提示（可空）
        public final String description;  // 工具描述（可空）
        public Candidate(String name, String searchHint, String description) {
            this.name = name == null ? "" : name;
            this.searchHint = searchHint == null ? "" : searchHint;
            this.description = description == null ? "" : description;
        }
        boolean isMcp() { return name.startsWith("mcp__"); }
    }

    private ToolSearchScorer() { }

    /**
     * 检索；query 支持 {@code select:a,b} 直选（返回按给定顺序，忽略其余规则）。
     * 返回按分数降序、同分按名字典序稳定的 top maxResults。
     */
    public static List<Candidate> search(List<Candidate> candidates, String query, int maxResults) {
        int top = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        if (candidates == null || candidates.isEmpty() || query == null || query.trim().isEmpty())
            return new ArrayList<Candidate>();
        String q = query.trim();
        if (q.toLowerCase(Locale.ROOT).startsWith("select:")) {
            List<Candidate> direct = new ArrayList<Candidate>();
            for (String want : q.substring(7).split("[,，]")) {
                String w = want.trim();
                if (w.isEmpty()) continue;
                for (Candidate c : candidates)
                    if (c.name.equalsIgnoreCase(w) && !direct.contains(c)) { direct.add(c); break; }
            }
            return direct;
        }
        List<Candidate> pool = new ArrayList<Candidate>(candidates);
        List<long[]> scored = new ArrayList<long[]>();   // [index, score]
        String needle = q.toLowerCase(Locale.ROOT);
        List<String> words = words(q);
        for (int i = 0; i < pool.size(); i++) {
            Candidate c = pool.get(i);
            long score = score(c, needle, words);
            if (score > 0) scored.add(new long[]{i, score});
        }
        scored.sort((a, b) -> a[1] != b[1] ? Long.compare(b[1], a[1]) : 0);
        List<Candidate> out = new ArrayList<Candidate>();
        for (int i = 0; i < scored.size() && out.size() < top; i++)
            out.add(pool.get((int) scored.get(i)[0]));
        return out;
    }

    private static long score(Candidate c, String needle, List<String> words) {
        String name = c.name.toLowerCase(Locale.ROOT);
        if (name.equals(needle)) return c.isMcp() ? 12 : 10;
        long best = 0;
        if (name.contains(needle)) best = Math.max(best, c.isMcp() ? 6 : 5);
        if (!c.searchHint.isEmpty() && c.searchHint.toLowerCase(Locale.ROOT).contains(needle))
            best = Math.max(best, 4);
        if (best == 0) {
            // mcp__server 前缀匹配（server 名词命中）
            if (c.isMcp() && name.split("__").length >= 3
                    && c.name.split("__")[1].toLowerCase(Locale.ROOT).contains(needle))
                best = 6;
        }
        if (best == 0 && name.contains(wordsJoined(words))) best = 3;   // 全名兜底（多词连排）
        if (best == 0 && !words.isEmpty() && name.contains(words.get(0))) best = 3;
        if (!c.description.isEmpty()) {
            String desc = " " + c.description.toLowerCase(Locale.ROOT) + " ";
            for (String w : words) {
                if (desc.contains(" " + w) || desc.contains(w + " ")) { best = Math.max(best, 2); break; }
                // 中文等无空格分词语言：描述子串命中同样计分（词边界法只适用空格分词）
                if (hasNonAscii(w) && desc.contains(w)) { best = Math.max(best, 2); break; }
            }
        }
        return best;
    }

    private static boolean hasNonAscii(String s) {
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) > 127) return true;
        return false;
    }

    private static String wordsJoined(List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) sb.append(w);
        return sb.toString();
    }

    private static List<String> words(String query) {
        List<String> out = new ArrayList<String>();
        for (String raw : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            raw = raw.trim();
            if (raw.startsWith("+")) raw = raw.substring(1);
            if (raw.length() >= 2) out.add(raw);
        }
        return out;
    }

    /** §5.4 自动启用阈值：延迟 schema 估算 token（2.5 字符/token）> 窗口×10% */
    public static boolean autoEnable(long deferredSchemaChars, int contextWindowTokens) {
        return deferredSchemaChars > 0 && contextWindowTokens > 0
                && deferredSchemaChars / 2.5 > contextWindowTokens * 0.10;
    }
}
