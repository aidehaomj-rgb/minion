package com.minion.core.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * v2.4 §3.7 P1（切片 37b 补遗）验收：elicitation 字段模型与限制、progress 通知解析、
 * notifications/cancelled 载荷、能力协商解析。JavaFX 表单渲染归人工验收批
 * （方向稿 UI 逻辑层 headless 可测原则）。
 */
public class McpPhase2CapabilitiesTest {

    /** elicitation：字段解析 + 上限（≤8 字段、≤60 标签）+ 畸形拒绝 */
    @Test public void elicitationFieldParsing() {
        JsonObject params = JsonParser.parseString("{\"fields\":["
                + "{\"name\":\"host\",\"label\":\"主机\",\"type\":\"text\",\"required\":true},"
                + "{\"name\":\"secret\",\"label\":\"密钥\",\"type\":\"password\"}"
                + "]}").getAsJsonObject();
        McpElicitation.Field[] fields = McpElicitation.parseFields(params);
        assertEquals(2, fields.length);
        assertEquals("host", fields[0].name);
        assertTrue(fields[0].required);
        assertEquals("password", fields[1].type);
        assertFalse(fields[1].required);

        // 空字段 / 超过 8 个 / 缺 name → null 拒绝
        assertNull(McpElicitation.parseFields(null));
        assertNull(McpElicitation.parseFields(new JsonObject()));
        StringBuilder tooMany = new StringBuilder("{\"fields\":[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) tooMany.append(',');
            tooMany.append("{\"name\":\"f").append(i).append("\"}");
        }
        tooMany.append("]}");
        assertNull("超过 8 个字段须拒绝", McpElicitation.parseFields(
                JsonParser.parseString(tooMany.toString()).getAsJsonObject()));
        assertNull("缺 name 须拒绝", McpElicitation.parseFields(
                JsonParser.parseString("{\"fields\":[{\"label\":\"无 name\"}]}").getAsJsonObject()));
    }

    /** elicitation：必填校验 + 值长度限制 + accept/decline 响应结构 */
    @Test public void elicitationValidationAndResponse() {
        JsonObject params = JsonParser.parseString("{\"fields\":["
                + "{\"name\":\"host\",\"label\":\"主机\",\"required\":true},"
                + "{\"name\":\"port\",\"label\":\"端口\"}]}").getAsJsonObject();
        McpElicitation.Field[] fields = McpElicitation.parseFields(params);

        // 正常输入
        JsonObject input = new JsonObject();
        input.addProperty("host", "192.168.1.1");
        input.addProperty("port", "8080");
        JsonObject content = McpElicitation.validate(fields, input);
        assertNotNull(content);
        assertEquals("192.168.1.1", content.get("host").getAsString());

        // 必填缺失 → null
        JsonObject missing = new JsonObject();
        missing.addProperty("port", "8080");
        assertNull("必填缺失须拒绝", McpElicitation.validate(fields, missing));

        // 超长值 → null
        JsonObject tooLong = new JsonObject();
        tooLong.addProperty("host", new String(new char[501]).replace('\0', 'x'));
        assertNull("超 500 字符须拒绝", McpElicitation.validate(fields, tooLong));

        // 响应结构
        McpElicitation.Response declined = McpElicitation.Response.decline();
        assertFalse(declined.accepted);
        assertEquals("decline", declined.toResult().get("action").getAsString());
        assertTrue(declined.toResult().has("reason"));
        McpElicitation.Response accepted = McpElicitation.Response.accept(content);
        assertTrue(accepted.toResult().get("action").getAsString().equals("accept"));
        assertTrue(accepted.toResult().has("content"));
    }

    /** progress 通知解析：token/progress/message/complete */
    @Test public void progressNotificationParsing() {
        JsonObject mid = JsonParser.parseString(
                "{\"method\":\"notifications/progress\",\"params\":"
                        + "{\"progressToken\":\"tok-1\",\"progress\":0.5,\"message\":\"下载中\"}}")
                .getAsJsonObject();
        McpProgressAndCancel.ProgressNotification midP =
                McpProgressAndCancel.ProgressNotification.parse(mid);
        assertNotNull(midP);
        assertEquals("tok-1", midP.progressToken);
        assertEquals(0.5, midP.progress, 0.001);
        assertEquals("下载中", midP.message);
        assertFalse(midP.isComplete);

        JsonObject done = JsonParser.parseString(
                "{\"params\":{\"progressToken\":\"tok-1\",\"progress\":1.0}}").getAsJsonObject();
        assertTrue(McpProgressAndCancel.ProgressNotification.parse(done).isComplete);
        assertNull("无 token 须忽略", McpProgressAndCancel.ProgressNotification.parse(
                JsonParser.parseString("{\"params\":{\"progress\":0.5}}").getAsJsonObject()));
        assertNull(McpProgressAndCancel.ProgressNotification.parse(null));
    }

    /** notifications/cancelled 载荷 */
    @Test public void cancelledNotificationPayload() {
        JsonObject n = McpProgressAndCancel.cancelledNotification("req-42", "用户停止");
        assertEquals("notifications/cancelled", n.get("method").getAsString());
        JsonObject params = n.getAsJsonObject("params");
        assertEquals("req-42", params.get("requestId").getAsString());
        assertEquals("用户停止", params.get("reason").getAsString());
        // 无 reason 时不带 reason 键
        JsonObject bare = McpProgressAndCancel.cancelledNotification("x", null);
        assertFalse(bare.getAsJsonObject("params").has("reason"));
    }

    /** 能力协商：serverCapabilities 解析与默认 */
    @Test public void capabilitiesNegotiation() {
        JsonObject init = JsonParser.parseString(
                "{\"capabilities\":{\"tools\":{},\"prompts\":{},\"resources\":{},"
                        + "\"elicitation\":true}}").getAsJsonObject();
        McpProgressAndCancel.Capabilities caps =
                McpProgressAndCancel.Capabilities.parse(init);
        assertTrue(caps.tools);
        assertTrue(caps.prompts);
        assertTrue(caps.resources);
        assertTrue(caps.elicitation);
        assertFalse(caps.logging);

        // 无 capabilities 段 → 全 false
        McpProgressAndCancel.Capabilities none =
                McpProgressAndCancel.Capabilities.parse(new JsonObject());
        assertFalse(none.tools);
        assertFalse(none.elicitation);

        // 只声明 tools（常见 server）→ prompts/resources/elicitation false
        JsonObject toolsOnly = JsonParser.parseString(
                "{\"capabilities\":{\"tools\":{}}}").getAsJsonObject();
        McpProgressAndCancel.Capabilities partial =
                McpProgressAndCancel.Capabilities.parse(toolsOnly);
        assertTrue(partial.tools);
        assertFalse(partial.prompts);
        assertFalse(partial.elicitation);
    }
}
