package com.minion.core.security.permission;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限规则 13 步统一裁决管线（开发说明 v2.4 §4.1，v2.2 把全部 deny 前置）。
 *
 * <p>任何一层要求继续时携带明确步骤号进入下一层；deny 全部先于 ask/allow/bypass，
 * 内容级 deny（第 2 步）对一切工具生效，免疫 bypass、整工具 allow 与用户误确认。
 * 工具自检 PASSTHROUGH 只表示"本工具无额外决定"，不产生 allow（第 10 步才认 ALLOW）。
 *
 * <p>纯函数：无 IO、无状态，规则集由 {@link RuleStore} 构建后传入；fail-closed——
 * 任何匹配异常按更保守分支处理（不命中 deny/ask 即落到 ask，绝不静默放行）。
 */
public final class PermissionEngine {

    /** 裁决结果；step 为命中的 13 步序号（审计与 UI 提示用） */
    public static final class Verdict {
        public enum Outcome { ALLOW, DENY, ASK }
        public final Outcome outcome;
        public final int step;
        public final String reason;

        Verdict(Outcome outcome, int step, String reason) {
            this.outcome = outcome;
            this.step = step;
            this.reason = reason;
        }

        public boolean allowed() { return outcome == Outcome.ALLOW; }
        public boolean denied() { return outcome == Outcome.DENY; }
        @Override public String toString() { return outcome + "@step" + step + " " + reason; }
    }

    /**
     * 按唯一顺序裁决。参数 content 为工具级内容（如 Bash 命令全文）；文件类规则的路径
     * 匹配使用 ctx.targetPath/realTargetPath（与 content 独立，两条都检查）。
     */
    public Verdict evaluate(String toolName, String content, RuleStore rules, PermissionContext ctx) {
        String tool = toolName == null ? "" : toolName.trim();
        String lower = tool.toLowerCase(java.util.Locale.ROOT);
        Verdict v = pipeline(lower, content, rules, ctx);
        // DONT_ASK：任何 ask 收紧为 deny（headless 用；第 13 步默认 ask 同样转换）
        if (v.outcome == Verdict.Outcome.ASK && ctx.mode == PermissionContext.Mode.DONT_ASK)
            return new Verdict(Verdict.Outcome.DENY, v.step, v.reason + "（dontAsk：ask 收紧为 deny）");
        return v;
    }

    private Verdict pipeline(String toolLower, String content, RuleStore rules, PermissionContext ctx) {
        // 1. 整工具 deny
        if (rules.has(toolLower, null, PermissionRule.Behavior.DENY))
            return deny(1, "整工具 deny 规则: " + toolLower);
        // 2. 内容级 deny（文本内容 + 文件路径，原路径与真实路径都检查）——免疫 bypass/allow/确认
        PermissionRule d = rules.contentRule(toolLower, content, ctx, PermissionRule.Behavior.DENY);
        if (d != null) return deny(2, "内容级 deny 规则: " + d);
        // 3. 工具自检 deny；plan（只读）模式拒绝一切非 allow（高危）工具——等价 V68 read-only 语义，
        //    只读工具经第 10 步 ALLOW 放行（含 BashCommandAnalyzer 接入后的只读 Bash）
        if (ctx.toolCheck == PermissionContext.ToolCheck.DENY) return deny(3, "工具自检 deny");
        if (ctx.mode == PermissionContext.Mode.PLAN && ctx.toolCheck != PermissionContext.ToolCheck.ALLOW)
            return deny(3, "plan(只读)模式拒绝非只读工具: " + toolLower);
        // 4. 工具声明需用户交互且自检要求交互 → ask（免疫 bypass）
        if (ctx.requiresUserInteraction && ctx.toolCheck == PermissionContext.ToolCheck.ASK)
            return ask(4, "工具声明必须用户交互");
        // 5. 整工具 ask（免疫 bypass）
        if (rules.has(toolLower, null, PermissionRule.Behavior.ASK))
            return ask(5, "整工具 ask 规则: " + toolLower);
        // 6. 内容级 ask
        PermissionRule a = rules.contentRule(toolLower, content, ctx, PermissionRule.Behavior.ASK);
        if (a != null) return ask(6, "内容级 ask 规则: " + a);
        // 7. 敏感路径 safetyCheck → ask（免疫 bypass）
        if (ctx.safetyCheckHit) return ask(7, "敏感路径强制询问（§4.2）");
        // 8. 工具自检 ask（免疫 bypass）
        if (ctx.toolCheck == PermissionContext.ToolCheck.ASK) return ask(8, "工具自检 ask");
        // 9. bypassPermissions 模式 → allow（不能越过前 8 步）
        if (ctx.mode == PermissionContext.Mode.BYPASS) return allow(9, "bypassPermissions 模式");
        // 9b. acceptEdits：工作区内文件写入自动 allow（deny/ask/safetyCheck 均已在前）
        if (ctx.mode == PermissionContext.Mode.ACCEPT_EDITS && ctx.insideWorkspace())
            return allow(9, "acceptEdits：工作区内写入自动放行");
        // 10. 工具自检 allow（仅在所有 deny/ask/safetyCheck 未命中时有效）
        if (ctx.toolCheck == PermissionContext.ToolCheck.ALLOW) return allow(10, "工具自检 allow");
        // 11. 整工具 allow
        if (rules.has(toolLower, null, PermissionRule.Behavior.ALLOW))
            return allow(11, "整工具 allow 规则: " + toolLower);
        // 12. 内容级 allow
        PermissionRule w = rules.contentRule(toolLower, content, ctx, PermissionRule.Behavior.ALLOW);
        if (w != null) return allow(12, "内容级 allow 规则: " + w);
        // 13. 默认 ask
        return ask(13, "默认询问");
    }

    private static Verdict deny(int step, String reason) { return new Verdict(Verdict.Outcome.DENY, step, reason); }
    private static Verdict allow(int step, String reason) { return new Verdict(Verdict.Outcome.ALLOW, step, reason); }
    private static Verdict ask(int step, String reason) { return new Verdict(Verdict.Outcome.ASK, step, reason); }
}
