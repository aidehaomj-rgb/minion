package com.minion.core.mcp;

import com.google.gson.JsonObject;

/**
 * v2.4 §3.7 P1（切片 37b 补遗）：progress 通知透传 + notifications/cancelled 取消 +
 * initialize 能力协商。aj-mcp-client 1.5 对这三项的支持不完整——本类自补 JSON-RPC
 * 层（不依赖库协议类），经 AjMcpClient.RawJsonRequest 通道发送。
 *
 * <p><b>能力协商</b>：connect 后调 initialize/result 中读 serverCapabilities
 * （tools/prompts/resources/elicitation），据实启用对应工具/命令/UI——缺能力不注册。
 */
public final class McpProgressAndCancel {

    /** server 能力快照（initialize result 的 serverCapabilities 解析） */
    public static final class Capabilities {
        public final boolean tools;
        public final boolean prompts;
        public final boolean resources;
        public final boolean elicitation;   // server 能发起 elicitation/create
        public final boolean logging;

        Capabilities(boolean tools, boolean prompts, boolean resources,
                boolean elicitation, boolean logging) {
            this.tools = tools;
            this.prompts = prompts;
            this.resources = resources;
            this.elicitation = elicitation;
            this.logging = logging;
        }

        public static Capabilities none() {
            return new Capabilities(false, false, false, false, false);
        }

        /** 默认按 tools=true（大量 server 不声明 capabilities 但支持 tools/list） */
        public static Capabilities parse(JsonObject initializeResult) {
            if (initializeResult == null) return none();
            JsonObject caps = initializeResult.has("capabilities")
                    && initializeResult.get("capabilities").isJsonObject()
                    ? initializeResult.getAsJsonObject("capabilities") : null;
            if (caps == null) return none();
            return new Capabilities(
                    !caps.has("tools") || caps.get("tools").isJsonObject() || caps.get("tools").getAsBoolean(),
                    caps.has("prompts") && (caps.get("prompts").isJsonObject() || caps.get("prompts").getAsBoolean()),
                    caps.has("resources") && (caps.get("resources").isJsonObject() || caps.get("resources").getAsBoolean()),
                    caps.has("elicitation") && caps.get("elicitation").getAsBoolean(),
                    caps.has("logging") && caps.get("logging").getAsBoolean());
        }
    }

    /** progress 通知（server→client notifications/progress）：透传给 tool_progress UI 消息 */
    public static final class ProgressNotification {
        public final String progressToken;
        public final Double progress;      // 0..1 可空
        public final String message;       // 可空
        public final boolean isComplete;

        ProgressNotification(String token, Double progress, String message, boolean complete) {
            this.progressToken = token;
            this.progress = progress;
            this.message = message;
            this.isComplete = complete;
        }

        public static ProgressNotification parse(JsonObject notification) {
            if (notification == null) return null;
            JsonObject params = notification.has("params") && notification.get("params").isJsonObject()
                    ? notification.getAsJsonObject("params") : null;
            if (params == null) return null;
            String token = params.has("progressToken") && params.get("progressToken").isJsonPrimitive()
                    ? params.get("progressToken").getAsString() : null;
            if (token == null) return null;
            Double progress = params.has("progress") && params.get("progress").isJsonPrimitive()
                    ? params.get("progress").getAsDouble() : null;
            String message = params.has("message") && params.get("message").isJsonPrimitive()
                    ? params.get("message").getAsString() : null;
            boolean complete = progress != null && progress >= 1.0;
            return new ProgressNotification(token, progress, message, complete);
        }
    }

    /** notifications/cancelled 载荷构造（中断在途调用时发送） */
    public static JsonObject cancelledNotification(String requestId, String reason) {
        JsonObject params = new JsonObject();
        params.addProperty("requestId", requestId == null ? "" : requestId);
        if (reason != null) params.addProperty("reason", reason);
        JsonObject o = new JsonObject();
        o.addProperty("method", "notifications/cancelled");
        o.add("params", params);
        return o;
    }

    private McpProgressAndCancel() { }
}
