package com.minion.core.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

/** §1.1 验收：Usage 缓存三口径解析、UsageTracker 分账、CostTracker 单价/比例/格式化/持久化、
 *  缓存打破判定（骤降/unknown/模型变化/归因/预期标记） */
public class CostTrackingTest {

    private static Usage usage(String json) {
        return Usage.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    // ==================== Usage 解析 ====================

    @Test public void parsesDeepseekCacheFields() {
        Usage u = usage("{\"prompt_tokens\":1000,\"completion_tokens\":50,"
                + "\"prompt_cache_hit_tokens\":800,\"prompt_cache_miss_tokens\":200}");
        assertEquals(800, u.cacheHitTokens);
        assertEquals(200, u.cacheMissTokens);
        assertTrue(u.cacheReported);
    }

    @Test public void parsesOpenAiCachedTokensFallback() {
        Usage u = usage("{\"prompt_tokens\":1000,\"completion_tokens\":10,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":600}}");
        assertEquals(600, u.cacheHitTokens);
        assertTrue(u.cacheReported);
    }

    /** 字段缺失=0 且不标记 reported（unknown 不参与打破判定） */
    @Test public void missingCacheFieldsAreUnknown() {
        Usage u = usage("{\"prompt_tokens\":100,\"completion_tokens\":5}");
        assertEquals(0, u.cacheHitTokens);
        assertFalse(u.cacheReported);
    }

    @Test public void trackerAccumulatesCacheSplits() {
        UsageTracker t = new UsageTracker();
        t.record(usage("{\"prompt_tokens\":100,\"completion_tokens\":5,"
                + "\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20}"));
        t.record(usage("{\"prompt_tokens\":50,\"completion_tokens\":5}"));   // unknown 不计缓存分账
        assertEquals(80, t.sessionCacheHit());
        assertEquals(20, t.sessionCacheMiss());
        assertEquals(150, t.sessionInput());
        JsonObject breakdown = t.breakdown();
        assertEquals(80, breakdown.get("cacheHitTokens").getAsLong());
    }

    // ==================== CostTracker ====================

    @Test public void costMathWithDefaultRatios() {
        CostTracker cost = new CostTracker();
        cost.setPrice("m", new CostTracker.Price(2.0, 8.0, null, null));   // cacheRead=0.2, cacheWrite=2.5
        Usage u = usage("{\"prompt_tokens\":1000000,\"completion_tokens\":500000,"
                + "\"prompt_cache_hit_tokens\":600000,\"prompt_cache_miss_tokens\":400000}");
        cost.record("m", u, 0);
        // input 0.4M×2 + output 0.5M×8 + cacheRead 0.6M×0.2 = 0.8+4+0.12 = 4.92 元
        assertEquals(4.92, cost.costOf("m"), 0.0001);
        assertEquals(4.92, cost.totalCost(), 0.0001);
    }

    /** 估算 usage 不计费；未知模型按默认模型价回退 */
    @Test public void estimatedNotChargedAndUnknownModelFallsBack() {
        CostTracker cost = new CostTracker();
        cost.setDefaultModel("main");
        cost.setPrice("main", new CostTracker.Price(1.0, 2.0, null, null));
        Usage est = Usage.estimate(java.util.Collections.emptyList(), "x");
        cost.record("any", est, 0);
        assertEquals(0, cost.totalCost(), 0.00001);
        Usage real = usage("{\"prompt_tokens\":100000,\"completion_tokens\":10000}");
        cost.record("other-model", real, 0);
        assertTrue(cost.unknownModel("other-model"));
        assertFalse(cost.unknownModel("main"));
        // input 0.1M×1 + output 0.01M×2 = 0.12 元
        assertEquals(0.12, cost.costOf("other-model"), 0.0001);
    }

    @Test public void formattingRule() {
        assertEquals("4.92", CostTracker.format(4.923));
        assertEquals("0.1200", CostTracker.format(0.12));
        assertEquals("0.5000", CostTracker.format(0.5));   // 恰好 0.5 不算大于
    }

