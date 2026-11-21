package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * 会话级 worktree 入口（v2.4 §3.5）：EnterWorktree/ExitWorktree——复用既有
 * {@link com.minion.core.agent.ChildTaskIsolation} worktree 机制与 requiresMerge 验收。
 * Enter 创建 .minion/worktrees/{slug} 并把会话 cwd 切入；Exit 询问 keep/cleanup
 * （经 ConfirmGate 确认）后移除登记。依赖本机 git x64；无 .git 时 isEnabled=false。
 */
public final class WorktreeTools {

    private final Workspace workspace;
    private final String sessionId;
    private final com.minion.core.tools.confirm.ConfirmGate confirm;
    private Path active;   // 当前会话切入的 worktree（Exit 恢复）

    public WorktreeTools(Workspace workspace, String sessionId,
                         com.minion.core.tools.confirm.ConfirmGate confirm) {
        this.workspace = workspace;
        this.sessionId = sessionId;
        this.confirm = confirm;
    }

    public final class EnterWorktreeTool implements Tool {
        @Override public String name() { return "EnterWorktree"; }
        @Override public String description() {
            return "创建隔离 git worktree 并把会话切入（基于当前 HEAD；合并验收走既有 requiresMerge）";
        }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("进入隔离 worktree",
                    new String[]{"name"}, new String[0]);
        }
        @Override public boolean isHighRisk(JsonObject args) { return true; }
        @Override public boolean isEnabled() {
            return Files.isDirectory(Paths.get(workspace.workDir()).resolve(".git"));
        }

        @Override public ToolResult execute(JsonObject args) throws Exception {
            if (!isEnabled()) return ToolResult.errorWithoutSideEffects("当前工作区不是 Git 仓库");
            String name = args.has("name") && !args.get("name").isJsonNull()
                    ? args.get("name").getAsString().trim() : "";
            if (name.isEmpty()) name = "wt-" + sessionId.substring(0, 8);
            if (!name.matches("[A-Za-z0-9_-]{1,64}"))
                return ToolResult.errorWithoutSideEffects("name 非法（字母数字_-，≤64）");
            try {
                com.minion.core.agent.ChildTaskIsolation.Record record =
                        new com.minion.core.agent.ChildTaskIsolation(Paths.get(workspace.workDir()))
                                .create(sessionId, "worktree-" + name, name,
                                        new ArrayList<>(java.util.Collections.singletonList(".")));
                active = Paths.get(record.worktree);
                return ToolResult.success("已创建并切入 worktree: " + record.worktree
                        + "\n后续工具以该目录为工作根；完成后 ExitWorktree 走合并验收");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.errorWithoutSideEffects("创建被中断，保留现场待核对");
            } catch (Exception failed) {
                return ToolResult.errorWithoutSideEffects("worktree 创建失败: " + failed.getMessage());
            }
        }
    }

    public final class ExitWorktreeTool implements Tool {
        @Override public String name() { return "ExitWorktree"; }
        @Override public String description() {
            return "退出当前 worktree：确认 keep（保留目录）或 cleanup（清理已合并的 worktree）";
        }
        @Override public JsonObject schema() {
            return SchemaGenerator.objectSchema("退出 worktree", new String[]{"cleanup"}, new String[0]);
        }
        @Override public boolean isHighRisk(JsonObject args) { return true; }

        @Override public ToolResult execute(JsonObject args) throws Exception {
            if (active == null) return ToolResult.errorWithoutSideEffects("当前不在 worktree 中");
            boolean cleanup = args.has("cleanup") && !args.get("cleanup").isJsonNull()
                    && "true".equalsIgnoreCase(args.get("cleanup").getAsString());
            String name = active.getFileName().toString();
            if (cleanup) {
                if (confirm != null && !confirm.checkEscapeWrite(this, args, active.toString()))
                    return ToolResult.errorWithoutSideEffects("用户未确认清理");
                try {
                    new com.minion.core.agent.ChildTaskIsolation(Paths.get(workspace.workDir())).remove(name);
                } catch (Exception failed) {
                    return ToolResult.errorWithoutSideEffects(
                            "worktree 未清理（可能含未合并修改）: " + failed.getMessage());
                }
                active = null;
                return ToolResult.success("已清理并退出 worktree: " + name);
            }
            active = null;
            return ToolResult.success("已退出 worktree（保留目录待后续处理）: " + name);
        }
    }
}
