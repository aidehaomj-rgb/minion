package com.minion.gui.command;

import com.google.gson.JsonObject;
import com.minion.core.skills.Skill;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.gui.session.SessionHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 斜杠命令本地分发（恢复 CLI 语义）：返回 null = 非命令（按普通消息发送）；
 *  非 null = 已本地执行的命令展示文本。命令结果永不发给 LLM。 */
public class CommandDispatcher {

    public CommandDispatcher() { }

    public String dispatch(SessionHandle h, String input) {
        if (input == null || !input.trim().startsWith("/")) return null;
        String[] parts = input.trim().split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if ("/help".equals(cmd)) return helpText();
        if ("/skills".equals(cmd)) return skillsText(h);
        if ("/skill".equals(cmd)) return dispatchSkill(h, parts);
        if ("/tokens".equals(cmd)) return tokensText(h);
        if ("/compact".equals(cmd)) return dispatchCompact(h);
        if ("/capabilities".equals(cmd) || "/能力".equals(cmd)) return capabilitiesText();
        if ("/diagnostics".equals(cmd) || "/诊断".equals(cmd)) return dispatchDiagnostics(h, parts);
        if ("/logs".equals(cmd) || "/日志".equals(cmd)) return dispatchLocalTool(h, "Logs", logsArgs(parts));
        if ("/update".equals(cmd) || "/更新".equals(cmd)) return dispatchLocalTool(h, "Update", updateArgs(parts));
        if ("/checkpoints".equals(cmd) || "/检查点".equals(cmd)) return dispatchLocalTool(h, "Checkpoint", checkpointArgs(parts));
        if ("/queue".equals(cmd) || "/队列".equals(cmd)) return dispatchQueue(h, parts);
        if ("/context".equals(cmd) || "/上下文".equals(cmd)) return dispatchLocalTool(h, "Context", simpleAction(parts,"status"));
        if ("/memory".equals(cmd) || "/记忆".equals(cmd)) return dispatchLocalTool(h, "Memory", simpleAction(parts,"status"));
        if ("/permissions".equals(cmd) || "/权限".equals(cmd)) return dispatchLocalTool(h, "Permission", permissionArgs(parts));
        if ("/secrets".equals(cmd) || "/密钥".equals(cmd)) return dispatchLocalTool(h, "Secrets", simpleAction(parts,"list"));
        return "未知命令 " + parts[0] + "（/help 查看）";
    }

    private String helpText() {
        return "可用命令：\n"
                + "/help            显示本帮助\n"
                + "/skills          列出可用技能\n"
                + "/skill <名> [参数]  加载技能到当前会话（可附参数，下一轮请求生效）\n"
                + "/compact         立即压缩上下文\n"
                + "/diagnostics [export]  运行环境诊断/导出报告\n"
                + "/logs [行数]     查看脱敏诊断日志\n"
                + "/update          检查离线更新包与备份\n"
                + "/checkpoints     查看文件修改检查点\n"
                + "/queue add <任务>  排队到当前任务结束后执行\n"
                + "/sessions [关键词]  搜索会话；/session fork|pin|archive|export\n"
                + "/context         查看上下文组成与占用\n"
                + "/memory [status|read]  查看项目长期记忆\n"
                + "/permissions [模式]  查看或切换权限策略\n"
                + "/secrets         列出已配置密钥名称\n"
                + "/capabilities    显示本机 Agent 能力与使用入口\n"
                + "/tokens          显示 token 用量统计";
    }

    private String capabilitiesText() {
        return "本机能力：\n"
                + "- Python/Anaconda：Python 工具（status/run_code/run_file）\n"
                + "- Excel/CSV：Excel 工具（inspect/read/write/append/merge/pivot/chart/export）\n"
                + "- SQLite：SQLite 工具（schema/query/execute）\n"
                + "- 本地知识库：Knowledge 工具（add/search/list/read/delete）\n"
                + "- 浏览器：Browser/BrowserEval/BrowserScreenshot/BrowserDebug\n"
                + "- 稳定性：Diagnostics、Logs、Checkpoint、Update、任务队列\n"
                + "- 开发构建：Read/Write/Edit/Glob/Grep/Bash、Git、后台进程与构建工具\n"
                + "- 扩展：Skills、MCP、子 Agent\n"
                + "可发送“检查 Python 环境”，Agent 会调用 Python status。";
    }


    /** 技能清单来自会话快照（每会话一份，切换会话/空间自然跟着变） */
    private static List<Skill> skillsOf(SessionHandle h) {
        return h == null || h.loop == null ? new ArrayList<Skill>() : h.loop.allSkills();
    }

    private String skillsText(SessionHandle h) {
        List<Skill> skills = skillsOf(h);
        if (skills.isEmpty()) {
            return "未发现可用技能。请检查 设置 → 基础设置 → 技能目录（skills.dir），"
                    + "或工作空间 → 项目级技能路径";
        }
        StringBuilder sb = new StringBuilder("可用技能（").append(skills.size()).append(" 个）：");
        for (Skill s : skills) sb.append('\n').append("- ").append(s.hint());
        return sb.toString();
    }

