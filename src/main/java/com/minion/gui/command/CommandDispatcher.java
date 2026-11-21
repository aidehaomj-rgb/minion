package com.minion.gui.command;

import com.google.gson.JsonObject;
import com.minion.core.skills.Skill;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.agent.ExecutionMode;
import com.minion.gui.session.SessionHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 斜杠命令本地分发（恢复 CLI 语义）：返回 null = 非命令（按普通消息发送）；
 *  非 null = 已本地执行的命令展示文本。命令结果永不发给 LLM。 */
public class CommandDispatcher {

    public CommandDispatcher() { }

    /** /config 的 FeatureFlags 视图（§13.1：值、来源、生效范围）；未接线时命令返回不可用提示 */
    private java.util.function.Supplier<com.minion.core.config.FeatureFlags> flags = () -> null;

    /** /stats 本地使用统计视图（§13.3）；未接线时命令返回不可用提示 */
    private java.util.function.Supplier<String> stats = () -> null;

    /** /doctor 环境与配置诊断（§13.1）；未接线时命令返回不可用提示 */
    private java.util.function.Supplier<String> doctor = () -> null;

    /** §7.2 /plugin 插件管理（列表/安装/启停/回退/市场）；未接线时返回提示 */
    private java.util.function.Function<String[], String> plugin = parts -> null;

    /** /queue add 的优先发送通道（§2.3 now 含中断在途）；未接线时走普通入队 */
    public interface QueuePriority {
        void apply(SessionHandle h, String text, String priority);
    }

    private volatile QueuePriority queuePriority;

    public CommandDispatcher queuePriority(QueuePriority channel) {
        this.queuePriority = channel;
        return this;
    }

    public CommandDispatcher doctor(java.util.function.Supplier<String> supplier) {
        this.doctor = supplier;
        return this;
    }

    public CommandDispatcher plugin(java.util.function.Function<String[], String> handler) {
        this.plugin = handler;
        return this;
    }

    /** /rewind 回退执行器（§9.3）：list=列出可回退消息；execute=按 eventId+mode 回退 */
    public interface RewindApi {
        String list(com.minion.core.agent.Session session);
        String execute(com.minion.core.agent.Session session, String eventId, String mode);
    }

    private volatile RewindApi rewind;

    public CommandDispatcher rewind(RewindApi api) { this.rewind = api; return this; }

    public CommandDispatcher featureFlags(java.util.function.Supplier<com.minion.core.config.FeatureFlags> supplier) {
        this.flags = supplier;
        return this;
    }

    public CommandDispatcher stats(java.util.function.Supplier<String> supplier) {
        this.stats = supplier;
        return this;
    }

