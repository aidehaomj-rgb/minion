package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 计划模式工具对（开发说明 v2.4 §3.4，单代理简化版）：EnterPlanMode 切只读计划模式
 * （复用既有 ExecutionMode.PLAN / /plan 通道）；ExitPlanMode 读计划文件内容返回给模型，
 * 由模型经 AskUserQuestion 请求用户批准（多探索代理/审批对话框属第 10 章 UI）。
 * 计划文件：{workDir}/.minion/plans/{sessionId}.md——plan 模式下唯一允许写入的内部路径
 * （仍走真实路径检查与写确认）。
 */
public final class PlanModeTools {

    /** 计划文件路径（会话级；/clear 或新会话自然切换） */
    public static Path planFile(String workDir, String sessionId) {
        return Paths.get(workDir, ".minion", "plans", sessionId + ".md");
    }

    /** EnterPlanMode：武装下一轮为只读计划模式（等价 /plan） */
    public static final class EnterPlanModeTool implements Tool {
        private final com.minion.core.agent.AgentLoop loop;

        public EnterPlanModeTool(com.minion.core.agent.AgentLoop loop) { this.loop = loop; }

        @Override public String name() { return "EnterPlanMode"; }
        @Override public String description() {
            return "进入只读计划模式（探索与分析，不改文件），把方案写入计划文件后 ExitPlanMode 交付审批";
        }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("进入计划模式", new String[0], new String[0]);
        }

        @Override public ToolResult execute(JsonObject args) {
            loop.requestExecutionMode(com.minion.core.agent.ExecutionMode.PLAN);
            return ToolResult.success("已进入计划模式：下一轮只读探索；把计划写入计划文件（Write 到 "
                    + "本会话计划路径，见系统提示）后调用 ExitPlanMode。");
        }
    }

    /** ExitPlanMode：读计划文件返回内容；批准交互由模型经 AskUserQuestion 完成（单代理简化） */
    public static final class ExitPlanModeTool implements Tool {
        private final String workDir;
        private final String sessionId;

        public ExitPlanModeTool(String workDir, String sessionId) {
            this.workDir = workDir;
            this.sessionId = sessionId;
        }

        @Override public String name() { return "ExitPlanMode"; }
        @Override public String description() {
            return "交付计划供审批：返回计划文件内容；请把计划展示给用户并用 AskUserQuestion 请求批准";
        }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("退出计划模式（交付审批）", new String[0], new String[0]);
        }

        @Override public ToolResult execute(JsonObject args) throws Exception {
            Path file = planFile(workDir, sessionId);
            if (!Files.isRegularFile(file))
                return ToolResult.errorWithoutSideEffects(
                        "计划文件不存在（plan 模式下应先把计划写入 " + file + "）；未退出计划模式");
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (content.trim().isEmpty())
                return ToolResult.errorWithoutSideEffects("计划文件为空；未退出计划模式");
            return ToolResult.success("计划如下（请原文展示给用户，用 AskUserQuestion 问是否批准；"
                    + "批准后用 /normal 切回普通模式执行）：\n\n" + content);
        }
    }

    private PlanModeTools() { }
}
