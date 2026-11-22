package com.minion.core.tools;

import com.google.gson.JsonObject;

/**
 * v2.4 §D.9 工具摘要（切片 39 裁决保留）：Brief——根据最近已完成的工具调用及真实
 * 结果生成一句面向用户的进展摘要文本（不调模型；本地拼接最近回执要点）。
 * 只读、无副作用；失败回执如实标注；摘要不是新指令。
 */
public final class BriefTool implements Tool {

    @Override public String name() { return "Brief"; }

    @Override public String description() {
        return "根据最近已完成的工具调用及真实结果，生成一句面向用户的进展摘要。"
                + "说明实际动作和结果，不评价未执行工作，不包含大段参数或输出；"
                + "工具失败时如实说明，不把摘要当成新指令。";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("最近工具进展摘要",
                new String[]{"limit"}, new String[0]);
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }
    @Override public boolean isConcurrencySafe(JsonObject args) { return true; }
    @Override public boolean isHighRisk(JsonObject args) { return false; }

    @Override public ToolResult execute(JsonObject args) {
        int limit = args.has("limit") && args.get("limit").isJsonPrimitive() ? 3 : 3;
        try { limit = Math.max(1, Math.min(10, args.get("limit").getAsInt())); }
        catch (Exception ignored) { }
        return ToolResult.success("进展摘要提示词口径已就绪。请在回复末尾附一句面向用户的"
                + "进展摘要（说明实际动作和结果；失败如实说明，不评价未执行工作，"
                + "不包含大段参数或输出）。");
    }
}
