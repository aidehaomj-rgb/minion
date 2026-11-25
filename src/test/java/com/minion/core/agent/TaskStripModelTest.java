package com.minion.core.agent;

import com.minion.core.agent.TaskBoardModel.BoardRow;
import com.minion.gui.TaskStrip;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Part E 任务条纯展示逻辑（headless）：n/m 计数、当前行选取、空态隐藏、全完成文案、
 *  截断、三态图标、三类终态判定、行标签徽标、重绘签名排除输出尾部。
 *  直接构造 BoardRow（包内构造器）覆盖 todo/task/background 全分支，不起进程。 */
public class TaskStripModelTest {

    private static BoardRow row(String kind, String title, String status, String owner, Integer exit) {
        return new BoardRow(kind, kind + "-1", title, owner, status, "", "", exit);
    }

    private static BoardRow todo(String title, boolean done) {
        return row(TaskBoardModel.KIND_TODO, title, done ? "完成" : "待办", "", null);
    }

    @Test public void countsAndCurrentTitle() {
        List<BoardRow> rows = Arrays.asList(
                todo("已完成项", true), todo("第一个未完成", false), todo("第二个未完成", false));
        int[] c = TaskStrip.counts(rows);
        assertEquals(1, c[0]);
        assertEquals(3, c[1]);
        assertEquals("第一个未完成", TaskStrip.currentTitle(rows));
    }

    @Test public void emptyRowsHiddenAndBlankSummary() {
        List<BoardRow> empty = new ArrayList<BoardRow>();
        assertEquals("", TaskStrip.summaryText(empty));
        assertArrayEquals(new int[]{0, 0}, TaskStrip.counts(empty));
        assertEquals("", TaskStrip.currentTitle(empty));
    }

    @Test public void allDoneSummary() {
        List<BoardRow> rows = Arrays.asList(todo("甲", true), todo("乙", true));
        assertEquals("✓ 任务清单 2/2 已完成", TaskStrip.summaryText(rows));
        assertEquals("", TaskStrip.currentTitle(rows));   // 全完成无当前行
    }

    @Test public void partialSummaryCarriesBadgeAndCurrent() {
        List<BoardRow> rows = Arrays.asList(todo("做完", true), todo("进行中", false));
        assertEquals("任务清单  [1/2]  当前：进行中", TaskStrip.summaryText(rows));
    }

