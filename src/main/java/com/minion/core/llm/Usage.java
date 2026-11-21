package com.minion.core.llm;

import com.google.gson.JsonObject;

public class Usage {
    public int inputTokens;
    public int outputTokens;
    public int reasoningTokens;
    public int totalTokens;
    /** §1.1 DeepSeek 前缀缓存命中/未命中（prompt_cache_hit_tokens/miss）；缺失=0 */
    public int cacheHitTokens;
    public int cacheMissTokens;
    /** true=本次响应明确提供了缓存字段（缺失是 unknown，不按 0 参与缓存打破判定） */
    public boolean cacheReported;
    /** true 表示本地粗估，不能用于校准上下文计数。 */
    public boolean estimated;

    public static Usage fromJson(com.google.gson.JsonObject usage) {
        Usage u = new Usage();
        if (usage == null) return u;
        if (usage.has("prompt_tokens") && !usage.get("prompt_tokens").isJsonNull())
            u.inputTokens = usage.get("prompt_tokens").getAsInt();
        if (usage.has("completion_tokens") && !usage.get("completion_tokens").isJsonNull())
            u.outputTokens = usage.get("completion_tokens").getAsInt();
        if (usage.has("completion_tokens_details")
                && usage.get("completion_tokens_details").isJsonObject()) {
            JsonObject details = usage.getAsJsonObject("completion_tokens_details");
            // reasoning_tokens 可能为 null（部分模型/接口不返回）：getAsInt 会抛 UOE，需守卫
            if (details.has("reasoning_tokens") && !details.get("reasoning_tokens").isJsonNull()) {
                u.reasoningTokens = details.get("reasoning_tokens").getAsInt();
            }
        }
        // §1.1 缓存字段三口径：DeepSeek prompt_cache_hit/miss_tokens + OpenAI
        // prompt_tokens_details.cached_tokens（内网网关兼容）；命中优先取 DeepSeek 口径
        if (usage.has("prompt_cache_hit_tokens") && !usage.get("prompt_cache_hit_tokens").isJsonNull()) {
            u.cacheHitTokens = usage.get("prompt_cache_hit_tokens").getAsInt();
            u.cacheReported = true;
        }
        if (usage.has("prompt_cache_miss_tokens") && !usage.get("prompt_cache_miss_tokens").isJsonNull()) {
            u.cacheMissTokens = usage.get("prompt_cache_miss_tokens").getAsInt();
            u.cacheReported = true;
        }
        if (!u.cacheReported && usage.has("prompt_tokens_details")
                && usage.get("prompt_tokens_details").isJsonObject()) {
            JsonObject details = usage.getAsJsonObject("prompt_tokens_details");
            if (details.has("cached_tokens") && !details.get("cached_tokens").isJsonNull()) {
                u.cacheHitTokens = details.get("cached_tokens").getAsInt();
                u.cacheReported = true;
            }
        }
        u.totalTokens = u.inputTokens + u.outputTokens;
        return u;
    }

    /** 取不到 API usage 时的估算兜底 */
    public static Usage estimate(java.util.List<Message> messages, String output) {
        Usage u = new Usage();
        u.estimated = true;
        u.inputTokens = com.minion.core.context.TokenCounter.estimateMessages(messages);
        u.outputTokens = com.minion.core.context.TokenCounter.estimate(output);
        u.totalTokens = u.inputTokens + u.outputTokens;
        return u;
    }
}
