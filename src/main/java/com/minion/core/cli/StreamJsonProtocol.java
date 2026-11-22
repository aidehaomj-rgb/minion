package com.minion.core.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.PrintStream;

/**
 * §12 stream-json 输出协议：NDJSON 每行一个 JSON 对象，线程安全写入 stdout。
 *
 * <p>输出消息类型（规格 §12.1 + §12.2）：
 * <ul>
 * <li>{@code system}：subtype=init / session_state_changed / protocol_error
 * <li>{@code assistant}：模型正文（含 tool_use）
 * <li>{@code stream_event}：text_delta / reasoning_delta / usage
 * <li>{@code tool_progress}：工具执行进度
 * <li>{@code control_request}：Minion→宿主控制请求（如 can_use_tool）
 * <li>{@code control_response}：对宿主 control_request 的成功/失败响应
 * <li>{@code result}：success 或 error_*（§2.4 终止原因枚举）
 * </ul>
 * 每条消息均附 {@code protocolVersion}（当前 1）。
 */
public final class StreamJsonProtocol {

    public static final int PROTOCOL_VERSION = 1;

    private final PrintStream out;
    private final boolean enabled;
    private static final Gson GSON = new Gson();

    public StreamJsonProtocol(PrintStream out, boolean enabled) {
        this.out = out;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    /** 输出一行 NDJSON（线程安全；非 stream-json 模式静默丢弃） */
    public void emit(String type, JsonObject payload) {
        if (!enabled) return;
        payload.addProperty("type", type);
        if (!payload.has("protocolVersion")) {
            payload.addProperty("protocolVersion", PROTOCOL_VERSION);
        }
        String line = GSON.toJson(payload);
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }

    /** system init 消息 */
    public void systemInit(String sessionId, String model, int toolCount, String permissionMode) {
        JsonObject o = new JsonObject();
        o.addProperty("subtype", "init");
        o.addProperty("session_id", sessionId);
        o.addProperty("model", model);
        o.addProperty("tools", toolCount);
        o.addProperty("permission_mode", permissionMode);
        o.addProperty("protocolVersion", PROTOCOL_VERSION);
        emit("system", o);
    }

    /** system session_state_changed（idle / running / requires_action） */
    public void sessionStateChanged(String sessionId, String state) {
        JsonObject o = new JsonObject();
        o.addProperty("subtype", "session_state_changed");
        o.addProperty("session_id", sessionId);
        o.addProperty("state", state);
        emit("system", o);
    }

    /** assistant 正文增量 */
    public void textDelta(String sessionId, String delta) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "text_delta");
        o.addProperty("session_id", sessionId);
        o.addProperty("delta", delta);
        emit("stream_event", o);
    }

    /** assistant 思考增量 */
    public void reasoningDelta(String sessionId, String delta) {
        JsonObject o = new JsonObject();
        o.addProperty("event", "reasoning_delta");
        o.addProperty("session_id", sessionId);
        o.addProperty("delta", delta);
        emit("stream_event", o);
    }

    /** 工具执行进度 */
    public void toolProgress(String sessionId, String toolUseId, String toolName, String status, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("session_id", sessionId);
        if (toolUseId != null) o.addProperty("tool_use_id", toolUseId);
        if (toolName != null) o.addProperty("tool_name", toolName);
        o.addProperty("status", status);
        if (detail != null) o.addProperty("detail", detail);
        emit("tool_progress", o);
    }

    /** Minion → 宿主控制请求（如 can_use_tool） */
    public void controlRequest(String requestId, String subtype, JsonObject requestBody) {
        JsonObject o = new JsonObject();
        o.addProperty("request_id", requestId);
        JsonObject req = requestBody != null ? requestBody.deepCopy() : new JsonObject();
        req.addProperty("subtype", subtype);
        o.add("request", req);
        emit("control_request", o);
    }

    /** 控制请求成功响应 */
    public void controlResponseSuccess(String requestId, JsonObject responseBody) {
        JsonObject o = new JsonObject();
        JsonObject resp = new JsonObject();
        resp.addProperty("subtype", "success");
        if (requestId != null) resp.addProperty("request_id", requestId);
        if (responseBody != null) resp.add("response", responseBody);
        o.add("response", resp);
        if (requestId != null) o.addProperty("request_id", requestId);
        emit("control_response", o);
    }

    /** 控制请求失败响应 */
    public void controlResponseError(String requestId, String code, String errorMessage) {
        JsonObject o = new JsonObject();
        JsonObject resp = new JsonObject();
        resp.addProperty("subtype", "error");
        if (requestId != null) resp.addProperty("request_id", requestId);
        if (code != null) resp.addProperty("code", code);
        resp.addProperty("error", errorMessage != null ? errorMessage : "unknown error");
        o.add("response", resp);
        if (requestId != null) o.addProperty("request_id", requestId);
        emit("control_response", o);
    }

    /** 协议层错误（畸形帧/超大帧/版本不兼容/背压） */
    public void protocolError(String requestId, String code, String message) {
        if (requestId != null && !requestId.isEmpty()) {
            controlResponseError(requestId, code, message);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("subtype", "protocol_error");
        o.addProperty("code", code);
        o.addProperty("error", message);
        emit("system", o);
    }

    /** assistant 完整消息 */
    public void assistantMessage(String sessionId, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("session_id", sessionId);
        o.add("message", o2(content));
        emit("assistant", o);
    }

    private static JsonObject o2(String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        m.addProperty("content", content);
        return m;
    }

    /** result 终结消息 */
    public void result(String sessionId, boolean success, String subtype,
                       long durationMs, int numTurns) {
        JsonObject o = new JsonObject();
        o.addProperty("subtype", subtype);
        o.addProperty("session_id", sessionId);
        o.addProperty("duration_ms", durationMs);
        o.addProperty("num_turns", numTurns);
        o.addProperty("is_error", !success);
        emit("result", o);
    }
}