    private String dispatchSkill(SessionHandle h, String[] parts) {
        if (parts.length < 2) return "用法: /skill <技能名> [参数]（/skills 查看列表）";
        for (Skill s : skillsOf(h)) {
            if (s.name.equalsIgnoreCase(parts[1])) {
                String args = joinTail(parts, 2);
                h.loop.offerSkillLoad(s, args);
                if (args.isEmpty()) {
                    return "已加载技能: " + s.name + "（正文将注入，下一轮请求生效）";
                }
                return "已加载技能: " + s.name + "（含参数: " + args + "，下一轮请求生效）";
            }
        }
        return "未找到技能: " + parts[1] + "（/skills 查看列表）";
    }

    /** 尾随文字拼接为技能参数（split 已折叠连续空白，此处以单空格还原） */
    private static String joinTail(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private String tokensText(SessionHandle h) {
        com.minion.core.llm.UsageTracker t = h.loop.usage();
        return String.format(Locale.ROOT, "会话统计: in %d  out %d  thinking %d  合计 %d",
                t.sessionInput(), t.sessionOutput(), t.sessionThinking(), t.sessionTotal());
    }

    /** /compact 含阻塞 LLM 调用：提交会话工作线程执行，绝不在 FX 线程跑；运行中时排队等回合结束 */
    private String dispatchCompact(SessionHandle h) {
        h.pool.submit(new Runnable() {
            @Override public void run() { h.loop.compactNow(); }
        });
        return "已请求压缩上下文（会话空闲后执行）";
    }

    private String dispatchDiagnostics(final SessionHandle h, String[] parts) {
        final JsonObject args = new JsonObject();
        args.addProperty("action", parts.length > 1 && "export".equalsIgnoreCase(parts[1]) ? "export" : "run");
        h.pool.submit(new Runnable() {
            @Override public void run() { h.controller.onSystem(executeTool(h, "Diagnostics", args)); }
        });
        return "已开始环境诊断，完成后将在消息区显示结果";
    }

    private String dispatchLocalTool(SessionHandle h, String name, JsonObject args) {
        return executeTool(h, name, args);
    }

    private static String executeTool(SessionHandle h, String name, JsonObject args) {
        Tool tool = h.loop.registry().get(name);
        if (tool == null) return name + " 功能尚未注册";
        try {
            ToolResult result = tool.execute(args);
            return (result.ok ? "" : "失败: ") + result.output;
        } catch (Exception e) { return name + " 执行异常: " + e.getMessage(); }
    }

    private String dispatchQueue(SessionHandle h, String[] parts) {
        if (parts.length == 1 || "list".equalsIgnoreCase(parts[1])) {
            List<SessionHandle.QueuedMessage> list = h.queuedSnapshot();
            if (list.isEmpty()) return "任务队列为空";
            StringBuilder sb = new StringBuilder("任务队列（").append(list.size()).append("）：");
            for (int i = 0; i < list.size(); i++) sb.append('\n').append(i + 1).append(". ").append(preview(list.get(i).text));
            return sb.toString();
        }
        if ("add".equalsIgnoreCase(parts[1])) {
            String text = joinTail(parts, 2);
            if (text.isEmpty()) return "用法: /queue add <任务内容>";
            return "已加入任务队列，当前位置 " + h.enqueue(text);
        }
        if ("remove".equalsIgnoreCase(parts[1])) {
            if (parts.length < 3) return "用法: /queue remove <序号>";
            try { return h.removeQueued(Integer.parseInt(parts[2])) ? "已移除队列任务" : "队列序号不存在"; }
            catch (NumberFormatException e) { return "队列序号必须是数字"; }
        }
        if ("clear".equalsIgnoreCase(parts[1])) return "已清空队列任务 " + h.clearQueued() + " 条";
        return "用法: /queue [list|add <任务>|remove <序号>|clear]";
    }

    private static JsonObject logsArgs(String[] parts) {
        JsonObject o = new JsonObject(); o.addProperty("action", "tail");
        if (parts.length > 1) {
            if ("export".equalsIgnoreCase(parts[1])) { o.addProperty("action", "export"); if (parts.length > 2) o.addProperty("path", parts[2]); }
            else o.addProperty("lines", parts[1]);
        }
        return o;
    }
    private static JsonObject updateArgs(String[] parts) {
        JsonObject o = new JsonObject(); o.addProperty("action", parts.length > 1 ? "verify" : "status");
        if (parts.length > 1) o.addProperty("packageDir", parts[1]);
        return o;
    }
    private static JsonObject checkpointArgs(String[] parts) {
        JsonObject o = new JsonObject(); o.addProperty("action", parts.length > 1 ? parts[1] : "list");
        if (parts.length > 2) o.addProperty("id", parts[2]);
        return o;
    }
    private static JsonObject simpleAction(String[] parts,String fallback){JsonObject o=new JsonObject();o.addProperty("action",parts.length>1?parts[1]:fallback);return o;}
    private static JsonObject permissionArgs(String[] parts){JsonObject o=new JsonObject();if(parts.length>1){o.addProperty("action","set");o.addProperty("mode",parts[1]);}else o.addProperty("action","status");return o;}
    private static String preview(String text) { return text.length() > 100 ? text.substring(0, 100) + "..." : text; }
}
