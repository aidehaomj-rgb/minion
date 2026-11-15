package com.minion.core.llm;

/** 隔离结束检查的正文与用量，不写入主会话模型历史。 */
public final class StopReviewResponse {
    public final String text;
    public final Usage usage;
    public StopReviewResponse(String text, Usage usage) { this.text=text; this.usage=usage; }
}
