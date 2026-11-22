package com.minion.core.mcp;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/** MCP 连接抽象：握手 + 工具清单 + 工具调用（实现基于 aj-mcp-client，stdio/SSE/Streamable 三传输）
 *  §3.7 二期（切片 37b）：resources/prompts 能力（服务端不支持时返回空/抛明确异常） */
public interface McpHandle {

    /** 工具调用超时（含 SSE 等待响应；playwright 导航可能较慢） */
    long CALL_TIMEOUT_MS = 120_000;

    /** 握手：initialize → notifications/initialized（幂等：已连接直接返回） */
    void connect() throws McpException;

    /** 工具清单（connect 之后调用） */
    List<McpToolInfo> listTools() throws McpException;

    /** 调用工具：content 数组文本化拼接（text 原样 / resource 转 JSON 文本），isError=true 时抛 McpException */
    String callTool(String name, JsonObject args) throws McpException;

    /** §3.7 二期：资源清单（uri/name/description/mimeType JSON 文本；不支持返回空表） */
    List<String> listResources() throws McpException;

    /** §3.7 二期：读资源（contents 文本化；不支持/不存在抛异常） */
    String readResource(String uri) throws McpException;

    /** §3.7 二期：提示清单（name/description JSON 文本；不支持返回空表） */
    List<String> listPrompts() throws McpException;

    /** §3.7 二期：取提示（展开后的消息文本；不支持/不存在抛异常） */
    String getPrompt(String name, Map<String, Object> arguments) throws McpException;

    /** 关闭连接、释放进程/流 */
    void close();

    /** §3.7 P1：能力快照（connect 后读取 serverCapabilities；未连接返回 none） */
    McpProgressAndCancel.Capabilities capabilities();

    /** §3.7 P1：发送 notifications/cancelled（中断在途调用；连接断开静默忽略） */
    void sendCancelled(String requestId, String reason);
}
