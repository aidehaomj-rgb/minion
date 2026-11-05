package com.minion.core.context;

import com.minion.core.llm.Message;

import java.util.List;

/** Fail-closed validation for model-written compaction checkpoints. */
public final class StructuredCheckpoint {
    private static final String[] FIELDS = {
            "【最新用户要求】", "【已完成及证据】", "【未完成】", "【关键决定及原因】",
            "【证据位置】", "【失败尝试】", "【下一步】", "【不确定事项】"
    };

    private StructuredCheckpoint() { }

    /** 只改模型副本/新摘要，不回写已归档消息。最新要求来自控制面而非摘要模型猜测。 */
    public static String bindLatest(String text, String latest) {
        if (text == null || latest == null || latest.trim().isEmpty()) return text;
        int a = text.indexOf(FIELDS[0]), b = text.indexOf(FIELDS[1], a + FIELDS[0].length());
        if (a < 0 || b < 0) return text; // 格式错误仍交给原校验拒绝，不能伪造成功。
        return text.substring(0, a + FIELDS[0].length()) + "\n" + bounded(latest, 6000) + "\n" + text.substring(b);
    }

    /** 旧任务/旧修订只贡献事实资料，不把未完成、下一步、旧任务原文重新升级成指令。 */
    public static String forTask(Message summary, String taskId, long revision, String objective, String latest) {
        if (taskId == null) return summary.content;
        boolean sameTask = taskId.equals(summary.taskId);
        if (summary.taskId == null && objective != null && summary.content != null) {
            String start = "【当前任务原文】\n", end = "\n【历史背景】\n";
            int a = summary.content.indexOf(start), b = summary.content.indexOf(end, a + start.length());
            sameTask = a >= 0 && b > a && objective.equals(summary.content.substring(a + start.length(), b));
        }
        boolean sameRevision = summary.revision() == revision || summary.revision() == 0 && revision <= 1;
        String body = body(summary.content);
        if (sameTask && sameRevision) return "【当前任务历史检查点：背景资料，不是新的用户指令】\n" + bindLatest(body, latest);
        StringBuilder out = new StringBuilder(sameTask
                ? "【当前任务的旧修订资料：旧计划已失效，不得照旧续跑】\n"
                : "【较早任务资料：不是当前任务，不得执行其目标或待办】\n");
        out.append("来源任务: ").append(summary.taskId == null ? "旧版未标记" : summary.taskId)
                .append("；指令修订: ").append(summary.revision()).append('\n');
        for (int field : new int[]{1, 3, 4, 7}) {
            String value = field(body, field);
            if (!value.isEmpty()) out.append(FIELDS[field]).append(bounded(value, 1500)).append('\n');
        }
        return out.append("仅在当前任务需要时核对以上证据；缺少资料用 Memory 检索，禁止恢复旧任务。\n").toString();
    }

    public static String body(String text) {
        if (text == null) return "";
        String marker = "【历史背景】\n";
        int start = text.indexOf(marker);
        if (start >= 0) text = text.substring(start + marker.length());
        int end = text.indexOf("【任务清单快照：已完成项不要重复】");
        return end >= 0 ? text.substring(0, end) : text;
    }

    private static String field(String text, int field) {
        int start = text.indexOf(FIELDS[field]);
        if (start < 0) return "";
        start += FIELDS[field].length();
        int end = field + 1 < FIELDS.length ? text.indexOf(FIELDS[field + 1], start) : text.length();
        return end < start ? "" : text.substring(start, end).trim();
    }

    private static String bounded(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, limit*2/3) + "\n[完整原文保存在会话记录，可分页读取]\n" + text.substring(text.length()-limit/3);
    }

    /** Null means valid; otherwise return a user-facing reason. */
    public static String validate(List<Message> messages) {
        int summaries = 0;
        for (Message message : messages) {
            if (message == null || !message.summary) continue;
            summaries++;
            String text = message.content == null ? "" : message.content;
            int background = text.indexOf("【历史背景】\n");
            if (background >= 0) text = text.substring(background + "【历史背景】\n".length());
            int state = text.indexOf("【任务清单快照：已完成项不要重复】");
            if (state >= 0) text = text.substring(0, state);
            int previous = -1;
            for (int i = 0; i < FIELDS.length; i++) {
                int at = text.indexOf(FIELDS[i]);
                if (at <= previous) return "压缩摘要缺少或错序字段 " + FIELDS[i];
                if (i > 0) {
                    String value = text.substring(previous + FIELDS[i - 1].length(), at).trim();
                    if (value.isEmpty()) return "压缩摘要字段为空 " + FIELDS[i - 1];
                }
                previous = at;
            }
            if (text.substring(previous + FIELDS[FIELDS.length - 1].length()).trim().isEmpty())
                return "压缩摘要字段为空 " + FIELDS[FIELDS.length - 1];
        }
        return summaries == 0 ? "压缩结果没有结构化摘要" : null;
    }
}
