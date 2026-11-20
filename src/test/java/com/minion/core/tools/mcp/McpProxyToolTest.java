package com.minion.core.tools.mcp;

import com.google.gson.JsonObject;
import com.minion.core.mcp.McpToolInfo;
import org.junit.Test;

import static org.junit.Assert.*;

/** McpProxyTool：元数据透传 + 调用委托 + 失败映射 ToolResult */
public class McpProxyToolTest {

    @Test
    public void metadata_passthrough() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        McpToolInfo info = new McpToolInfo("fake_tool", "fake desc", schema);
        McpProxyTool tool = new McpProxyTool(null, "fake_server", info);
        assertEquals("fake_tool", tool.name());
        assertEquals("fake desc", tool.description());
        assertEquals(schema, tool.schema());
        // v2.4 §3.7 P0：MCP 默认高危确认（旧语义"永不确认"已修复）；应急开关构造时回退
        assertTrue(tool.isHighRisk(null));
        assertEquals("mcp:fake_server", tool.serverWhitelistKey());
        assertFalse(new McpProxyTool(null, "fake_server", info, false).isHighRisk(null));
    }
}