    public String dispatch(SessionHandle h, String input) {
        if (input == null || !input.trim().startsWith("/")) return null;
        String[] parts = input.trim().split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if ("/help".equals(cmd)) return helpText();
        if ("/config".equals(cmd) || "/开关".equals(cmd)) return configText(parts);
        if ("/stats".equals(cmd) || "/统计".equals(cmd)) return statsText();
        if ("/rewind".equals(cmd) || "/回退".equals(cmd)) return rewindText(h, parts);
        if ("/cost".equals(cmd) || "/费用".equals(cmd)) return costText(h);
        if ("/plugin".equals(cmd) || "/插件".equals(cmd) || "/reload-plugins".equals(cmd)) {
            // §7.2 /plugin 系（/reload-plugins 等价 /plugin reload）
            String[] effective = "/reload-plugins".equals(cmd)
                    ? new String[]{"/plugin", "reload"} : parts;
            String result = plugin.apply(effective);
            return result == null ? "插件管理未接线" : result;
        }
        if ("/doctor".equals(cmd) || "/诊断环境".equals(cmd)) {
            String text = doctor == null ? null : doctor.get();
            return text == null ? "/doctor 不可用（诊断源未接线）" : text;
        }
        // ==================== §11.2 补齐命令（v2.4 全清单） ====================
        if ("/clear".equals(cmd) || "/reset".equals(cmd) || "/new".equals(cmd) || "/清空".equals(cmd))
            return "会话清空：请通过 GUI 新建会话（/new 或 Ctrl+N）；CLI 用 minion -p（每次独立）";
        if ("/model".equals(cmd) || "/模型".equals(cmd)) return modelText(parts);
        if ("/effort".equals(cmd)) return "推理力度切换：请在设置窗口（模型配置）调整 model.reasoningEffort\n当前: " + (h.loop != null ? h.session.modelName : "未知");
        if ("/status".equals(cmd) || "/状态".equals(cmd)) return statusText(h);
        if ("/vim".equals(cmd)) return "Vim 模式：V68 输入框已内嵌 Emacs 快捷键子集；Vim 完整键位待 UI 切片";
        if ("/add-dir".equals(cmd)) return addDirText(parts);
        if ("/resume".equals(cmd) || "/continue".equals(cmd) || "/恢复".equals(cmd)) return resumeText(h, parts);
        if ("/rename".equals(cmd) || "/改名".equals(cmd)) return renameText(h, parts);
        if ("/export".equals(cmd) || "/导出".equals(cmd)) return exportText(h);
        if ("/diff".equals(cmd) || "/变更".equals(cmd)) return dispatchLocalTool(h, "Git", gitDiffArgs());
        if ("/branch".equals(cmd) || "/fork".equals(cmd) || "/分叉".equals(cmd)) return branchText();
        if ("/btw".equals(cmd) || "/旁支".equals(cmd)) return btwText(parts);
        if ("/insights".equals(cmd) || "/洞察".equals(cmd)) return "Insights 属 prompt 命令（分析历史会话）；请直接发送分析请求";
        if ("/plugin".equals(cmd) || "/plugins".equals(cmd) || "/reload-plugins".equals(cmd))
            return "插件管理：请使用 GUI 设置窗口；本版本无 CLI 插件命令（§7.2 Plugins 随后端切片）";
        if ("/mcp".equals(cmd)) return dispatchLocalTool(h, "MCP", simpleAction(parts, "list"));
        if ("/ide".equals(cmd)) return "IDE 桥：lsp.enabled=true 且 lsp.servers 配置后可用（/doctor 查看）";
        if ("/security-review".equals(cmd) || "/安全审查".equals(cmd)) return armMode(h, ExecutionMode.REVIEW);
        if ("/commit".equals(cmd) || "/提交".equals(cmd)) return commitText(parts);
        if ("/statusline".equals(cmd)) return "StatusLine：随第 10 章 UI 切片实现";
        if ("/output-style".equals(cmd)) return "Output Style：随第 10 章 UI 切片实现";
        if ("/exit".equals(cmd) || "/quit".equals(cmd) || "/退出".equals(cmd)) return "退出：请关闭 GUI 窗口（CLI 自动退出）";
        if ("/init".equals(cmd) || "/初始化".equals(cmd)) return initText(h);
        // ==================== 既有命令 ====================
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
        if ("/plan".equals(cmd) || "/计划".equals(cmd)) return armMode(h,ExecutionMode.PLAN);
        if ("/goal".equals(cmd) || "/目标".equals(cmd)) return armMode(h,ExecutionMode.GOAL);
        if ("/review".equals(cmd) || "/审查".equals(cmd)) return armMode(h,ExecutionMode.REVIEW);
        if ("/normal".equals(cmd) || "/普通".equals(cmd)) return armMode(h,ExecutionMode.NORMAL);
        if ("/task".equals(cmd) || "/agents".equals(cmd)) {
            // §7.3 /agents：任务视图 + 自定义子代理定义清单（编辑直接改 .minion/agents/*.md）
            StringBuilder taskView = new StringBuilder(h.session.taskState.render(h.session.todos));
            if ("/agents".equals(cmd) && h.loop != null) {
                java.util.List<com.minion.core.agent.AgentDefinitions.Definition> defs =
                        com.minion.core.agent.AgentDefinitions.loadAll(h.loop.workspace().workDir());
                if (!defs.isEmpty()) {
                    taskView.append("\n\n自定义子代理（.minion/agents/*.md，直接编辑文件管理）：");
                    for (com.minion.core.agent.AgentDefinitions.Definition d : defs)
                        taskView.append("\n  ").append(d.name).append(" — ")
                                .append(d.description.isEmpty() ? "（无描述）" : d.description)
                                .append(d.tools.isEmpty() ? "" : "（tools: " + d.tools + "）");
                }
            }
            return taskView.toString();
        }
        if ("/context".equals(cmd) || "/上下文".equals(cmd)) {
            // §5.5 上下文占用视图；无循环运行时回退 Context 工具状态
            if (h.loop != null) return h.loop.contextView();
            return dispatchLocalTool(h, "Context", simpleAction(parts,"status"));
        }
        if ("/memory".equals(cmd) || "/记忆".equals(cmd)) return dispatchLocalTool(h, "Memory", simpleAction(parts,"status"));
        if ("/permissions".equals(cmd) || "/权限".equals(cmd)) return dispatchLocalTool(h, "Permission", permissionArgs(parts));
        if ("/secrets".equals(cmd) || "/密钥".equals(cmd)) return dispatchLocalTool(h, "Secrets", simpleAction(parts,"list"));
        // §7.1 user-invocable 技能别名：/skill-name [参数] 等价 /skill <name> [参数]（内置命令优先）
        if (cmd.length() > 1) {
            for (Skill s : skillsOf(h)) {
                if (s.userInvocable && cmd.substring(1).equalsIgnoreCase(s.name)) {
                    String[] forwarded = new String[parts.length + 1];
                    forwarded[0] = "/skill";
                    forwarded[1] = s.name;
                    System.arraycopy(parts, 1, forwarded, 2, parts.length - 1);
                    return dispatchSkill(h, forwarded);
                }
            }
        }
        return "未知命令 " + parts[0] + "（/help 查看）";
    }

