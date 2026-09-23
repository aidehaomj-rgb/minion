package com.minion.core.llm;

import com.google.gson.JsonObject;

import java.util.List;

public interface LlmClient {
    /** 流式对话；handler 回调在调用线程。tools 为空列表/null 表示不带工具。 */
    void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
            throws LlmException;

    /** 恢复请求专用：临时关闭模型思考，强制产出正文/工具动作；默认实现保持兼容。 */
    default void streamChatWithoutThinking(List<Message> messages, List<JsonObject> tools,
                                           StreamHandler handler) throws LlmException {
        streamChat(messages, tools, handler);
    }

    /** 非流式对话（压缩等内部请求），返回 content */
    String completeChat(List<Message> messages, String systemPrompt) throws LlmException;

    /** 中断进行中的请求（Ctrl+C / 用户打断） */
    default void cancel() { }

    /** 释放底层资源（HTTP 连接池/线程）；默认无操作。关闭后不可再发起请求 */
    default void close() { }
}
