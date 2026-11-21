package com.minion.core.tools.confirm;

public interface ConfirmUi {
    enum Decision { APPROVE, REJECT, APPROVE_WHITELIST, APPROVE_SESSION }

    /** 询问用户；返回决策。由实现方负责渲染提示与读取输入。 */
    Decision ask(String message);

    /**
     * §10.3 结构化确认（判定来源/风险标注/diff/可编辑规则建议）：默认回落纯文本询问，
     * GUI 实现覆写渲染富卡片。UI 只展示与收集决策，不得据此绕过 PermissionEngine。
     */
    default Decision askDetailed(PermissionExplainer.Explanation explanation, String message) {
        return ask(message);
    }

    /**
     * §10.3 「允许并写规则」时用户可能编辑过规则建议文本；返回 null=未编辑（用建议原值
     * 或走旧路径）。仅在 askDetailed 返回 APPROVE_WHITELIST 后由 ConfirmGate 读取。
     */
    default String editedRule() { return null; }
}
