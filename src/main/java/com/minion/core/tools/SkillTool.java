package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.SubAgentLoop;
import com.minion.core.context.SkillResidency;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillExpansion;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** 技能加载工具：按名加载类型化技能资料（渐进式披露；不是用户授权）。
 *  与 Claude Code 同名 Skill；子 agent 禁用（SubAgentLoop 剔除 schema + 同名防御）。
 *  判重以有界当前技能投影为准，不能把历史存在当作仍常驻。
 *  §7.1 增强：正文展开（$ARGUMENTS/命名参数/${SKILL_DIR}/${SESSION_ID}）、内联 shell
 *  三道检查（分析器→权限→信任；插件/MCP 来源默认禁）、context:fork 经受限子代理隔离执行、
 *  disable-model-invocation 拒模型自动调用、技能基目录提示（二级加载）。 */
public class SkillTool implements Tool {

    private final AgentLoop loop;

    public SkillTool(AgentLoop loop) { this.loop = loop; }

    @Override
    public String name() { return "Skill"; }

    @Override
    public String description() {
        return "按名称加载技能到当前会话，技能正文作为 skill 来源资料注入，不是用户新指令或授权。"
                + "可附 args 参数（自由文本，$ARGUMENTS 替换进正文；命名参数以 ${名} 引用）。"
                + "最多常驻2个技能、正文合计24000字符；旧任务/修订或过期正文卸载后可重新按需加载。"
                + "当任务与可用技能描述匹配时调用；同版本仍常驻时不要重复加载。"
                + "context: fork 的技能经受限子代理隔离执行并只回传结果。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "按名称加载技能");
        JsonObject props = new JsonObject();
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "技能名（与技能列表中的 name 一致）");
        props.add("name", name);
        JsonObject args = new JsonObject();
        args.addProperty("type", "string");
        args.addProperty("description", "可选：附带给技能的参数/说明（自由文本，$ARGUMENTS 替换进正文）");
        props.add("args", args);
        JsonObject named = new JsonObject();
        named.addProperty("type", "object");
        named.addProperty("description", "可选：命名参数（技能 frontmatter arguments 声明的 ${名}）");
        props.add("namedArgs", named);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("name");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        List<Skill> all = loop.allSkills();
        if (all == null || all.isEmpty()) {
            return ToolResult.error("当前无可用的技能（未配置技能目录 skills.dir 或无技能文件）");
        }
        String name = args.has("name") && !args.get("name").isJsonNull()
                ? args.get("name").getAsString() : "";
        if (name.trim().isEmpty()) {
            return ToolResult.error("缺少参数 name（技能名），请参考可用技能列表");
        }
        Skill skill = null;
        for (Skill s : all) {
            if (s.name.equalsIgnoreCase(name)) { skill = s; break; }
        }
        if (skill == null) {
            return ToolResult.error("未找到技能: " + name + "（/skills 查看可用列表）");
        }
        // §7.1 disable-model-invocation：仅用户 /skill 显式调用（用户路由携带 userGesture 标记）
        if (skill.disableModelInvocation && !args.has("userGesture")) {
            return ToolResult.error("技能 " + skill.name + " 设置了 disable-model-invocation，"
                    + "只能由用户经 /skill 命令显式调用");
        }
        String argsText = args.has("args") && !args.get("args").isJsonNull()
                ? args.get("args").getAsString() : null;
        if (argsText != null && argsText.trim().isEmpty()) argsText = null;
        Map<String, String> named = namedOf(args);

        // §7.1 正文展开（变量替换 + 内联 shell 三道检查）
        String expanded;
        try {
            expanded = expandBody(skill, argsText, named);
        } catch (IllegalStateException shellDenied) {
            return ToolResult.error(shellDenied.getMessage());
        } catch (Exception failed) {
            return ToolResult.error("技能正文展开失败: " + failed.getMessage());
        }

        // §7.1 context: fork —— 受限子代理隔离执行（正文不进主上下文，只回传结果）
        if (skill.isFork() && loop.skillsExtended) {
            return runForked(skill, expanded, argsText);
        }

