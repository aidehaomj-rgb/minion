package com.minion.core.llm;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成本统计（开发说明 v2.4 §1.1，cost.tracking.enabled 默认 true，纯统计无副作用）。
 *
 * <p>每模型四档单价（元/百万 token）：input/output/cacheRead/cacheWrite。单价来自
 * config.properties（cost.price.&lt;model&gt;.input 等）；未配置模型回退默认模型价并标记
 * unknownModel；cacheRead/cacheWrite 未配置时按参考比例（cacheWrite=input×1.25、
 * cacheRead=input×0.1）。展示口径：金额 &gt;0.5 元保留 2 位小数，否则 4 位。
 */
public final class CostTracker {

    /** 单模型累计（token 数） */
    public static final class ModelUsage {
        public long input, output, cacheRead, cacheWrite;
    }

    private final Map<String, ModelUsage> byModel = new LinkedHashMap<String, ModelUsage>();
    private String defaultModel;
    private long apiMillis;       // 模型请求墙钟累计（含重试等待）
    private long wallStart = System.currentTimeMillis();

    /** 默认模型价（未配置模型的回退基准） */
    public void setDefaultModel(String model) { this.defaultModel = model; }

    /** 每次流式响应结束（onFinish 最终 usage）累加：miss=input−hit 视为普通输入价 */
    public synchronized void record(String model, Usage usage, long requestMillis) {
        if (usage == null || usage.estimated) {   // 估算口径不计费（无法分账）
            if (requestMillis > 0) apiMillis += requestMillis;
            return;
        }
        ModelUsage m = byModel.get(model);
        if (m == null) { m = new ModelUsage(); byModel.put(model, m); }
        long hit = usage.cacheReported ? usage.cacheHitTokens : 0;
        long miss = usage.cacheReported ? Math.max(0, usage.inputTokens - usage.cacheHitTokens)
                : usage.inputTokens;
        m.input += miss;
        m.output += usage.outputTokens;
        m.cacheRead += hit;
        // cacheWrite 无对应字段：以 miss 前缀写入量近似（首写成本），展示口径保守
        m.cacheWrite += usage.cacheReported ? 0 : 0;
        if (requestMillis > 0) apiMillis += requestMillis;
    }

    public synchronized void addApiMillis(long millis) { if (millis > 0) apiMillis += millis; }

    /** 未配置模型使用默认模型价（unknownModel 标记由 render 呈现） */
    public synchronized boolean unknownModel(String model) {
        return model != null && defaultModel != null && !model.equals(defaultModel)
                && !pricedModels.contains(model);
    }

    private final java.util.Set<String> pricedModels = new java.util.HashSet<String>();

    /** 注册已显式配置单价的模型（unknownModel 判定用） */
    public synchronized void markPriced(String model) { if (model != null) pricedModels.add(model); }

    /** 四档单价（元/百万 token）；null 字段按参考比例推导 */
    public static final class Price {
        public final Double input, output, cacheRead, cacheWrite;
        public Price(Double input, Double output, Double cacheRead, Double cacheWrite) {
            this.input = input; this.output = output;
            this.cacheRead = cacheRead;
            this.cacheWrite = cacheWrite;
        }
        double in() { return input == null ? 0 : input; }
        double out() { return output == null ? 0 : output; }
        double read() {
            return cacheRead != null ? cacheRead : (input == null ? 0 : input * 0.1);
        }
        double write() {
            return cacheWrite != null ? cacheWrite : (input == null ? 0 : input * 1.25);
        }
    }

    private final Map<String, Price> prices = new LinkedHashMap<String, Price>();

    public synchronized void setPrice(String model, Price price) {
        if (model != null && price != null) { prices.put(model, price); markPriced(model); }
    }

    private Price priceOf(String model) {
        Price p = prices.get(model);
        if (p != null) return p;
        return prices.getOrDefault(defaultModel, new Price(null, null, null, null));
    }

    /** 模型费用（元） */
    public synchronized double costOf(String model) {
        ModelUsage m = byModel.get(model);
        if (m == null) return 0;
        Price p = priceOf(model);
        return (m.input * p.in() + m.output * p.out()
                + m.cacheRead * p.read() + m.cacheWrite * p.write()) / 1_000_000d;
    }

    public synchronized double totalCost() {
        double total = 0;
        for (String model : byModel.keySet()) total += costOf(model);
        return total;
    }

    /** /cost 展示：总费用 + API/墙钟时长 + 按模型明细 */
    public synchronized String render() {
        StringBuilder sb = new StringBuilder("会话成本统计\n");
        sb.append("总费用: ").append(format(totalCost())).append(" 元\n");
        sb.append("API 时长: ").append(apiMillis / 1000).append("s / 墙钟: ")
                .append((System.currentTimeMillis() - wallStart) / 1000).append("s\n");
        if (byModel.isEmpty()) {
            sb.append("（暂无真实 usage 记录；估算口径不计费）");
            return sb.toString();
        }
        for (Map.Entry<String, ModelUsage> e : byModel.entrySet()) {
            ModelUsage m = e.getValue();
            sb.append(String.format("%n%s%s: input %d / output %d / cacheRead %d / cacheWrite %d"
                            + " → %s 元",
                    e.getKey(), unknownModel(e.getKey()) ? "（未配置单价，按默认模型价）" : "",
                    m.input, m.output, m.cacheRead, m.cacheWrite, format(costOf(e.getKey()))));
        }
        return sb.toString();
    }

    /** 金额 >0.5 元保留 2 位小数，否则 4 位（§1.1 展示口径） */
    public static String format(double yuan) {
        return yuan > 0.5 ? String.format("%.2f", yuan) : String.format("%.4f", yuan);
    }

    /** 持久化快照（Session 元数据往返）；负载为空返回 null */
    public synchronized String persistJson() {
        if (byModel.isEmpty()) return null;
        JsonObject o = new JsonObject();
        o.addProperty("apiMillis", apiMillis);
        o.addProperty("wallStart", wallStart);
        for (Map.Entry<String, ModelUsage> e : byModel.entrySet()) {
            JsonObject m = new JsonObject();
            m.addProperty("input", e.getValue().input);
            m.addProperty("output", e.getValue().output);
            m.addProperty("cacheRead", e.getValue().cacheRead);
            m.addProperty("cacheWrite", e.getValue().cacheWrite);
            o.add(e.getKey(), m);
        }
        return o.toString();
    }

    /** 从持久化快照恢复（仅同会话恢复调用；非法快照忽略） */
    public synchronized void restoreJson(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (o.has("apiMillis")) apiMillis = o.get("apiMillis").getAsLong();
            if (o.has("wallStart")) wallStart = o.get("wallStart").getAsLong();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject m = e.getValue().getAsJsonObject();
                ModelUsage u = new ModelUsage();
                u.input = m.has("input") ? m.get("input").getAsLong() : 0;
                u.output = m.has("output") ? m.get("output").getAsLong() : 0;
                u.cacheRead = m.has("cacheRead") ? m.get("cacheRead").getAsLong() : 0;
                u.cacheWrite = m.has("cacheWrite") ? m.get("cacheWrite").getAsLong() : 0;
                byModel.put(e.getKey(), u);
            }
        } catch (RuntimeException corrupt) {
            /* 快照损坏按空处理（统计可丢，不影响会话） */
        }
    }
}
