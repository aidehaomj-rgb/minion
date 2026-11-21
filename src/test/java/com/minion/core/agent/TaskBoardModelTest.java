package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.dev.ProcessTool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * v2.4 §10.6/§10.7 任务面板共用可视化模型验收：TodoWrite 待办 + TaskV2 子任务
 * （状态/负责人/验收）+ 后台任务（ProcessTool 唯一真源；实时输出尾部）三类合一、
 * 只读快照（不改账本）、文本渲染分段。面板只读原则：模型无任何写路径。
 */
public class TaskBoardModelTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 三类行合并 + 只读语义 */
    @Test public void mergesTodosTasksAndBackground() throws Exception {
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash-0731");
        session.todos.replace(java.util.Arrays.asList(
                new TodoList.TodoItem("汇总整表", false),
                new TodoList.TodoItem("核对缺失", true)));
        session.taskState.begin("产出报告", false);
        TaskState.Child child = session.taskState.startChild("核对数据");
        child.status = "completed";
        child.result = "3 张表已核对";
        child.mode = "worker";

        // 后台任务：ProcessTool 真源（短命令即结束；账本登记即入面板）
        Path ws = tmp.newFolder("ws").toPath();
        ProcessTool process = new ProcessTool(new Workspace(ws.getParent().toString()),
                tmp.newFolder("ledger").toPath().resolve("jobs.json"));
        JsonObject start = new JsonObject();
        start.addProperty("action", "start");
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        start.addProperty("command", win ? "cmd /c echo board-ok" : "echo board-ok");
        com.minion.core.tools.ToolResult started = process.execute(start);
        assertTrue("后台任务应可启动: " + started.output, started.ok);

        List<TaskBoardModel.BoardRow> rows =
                TaskBoardModel.snapshot(session, process.runtimeJobs());   // throws IOException → 已声明
        assertEquals(2, count(rows, TaskBoardModel.KIND_TODO));
        assertEquals(1, count(rows, TaskBoardModel.KIND_TASK));
        assertEquals(1, count(rows, TaskBoardModel.KIND_BACKGROUND));

        TaskBoardModel.BoardRow task = first(rows, TaskBoardModel.KIND_TASK);
        assertEquals("completed", task.status);
        assertEquals("worker", task.owner);
        assertTrue(task.acceptance.contains("3 张表已核对"));

        TaskBoardModel.BoardRow bg = first(rows, TaskBoardModel.KIND_BACKGROUND);
        assertNotNull(bg.id);
        assertTrue("描述应携带命令", bg.title.contains("board-ok"));

        String text = TaskBoardModel.render(rows);
        assertTrue(text.contains("[待办 TodoWrite]"));
        assertTrue(text.contains("☐ 汇总整表"));
        assertTrue(text.contains("☑ 核对缺失"));
        assertTrue(text.contains("[子任务 TaskV2]"));
        assertTrue(text.contains("worker"));
        assertTrue(text.contains("[后台任务 /tasks]"));
        process.shutdown();
    }

    /** 空会话 + 无注册表：空面板不抛异常；jobs=null 安全 */
    @Test public void emptySnapshotSafe() throws Exception {
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash-0731");
        List<TaskBoardModel.BoardRow> rows = TaskBoardModel.snapshot(session, null);
        assertTrue(rows.isEmpty());
        assertTrue(TaskBoardModel.render(rows).contains("（空）"));
        assertTrue(TaskBoardModel.snapshot(null, null).isEmpty());
    }

    /** 输出尾部：结果文件存在时读尾部、缺失/损坏返回空（只读容错） */
    @Test public void outputTailBoundedAndSafe() throws Exception {
        java.nio.file.Path out = tmp.newFolder().toPath().resolve("out.txt");
        assertTrue(TaskBoardModel.outputTail("").isEmpty());
        assertTrue(TaskBoardModel.outputTail(null).isEmpty());
        assertTrue(TaskBoardModel.outputTail(out.toString()).isEmpty());   // 不存在
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append("行").append(i).append('\n');
        java.nio.file.Files.write(out, big.toString().getBytes("UTF-8"));
        String tail = TaskBoardModel.outputTail(out.toString());
        assertFalse(tail.isEmpty());
        assertTrue("尾部 ≤240 字符", tail.length() <= 241);   // 240 + 省略号
        assertTrue("读的是尾部", tail.contains("行499"));
    }

    private static int count(List<TaskBoardModel.BoardRow> rows, String kind) {
        int n = 0;
        for (TaskBoardModel.BoardRow r : rows) if (r.kind.equals(kind)) n++;
        return n;
    }

    private static TaskBoardModel.BoardRow first(List<TaskBoardModel.BoardRow> rows, String kind) {
        for (TaskBoardModel.BoardRow r : rows) if (r.kind.equals(kind)) return r;
        throw new AssertionError("无 " + kind + " 行");
    }
}