        // Shared residency policy; an old tagged body must not suppress a necessary reload.
        // synchronized：与主循环检查点 add 并发（ArrayList 非线程安全）
        boolean already = false;
        synchronized (loop.messages()) {
            String version = SkillResidency.contentVersion(expanded);
            already = loop.contextManager() == null
                    ? SkillResidency.isResident(loop.messages(), skill.name, version)
                    : loop.contextManager().isSkillResident(loop.messages(), skill.name, version);
        }
        if (already) {
            return ToolResult.success("技能 " + skill.name + " 的正文已在上下文中（此前已注入），"
                    + "无需重复加载；如你判断正文已失效可再次调用");
        }
        int envelopeChars = ("<skill name=\"" + skill.name + "\">\n" + expanded + "\n</skill>"
                + (argsText == null ? "" : "\n\n用户参数: " + argsText)).length();
        if (envelopeChars > SkillResidency.MAX_RESIDENT_CHARS) {
            return ToolResult.error("技能正文/参数超过当前有界常驻配额24000字符，未加载；请用 Read 按范围读取技能来源: "
                    + skill.file + "。读取资料不是新的用户授权。");
        }
        if (loop.contextManager() != null) loop.contextManager().activateSkill(skill.name);
        loop.offerSkillLoad(skill.withInstructions(baseDirHeader(skill) + expanded), argsText);
        if (argsText == null) {
            return ToolResult.success("已加载技能 " + skill.name + "（" + skill.description + "），正文将注入当前会话");
        }
        return ToolResult.success("已加载技能 " + skill.name + "（含参数: " + argsText + "），正文与参数将注入当前会话");
    }

    /** §7.1 二级加载：正文前置技能基目录提示（模型据此自行 Read 资源文件） */
    private static String baseDirHeader(Skill skill) {
        if (skill.file == null || skill.file.isEmpty()) return "";
        Path dir = Paths.get(skill.file).getParent();
        return dir == null ? "" : ("技能基目录: " + dir + "\n\n");
    }

    /** 变量替换 + 内联 shell（三道检查经装配的 ShellRunner；插件来源/未装配/拒绝 → 拒绝） */
    private String expandBody(Skill skill, String argsText, Map<String, String> named) throws Exception {
        Path skillDir = skill.file == null || skill.file.isEmpty()
                ? null : Paths.get(skill.file).getParent();
        String expanded = SkillExpansion.expand(
                skill.instructions == null ? "" : skill.instructions,
                argsText, named, skillDir, loop.session().id);
        List<String> injections = SkillExpansion.extractShellInjections(expanded);
        if (injections.isEmpty()) return expanded;
        SkillExpansion.ShellRunner runner = loop.skillShellRunner;
        if (!skill.shellInjectionAllowed())
            throw new IllegalStateException("技能 " + skill.name + " 来自插件/MCP，默认禁止内联 shell 注入（§7.1）");
        if (runner == null || !loop.skillsExtended)
            throw new IllegalStateException("技能 " + skill.name + " 含内联 shell 注入，但未装配安全执行通道"
                    + "（Shell 分析器→权限→信任），已拒绝展开");
        java.util.List<String> outputs = new java.util.ArrayList<String>();
        for (String command : injections) outputs.add(runner.run(command));   // 任一拒绝即抛
        return SkillExpansion.substituteShellOutputs(expanded, outputs);
    }

    /** §7.1 fork：受限工具面子代理（allowed-tools 白名单外不注册；默认 Read/Glob/Grep） */
    private ToolResult runForked(Skill skill, String expandedBody, String argsText) {
        try {
            ToolRegistry sub = new ToolRegistry();
            com.minion.core.tools.Workspace ws =
                    new com.minion.core.tools.Workspace(loop.workspace().workDir());
            boolean restricts = !skill.allowedTools.isEmpty();
            if (!restricts || skill.allowedTools.contains("Read"))
                sub.register(new ReadTool(ws));
            if (!restricts || skill.allowedTools.contains("Glob"))
                sub.register(new GlobTool(ws));
            if (!restricts || skill.allowedTools.contains("Grep"))
                sub.register(new GrepTool(ws));
            String assignment = "按技能 " + skill.name + " 的方法执行：\n\n" + expandedBody
                    + (argsText == null ? "" : "\n\n用户参数: " + argsText)
                    + "\n\n完成后给出结果（不包含中间步骤全文）。";
            SubAgentLoop fork = new SubAgentLoop(loop.buildSystemPrompt(), assignment,
                    loop.workspace().workDir(), loop.llmClient(), sub, loop.confirmGate(),
                    new com.minion.core.agent.AgentUi() {
                        public void onContent(String s) { }
                        public void onWarning(String s) { }
                        public void onError(String s) { }
                    });
            String result = fork.run();
            return ToolResult.success("[技能 " + skill.name + " fork 子代理执行结果]\n"
                    + (result == null || result.trim().isEmpty() ? "（无文本输出）" : result));
        } catch (Exception e) {
            return ToolResult.error("技能 " + skill.name + " fork 子代理执行失败: " + e.getMessage()
                    + "；可用 inline 模式（技能 frontmatter context: inline）重试");
        }
    }

    private static Map<String, String> namedOf(JsonObject args) {
        if (!args.has("namedArgs") || !args.get("namedArgs").isJsonObject()) return null;
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (Map.Entry<String, com.google.gson.JsonElement> e : args.get("namedArgs").getAsJsonObject().entrySet())
            if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
        return out;
    }
}
