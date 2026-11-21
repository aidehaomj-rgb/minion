package com.minion.core.context;

import com.google.gson.JsonParser;
import com.minion.core.agent.Session;
import com.minion.core.diagnostics.DiagnosticLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * 会话记忆辅助压缩（开发说明 v2.4 §5.7，compact.sessionMemory.enabled 默认 false）：
 * 自动压缩前先尝试复用最近已生成的结构化检查点（{workDir}/.minion/session-memory.json）——
 * 只有「内容覆盖当前待压缩范围 + 来源哈希未失效 + 保留当前任务锚点」三条件齐备才采用，
 * 否则回退完整压缩。失败/不支持一律回退，不阻断主任务；MEMORY.md 存在不跳过真实对话摘要。
 */
public final class SessionMemoryCompact {

    private final Path file;
    private final String workDir;

    public SessionMemoryCompact(String workDir) {
        this.workDir = workDir;
        this.file = Paths.get(workDir, ".minion", "session-memory.json");
    }

    /** 已缓存的会话记忆（来源哈希绑定生成时的消息范围） */
    public static final class Cached {
        public String summary;        // 结构化摘要文本
        public String coversHash;     // 生成时覆盖消息的 SHA-256（失效判定）
        public int coversMessages;    // 覆盖的消息数
        public String taskAnchor;     // 生成时的任务目标（锚点保留判据）
        public long generatedAt;
    }

    /** 读取缓存记忆；无/损坏返回 null */
    public Cached load() {
        try {
            if (!Files.isRegularFile(file)) return null;
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            com.google.gson.JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            Cached c = new Cached();
            c.summary = o.has("summary") ? o.get("summary").getAsString() : null;
            c.coversHash = o.has("coversHash") ? o.get("coversHash").getAsString() : null;
            c.coversMessages = o.has("coversMessages") ? o.get("coversMessages").getAsInt() : 0;
            c.taskAnchor = o.has("taskAnchor") ? o.get("taskAnchor").getAsString() : null;
            c.generatedAt = o.has("generatedAt") ? o.get("generatedAt").getAsLong() : 0;
            return c.summary == null || c.coversHash == null ? null : c;
        } catch (Exception corrupt) {
            return null;
        }
    }

    /**
     * 判定缓存可否复用（三条件）：覆盖范围（消息数 ≥ 待压缩数）、来源哈希一致、
     * 任务锚点仍在当前目标中（防旧任务记忆冒充新任务摘要）。
     */
    public boolean reusable(Cached cached, Session session, int messagesToCompact) {
        if (cached == null) return false;
        if (cached.coversMessages < messagesToCompact) return false;
        String currentGoal = session.taskState == null ? null : session.taskState.objective;
        if (currentGoal != null && cached.taskAnchor != null
                && !currentGoal.contains(clip(cached.taskAnchor, 20))) {
            DiagnosticLog.info("session-memory", "任务锚点已变化，缓存记忆不可复用");
            return false;
        }
        return true;
    }

    /** 写入新缓存（压缩完成后调用；失败仅告警） */
    public void store(String summary, String coversHash, int coversMessages, String taskAnchor) {
        try {
            Files.createDirectories(file.getParent());
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("summary", summary);
            o.addProperty("coversHash", coversHash);
            o.addProperty("coversMessages", coversMessages);
            o.addProperty("taskAnchor", taskAnchor == null ? "" : taskAnchor);
            o.addProperty("generatedAt", System.currentTimeMillis());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, o.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failed) {
            DiagnosticLog.warn("session-memory", "缓存写入失败（不影响压缩主流程）: " + failed.getMessage());
        }
    }

    /** 消息列表的来源哈希（前 n 条；压缩范围指纹） */
    public static String hashOf(java.util.List<com.minion.core.llm.Message> messages, int n) {
        try {
            StringBuilder sb = new StringBuilder();
            int count = Math.min(n, messages.size());
            for (int i = 0; i < count; i++) {
                com.minion.core.llm.Message m = messages.get(i);
                sb.append(m.role).append('|')
                  .append(m.content == null ? 0 : m.content.length()).append('|')
                  .append(m.content == null ? "" : m.content.hashCode()).append(';');
            }
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}
