package com.minion.core.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * v2.4 §3.7 P1（切片 37b 补遗）：elicitation——server 发起的用户输入请求。
 * 结构化字段模型：限制字段数（≤8）、单字段标签长度（≤60）与输入长度（≤500）；
 * 禁止敏感信息明文回显（type=password 仅供 GUI 掩码渲染，回传值照常但不落日志）；
 * 超时/取消一律回 decline。GUI 渲染归 JavaFX 表单对话框（ElicitationDialog），
 * headless 场景直接 decline。
 */
public final class McpElicitation {

    /** 单次请求的字段定义（server 侧声明） */
    public static final class Field {
        public final String name;
        public final String label;
        public final String type;         // text | password（password 掩码渲染）
        public final boolean required;
        public final String placeholder;

        Field(String name, String label, String type, boolean required, String placeholder) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.required = required;
            this.placeholder = placeholder;
        }
    }

    public static final int MAX_FIELDS = 8;
    public static final int MAX_LABEL_CHARS = 60;
    public static final int MAX_VALUE_CHARS = 500;

    /** 响应：action=accept 携带 content；decline 无内容 */
    public static final class Response {
        public final boolean accepted;
        public final JsonObject content;   // 字段名→值（仅 accept）

        Response(boolean accepted, JsonObject content) {
            this.accepted = accepted;
            this.content = content;
        }

        public static Response decline() { return new Response(false, null); }

        public static Response accept(JsonObject content) { return new Response(true, content); }

        /** 序列化为 MCP JSON-RPC result（accept 带 content；decline 带 reason） */
        public JsonObject toResult() {
            JsonObject o = new JsonObject();
            o.addProperty("action", accepted ? "accept" : "decline");
            if (accepted && content != null) o.add("content", content);
            else if (!accepted) o.addProperty("reason", "用户取消或超时");
            return o;
        }
    }

    /** 解析 server 的 elicitation/create 请求 params（字段超限/畸形 → null=拒绝） */
    public static Field[] parseFields(JsonObject params) {
        if (params == null) return null;
        JsonArray fields = params.has("fields") && params.get("fields").isJsonArray()
                ? params.getAsJsonArray("fields") : null;
        if (fields == null || fields.size() == 0 || fields.size() > MAX_FIELDS) return null;
        java.util.List<Field> out = new java.util.ArrayList<Field>();
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).isJsonObject()) return null;
            JsonObject f = fields.get(i).getAsJsonObject();
            String name = str(f, "name");
            String label = clip(str(f, "label") == null ? name : str(f, "label"), MAX_LABEL_CHARS);
            String type = "password".equals(str(f, "type")) ? "password" : "text";
            boolean required = f.has("required") && f.get("required").getAsBoolean();
            String placeholder = clip(str(f, "placeholder"), MAX_LABEL_CHARS);
            if (name == null || name.trim().isEmpty() || label == null) return null;
            out.add(new Field(name, label, type, required, placeholder));
        }
        return out.toArray(new Field[out.size()]);
    }

    /** 用户输入值校验（必填非空 + 长度 ≤500；超限截断拒绝 → null） */
    public static JsonObject validate(Field[] fields, JsonObject userInput) {
        if (fields == null || userInput == null) return null;
        JsonObject content = new JsonObject();
        for (Field f : fields) {
            String value = userInput.has(f.name) && userInput.get(f.name).isJsonPrimitive()
                    ? userInput.get(f.name).getAsString() : null;
            if (f.required && (value == null || value.trim().isEmpty())) return null;
            if (value != null && value.length() > MAX_VALUE_CHARS) return null;
            if (value != null) content.addProperty(f.name, value);
        }
        return content;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsString() : null;
    }

    private static String clip(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private McpElicitation() { }
}
