package com.minion.core.tools;

import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** §3.5 worktree 入口验收：Enter 建目录切入（无 .git 时 disabled）、Exit keep/cleanup */
public class WorktreeToolsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path gitRepo() throws Exception {
        Path repo = tmp.newFolder().toPath();
        new ProcessBuilder("git", "init", "-q")
                .directory(repo.toFile()).redirectErrorStream(true).start().waitFor();
        Files.write(repo.resolve("a.txt"), "x".getBytes("UTF-8"));
        new ProcessBuilder("git", "add", "-A").directory(repo.toFile())
                .redirectErrorStream(true).start().waitFor();
        new ProcessBuilder("git", "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "-qm", "init").directory(repo.toFile())
                .redirectErrorStream(true).start().waitFor();
        return repo;
    }

    @Test public void enterCreatesAndExitKeepsOrCleans() throws Exception {
        Path repo = gitRepo();
        Workspace ws = new Workspace(repo.toString());
        ConfirmGate gate = new ConfirmGate(
                com.minion.core.config.Config.load(tmp.newFolder().toPath()),
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        WorktreeTools tools = new WorktreeTools(ws, "session123", gate);
        WorktreeTools.EnterWorktreeTool enter = tools.new EnterWorktreeTool();
        assertTrue(enter.isEnabled());
        com.minion.core.tools.ToolResult in = enter.execute(new com.google.gson.JsonObject());
        assertTrue(in.output, in.ok);
        String path = in.output.split("\n")[0].replace("已创建并切入 worktree: ", "");
        assertTrue(Files.isDirectory(java.nio.file.Paths.get(path)));
        // Exit keep：保留目录
        WorktreeTools.ExitWorktreeTool exit = tools.new ExitWorktreeTool();
        com.minion.core.tools.ToolResult kept = exit.execute(new com.google.gson.JsonObject());
        assertTrue(kept.ok);
        assertTrue(kept.output.contains("保留"));
        assertTrue(Files.isDirectory(java.nio.file.Paths.get(path)));
    }

    /** 无 .git：Enter 工具 disabled 且执行明确报错 */
    @Test public void nonGitWorkspaceDisabled() throws Exception {
        Path plain = tmp.newFolder().toPath();
        WorktreeTools tools = new WorktreeTools(new Workspace(plain.toString()), "s", null);
        WorktreeTools.EnterWorktreeTool enter = tools.new EnterWorktreeTool();
        assertFalse(enter.isEnabled());
        assertFalse(enter.execute(new com.google.gson.JsonObject()).ok);
        // 未 Enter 时 Exit 报错
        assertFalse(tools.new ExitWorktreeTool().execute(new com.google.gson.JsonObject()).ok);
    }
}
