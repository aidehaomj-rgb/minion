package com.minion.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * StructuredOutput（v2.4 工具面）：模型交付结构化 JSON 的出口——data 必须是 JSON 对象，
 * schema 可选（描述期望形状，供消费方校验）。输出原样回传（经既有 ToolOutputGate 上限），
 * 会话事件日志可据 tool_name=StructuredOutput 提取结构化结果。
 */
public final class StructuredOutputTool implements Tool {

    @Override public String name() { return "StructuredOutput"; }

    @Override public String description() {
        return "交付结构化 JSON 结果（data 必须为对象；可选 schema 描述期望形状）；用于程序化消费场景";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("结构化输出",
                new String[]{"data", "schema"}, new String[]{"data"});
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }

    @Override public ToolResult execute(JsonObject args) {
        if (args == null || !args.has("data") || args.get("data").isJsonNull())
            return ToolResult.errorWithoutSideEffects("缺少 data（JSON 对象）");
        JsonElement data = args.get("data");
        if (!data.isJsonObject())
            return ToolResult.errorWithoutSideEffects("data 必须是 JSON 对象（数组请包一层 {\"items\": ...}）");
        // 规范化回传：重新序列化保证紧凑单行（下游按 tool_name 提取）
        JsonObject out = new JsonObject();
        out.add("data", data.getAsJsonObject());
        if (args.has("schema") && !args.get("schema").isJsonNull())
            out.add("schema", args.get("schema"));
        return ToolResult.success(out.toString());
    }
}