    @Test public void persistRestoreRoundTrip() {
        CostTracker cost = new CostTracker();
        cost.setPrice("m", new CostTracker.Price(1.0, 1.0, null, null));
        cost.record("m", usage("{\"prompt_tokens\":1000,\"completion_tokens\":100,"
                + "\"prompt_cache_hit_tokens\":500,\"prompt_cache_miss_tokens\":500}"), 1234);
        String json = cost.persistJson();
        CostTracker restored = new CostTracker();
        restored.setPrice("m", new CostTracker.Price(1.0, 1.0, null, null));
        restored.restoreJson(json);
        assertEquals(cost.costOf("m"), restored.costOf("m"), 0.000001);
        restored.restoreJson("not-json");   // 损坏快照按空处理不炸
        restored.restoreJson(null);
    }

    // ==================== PromptCacheBreakDetector ====================

    private static PromptCacheBreakDetector.Break respond(
            PromptCacheBreakDetector d, long hit, long now) {
        if (hit >= 0) {
            JsonObject j = new JsonObject();
            j.addProperty("prompt_tokens", 1000);
            j.addProperty("completion_tokens", 5);
            j.addProperty("prompt_cache_hit_tokens", (int) hit);
            j.addProperty("prompt_cache_miss_tokens", 1000 - (int) Math.min(hit, 1000));
            return d.afterResponse(Usage.fromJson(j), now);
        }
        return d.afterResponse(usage("{\"prompt_tokens\":100,\"completion_tokens\":5}"), now);
    }

    @Test public void detectsSharpDropWithAttribution() {
        PromptCacheBreakDetector d = new PromptCacheBreakDetector();
        long t0 = System.currentTimeMillis();
        d.beforeRequest("m", "prefix-A");
        assertNull(respond(d, 10000, t0));
        // 前缀变化 + 骤降（10000→1000，<95% 且降 9000≥2000）→ 客户端变更
        d.beforeRequest("m", "prefix-B");
        PromptCacheBreakDetector.Break b = respond(d, 1000, t0 + 60_000);
        assertNotNull(b);
        assertTrue(b.cause.contains("客户端变更"));
        assertEquals(1, d.breaks().size());
    }

    @Test public void ttlExpiryAttributionWhenPrefixUnchanged() {
        PromptCacheBreakDetector d = new PromptCacheBreakDetector();
        long t0 = System.currentTimeMillis();
        d.beforeRequest("m", "same");
        assertNull(respond(d, 10000, t0));
        d.beforeRequest("m", "same");
        PromptCacheBreakDetector.Break b = respond(d, 1000, t0 + 2 * 3600_000L);   // >1h
        assertNotNull(b);
        assertTrue(b.cause.contains("TTL 过期"));
    }

    /** 小降幅/字段缺失/模型变化不触发 */
    @Test public void noFalseBreaks() {
        PromptCacheBreakDetector d = new PromptCacheBreakDetector();
        long t0 = System.currentTimeMillis();
        d.beforeRequest("m", "p");
        assertNull(respond(d, 10000, t0));
        d.beforeRequest("m", "p");
        assertNull(respond(d, 9600, t0 + 1000));        // 降幅 4% < 5% 阈值
        d.beforeRequest("m", "p");
        assertNull(respond(d, -1, t0 + 2000));          // unknown：不按 0 触发
        d.beforeRequest("m2", "p");                     // 模型变化重置基线
        assertNull(respond(d, 100, t0 + 3000));
        assertTrue(d.breaks().isEmpty());
    }

    /** microcompact 预期打破标记：下一次骤降不计事件 */
    @Test public void expectedBreakIsNotRecorded() {
        PromptCacheBreakDetector d = new PromptCacheBreakDetector();
        long t0 = System.currentTimeMillis();
        d.beforeRequest("m", "p");
        assertNull(respond(d, 10000, t0));
        d.expectBreak();
        d.beforeRequest("m", "p");
        assertNull(respond(d, 500, t0 + 1000));   // 预期内：不记
        assertTrue(d.breaks().isEmpty());
        d.beforeRequest("m", "p");
        assertNull(respond(d, 100, t0 + 2000));   // 标记已消费：100→? 降幅 400<2000 不触发
        assertTrue(d.breaks().isEmpty());
    }
}
