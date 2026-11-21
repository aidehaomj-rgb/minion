package com.minion.core.tools.confirm;

import com.google.gson.JsonObject;
import com.minion.core.security.permission.PermissionEngine;
import com.minion.core.tools.Tool;

/**
 * v2.4 §10.3 权限判定解释（逻辑层，headless 可测）：给确认弹窗提供结构化信息——
 * 判定来源与原因、Bash 命令风险标注、可编辑前缀规则建议、Write/Edit 的 diff 数据。
 * UI 只负责展示与收集决策（含用户编辑后的规则文本），不得绕过 PermissionEngine。
 */
public final class PermissionExplainer {

    /** 弹窗结构化信息（FX 卡片渲染输入；diff 仅数据，渲染归 UI） */
    public static final class Explanation {
        public final String tool;
        /** 判定来源：PermissionEngine 步号+原因（为何落到 ask） */
        public final String decisionSource;
        public final String reason;
        /** 风险级：高/中/低 */
        public final String riskLevel;
        /** 风险细节：Bash 分析器命中类别；Write/Edit 覆盖/越界说明 */
        public final String riskDetail;
        /** 前缀规则建议初值（用户可编辑；格式 Tool 或 Tool(content)） */
        public final String suggestedRule;
        /** 建议落盘作用域：project / local / user */
        public final String ruleScope;
        /** Write/Edit 的目标路径（diff 数据；null=非文件写工具） */
        public final String diffPath;
        public final String diffOld;    // Edit 的 old_string（Write 覆盖时为旧文件摘要；可空）
        public final String diffNew;    // Edit 的 new_string / Write 的 content（可空）

        Explanation(String tool, String decisionSource, String reason, String riskLevel,
                String riskDetail, String suggestedRule, String ruleScope,
                String diffPath, String diffOld, String diffNew) {
            this.tool = tool;
            this.decisionSource = decisionSource;
            this.reason = reason;
            this.riskLevel = riskLevel;
            this.riskDetail = riskDetail;
            this.suggestedRule = suggestedRule;
            this.ruleScope = ruleScope;
            this.diffPath = diffPath;
            this.diffOld = diffOld;
            this.diffNew = diffNew;
        }
    }

    /** 从 ask 判定组装配解释（Bash 走分析器；Write/Edit 附 diff；其余按高危标记） */
    public static Explanation explain(PermissionEngine.Verdict verdict, Tool tool,
            JsonObject args, String content) {
        String name = tool.name();
        String riskLevel = tool.isHighRisk(args) ? "高" : "低";
        String riskDetail = "";
        String suggestedRule = name;
        String diffPath = null, diffOld = null, diffNew = null;

        if ("Bash".equals(name) && args.has("command")) {
            String command = args.get("command").getAsString();
            com.minion.core.security.bash.BashCommandAnalyzer.Analysis a =
                    new com.minion.core.security.bash.BashCommandAnalyzer().analyze(command);
            if (a.kind != com.minion.core.security.bash.BashCommandAnalyzer.Analysis.Kind.SIMPLE) {
                riskLevel = "高";
                riskDetail = "命令分析器: " + a.kind;
            } else if (!a.findings.isEmpty()) {
                riskLevel = "高";
                riskDetail = "命令分析器命中 " + a.findings.size() + " 项高危检查";
            } else {
                riskLevel = "中";
                riskDetail = a.readOnly ? "只读命令（分析器通过）" : "写命令（分析器无高危命中）";
            }
            String prefix = com.minion.core.tools.DangerousCommands.firstToken(command);
            suggestedRule = prefix.isEmpty() ? "Bash" : "Bash(" + prefix + ":*)";
        } else if ("Edit".equals(name)) {
            riskDetail = "文件内容修改";
            diffPath = args.has("path") ? args.get("path").getAsString() : null;
            diffOld = args.has("old_string") ? args.get("old_string").getAsString() : null;
            diffNew = args.has("new_string") ? args.get("new_string").getAsString() : null;
            suggestedRule = dirPrefixRule("Edit", diffPath);
        } else if ("Write".equals(name)) {
            riskDetail = "文件写入（新建或整文件覆盖）";
            diffPath = args.has("path") ? args.get("path").getAsString() : null;
            diffNew = args.has("content") ? args.get("content").getAsString() : null;
            suggestedRule = dirPrefixRule("Write", diffPath);
        } else if (tool.isHighRisk(args)) {
            riskDetail = "工具自声明高危操作";
        }
        return new Explanation(name,
                "PermissionEngine 第 " + verdict.step + " 步: " + verdict.reason,
                verdict.reason, riskLevel, riskDetail,
                suggestedRule, "project", diffPath, diffOld, diffNew);
    }

    /** 目录前缀规则建议：Edit/Write(path) 匹配同目录后续写入（更收敛于整工具放行） */
    private static String dirPrefixRule(String tool, String path) {
        if (path == null || path.trim().isEmpty()) return tool;
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String dir = slash < 0 ? "" : normalized.substring(0, slash + 1);
        return dir.isEmpty() ? tool : tool + "(" + dir + "*)";
    }

    private PermissionExplainer() { }
}