    /** /config [过滤词]：FeatureFlags 值/来源/生效范围视图（§13.1；只读展示，修改走 config.properties） */
    private String configText(String[] parts) {
        com.minion.core.config.FeatureFlags view = flags.get();
        if (view == null) return "配置视图不可用（FeatureFlags 未接线）";
        return view.render(parts.length > 1 ? parts[1] : null);
    }

    /** /stats：本地使用统计（§13.3；会话/消息/token 累计，快照落 stats.json） */
    private String statsText() {
        String text = stats.get();
        return text == null ? "统计不可用（数据源未接线）" : text;
    }

    /** /rewind [eventId] [conversation|summarize|code|both]：无参列出可回退消息（§9.3） */
    private String rewindText(SessionHandle h, String[] parts) {
        RewindApi api = rewind;
        if (api == null) return "/rewind 不可用（回退执行器未接线）";
        if (h.session == null) return "/rewind 不可用（会话未装载）";
        if (parts.length == 1) return api.list(h.session);
        String mode = parts.length >= 3 ? parts[2].toLowerCase(Locale.ROOT) : "conversation";
        return api.execute(h.session, parts[1], mode);
    }

    /** /cost：会话成本与缓存打破视图（§1.1；数据来自会话循环的 CostTracker） */
    private String costText(SessionHandle h) {
        if (h.loop == null) return "/cost 不可用（会话未装载）";
        return h.loop.costReport();
    }

    // ==================== §11.2 补齐命令的辅助方法 ====================

    private String modelText(String[] parts) {
        if (parts.length < 2) return "用法: /model <模型名>（当前仅提示；GUI 设置窗切换）";
        return "模型切换请在 GUI 设置窗口操作（涉及 API Key/端点联动）\n请求模型: " + parts[1];
    }

    private String statusText(SessionHandle h) {
        return "会话: " + (h.title != null ? h.title : h.id)
                + "\n模型: " + (h.session != null ? h.session.modelName : "未知")
                + "\n运行中: " + h.isLoaded()
                + "\n工具: " + (h.loop != null ? h.loop.registry().schemas().size() : 0)
                + "\n上下文: " + (h.loop != null && h.loop.contextManager() != null
                        ? h.loop.contextManager().estimate(h.session.messages) + " token" : "未启用");
    }

    private String addDirText(String[] parts) {
        if (parts.length < 2) return "用法: /add-dir <目录路径>";
        return "附加目录请在 GUI 设置窗口（工作空间管理）添加\n请求目录: " + parts[1];
    }

    private String resumeText(SessionHandle h, String[] parts) {
        return "会话恢复：请使用 GUI 侧边栏（历史会话列表）\n"
                + "当前会话: " + h.id + (parts.length > 1 ? "\n搜索词: " + parts[1] : "");
    }

    private String renameText(SessionHandle h, String[] parts) {
        if (parts.length < 2) return "用法: /rename <新标题>";
        return "会话改名: " + parts[1] + "（请通过 GUI 侧边栏右键操作）\n当前标题: " + h.title;
    }

    private String exportText(SessionHandle h) {
        return "导出会话：请使用 GUI 菜单（文件→导出 Markdown）\n会话: " + h.id + "（" + h.session.messages.size() + " 条消息）";
    }

    private static com.google.gson.JsonObject gitDiffArgs() {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("action", "diff");
        return args;
    }

    private String branchText() {
        return "分叉会话：请使用 GUI 侧边栏（右键→分叉）";
    }

    private String btwText(String[] parts) {
        if (parts.length < 2) return "用法: /btw <旁支问题>（不打断主线）";
        return "旁支提问请使用 task 工具（mode=explore）或 AgentControl side_query：\n/btw 的独立通道随 §8.1 接线";
    }

