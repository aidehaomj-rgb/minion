package com.minion.core.tools;

import com.google.gson.JsonObject;

/**
 * Config 工具（v2.4 §13.x 工具面）：模型侧只读查看行为开关（值/来源/生效范围）——
 * 与 /config 命令同一数据源（FeatureFlags 目录）。修改走 config.properties（用户动作）。
 */
public final class ConfigViewTool implements Tool {

    private final com.minion.core.config.FeatureFlags flags;

    public ConfigViewTool(com.minion.core.config.FeatureFlags flags) { this.flags = flags; }

    @Override public String name() { return "Config"; }

    @Override public String description() {
        return "只读查看行为开关（值/来源/生效范围）；query 为空返回全部，否则按键名过滤";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("查看配置开关", new String[]{"query"}, new String[0]);
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }
    @Override public boolean isConcurrencySafe(JsonObject args) { return true; }

    @Override public ToolResult execute(JsonObject args) {
        String query = args != null && args.has("query") && !args.get("query").isJsonNull()
                ? args.get("query").getAsString().trim() : "";
        return ToolResult.success(flags.render(query.isEmpty() ? null : query));
    }
}
