package com.minion.core.cli;

import com.minion.core.llm.ImagePart;
import com.minion.core.tools.ToolResult;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Headless UI（§12）：收集 AgentLoop 回调供 CLI 输出——无 JavaFX。
 * 正文增量经 StreamJsonProtocol 转发；错误/警告到 stderr。
 */
public final class HeadlessUi implements com.minion.core.agent.AgentUi {
    public final List<String> warnings = new CopyOnWriteArrayList<String>();
    public final List<String> errors = new CopyOnWriteArrayList<String>();
    private final StreamJsonProtocol protocol;
    private final String sessionId;

    public HeadlessUi(StreamJsonProtocol protocol, String sessionId) {
        this.protocol = protocol;
        this.sessionId = sessionId;
    }

    @Override public void onContent(String delta) {
        if (protocol != null) protocol.textDelta(sessionId, delta);
    }
    @Override public void onWarning(String message) { warnings.add(message); }
    @Override public void onError(String message) { errors.add(message); }
}
