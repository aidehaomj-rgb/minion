package com.minion.core.cli;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §12.2 SDK 宿主桥：Minion→宿主控制请求（can_use_tool 等）的 pending 管理——
 * request_id 关联、<b>宿主无响应超时 fail-closed（deny）</b>、pending 数有界
 * （超上限立即 fail 不发帧）、断连/会话终止清理全部 pending（按 deny 处理）。
 * 线程安全：工具线程发请求，SDK 读循环线程回填响应。
 */
public final class SdkHostBridge {

    /** pending 上限（并发工具批×历史请求残留；超限=协议层已失衡，fail-closed） */
    public static final int MAX_PENDING = 64;
    /** can_use_tool 宿主无响应默认超时（毫秒）——超时按 deny */
    public static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final StreamJsonProtocol protocol;
    private final long timeoutMs;
    private final AtomicLong requestSeq = new AtomicLong();
    private final Map<String, CompletableFuture<JsonObject>> pending =
            new ConcurrentHashMap<String, CompletableFuture<JsonObject>>();
    private volatile boolean closed;

    public SdkHostBridge(StreamJsonProtocol protocol, long timeoutMs) {
        this.protocol = protocol;
        this.timeoutMs = timeoutMs;
    }

    /** 宿主回填响应（SDK 读循环调用）；未知 request_id 忽略 */
    public void complete(String requestId, JsonObject response) {
        if (requestId == null) return;
        CompletableFuture<JsonObject> f = pending.remove(requestId);
        if (f != null) f.complete(response);
    }

    /** 断连/终止：全部 pending 立即失败（按 deny 语义）；后续请求一律 fail */
    public void closeAll() {
        closed = true;
        for (CompletableFuture<JsonObject> f : pending.values())
            f.completeExceptionally(new TimeoutException("sdk session closed"));
        pending.clear();
    }

    public boolean isClosed() { return closed; }

    /**
     * can_use_tool 往返：宿主返回 {"behavior":"allow"|"deny"|"ask","updatedInput":{...}}；
     * 超时/断连/pending 超限/畸形响应 → deny（fail-closed，绝不因协议问题放行）。
     */
    public JsonObject canUseTool(String toolName, JsonObject input) {
        JsonObject body = new JsonObject();
        body.addProperty("tool_name", toolName);
        body.add("input", input == null ? new JsonObject() : input);
        return roundTrip("can_use_tool", body);
    }

    /** 通用往返（超时/断连/超限 → null=按 deny 处理） */
    public JsonObject roundTrip(String subtype, JsonObject requestBody) {
        if (closed) return null;
        String requestId = "req-" + requestSeq.incrementAndGet();
        CompletableFuture<JsonObject> future = new CompletableFuture<JsonObject>();
        if (pending.size() >= MAX_PENDING) return null;   // 背压上限：fail-closed
        pending.put(requestId, future);
        protocol.controlRequest(requestId, subtype, requestBody);
        try {
            JsonObject resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return resp == null ? null : resp;
        } catch (Exception failed) {
            pending.remove(requestId);
            return null;
        }
    }

    /** pending 数（诊断/测试） */
    public int pendingCount() { return pending.size(); }
}
