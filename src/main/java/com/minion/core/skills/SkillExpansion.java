package com.minion.core.skills;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * §7.1 技能正文展开：$ARGUMENTS、命名参数 ${名}、${SKILL_DIR}、${SESSION_ID} 替换，
 * 及 `` !`cmd` `` 内联 shell 注入的提取/回填。
 *
 * <p><b>内联 shell 三道检查</b>（§7.1 明示，allowed-tools 不是执行授权）：提取出的命令由
 * 调用方（SkillTool 装配的 {@link ShellRunner}）依次过 Shell 分析器、PermissionEngine、
 * §10.9 信任复核后执行；任一拒绝即整体拒绝（不执行、原样保留标记并报错）。
 * MCP/插件来源技能默认禁注入（{@link Skill#shellInjectionAllowed}）。
 */
public final class SkillExpansion {

    /** `` !`cmd` `` 内联注入标记（命令内不允许反引号/换行） */
    private static final Pattern SHELL_INJECTION =
            Pattern.compile("!`([^`\\r\\n]+)`");

    private SkillExpansion() { }

    /** 变量替换（不动内联 shell 标记；命令内也不做二次替换，防注入混淆） */
    public static String expand(String body, String argumentsText, Map<String, String> named,
            Path skillDir, String sessionId) {
        if (body == null) return "";
        StringBuilder out = new StringBuilder(body.length());
        Matcher inject = SHELL_INJECTION.matcher(body);
        int copied = 0;
        while (inject.find()) {
            out.append(substituteVariables(body.substring(copied, inject.start()),
                    argumentsText, named, skillDir, sessionId));
            out.append(inject.group(0));   // shell 标记原样保留，提取阶段统一处理
            copied = inject.end();
        }
        out.append(substituteVariables(body.substring(copied),
                argumentsText, named, skillDir, sessionId));
        return out.toString();
    }

    private static String substituteVariables(String text, String argumentsText,
            Map<String, String> named, Path skillDir, String sessionId) {
        String s = text.replace("$ARGUMENTS", argumentsText == null ? "" : argumentsText);
        if (named != null)
            for (Map.Entry<String, String> e : named.entrySet())
                s = s.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        if (skillDir != null) s = s.replace("${SKILL_DIR}", skillDir.toString());
        if (sessionId != null) s = s.replace("${SESSION_ID}", sessionId);
        return s;
    }

    /** 提取内联 shell 命令（按出现顺序；无注入返回空表） */
    public static List<String> extractShellInjections(String expandedBody) {
        List<String> out = new ArrayList<String>();
        if (expandedBody == null) return out;
        Matcher m = SHELL_INJECTION.matcher(expandedBody);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    /** 执行结果回填：第 i 条命令的输出替换第 i 个标记（输出截断 4000 字符防正文爆炸） */
    public static String substituteShellOutputs(String expandedBody,
            List<String> outputs) {
        if (expandedBody == null || outputs == null || outputs.isEmpty()) return expandedBody;
        Matcher m = SHELL_INJECTION.matcher(expandedBody);
        StringBuffer out = new StringBuffer();
        int index = 0;
        while (m.find()) {
            String output = index < outputs.size() ? outputs.get(index) : "";
            if (output == null) output = "";
            if (output.length() > 4000) output = output.substring(0, 4000) + "…(截断)";
            m.appendReplacement(out, Matcher.quoteReplacement(output));
            index++;
        }
        m.appendTail(out);
        return out.toString();
    }

    /** 内联 shell 执行器（SkillTool 装配：Shell 分析器 → PermissionEngine → §10.9 信任） */
    public interface ShellRunner {
        /** @return 命令输出；拒绝/失败抛 IllegalStateException（信息直达模型） */
        String run(String command) throws Exception;
    }
}
