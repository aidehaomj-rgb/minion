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

/** I1 当前化投影：任务条只列当前任务情况——历史修订已结束子agent/已结束后台不入条，
 *  当前修订已完成步骤保留✓，running 排前；全量 snapshot 逐字不变（面板//tasks 账本）。 */
public class TaskBoardModelCurrentSnapshotTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static int count(List<TaskBoardModel.BoardRow> rows, String kind) {
        int n = 0;
        for (TaskBoardModel.BoardRow r : rows) if (kind.equals(r.kind)) n++;
        return n;
    }

    private static TaskBoardModel.BoardRow first(List<TaskBoardModel.BoardRow> rows, String kind) {
        for (TaskBoardModel.BoardRow r : rows) if (kind.equals(r.kind)) return r;
        throw new AssertionError("无 " + kind + " 行");
    }

    @Test public void historyFinishedChildExcludedRunningKept() throws Exception {
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash-0731");
        session.taskState.begin("处理查获明细", true);
        TaskState.Child old = session.taskState.startChild("chunk_1 提取");
        session.taskState.coordinateChild(old.id, "step_1");
        session.taskState.finishChild(old.id, "已完成", true, null);
        session.taskState.addGuidance("改为按单号处理");
        TaskState.Child running = session.taskState.startChild("chunk_2 提取");
        session.taskState.coordinateChild(running.id, "step_2");

        List<TaskBoardModel.BoardRow> current = TaskBoardModel.currentSnapshot(session, null);
        assertEquals("历史修订已结束子agent不入当前条", 1, count(current, TaskBoardModel.KIND_TASK));
        assertEquals(running.id, first(current, TaskBoardModel.KIND_TASK).id);
        List<TaskBoardModel.BoardRow> full = TaskBoardModel.snapshot(session, null);
        assertEquals("全量账本仍含历史（特征化）", 2, count(full, TaskBoardModel.KIND_TASK));
    }

    @Test public void currentRevisionFinishedChildKept() throws Exception {
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash-0731");
        session.taskState.begin("任务", true);
        TaskState.Child c = session.taskState.startChild("chunk_1");
        session.taskState.coordinateChild(c.id, "step_1");
        session.taskState.finishChild(c.id, "已完成", true, null);

        List<TaskBoardModel.BoardRow> current = TaskBoardModel.currentSnapshot(session, null);
        assertEquals("当前修订已完成步骤保留✓", 1, count(current, TaskBoardModel.KIND_TASK));
        assertEquals("returned_needs_review", first(current, TaskBoardModel.KIND_TASK).status);
    }

    @Test public void todosKeptInPlanOrderBeforeChildren() throws Exception {
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash-0731");
        session.todos.replace(java.util.Arrays.asList(
                new TodoList.TodoItem("重读全部docx并梳理线索", false),
                new TodoList.TodoItem("汇总去重生成线索清单docx", false)));
        session.taskState.begin("任务", true);
        session.taskState.startChild("chunk_1 提取");

        List<TaskBoardModel.BoardRow> current = TaskBoardModel.currentSnapshot(session, null);
        assertEquals(3, current.size());
        assertEquals(TaskBoardModel.KIND_TODO, current.get(0).kind);
        assertEquals("重读全部docx并梳理线索", current.get(0).title);
        assertEquals(TaskBoardModel.KIND_TODO, current.get(1).kind);
        assertEquals(TaskBoardModel.KIND_TASK, current.get(2).kind);
    }

    @Test public void runningChildrenSortBeforeFinishedCurrent() throws Exception {
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash-0731");
        session.taskState.begin("任务", true);
        TaskState.Child done = session.taskState.startChild("先完成的");
        session.taskState.coordinateChild(done.id, "step_1");
        session.taskState.finishChild(done.id, "已完成", true, null);
        TaskState.Child running = session.taskState.startChild("在跑的");
        session.taskState.coordinateChild(running.id, "step_2");

        List<TaskBoardModel.BoardRow> current = TaskBoardModel.currentSnapshot(session, null);
        assertEquals(2, count(current, TaskBoardModel.KIND_TASK));
        assertEquals("running 排前", running.id, current.get(0).id);
        assertEquals(done.id, current.get(1).id);
    }

    @Test public void settledBackgroundExcludedRunningKept() throws Exception {
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash-0731");
        Path ws = tmp.newFolder("ws").toPath();
        ProcessTool process = new ProcessTool(new Workspace(ws.getParent().toString()),
                tmp.newFolder("ledger").toPath().resolve("jobs.json"));
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        JsonObject finished = new JsonObject();
        finished.addProperty("action", "start");
        finished.addProperty("command", win ? "cmd /c echo current-ok" : "echo current-ok");
        assertTrue(process.execute(finished).ok);
        JsonObject running = new JsonObject();
        running.addProperty("action", "start");
        running.addProperty("command", win ? "cmd /c ping -n 4 127.0.0.1 > nul" : "sleep 3");
        assertTrue(process.execute(running).ok);
        for (int i = 0; i < 100; i++) {
            boolean settled = false;
            for (RuntimeJobRegistry.JobRecord j : process.runtimeJobs().list())
                if (j.description != null && j.description.contains("current-ok") && j.status.settled()) settled = true;
            if (settled) break;
            Thread.sleep(50);
        }

        List<TaskBoardModel.BoardRow> current = TaskBoardModel.currentSnapshot(session, process.runtimeJobs());
        assertEquals("已结束后台不入当前条", 1, count(current, TaskBoardModel.KIND_BACKGROUND));
        assertTrue(first(current, TaskBoardModel.KIND_BACKGROUND).title.contains("ping")
                || first(current, TaskBoardModel.KIND_BACKGROUND).title.contains("sleep"));
        assertEquals("全量账本仍含已结束后台（特征化）", 2,
                count(TaskBoardModel.snapshot(session, process.runtimeJobs()), TaskBoardModel.KIND_BACKGROUND));
        process.shutdown();
    }

    @Test public void emptySessionSafe() throws Exception {
        assertTrue(TaskBoardModel.currentSnapshot(null, null).isEmpty());
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash-0731");
        assertTrue(TaskBoardModel.currentSnapshot(session, null).isEmpty());
    }
}