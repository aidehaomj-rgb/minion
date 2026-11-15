package com.minion.core.agent;

import java.util.Locale;

/** User-selected execution semantics, aligned with mainstream coding agents. */
public enum ExecutionMode {
    NORMAL("normal", "普通任务"),
    PLAN("plan", "计划模式"),
    GOAL("goal", "目标模式"),
    REVIEW("review", "审查模式");

    public final String id;
    public final String label;

    ExecutionMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static ExecutionMode parse(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ExecutionMode mode : values()) if (mode.id.equals(normalized)) return mode;
        }
        return NORMAL;
    }

    public String promptRules() {
        switch (this) {
            case PLAN:
                return "\n\n=== 当前回合模式：计划 ===\n"
                        + "只读探索和规划，不得修改文件、运行有副作用的命令、写入交付物或派发执行型子Agent。"
                        + "先收集必要上下文，再输出可评审的目标、步骤、依赖、风险和验收条件。"
                        + "计划本身是本次交付；用户确认后再切换普通任务执行。";
            case GOAL:
                return "\n\n=== 当前回合模式：目标 ===\n"
                        + "围绕一个明确目标持续执行，直到完成条件有实际证据。"
                        + "优先使用真实测试、构建、文件回执或可复现运行结果；"
                        + "阶段性说明不等于完成。只有当前目标确有缺项时才继续，不得为了复核重复已完成动作。";
            case REVIEW:
                return "\n\n=== 当前回合模式：审查 ===\n"
                        + "只做独立审查，不修改被审查文件。重点检查正确性、回归风险、证据充分性和未验证范围；"
                        + "结论必须引用实际读取或运行的证据，不能把“没发现问题”当作已验证。";
            case NORMAL:
            default:
                return "\n\n=== 当前回合模式：普通 ===\n"
                        + "直接完成当前问题或修改。纯问答在给出答案后结束；"
                        + "代码和文件修改使用实际检查验证，不要求无关的独立复核。";
        }
    }

    public boolean requiresCompletionReview() {
        return this == GOAL || this == REVIEW;
    }

    public boolean readOnlyTurn() {
        return this == PLAN || this == REVIEW;
    }
}
