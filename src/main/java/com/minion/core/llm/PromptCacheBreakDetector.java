package com.minion.core.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 缓存打破检测（开发说明 v2.4 §1.1，cost.cachebreak.detect 默认 true）。
 *
 * <p>调用前：对稳定前缀（system 串 + 工具 schema 串）做 SHA-256 记录模型名。
 * 调用后：仅在同一模型且相邻两次响应都明确提供缓存字段时比较——本次 cacheHit
 * &lt; 上次×95% 且绝对降幅 ≥2000 token 记一次 break；字段缺失是 unknown 不按 0 触发；
 * 模型变化重置基线。归因：前缀 hash 变→「客户端变更」；未变且距上次 &gt;1h→「缓存 TTL
 * 过期」；&gt;5min→「短 TTL 过期」；否则「服务端原因」。microcompact 主动清理可经
 * {@link #expectBreak} 预约标记避免误报。
 */
public final class PromptCacheBreakDetector {

    /** 一次 break 事件（供 /cost verbose 与会话事件日志展示） */
    public static final class Break {
        public final long at;
        public final long prevHit, hit;
        public final String cause;
        Break(long at, long prevHit, long hit, String cause) {
            this.at = at; this.prevHit = prevHit; this.hit = hit; this.cause = cause;
        }
    }

    private String lastModel;
    private String lastPrefixHash;
    private long lastHit = -1;
    private long lastRequestAt;
    private final List<Break> breaks = new ArrayList<Break>();
    /** microcompact 预约的「预期打破」标记（下一次 break 不计） */
    private boolean breakExpected;

    /** 调用前记录稳定前缀（system 串 + schema 串；调用方剔除易变段） */
    public synchronized void beforeRequest(String model, String stablePrefix) {
        String hash = sha256(stablePrefix == null ? "" : stablePrefix);
        notePrefix(hash);
        this.lastPrefixHash = hash;
        if (model == null || !model.equals(lastModel)) {
            lastModel = model;
            lastHit = -1;   // 模型变化重置基线
        }
    }

    /** microcompact/显式清理前预约：下一次检测到的 break 视为预期，不记事件 */
    public synchronized void expectBreak() { breakExpected = true; }

    /** 调用后判定；返回本次归因的 break（未触发返回 null） */
    public synchronized Break afterResponse(Usage usage, long now) {
        if (usage == null || usage.estimated) return null;
        if (!usage.cacheReported) {   // unknown：不按 0 触发，但推进时间基线
            lastRequestAt = now;
            lastHit = -1;
            return null;
        }
        Break detected = null;
        if (lastHit >= 0 && usage.cacheHitTokens < lastHit * 0.95
                && lastHit - usage.cacheHitTokens >= 2000) {
            String cause = breakExpected ? "预期打破（主动清理）" : attribute(now);
            if (!breakExpected) {
                detected = new Break(now, lastHit, usage.cacheHitTokens, cause);
                breaks.add(detected);
            }
            breakExpected = false;
        }
        lastHit = usage.cacheHitTokens;
        lastRequestAt = now;
        return detected;
    }

    private String attribute(long now) {
        long gap = now - lastRequestAt;
        // hash 变化判定由调用方对比（beforeRequest 已更新）——此处用「上次前缀 hash 与
        // 本次是否相同」需要双份保存；简化：保留双 hash（prevPrefixHash）
        if (prefixChanged) return "客户端变更（稳定前缀变化）";
        if (gap > 3600_000L) return "缓存 TTL 过期（>1h 未请求）";
        if (gap > 300_000L) return "短 TTL 过期（>5min 未请求）";
        return "服务端原因";
    }

    private boolean prefixChanged;
    private String prevPrefixHash;

    /** beforeRequest 内部调用的双 hash 记录（attribute 用） */
    synchronized void notePrefix(String hash) {
        prefixChanged = prevPrefixHash != null && !prevPrefixHash.equals(hash);
        prevPrefixHash = hash;
    }

    public synchronized List<Break> breaks() { return new ArrayList<Break>(breaks); }

    private static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
