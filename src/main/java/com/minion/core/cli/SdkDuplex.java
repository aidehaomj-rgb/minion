package com.minion.core.cli;

import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * §12.2 SDK 双向协议主循环：stdin/stdout NDJSON——stdout 只写协议帧（StreamJsonProtocol
 * 唯一写者；日志走 stderr/文件），stdin 逐帧处理。首帧必须 initialize（版本不兼容
 * protocol_error 拒执退出）；user 帧经单线程 executor 串行跑轮（读线程保持响应
 * interrupt/control_response）；工具线程的 can_use_tool 等待由 SdkHostBridge 关联
 * 回填；EOF/断连：中断在途 + 清理全部 pending（deny 语义）+ 有界等待收尾。
 * 畸形/超大帧回协议错误不执行。
 */
public final class SdkDuplex {

    /** 会话轮执行抽象（HeadlessRunner 装配 / 测试注入） */
    public interface TurnEngine {
        String sessionId();
        void runUserTurn(String prompt) throws Exception;
        boolean lastTurnSucceeded();
        int lastTurnToolRounds();
        String pauseCode();
        void interrupt();
        /** 会话级设置；返回错误文案或 null=成功 */
        String setPermissionMode(String mode);
        String setModel(String model);
    }

    private final StreamJsonProtocol protocol;
    private final TurnEngine engine;
    private final SdkHostBridge bridge;
    /** §12.2 二期扩展请求（subtype → 处理器；可空=无扩展）；统一异步+30s 超时 */
    private final java.util.Map<String, SdkRequestHandler> handlers;
    private final ExecutorService controlExecutor;

    public SdkDuplex(StreamJsonProtocol protocol, TurnEngine engine, SdkHostBridge bridge) {
        this(protocol, engine, bridge, null);
    }

