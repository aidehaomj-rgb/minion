package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * v2.4 StructuredOutput 验收 + 2026-11-23 内网弱模型修复：
 * schema 曾把 data 声明成 string（SchemaGenerator 的全 string 默认值），模型照着把整个对象序列化成
 * 字符串传，工具又直接拒绝——“data 必须是 JSON 对象”（办公室 Win7 实机 qwen flash 实测）。
 */
public class StructuredOutputToolTest {
    private final StructuredOutputTool tool = new StructuredOutputTool();
    private static JsonObject parse(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    /** schema 必须如实声明 data/schema 是 object，且带说明 */
    @Test public void schemaDeclaresObjectTypesWithDescriptions() {
        JsonObject schema = tool.schema();
        assertEquals("object", schema.get("type").getAsString());
        JsonObject props = schema.getAsJsonObject("properties");
        for (String key : new String[]{"data", "schema"}) {
            JsonObject prop = props.getAsJsonObject(key);
            assertEquals(key, "object", prop.get("type").getAsString());
            assertFalse(key + " 应有参数说明", prop.get("description").getAsString().trim().isEmpty());
        }
        JsonArray required = schema.getAsJsonArray("required");
        assertEquals(1, required.size());
        assertEquals("data", required.get(0).getAsString());
        assertTrue("工具描述要直接说明 data 传什么", tool.description().contains("JSON 对象"));
    }

    @Test public void objectIsEchoedAsCompactJson() {
        ToolResult result = tool.execute(parse("{\"data\":{\"a\":1,\"b\":[1,2]}}"));
        assertTrue(result.output, result.ok);
        assertEquals("{\"data\":{\"a\":1,\"b\":[1,2]}}", result.output);
    }

    /** 弱模型常见写法一：对象被序列化成字符串 */
    @Test public void stringEncodedObjectIsParsed() {
        JsonObject args = new JsonObject();
        args.addProperty("data", "{\"name\":\"张三\",\"tags\":[\"a\",\"b\"]}");
        ToolResult result = tool.execute(args);
        assertTrue(result.output, result.ok);
        assertEquals("张三", parse(result.output).getAsJsonObject("data").get("name").getAsString());
    }

    /** 弱模型常见写法二：顶层数组（字符串形式或原生数组都包成 {"items": [...]}） */
    @Test public void topLevelArrayIsWrappedAsItems() {
        JsonObject asString = new JsonObject();
        asString.addProperty("data", "[1,2,3]");
        assertEquals("{\"data\":{\"items\":[1,2,3]}}", tool.execute(asString).output);

        JsonObject nativeArray = new JsonObject();
        nativeArray.add("data", new JsonArray());
        nativeArray.getAsJsonArray("data").add("x");
        assertEquals("{\"data\":{\"items\":[\"x\"]}}", tool.execute(nativeArray).output);
    }

    /** 真正传错时拒绝，并给出能照着改的提示（不是笼统一句“必须是 JSON 对象”） */
    @Test public void nonJsonStringIsRejectedWithActionableHint() {
        ToolResult result = tool.execute(parse("{\"data\":\"  张三, 25岁  \"}"));
        assertFalse(result.ok);
        assertTrue(result.noSideEffect);
        assertTrue(result.output, result.output.contains("items") && result.output.contains("JSON 对象"));
    }

    @Test public void scalarAndMissingDataAreRejected() {
        assertFalse(tool.execute(parse("{\"data\":\"\"}")).ok);
        assertFalse(tool.execute(parse("{\"data\":42}")).ok);
        assertFalse(tool.execute(parse("{\"schema\":{}}")).ok);
        assertFalse(tool.execute(null).ok);
    }

    /** 可选 schema：对象原样回传；字符串形式的 JSON 对象同样解析；其他忽略 */
    @Test public void optionalSchemaIsEchoedWhenUsable() {
        JsonObject args = new JsonObject();
        args.add("data", new JsonObject());
        args.addProperty("schema", "{\"type\":\"object\"}");
        JsonObject out = parse(tool.execute(args).output);
        assertEquals("object", out.getAsJsonObject("schema").get("type").getAsString());

        args.addProperty("schema", "not json");
        assertFalse(parse(tool.execute(args).output).has("schema"));
    }

    @Test public void deliveringDataIsReadOnly() {
        assertTrue(tool.isReadOnly(parse("{\"data\":{}}")));
    }
}
