package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * StructuredOutput（v2.4 工具面）：模型交付结构化 JSON 的出口——data 是 JSON 对象，
 * schema 可选（描述期望形状，供消费方校验）。输出原样回传（经既有 ToolOutputGate 上限），
 * 会话事件日志可据 tool_name=StructuredOutput 提取结构化结果。
 *
 * <p>schema 如实声明 data 为 object（SchemaGenerator 全 string 的默认值会误导模型把整个
 * 对象当字符串传——内网 qwen 实测）；同时容忍两种常见弱模型写法：字符串形式的 JSON
 * （自动解析）与顶层数组（自动包成 {"items": [...]}），不再直接拒绝。
 */
public final class StructuredOutputTool implements Tool {

    @Override public String name() { return "StructuredOutput"; }

    @Override public String description() {
        return "交付结构化 JSON 结果（data 直接传 JSON 对象，不要转成字符串；可选 schema 描述期望形状）；用于程序化消费场景";
    }

    @Override public JsonObject schema() {
        JsonObject schema = SchemaGenerator.objectSchema("结构化输出",
                new String[]{"data", "schema"}, new String[]{"data"});
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject data = new JsonObject();
        data.addProperty("type", "object");
        data.addProperty("description", "要交付的 JSON 对象本身（嵌套对象/数组直接写，不要序列化成字符串）");
        props.add("data", data);
        JsonObject schemaProp = new JsonObject();
        schemaProp.addProperty("type", "object");
        schemaProp.addProperty("description", "可选：描述 data 期望形状的 JSON Schema");
        props.add("schema", schemaProp);
        return schema;
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }

    @Override public ToolResult execute(JsonObject args) {
        if (args == null || !args.has("data") || args.get("data").isJsonNull())
            return ToolResult.errorWithoutSideEffects("缺少 data（JSON 对象）");
        JsonElement data = normalize(args.get("data"));
        if (data == null)
            return ToolResult.errorWithoutSideEffects("data 必须是 JSON 对象（数组请包一层 {\"items\": ...}；"
                    + "字符串里放 JSON 也可以，但须是完整的对象或数组文本）");
        // 规范化回传：重新序列化保证紧凑单行（下游按 tool_name 提取）
        JsonObject out = new JsonObject();
        if (data.isJsonObject()) {
            out.add("data", data.getAsJsonObject());
        } else {
            JsonObject wrapped = new JsonObject();
            wrapped.add("items", data.getAsJsonArray());
            out.add("data", wrapped);
        }
        if (args.has("schema") && !args.get("schema").isJsonNull()) {
            JsonElement schema = args.get("schema").isJsonPrimitive()
                    ? normalize(args.get("schema")) : args.get("schema");
            if (schema != null && schema.isJsonObject()) out.add("schema", schema);
        }
        return ToolResult.success(out.toString());
    }

    /**
     * 宽松归一：对象/数组原样；字符串则按 JSON 文本解析（对象→对象；数组→数组）。
     * 返回 null=无法归一（调用方给出可照着改的提示）。
     */
    private static JsonElement normalize(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) return null;
        if (raw.isJsonObject() || raw.isJsonArray()) return raw;
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) return null;
        String text = raw.getAsString().trim();
        if (text.isEmpty()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() || parsed.isJsonArray() ? parsed : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }
}
