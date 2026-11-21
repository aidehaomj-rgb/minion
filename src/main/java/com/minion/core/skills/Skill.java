package com.minion.core.skills;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 技能（frontmatter + 指令 + 来源）。来源仅用于展示与合并排序，不参与匹配。 */
public class Skill {

    /** 内置技能（config: skills.dir） */
    public static final String SOURCE_GLOBAL = "global";
    /** 项目级技能（工作空间 projectSkillsDir） */
    public static final String SOURCE_PROJECT = "project";
    /** MCP/插件来源（默认禁 shell 注入，§7.1） */
    public static final String SOURCE_PLUGIN = "plugin";

    public final String name;
    public final String description;
    public final String instructions; // SKILL.md 正文
    public final String file;         // 源文件完整路径（供模型用 Read 读取）
    public final String source;       // SOURCE_GLOBAL / SOURCE_PROJECT / SOURCE_PLUGIN

    // ===== §7.1 frontmatter 全字段（缺省=V68 兼容：无扩展语义） =====
    /** when_to_use：何时用本技能（一级清单注入的 token 估算含此字段） */
    public final String whenToUse;
    /** allowed-tools：只限定工具面，不是执行授权（§7.1 明示） */
    public final List<String> allowedTools;
    /** argument-hint：参数提示（/skill-name 补全与 Skill 工具描述用） */
    public final String argumentHint;
    /** arguments：命名参数声明（名 → 描述；正文以 ${名} 引用） */
    public final Map<String, String> arguments;
    /** model(inherit)：技能执行用模型（inherit=会话模型） */
    public final String model;
    public final String version;
    /** disable-model-invocation：true 时模型不可自动调用（仅用户 /skill 显式） */
    public final boolean disableModelInvocation;
    /** user-invocable：是否注册为 /skill-name 斜杠命令（默认 true） */
    public final boolean userInvocable;
    /** context：inline（默认，正文注入当前会话）| fork（子代理执行） */
    public final String context;
    /** agent：context=fork 时使用的子代理类型（.minion/agents 定义名，缺省通用） */
    public final String agent;
    /** paths：gitignore 风格 glob 条件激活（触碰匹配文件才进动态技能表） */
    public final List<String> paths;

    public Skill(String name, String description, String instructions, String file, String source) {
        this(name, description, instructions, file, source,
                null, null, null, null, null, null, false, true, "inline", null, null);
    }

    /** 兼容内置/旧扩展调用：未显式给来源时视为内置技能。 */
    public Skill(String name, String description, String instructions, String file) {
        this(name, description, instructions, file, SOURCE_GLOBAL);
    }

    public Skill(String name, String description, String instructions, String file, String source,
            String whenToUse, List<String> allowedTools, String argumentHint,
            Map<String, String> arguments, String model, String version,
            boolean disableModelInvocation, boolean userInvocable,
            String context, String agent, List<String> paths) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.file = file;
        this.source = source;
        this.whenToUse = whenToUse == null ? "" : whenToUse;
        this.allowedTools = allowedTools == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(allowedTools);
        this.argumentHint = argumentHint == null ? "" : argumentHint;
        this.arguments = arguments == null
                ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(arguments);
        this.model = model == null ? "inherit" : model;
        this.version = version == null ? "" : version;
        this.disableModelInvocation = disableModelInvocation;
        this.userInvocable = userInvocable;
        this.context = "fork".equalsIgnoreCase(context) ? "fork" : "inline";
        this.agent = agent == null ? "" : agent;
        this.paths = paths == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(paths);
    }

    /** §7.1 一级渐进加载：paths 非空 → 条件技能（初始不注入清单，触碰匹配文件才激活） */
    public boolean isConditional() { return !paths.isEmpty(); }

    /** 以展开后的正文复制（§7.1 $ARGUMENTS/命名参数/${SKILL_DIR} 替换与内联 shell 回填后注入） */
    public Skill withInstructions(String expanded) {
        return new Skill(name, description, expanded, file, source,
                whenToUse, allowedTools, argumentHint, arguments, model, version,
                disableModelInvocation, userInvocable, context, agent, paths);
    }

    /** §7.1 context=fork：正文交子代理执行（隔离上下文） */
    public boolean isFork() { return "fork".equals(context); }

    /** §7.1 MCP/插件来源默认禁 shell 注入（!`cmd` 不展开） */
    public boolean shellInjectionAllowed() { return !SOURCE_PLUGIN.equals(source); }

    /** 带来源标注的单行提示：提示词技能段、/skills 列表、补全弹层共用（§7.1 含 when_to_use） */
    public String hint() {
        String tag = SOURCE_PROJECT.equals(source) ? "[项目] "
                : SOURCE_PLUGIN.equals(source) ? "[插件] " : "[内置] ";
        String base = tag + name + " — " + (description == null || description.isEmpty() ? "无描述" : description);
        if (whenToUse != null && !whenToUse.isEmpty()) return base + "（" + whenToUse + "）";
        return base;
    }
}