    private String commitText(String[] parts) {
        return "Git 提交：请使用 Git 工具（stage → commit）\n"
                + "归因（attribution.commit 默认 true）自动追加 Generated with Minion + Co-Authored-By";
    }

    /** §6.3 /init：分析代码库生成 MINION.md（prompt 命令——展开为指导文本发模型） */
    private String initText(SessionHandle h) {
        return "[系统指令] 请分析当前代码库并生成 MINION.md 文件，写入项目根目录。要点：\n"
                + "1. 项目简介（一段话）\n2. 构建与测试命令\n3. 代码风格约定\n"
                + "4. 常见陷阱与注意事项\n5. 目录结构概览\n"
                + "使用 Read/Glob/Grep 工具探索代码后，用 Write 工具生成 MINION.md。";
    }

    private String helpText() {
        return "可用命令：\n"
                + "/help            显示本帮助\n"
                + "/config [过滤]   查看行为开关的值、来源与生效范围\n"
                + "/skills          列出可用技能\n"
                + "/skill <名> [参数]  加载技能到当前会话（可附参数，下一轮请求生效）\n"
                + "/compact         立即压缩上下文\n"
                + "/diagnostics [export]  运行环境诊断/导出报告\n"
                + "/logs [行数]     查看脱敏诊断日志\n"
                + "/update          检查离线更新包与备份\n"
                + "/checkpoints     查看文件修改检查点\n"
                + "/queue add <任务>  排队到当前任务结束后执行\n"
                + "/plan            下一条消息使用只读计划模式\n"
                + "/goal            下一条消息使用持续目标模式\n"
                + "/review          下一条消息使用独立只读审查模式\n"
                + "/normal          下一条消息恢复普通模式\n"
                + "/task /agents    查看当前目标、断点、子Agent分工与记录\n"
                + "/sessions [关键词]  搜索会话；/session fork|pin|archive|export\n"
                + "/context         查看上下文组成与占用\n"
                + "/memory [status|read]  查看项目长期记忆\n"
                + "/permissions [模式]  查看或切换权限策略\n"
                + "/secrets         列出已配置密钥名称\n"
                + "/capabilities    显示本机 Agent 能力与使用入口\n"
                + "/tokens          显示 token 用量统计";
    }

    private String armMode(SessionHandle h,ExecutionMode mode) {
        h.loop.requestExecutionMode(mode);
        return "执行模式已切换为「"+mode.label+"」，对下一条任务生效。";
    }

    private String capabilitiesText() {
        return "本机能力：\n"
                + "- Python/Anaconda：Python 工具（status/run_code/run_file）\n"
                + "- Excel/CSV：Excel 工具（inspect/read/write/append/merge/pivot/chart/export）\n"
                + "- SQLite：SQLite 工具（schema/query/execute）\n"
                + "- 本地知识库：Knowledge 工具（add/search/list/read/delete）\n"
                + "- 浏览器：Browser/BrowserAction/BrowserEval/BrowserScreenshot/BrowserDebug；仅打开 URL 用 OpenUrl\n"
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
            // §2.3 三档：/queue add now|next|later <任务>（缺省 later）
            String priority = "later";
            int textFrom = 2;
            if (parts.length >= 4 && java.util.Arrays.asList("now", "next", "later").contains(parts[2].toLowerCase(Locale.ROOT))) {
                priority = parts[2].toLowerCase(Locale.ROOT);
                textFrom = 3;
            }
            String text = joinTail(parts, textFrom);
            if (text.isEmpty()) return "用法: /queue add [now|next|later] <任务内容>";
            int position;
            if (queuePriority != null) {   // 接线了优先发送（now 含中断在途）
                position = -1;
                queuePriority.apply(h, text, priority);
            } else {
                position = h.enqueue(text, null, false, priority);
                if (h.loop != null) h.loop.persistQueue();
            }
            return position < 0 ? "已按 " + priority + " 优先级提交" : "已加入任务队列，当前位置 " + position;
        }
        if ("remove".equalsIgnoreCase(parts[1])) {
            if (parts.length < 3) return "用法: /queue remove <序号>";
            try { boolean removed=h.removeQueued(Integer.parseInt(parts[2])); h.loop.persistQueue(); return removed ? "已移除队列任务" : "队列序号不存在"; }
            catch (NumberFormatException e) { return "队列序号必须是数字"; }
        }
        if ("clear".equalsIgnoreCase(parts[1])) { int count=h.clearQueued(); h.loop.persistQueue(); return "已清空队列任务 " + count + " 条"; }
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
