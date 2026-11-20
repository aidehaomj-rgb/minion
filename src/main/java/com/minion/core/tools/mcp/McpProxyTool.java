package com.minion.core.tools.mcp;

import com.google.gson.JsonObject;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpToolInfo;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

/**
 * MCP 工具适配器：把 MCP 服务器的工具暴露为内部 Tool（调用委托 McpManager 路由）。
 *
 * <p>确认语义（开发说明 v2.4 §3.7 P0）：MCP 工具是外部进程，行为不受 Minion 侧静态分析约束，
 * 因此<b>默认按高危确认</b>；免确认只认用户存过的 allow 规则——ConfirmGate 白名单中的
 * 整 server 键 {@code mcp:<server>} 或本工具名单独键。服务器自声明的 {@code readOnlyHint}
 * 只用于展示，不产生任何免确认或并行效果（§2.1）。
 * {@code mcp.confirm.skip=true} 为应急回退（V68 旧行为：永不确认），仅限排障。
 */
public class McpProxyTool implements Tool {

    private final McpManager manager;
    private final String serverName;
    private final McpToolInfo info;
    /** false = mcp.confirm.skip 应急开关生效，恢复 V68 永不确认行为 */
    private final boolean confirmRequired;

    public McpProxyTool(McpManager manager, String serverName, McpToolInfo info) {
        this(manager, serverName, info, true);
    }

    public McpProxyTool(McpManager manager, String serverName, McpToolInfo info, boolean confirmRequired) {
        this.manager = manager;
        this.serverName = serverName;
        this.info = info;
        this.confirmRequired = confirmRequired;
    }

    @Override public String name() { return info.name; }
    @Override public String description() { return info.description; }
    @Override public JsonObject schema() { return info.schema; }

    /** 所属服务器名（注册冲突判定：同名已注册工具是否本服务器旧注册） */
    public String serverName() { return serverName; }

    /** 整 server 白名单键：ConfirmGate.whitelistTools 中配置 {@code mcp:<server>} 放行整个服务器 */
    public String serverWhitelistKey() { return "mcp:" + serverName; }

    @Override
    public ToolResult execute(JsonObject args) {
        try {
            return ToolResult.success(manager.call(serverName, info.name, args));
        } catch (Exception e) {
            // 传输层失败/工具错误：返回失败 ToolResult 给模型自调
            return ToolResult.error(e.getMessage() == null ? "MCP 调用失败" : e.getMessage());
        }
    }

    @Override public boolean isHighRisk(JsonObject args) { return confirmRequired; }
}