    @Test public void currentTitleTruncatedTo60() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 80; i++) longTitle.append("字");
        List<BoardRow> rows = Arrays.asList(todo(longTitle.toString(), false));
        String cur = TaskStrip.currentTitle(rows);
        assertEquals(61, cur.length());   // 60 + 省略号
        assertTrue(cur.endsWith("…"));
        assertTrue(TaskStrip.summaryText(rows).contains(cur));
    }

    @Test public void clipHandlesNullAndNewlines() {
        assertEquals("", TaskStrip.clip(null, 60));
        assertEquals("a b", TaskStrip.clip("a\nb", 60));
        assertEquals("abc", TaskStrip.clip("abc", 60));
    }

    @Test public void isDoneAcrossKinds() {
        assertTrue(TaskStrip.isDone(todo("x", true)));
        assertFalse(TaskStrip.isDone(todo("x", false)));
        // task 终态
        assertTrue(TaskStrip.isDone(row(TaskBoardModel.KIND_TASK, "t", "returned_needs_review", "worker", null)));
        assertTrue(TaskStrip.isDone(row(TaskBoardModel.KIND_TASK, "t", "cancelled", "worker", null)));
        assertFalse(TaskStrip.isDone(row(TaskBoardModel.KIND_TASK, "t", "running", "worker", null)));
        assertFalse(TaskStrip.isDone(row(TaskBoardModel.KIND_TASK, "t", "paused", "worker", null)));
        // background：exitCode 或终态
        assertTrue(TaskStrip.isDone(row(TaskBoardModel.KIND_BACKGROUND, "b", "COMPLETED", "shell", 0)));
        assertTrue(TaskStrip.isDone(row(TaskBoardModel.KIND_BACKGROUND, "b", "RUNNING", "shell", 1)));
        assertFalse(TaskStrip.isDone(row(TaskBoardModel.KIND_BACKGROUND, "b", "RUNNING", "shell", null)));
    }

    @Test public void threeStateIcon() {
        assertEquals("✓", TaskStrip.icon(todo("x", true)));
        assertEquals("○", TaskStrip.icon(todo("x", false)));
        assertEquals("▶", TaskStrip.icon(row(TaskBoardModel.KIND_TASK, "t", "running", "worker", null)));
        assertEquals("○", TaskStrip.icon(row(TaskBoardModel.KIND_TASK, "t", "paused", "worker", null)));
        assertEquals("▶", TaskStrip.icon(row(TaskBoardModel.KIND_BACKGROUND, "b", "RUNNING", "shell", null)));
        assertEquals("✓", TaskStrip.icon(row(TaskBoardModel.KIND_BACKGROUND, "b", "FAILED", "shell", 2)));
    }

    @Test public void rowLabelBadges() {
        assertEquals("○ 待办事项", TaskStrip.rowLabel(todo("待办事项", false)));
        String task = TaskStrip.rowLabel(row(TaskBoardModel.KIND_TASK, "核对数据", "running", "worker", null));
        assertTrue(task.contains("▶ 核对数据"));
        assertTrue(task.contains("worker·running"));
        String bg = TaskStrip.rowLabel(row(TaskBoardModel.KIND_BACKGROUND, "跑脚本", "COMPLETED", "shell", 0));
        assertTrue(bg.contains("✓ 跑脚本"));
        assertTrue(bg.contains("exit=0"));
    }

    @Test public void signatureIgnoresRecentOutput() {
        BoardRow a = new BoardRow(TaskBoardModel.KIND_BACKGROUND, "b-1", "跑脚本", "shell", "RUNNING", "", "输出甲", null);
        BoardRow b = new BoardRow(TaskBoardModel.KIND_BACKGROUND, "b-1", "跑脚本", "shell", "RUNNING", "", "输出乙更长", null);
        assertEquals("输出尾部变化不应触发重绘", TaskStrip.signature(Arrays.asList(a)), TaskStrip.signature(Arrays.asList(b)));
        BoardRow c = new BoardRow(TaskBoardModel.KIND_BACKGROUND, "b-1", "跑脚本", "shell", "COMPLETED", "", "输出甲", 0);
        assertNotEquals("状态变化应触发重绘", TaskStrip.signature(Arrays.asList(a)), TaskStrip.signature(Arrays.asList(c)));
    }

    // ---- H1：折叠态运行中子Agent徽标 ----

    @Test public void runningChildBadgeInSummary() {
        List<BoardRow> rows = Arrays.asList(
                todo("汇总", false),
                row(TaskBoardModel.KIND_TASK, "chunk_1 提取", "running", "explore", null));
        assertEquals("任务清单  [0/2]  当前：汇总 · 子Agent 1运行", TaskStrip.summaryText(rows));
    }

    @Test public void multipleRunningChildrenBadge() {
        List<BoardRow> rows = Arrays.asList(
                row(TaskBoardModel.KIND_TASK, "chunk_1", "running", "worker", null),
                row(TaskBoardModel.KIND_TASK, "chunk_2", "running", "worker", null));
        assertTrue(TaskStrip.summaryText(rows).endsWith(" · 子Agent 2运行"));
    }

    @Test public void finishedChildNoBadge() {
        List<BoardRow> rows = Arrays.asList(
                todo("汇总", false),
                row(TaskBoardModel.KIND_TASK, "chunk_1", "returned_needs_review", "worker", null));
        assertEquals("任务清单  [1/2]  当前：汇总", TaskStrip.summaryText(rows));
    }

    @Test public void allDoneTodosWithRunningChildStillBadged() {
        List<BoardRow> rows = Arrays.asList(
                todo("甲", true),
                row(TaskBoardModel.KIND_TASK, "chunk_1", "running", "explore", null));
        assertEquals("任务清单  [1/2]  当前：chunk_1 · 子Agent 1运行", TaskStrip.summaryText(rows));
    }

    @Test public void backgroundRunningNotCountedAsChildBadge() {
        List<BoardRow> rows = Arrays.asList(
                row(TaskBoardModel.KIND_BACKGROUND, "跑脚本", "RUNNING", "shell", null));
        assertFalse("后台 shell 任务不算子Agent徽标", TaskStrip.summaryText(rows).contains("子Agent"));
    }

    // ---- I2：图标取色/进度/状态徽标 ----

    @Test public void iconClassThreeStates() {
        assertEquals("done", TaskStrip.iconClass(todo("x", true)));
        assertEquals("pending", TaskStrip.iconClass(todo("x", false)));
        assertEquals("active", TaskStrip.iconClass(row(TaskBoardModel.KIND_TASK, "t", "running", "worker", null)));
        assertEquals("done", TaskStrip.iconClass(row(TaskBoardModel.KIND_TASK, "t", "returned_needs_review", "worker", null)));
        assertEquals("active", TaskStrip.iconClass(row(TaskBoardModel.KIND_BACKGROUND, "b", "RUNNING", "shell", null)));
    }

    @Test public void progressFraction() {
        assertEquals(0.0, TaskStrip.progress(new ArrayList<BoardRow>()), 0.0001);
        assertEquals(0.5, TaskStrip.progress(Arrays.asList(todo("a", true), todo("b", false))), 0.0001);
        assertEquals(1.0, TaskStrip.progress(Arrays.asList(todo("a", true))), 0.0001);
    }

    @Test public void chipTextChineseStatusAndExit() {
        assertEquals("", TaskStrip.chipText(todo("x", false)));
        assertEquals("运行中", TaskStrip.chipText(row(TaskBoardModel.KIND_TASK, "t", "running", "worker", null)));
        assertEquals("待复核", TaskStrip.chipText(row(TaskBoardModel.KIND_TASK, "t", "returned_needs_review", "worker", null)));
        assertEquals("失败/中断", TaskStrip.chipText(row(TaskBoardModel.KIND_TASK, "t", "failed_or_interrupted", "worker", null)));
        assertEquals("exit=0", TaskStrip.chipText(row(TaskBoardModel.KIND_BACKGROUND, "b", "COMPLETED", "shell", 0)));
        assertEquals("排队中", TaskStrip.chipText(row(TaskBoardModel.KIND_BACKGROUND, "b", "QUEUED", "shell", null)));
    }
}