    public SdkDuplex(StreamJsonProtocol protocol, TurnEngine engine, SdkHostBridge bridge,
            java.util.Map<String, SdkRequestHandler> handlers) {
        this.protocol = protocol;
        this.engine = engine;
        this.bridge = bridge;
        this.handlers = handlers;
        this.controlExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "minion-sdk-control");
            t.setDaemon(true);
            return t;
        });
    }

    /** 双向循环；返回进程退出码（0=正常 result 收束/EOF，2=协议拒执） */
    public int run(BufferedReader in) throws IOException {
        ExecutorService turns = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "minion-sdk-turns");
            t.setDaemon(true);
            return t;
        });
        try {
            // 首帧必须 initialize；版本不兼容明确报错拒执
            SdkFrameIo.Read first = SdkFrameIo.readFrame(in);
            if (first.overrun) {
                protocol.protocolError(null, "frame_too_large", "首帧超过 1MiB 上限，拒执");
                return 2;
            }
            JsonObject init = first.line == null ? null : SdkFrameIo.parse(first.line);
            if (init == null || !"initialize".equals(str(init, "type"))) {
                protocol.protocolError(requestIdOf(init),
                        "expected_initialize", "首帧必须为 initialize");
                return 2;
            }
            if (!SdkFrameIo.versionCompatible(init, StreamJsonProtocol.PROTOCOL_VERSION)) {
                protocol.protocolError(requestIdOf(init), "version_mismatch",
                        "protocolVersion 不兼容（服务端 " + StreamJsonProtocol.PROTOCOL_VERSION
                                + "）: " + str(init, "protocolVersion"));
                return 2;
            }
            JsonObject initBody = new JsonObject();
            initBody.addProperty("session_id", engine.sessionId());
            initBody.addProperty("protocolVersion", StreamJsonProtocol.PROTOCOL_VERSION);
            protocol.controlResponseSuccess(requestIdOf(init), initBody);
            protocol.sessionStateChanged(engine.sessionId(), "idle");

            Future<?> inFlight = null;
            boolean running = true;
            while (running) {
                SdkFrameIo.Read read = SdkFrameIo.readFrame(in);
                if (read.overrun) {
                    protocol.protocolError(null, "frame_too_large",
                            "帧超过 " + SdkFrameIo.MAX_FRAME_BYTES + " 字节上限，已丢弃不执行");
                    continue;
                }
                if (read.line == null) break;   // EOF：宿主断连
                JsonObject frame = SdkFrameIo.parse(read.line);
                if (frame == null) {
                    protocol.protocolError(null, "malformed_frame", "畸形 JSON 帧，不执行");
                    continue;
                }
                String type = str(frame, "type");
                if ("control_response".equals(type)) {
                    String requestId = str(frame, "request_id");
                    JsonObject response = frame.has("response") && frame.get("response").isJsonObject()
                            ? frame.getAsJsonObject("response") : frame;
                    bridge.complete(requestId, response);
                } else if ("control_request".equals(type)) {
                    dispatchControl(frame, inFlight);
                } else if ("user".equals(type)) {
                    final String prompt = str(frame, "content");
                    if (prompt == null || prompt.trim().isEmpty()) {
                        protocol.protocolError(requestIdOf(frame), "empty_user", "user 帧缺少 content");
                        continue;
                    }
                    protocol.sessionStateChanged(engine.sessionId(), "running");
                    inFlight = turns.submit(() -> runOneTurn(prompt));
                } else {
                    protocol.protocolError(requestIdOf(frame), "unknown_type", "未知帧类型: " + type);
                }
            }
            // 断连/EOF：中断在途 + 清 pending（deny 语义）+ 有界等待收尾
            engine.interrupt();
            bridge.closeAll();
            if (inFlight != null) {
                try { inFlight.get(10, TimeUnit.SECONDS); }
                catch (Exception ignored) { turns.shutdownNow(); }
            }
            return 0;
        } finally {
            turns.shutdownNow();
            controlExecutor.shutdownNow();
        }
    }

    /** 单轮执行（turn 线程）：跑完发 assistant + result，回到 idle */
    private void runOneTurn(String prompt) {
        long start = System.currentTimeMillis();
        try {
            engine.runUserTurn(prompt);
            String pause = engine.pauseCode();
            boolean ok = pause == null && engine.lastTurnSucceeded();
            String subtype = pause != null ? pause : ok ? "success" : "error_max_turns";
            protocol.assistantMessage(engine.sessionId(), lastAssistantText());
            protocol.result(engine.sessionId(), ok, subtype,
                    System.currentTimeMillis() - start, engine.lastTurnToolRounds());
        } catch (Exception e) {
            protocol.result(engine.sessionId(), false, "error_exception",
                    System.currentTimeMillis() - start, 0);
            System.err.println("[sdk] 轮执行异常: " + e.getMessage());
        } finally {
            protocol.sessionStateChanged(engine.sessionId(), "idle");
        }
    }

    /** 宿主→Minion 控制请求分发（interrupt/set_permission_mode/set_model/keep_alive） */
    private void dispatchControl(JsonObject frame, Future<?> inFlight) {
        String requestId = requestIdOf(frame);
        String subtype = str(frame, "subtype");
        if ("interrupt".equals(subtype)) {
            engine.interrupt();
            protocol.controlResponseSuccess(requestId, new JsonObject());
        } else if ("set_permission_mode".equals(subtype)) {
            String error = engine.setPermissionMode(str(frame, "mode"));
            if (error == null) protocol.controlResponseSuccess(requestId, new JsonObject());
            else protocol.controlResponseError(requestId, "invalid_mode", error);
        } else if ("set_model".equals(subtype)) {
            String error = engine.setModel(str(frame, "model"));
            if (error == null) protocol.controlResponseSuccess(requestId, new JsonObject());
            else protocol.controlResponseError(requestId, "invalid_model", error);
        } else if ("keep_alive".equals(subtype)) {
            protocol.controlResponseSuccess(requestId, new JsonObject());
        } else if ("initialize".equals(subtype)) {
            protocol.controlResponseError(requestId, "already_initialized", "会话已初始化");
        } else if (handlers != null && handlers.containsKey(subtype)) {
            dispatchExtended(requestId, subtype, frame);
        } else {
            protocol.controlResponseError(requestId, "unknown_request", "未知控制请求: " + subtype);
        }
    }

    /** §12.2 二期扩展请求：异步执行 + 30s 有界超时（慢请求不阻塞读循环 interrupt 响应） */
    private void dispatchExtended(final String requestId, final String subtype, JsonObject frame) {
        final JsonObject body = frame.has("body") && frame.get("body").isJsonObject()
                ? frame.getAsJsonObject("body") : new JsonObject();
        final SdkRequestHandler handler = handlers.get(subtype);
        try {
            JsonObject result = controlExecutor.submit(() -> handler.handle(body))
                    .get(30, TimeUnit.SECONDS);
            protocol.controlResponseSuccess(requestId, result == null ? new JsonObject() : result);
        } catch (java.util.concurrent.TimeoutException timeout) {
            protocol.controlResponseError(requestId, "timeout", "请求处理超时（30s）: " + subtype);
        } catch (Exception failed) {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            if (cause instanceof SdkRequestHandler.SdkRequestError)
                protocol.controlResponseError(requestId,
                        ((SdkRequestHandler.SdkRequestError) cause).code, cause.getMessage());
            else
                protocol.controlResponseError(requestId, "internal_error",
                        subtype + " 处理失败: " + cause.getMessage());
        }
    }

    private String lastAssistantText() {
        return "";   // 正文已由 stream_event text_delta 流式送达；此处留空避免重复
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsString() : null;
    }

    private static String requestIdOf(JsonObject frame) {
        String id = str(frame, "request_id");
        return id != null ? id : str(frame, "requestId");
    }
}
