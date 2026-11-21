package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.LocalJobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cron 工具（v2.4 §3.x 定时任务面）：定时 prompt 任务的 add/list/remove——包装既有
 * {@link LocalJobs} 账本（唯一真源：jobs.json 校验/安全工具白名单/启用门控不自动开）。
 * 定时器由宿主 jobClock 驱动（V68 既有 tick 通道），本工具只做登记面。
 */
public final class CronTool implements Tool {

    private final LocalJobs jobs;

    public CronTool(LocalJobs jobs) { this.jobs = jobs; }

    /** 同包测试/宿主访问底层账本（唯一真源） */
    LocalJobs jobs() { return jobs; }

    @Override public String name() { return "Cron"; }

    @Override public String description() {
        return "定时任务登记：add（prompt+intervalMinutes+title）/list/remove；由本地调度器按间隔触发（只读工具白名单）";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("管理定时 prompt 任务",
                new String[]{"action", "id", "title", "prompt", "intervalMinutes"}, new String[]{"action"});
    }

    @Override public boolean isHighRisk(JsonObject args) {
        String action = text(args, "action").toLowerCase(java.util.Locale.ROOT);
        return "add".equals(action) || "remove".equals(action);
    }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = text(args, "action").toLowerCase(java.util.Locale.ROOT);
        if ("add".equals(action)) return add(args);
        if ("list".equals(action)) return list();
        if ("remove".equals(action)) return remove(text(args, "id"));
        return ToolResult.errorWithoutSideEffects("action 只支持 add/list/remove: " + action);
    }

    private ToolResult add(JsonObject args) throws Exception {
        String prompt = text(args, "prompt");
        if (prompt.trim().isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 prompt（定时执行的任务提示词）");
        int interval = 60;
        try {
            if (args.has("intervalMinutes") && !args.get("intervalMinutes").isJsonNull())
                interval = args.get("intervalMinutes").getAsInt();
        } catch (NumberFormatException e) {
            return ToolResult.errorWithoutSideEffects("intervalMinutes 格式错误");
        }
        if (interval < 1) return ToolResult.errorWithoutSideEffects("intervalMinutes 必须 ≥1");
        LocalJobs.Job job = new LocalJobs.Job();
        job.title = text(args, "title").isEmpty() ? prompt.substring(0, Math.min(30, prompt.length())) : text(args, "title");
        job.kind = "timer";
        job.prompt = prompt.trim();
        job.intervalMinutes = interval;
        job.status = "registered: 待用户在工作台启用（登记不自动执行）";
        // 新任务默认不启用：LocalJobs 的启用门控是用户动作（V68 语义，防模型自启定时器）
        job.enabled = false;
        job.lastRun = 0;
        try {
            jobs.saveJob(null, job, null);   // id=null=新增（saveJob 内部生成 id）
        } catch (Exception denied) {
            return ToolResult.errorWithoutSideEffects("登记被拒（提示词/间隔/数量上限校验）: " + denied.getMessage());
        }
        return ToolResult.success("已登记定时任务「" + job.title + "」每 " + interval
                + " 分钟（id=" + job.id + "，默认未启用；请在工作台启用后生效）");
    }

    private ToolResult list() {
        List<LocalJobs.Job> snapshot = jobs.snapshot();
        if (snapshot.isEmpty()) return ToolResult.success("无定时任务");
        StringBuilder sb = new StringBuilder();
        for (LocalJobs.Job j : snapshot)
            sb.append(j.id).append(" [").append(j.enabled ? "enabled" : "disabled").append("] 每 ")
              .append(j.intervalMinutes).append("min 「").append(j.title).append("」 上次: ")
              .append(j.lastRun == 0 ? "从未" : new java.text.SimpleDateFormat("MM-dd HH:mm")
                      .format(new java.util.Date(j.lastRun))).append('\n');
        return ToolResult.success(sb.toString().trim());
    }

    private ToolResult remove(String id) throws Exception {
        if (id.trim().isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 id");
        try {
            jobs.deleteJob(id.trim());
        } catch (Exception denied) {
            return ToolResult.errorWithoutSideEffects("删除失败: " + denied.getMessage());
        }
        return ToolResult.success("已删除定时任务 " + id);
    }

    private static String text(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }
}
