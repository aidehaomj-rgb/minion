package com.minion.core.context;

import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.SideQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用摘要（开发说明 v2.4 §5.8，toolSummary.enabled 默认 false）：
 * 工具批结束后经 {@link SideQuery} 异步生成面向用户的简短摘要——与 toolUseId 集合绑定；
 * 失败只省略摘要（不影响工具结果/下一轮调用/主循环门禁）；不注入用户指令通道。
 * 子代理不生成主界面摘要（调用方保证）。
 */
public final class ToolUseSummarizer {

    private ToolUseSummarizer() { }

    /**
     * 异步生成摘要（调用方在线程池执行；返回 null=失败/无内容）。
     * 摘要作为 taskControl 消息附加（面向用户展示，不进入用户指令语义）。
     */
    public static Message summarize(LlmClient llm, List<String> toolNames,
                                    List<String> resultPreviews, String userGoal) {
        if (llm == null || toolNames == null || toolNames.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("用不超过两句话向用户总结这批工具做了什么（面向结果，不列参数）：\n");
        for (int i = 0; i < toolNames.size() && i < 12; i++)
            sb.append("- ").append(toolNames.get(i)).append(": ")
              .append(preview(resultPreviews == null ? "" : resultPreviews.get(i))).append('\n');
        if (userGoal != null && !userGoal.trim().isEmpty())
            sb.append("当前目标: ").append(userGoal.trim());
        String text = SideQuery.complete(llm,
                new ArrayList<Message>(java.util.Collections.singletonList(Message.user(sb.toString()))),
                "你是工具执行摘要助手。只输出摘要本身，不要前缀、解释或行动建议。", "tool-summary");
        if (text == null || text.trim().isEmpty()) return null;
        Message m = Message.taskControl("[工具批摘要] " + text.trim());
        return m;
    }

    private static String preview(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }
}
