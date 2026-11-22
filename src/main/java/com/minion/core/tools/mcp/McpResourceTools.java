package com.minion.core.tools.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.mcp.McpManager;
import com.minion.core.tools.OutputDump;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.util.List;

/**
 * v2.4 §3.7 二期（切片 37b）：MCP resources 只读工具——ListMcpResources /
 * ReadMcpResource。连接的 MCP 服务器各注册一对（名称带 server 前缀防冲突）；
 * 只读、并发安全；结果超限落盘同 Read。
 */
public final class McpResourceTools {

    private McpResourceTools() { }

    /** ListMcpResources：列出指定 server（或全部已连接）的资源清单 */
    public static final class ListTool implements Tool {
        private final McpManager manager;

        public ListTool(McpManager manager) { this.manager = manager; }

        @Override public String name() { return "ListMcpResources"; }
        @Override public String description() {
            return "列出 MCP 服务器的资源（uri/name/description/mimeType）；server 缺省=全部已连接服务器";
        }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("MCP 资源清单",
                    new String[]{"server"}, new String[0]);
        }
        @Override public boolean isReadOnly(JsonObject args) { return true; }
        @Override public boolean isConcurrencySafe(JsonObject args) { return true; }
        @Override public boolean isHighRisk(JsonObject args) { return false; }

        @Override public ToolResult execute(JsonObject args) {
            String server = args.has("server") && !args.get("server").isJsonNull()
                    ? args.get("server").getAsString() : null;
            StringBuilder sb = new StringBuilder();
            for (com.minion.core.mcp.McpServer s : manager.servers()) {
                if (server != null && !server.isEmpty() && !s.name.equals(server)) continue;
                if (s.state != com.minion.core.mcp.McpServer.State.CONNECTED) continue;
                try {
                    List<String> resources = manager.resourcesOf(s.name);
                    if (resources.isEmpty()) continue;
                    sb.append("[").append(s.name).append("]\n");
                    for (String r : resources) sb.append("  ").append(r).append('\n');
                } catch (Exception unsupported) {   // 服务器无 resources 能力：跳过不报错
                }
            }
            if (sb.length() == 0) return ToolResult.success("无可用 MCP 资源（服务器未连接或不支持 resources）");
            return ToolResult.success(sb.toString().trim());
        }
    }

    /** ReadMcpResource：读指定 server 的资源内容 */
    public static final class ReadTool implements Tool {
        private final McpManager manager;

        public ReadTool(McpManager manager) { this.manager = manager; }

        @Override public String name() { return "ReadMcpResource"; }
        @Override public String description() {
            return "读取 MCP 服务器资源内容（uri 来自 ListMcpResources）";
        }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("读 MCP 资源",
                    new String[]{"server", "uri"}, new String[]{"server", "uri"});
        }
        @Override public boolean isReadOnly(JsonObject args) { return true; }
        @Override public boolean isConcurrencySafe(JsonObject args) { return true; }
        @Override public boolean isHighRisk(JsonObject args) { return false; }

        @Override public ToolResult execute(JsonObject args) {
            String server = args.has("server") && !args.get("server").isJsonNull()
                    ? args.get("server").getAsString() : "";
            String uri = args.has("uri") && !args.get("uri").isJsonNull()
                    ? args.get("uri").getAsString() : "";
            if (server.isEmpty() || uri.isEmpty())
                return ToolResult.error("缺少 server/uri 参数");
            try {
                String content = manager.readResource(server, uri);
                if (content.length() > 30000) {
                    java.nio.file.Path dump = OutputDump.write(null, "mcp-resource", content);
                    return ToolResult.success(content.substring(0, 30000)
                            + "\n…超限，完整内容: " + (dump == null ? "落盘失败" : dump));
                }
                return ToolResult.success(content.isEmpty() ? "（空资源）" : content);
            } catch (Exception e) {
                return ToolResult.error("读资源失败(" + server + "/" + uri + "): " + e.getMessage());
            }
        }
    }
}
