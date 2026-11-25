package com.minion.core.tools.context;

import com.google.gson.JsonObject;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolArguments;
import com.minion.core.tools.ToolResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 第三批 B3：会话历史主动召回（只读，普通权限）。
 *  search：bigram 分词扫当前项目会话存储，匹配 user/assistant 文本，排序=匹配分×新近度，≤20 条；
 *  read：sessionId+命中位置 → ≤50 条消息投影（不泄系统提示/控制消息/压缩摘要）。
 *  隐私边界：SessionStore 本身按工作空间隔离（jarDir/session/<空间名>），跨项目不可见；
 *  再按 workDir 过滤一道，空间改名等边界下也不串项目。 */
public final class SessionRecallTool implements Tool {

    private static final int MAX_SEARCH_HITS = 20;
    private static final int MAX_READ_MESSAGES = 50;
    /** 新近度半衰期：30 天前的会话权重减半 */
    private static final double RECENCY_HALF_MILLIS = 30L * 24 * 3600 * 1000;

    private final SessionStore store;
    private final String workDir;

    public SessionRecallTool(SessionStore store, String workDir) {
        this.store = store;
        this.workDir = workDir;
    }

    @Override public String name() { return "SessionRecall"; }

    @Override public String description() {
        return "主动检索当前项目的历史会话（只读，跨项目不可见）。action=search：query 关键词（中文按相邻双字匹配），"
                + "返回 sessionId/标题/时间/命中位置/片段，按相关度×新近度排序；"
                + "action=read：sessionId+around（search 给出的命中位置）读取前后最多 50 条对话消息。"
                + "每轮自动召回之外的定向查找用本工具；找不到不代表历史不存在，可换关键词。";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema(description(),
                new String[]{"action", "query", "sessionId", "around", "limit"}, new String[]{"action"});
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }
    @Override public boolean isDestructive(JsonObject args) { return false; }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = text(args, "action").toLowerCase(Locale.ROOT);
        if ("search".equals(action)) return search(text(args, "query"), ToolArguments.integer(args, "limit", 10));
        if ("read".equals(action)) return read(text(args, "sessionId"), ToolArguments.integer(args, "around", 0));
        return ToolResult.error("未知 action: " + action + "（支持 search/read）");
    }

    private ToolResult search(String query, int limit) throws Exception {
        if (query == null || query.trim().isEmpty()) return ToolResult.error("search 需要 query");
        limit = Math.max(1, Math.min(limit, MAX_SEARCH_HITS));
        Set<String> terms = terms(query);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        List<Hit> hits = new ArrayList<Hit>();
        for (SessionStore.SessionMeta meta : store.list()) {
            if (!belongsToProject(meta)) continue;
            com.minion.core.agent.Session session;
            try { session = store.load(meta.id); }
            catch (Exception unreadable) { continue; }   // 单会话损坏不中断整体检索
            List<Message> visible = visible(session.messages);
            long ts = meta.lastMessageTs > 0 ? meta.lastMessageTs : meta.modifiedMillis;
            double recency = 1.0 / (1.0 + Math.max(0, now - ts) / RECENCY_HALF_MILLIS);
            for (int i = 0; i < visible.size(); i++) {
                Message m = visible.get(i);
                String lower = m.content.toLowerCase(Locale.ROOT);
                int score = 0, first = -1;
                for (String term : terms) {
                    int at = lower.indexOf(term);
                    if (at >= 0) { score += term.length() >= 2 ? 3 : 1; if (first < 0 || at < first) first = at; }
                }
                if (lower.contains(lowerQuery)) { score += 20; first = lower.indexOf(lowerQuery); }
                if (score > 0) hits.add(new Hit(meta, i, score * recency, m.content, first, ts));
            }
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) { return Double.compare(b.score, a.score); }
        });
        if (hits.isEmpty()) return ToolResult.success("当前项目历史会话中未找到相关内容（可换关键词或缩短查询）。");
        StringBuilder out = new StringBuilder("历史会话命中（相关度×新近度排序）：");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            Hit h = hits.get(i);
            String title = h.meta.title == null || h.meta.title.trim().isEmpty() ? h.meta.preview : h.meta.title;
            out.append("\n\n[").append(h.meta.id).append("] ").append(title == null ? "(无标题)" : title)
                    .append(" | ").append(format.format(new Date(h.ts)))
                    .append(" | around=").append(h.index)
                    .append(" | score=").append(String.format(Locale.ROOT, "%.2f", h.score))
                    .append('\n').append(snippet(h.content, h.first));
        }
        out.append("\n\n用 SessionRecall(action=read,sessionId=…,around=命中位置) 读取上下文。");
        return ToolResult.success(out.toString());
    }

    private ToolResult read(String sessionId, int around) throws Exception {
        if (sessionId == null || sessionId.trim().isEmpty()) return ToolResult.error("read 需要 sessionId（search 结果给出）");
        SessionStore.SessionMeta meta = null;
        for (SessionStore.SessionMeta candidate : store.list()) {
            if (candidate.id.equals(sessionId.trim())) { meta = candidate; break; }
        }
        if (meta == null || !belongsToProject(meta))
            return ToolResult.error("会话不存在或不属于当前项目: " + sessionId);
        com.minion.core.agent.Session session = store.load(meta.id);
        List<Message> visible = visible(session.messages);
        if (visible.isEmpty()) return ToolResult.success("该会话没有可展示的对话消息。");
        int center = Math.max(0, Math.min(around, visible.size() - 1));
        int from = Math.max(0, center - MAX_READ_MESSAGES / 2);
        int to = Math.min(visible.size(), from + MAX_READ_MESSAGES);
        from = Math.max(0, to - MAX_READ_MESSAGES);
        StringBuilder out = new StringBuilder("会话 ").append(meta.id).append(" 消息 ")
                .append(from).append("–").append(to - 1).append("（共 ").append(visible.size()).append(" 条可见消息）：");
        for (int i = from; i < to; i++) {
            Message m = visible.get(i);
            out.append("\n\n[").append(i).append("] ").append(m.role == Message.Role.USER ? "用户" : "助手").append(":\n")
                    .append(clip(m.content, 1500));
        }
        return ToolResult.success(out.toString());
    }

    /** 可见投影：真实用户/助手正文；排除系统提示、工具回执、压缩摘要、控制/召回/技能注入与图片占位 */
    private static List<Message> visible(List<Message> messages) {
        List<Message> out = new ArrayList<Message>();
        if (messages == null) return out;
        for (Message m : messages) {
            if (m == null || m.content == null || m.content.trim().isEmpty()) continue;
            if (m.summary || m.toolImage) continue;
            if (m.role == Message.Role.USER) {
                if (m.origin != null && m.origin != Message.Origin.REAL_USER) continue;
                out.add(m);
            } else if (m.role == Message.Role.ASSISTANT) {
                if (m.origin != null) continue;
                out.add(m);
            }
        }
        return out;
    }

    private boolean belongsToProject(SessionStore.SessionMeta meta) {
        if (meta.workDir == null || meta.workDir.trim().isEmpty()) return true;   // 旧记录无 workDir：存储边界已隔离
        if (workDir == null) return true;
        try {
            Path a = Paths.get(meta.workDir).toAbsolutePath().normalize();
            Path b = Paths.get(workDir).toAbsolutePath().normalize();
            return a.equals(b);
        } catch (Exception invalid) { return false; }
    }

    /** 与 KnowledgeTool.terms 同口径：小写词元 + 相邻 CJK 双字（bigram），LinkedHashSet 去重 */
    static Set<String> terms(String q) {
        String s = q.toLowerCase(Locale.ROOT).trim();
        Set<String> out = new LinkedHashSet<String>();
        for (String x : s.split("[^\\p{L}\\p{N}_]+")) if (!x.isEmpty()) out.add(x);
        for (int i = 0; i + 1 < s.length(); i++) {
            char a = s.charAt(i), b = s.charAt(i + 1);
            if (isCjk(a) && isCjk(b)) out.add(s.substring(i, i + 2));
        }
        return out;
    }

    private static boolean isCjk(char c) { return c >= 0x3400 && c <= 0x9fff; }

    private static String snippet(String s, int at) {
        if (at < 0) at = 0;
        int from = Math.max(0, at - 120), to = Math.min(s.length(), at + 120);
        return (from > 0 ? "…" : "") + s.substring(from, to).replace('\r', ' ').replace('\n', ' ')
                + (to < s.length() ? "…" : "");
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…[截断]";
    }

    private static String text(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static final class Hit {
        final SessionStore.SessionMeta meta;
        final int index;
        final double score;
        final String content;
        final int first;
        final long ts;
        Hit(SessionStore.SessionMeta meta, int index, double score, String content, int first, long ts) {
            this.meta = meta; this.index = index; this.score = score; this.content = content; this.first = first; this.ts = ts;
        }
    }
}