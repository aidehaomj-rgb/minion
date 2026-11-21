package com.minion.core.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.PrintStream;

/**
 * §12 stream-json 输出协议：NDJSON 每行一个 JSON 对象。
 *
 * <p>输出消息类型（规格 §12.1 子集——headless 单次执行所需）：
 * <ul>
 * <li>{@code system}：subtype=init（会话/模型/工具数）或 result（终结）
 * <li>{@code assistant}：模型正文（含 tool_use）
 * <li>{@code stream_event}：text_delta / usage
 * <li>{@code result}：success 或 error_*（§2.4 终止原因枚举）
 * </ul>
 * 每条消息带 sessionId/type；result 附 duration/numTurns/subtype。
 */
public final class StreamJsonProtocol {

    private final PrintStream out;
    private final boolean enabled;
    private static final Gson GSON = new Gson();

    public StreamJsonProtocol(PrintStream out, boolean enabled) {
        this.out = out;
        this.enabled = enabled;
    }

    /** 输出一行 NDJSON（非 stream-json 模式静默丢弃） */
    public void emit(String type, JsonObject payload) {
        if (!enabled) return;
        payload.addProperty("type", type);
        out.println(GSON.toJson(payload));
        out.flush();
    }

    /** system init 消息 */
    public void systemInit(String sessionId, String model, int toolCount, String permissionMode) {
        JsonObject o = new JsonObject();
        o.addProperty("subtype", "init");
        o.addProperty("session_id", sessionId);
        o.addProperty("model", model);
        o.addProperty("tools", toolCount);
        o.addProperty("permission_mode", permissionMode);
        o.addProperty("protocolVersion", 1);
        emit("system", o);
    }

    /** assistant 正文增量 */
    public void textDelta(String sessionId, String delta) {
        JsonObject o = new JsonObject();
        o.addProperty("session_id", sessionId);
        o.addProperty("delta", delta);
        emit("stream_event", o);
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
