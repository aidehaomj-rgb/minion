package com.minion.gui;

import com.minion.core.agent.TaskState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** I3 验收：任务面板状态徽标配色类与运行中置顶排序（纯静态逻辑，不启 FX toolkit） */
public class TaskDashboardStatusStyleTest {

    private static TaskState.Child child(String id, String status) {
        TaskState.Child c = new TaskState.Child();
        c.id = id;
        c.status = status;
        return c;
    }

    @Test public void statusClassMapsFourChipColors() {
        assertEquals("agent-status-running", TaskDashboard.statusClass("running"));
        assertEquals("agent-status-running", TaskDashboard.statusClass("RUNNING"));
        assertEquals("agent-status-done", TaskDashboard.statusClass("completed"));
        assertEquals("agent-status-done", TaskDashboard.statusClass("reviewed"));
        assertEquals("agent-status-done", TaskDashboard.statusClass("validated"));
        assertEquals("agent-status-failed", TaskDashboard.statusClass("failed_or_interrupted"));
        assertEquals("agent-status-failed", TaskDashboard.statusClass("FAILED"));
        assertEquals("agent-status-failed", TaskDashboard.statusClass("merge_failed_needs_review"));
        assertEquals("agent-status-other", TaskDashboard.statusClass("returned_needs_review"));
        assertEquals("agent-status-other", TaskDashboard.statusClass("queued"));
        assertEquals("agent-status-other", TaskDashboard.statusClass(null));
    }

    @Test public void runningFirstIsStableWithinRanks() {
        List<TaskState.Child> rows = new ArrayList<TaskState.Child>(Arrays.asList(
                child("a", "completed"), child("b", "running"), child("c", "returned_needs_review"),
                child("d", "RUNNING"), child("e", "failed_or_interrupted")));
        List<TaskState.Child> sorted = TaskDashboard.runningFirst(rows);
        assertEquals(Arrays.asList("b", "d", "a", "c", "e"),
                Arrays.asList(sorted.get(0).id, sorted.get(1).id, sorted.get(2).id, sorted.get(3).id, sorted.get(4).id));
        assertEquals("入参台账顺序不被修改", Arrays.asList("a", "b", "c", "d", "e"),
                Arrays.asList(rows.get(0).id, rows.get(1).id, rows.get(2).id, rows.get(3).id, rows.get(4).id));
    }

    @Test public void chipTextReusesStripChineseStatus() {
        assertEquals("运行中", TaskStrip.zhStatus("running"));
        assertEquals("待复核", TaskStrip.zhStatus("returned_needs_review"));
        assertEquals("失败/中断", TaskStrip.zhStatus("failed_or_interrupted"));
    }
}