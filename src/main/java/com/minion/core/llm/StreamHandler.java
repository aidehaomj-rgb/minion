package com.minion.core.llm;

import java.util.List;

public interface StreamHandler {
    /** 仅终止当前响应，不等同于用户停止或全局客户端取消。 */
    default boolean shouldStopResponse() { return false; }
    default void onToolCallDelta() { }
    default void onThinking(String delta) { }
    default void onContent(String delta) { }
    default void onUsage(Usage usage) { }
    void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls);
    default void onError(LlmException e) { }
}
