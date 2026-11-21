package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ToolSearch 工具（v2.4 §5.4/§3.6，toolsearch.enabled 默认 false）：
 * 纯本地词法打分（{@link ToolSearchScorer}），query 支持 select:名 直选；
 * 命中返回名字+描述+完整 schema（模型据此使用；延迟 schema 剥离待 token 节省实测
 * ≥40% 后启用）。ToolSearch 自身永不延迟。
 */
public final class ToolSearchTool implements Tool {

    private final ToolRegistry registry;

    public ToolSearchTool(ToolRegistry registry) { this.registry = registry; }

    @Override public String name() { return "ToolSearch"; }

    @Override public String description() {
        return "按关键词检索可用工具（select:a,b 直选；返回名字/描述/schema 的 top 5）";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("检索工具",
                new String[]{"query", "max_results"}, new String[]{"query"});
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }
    @Override public boolean isConcurrencySafe(JsonObject args) { return true; }

    @Override public ToolResult execute(JsonObject args) {
        String query = args.has("query") && !args.get("query").isJsonNull()
                ? args.get("query").getAsString() : "";
        if (query.trim().isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 query");
        int max = 5;
        try {
            if (args.has("max_results") && !args.get("max_results").isJsonNull())
                max = Math.max(1, Math.min(20, args.get("max_results").getAsInt()));
        } catch (NumberFormatException ignored) { }
        // 候选：注册表中启用且非延迟豁免的工具
        List<ToolSearchScorer.Candidate> candidates = new ArrayList<ToolSearchScorer.Candidate>();
        for (JsonObject schema : registry.schemas()) {
            JsonObject fn = schema.getAsJsonObject("function");
            if (fn == null) continue;
            String name = fn.has("name") ? fn.get("name").getAsString() : "";
            String desc = fn.has("description") ? fn.get("description").getAsString() : "";
            candidates.add(new ToolSearchScorer.Candidate(name, null, desc));
        }
        List<ToolSearchScorer.Candidate> hits = ToolSearchScorer.search(candidates, query, max);
        if (hits.isEmpty())
            return ToolResult.success("无匹配工具；可换关键词或 select:精确名 直选");
        StringBuilder sb = new StringBuilder("匹配工具 " + hits.size() + " 个：\n");
        for (ToolSearchScorer.Candidate c : hits) {
            sb.append("\n== ").append(c.name).append(" ==\n").append(c.description).append('\n');
            Tool tool = registry.get(c.name);
            if (tool != null) sb.append("schema: ").append(tool.schema()).append('\n');
        }
        return ToolResult.success(sb.toString());
    }
}
