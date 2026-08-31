package com.minion.gui.input;

import com.minion.core.skills.Skill;

import java.util.ArrayList;
import java.util.List;

/** 斜杠补全数据：本地命令 + 技能条目（label=/skill <名>，desc=frontmatter 描述）。纯静态。 */
public final class SlashSuggester {

    /** 内置命令（含描述，供弹层右侧灰字展示） */
    private static List<Suggestion> builtins() {
        List<Suggestion> out = new ArrayList<Suggestion>();
        out.add(new Suggestion("/help", "/help", "显示本帮助", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/skills", "/skills", "列出可用技能", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/skill", "/skill", "加载技能到当前会话", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/compact", "/compact", "立即压缩上下文", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/capabilities", "/capabilities", "显示本机 Agent 能力", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/tokens", "/tokens", "显示 token 用量统计", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/diagnostics", "/diagnostics", "运行环境诊断", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/logs", "/logs", "查看脱敏日志", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/update", "/update", "检查离线更新状态", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/checkpoints", "/checkpoints", "查看修改检查点", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/queue", "/queue", "管理后台任务队列", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/sessions", "/sessions", "搜索与查看会话", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/session", "/session", "置顶/归档/分叉/导入导出", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/context", "/context", "查看上下文占用", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/memory", "/memory", "查看项目长期记忆", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/permissions", "/permissions", "查看权限策略", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/secrets", "/secrets", "列出加密密钥名称", Suggestion.Type.COMMAND));
        return out;
    }

    /** 技能条目：选中插入 /skill <名>；desc 前标注来源（与 /skills 的 hint() 一致） */
    public static List<Suggestion> skillEntries(List<Skill> skills) {
        List<Suggestion> out = new ArrayList<Suggestion>();
        if (skills == null) return out;
        for (Skill s : skills) {
            String label = "/skill " + s.name;
            out.add(new Suggestion(label, label,
                    (com.minion.core.skills.Skill.SOURCE_PROJECT.equals(s.source) ? "[项目] " : "[内置] ")
                            + s.description, Suggestion.Type.SKILL));
        }
        return out;
    }

    /** SLASH 模式全集：内置命令 + 技能条目 */
    public static List<Suggestion> all(List<Skill> skills) {
        List<Suggestion> out = builtins();
        out.addAll(skillEntries(skills));
        return out;
    }

    private SlashSuggester() { }
}
