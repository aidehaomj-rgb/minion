package com.minion.core.agent;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * G1：用户补充推进任务修订后，仍在 running 的子 agent 不得从数量/面板/上下文隐身；
 * 已结束态子 agent 维持原修订过滤（历史修订结果不冒充当前）。
 */
public class TaskStateChildVisibilityTest {

    private static boolean contains(List<TaskState.Child> rows, String id) {
        for (TaskState.Child c : rows) if (id.equals(c.id)) return true;
        return false;
    }

    @Test public void runningChildSurvivesRevisionBump() {
        TaskState ts = new TaskState();
        ts.begin("处理查获明细", true);
        TaskState.Child c = ts.startChild("chunk_1 提取");
        assertEquals(1L, ts.revision());

        ts.addGuidance("改为按单号处理");
        assertEquals(2L, ts.revision());

        assertTrue("running 子agent修订推进后仍须在快照中", contains(ts.childSnapshot(), c.id));
        assertTrue("running 子agent修订推进后仍须在面板行中", contains(ts.childRows(10), c.id));
        String render = ts.render(new TodoList());
        assertTrue(render.contains("当前任务子Agent数量: 1"));
        assertTrue(render.contains(c.id));
    }

    @Test public void coordinatedRunningChildSurvivesRevisionBump() {
        TaskState ts = new TaskState();
        ts.begin("处理查获明细", true);
        TaskState.Child c = ts.startChild("chunk_1 提取");
        ts.coordinateChild(c.id, "step_1");
        ts.addGuidance("改为按单号处理");
        assertEquals(2L, ts.revision());

        assertTrue("协调型 running 子agent修订推进后仍须可见（G1 办公场景）", contains(ts.childSnapshot(), c.id));
        assertTrue(ts.render(new TodoList()).contains("当前任务子Agent数量: 1"));
    }

    @Test public void finishedChildOfOldRevisionStillFiltered() {
        TaskState ts = new TaskState();
        ts.begin("任务", true);
        TaskState.Child c = ts.startChild("chunk_1");
        ts.coordinateChild(c.id, "step_1");
        ts.addGuidance("补充要求");
        ts.finishChild(c.id, "已完成", true, null);

        assertFalse("已结束旧修订协调子agent恢复旧过滤", contains(ts.childSnapshot(), c.id));
        assertFalse(contains(ts.childRows(10), c.id));
        assertTrue(ts.render(new TodoList()).contains("当前任务子Agent数量: 0"));
    }

    @Test public void nonCoordinatedFinishedChildAlwaysVisible() {
        TaskState ts = new TaskState();
        ts.begin("任务", true);
        TaskState.Child c = ts.startChild("chunk_1");
        ts.addGuidance("补充要求");
        ts.finishChild(c.id, "已完成", true, null);

        assertTrue("非协调子agent不受修订过滤（旧语义保持不变）", contains(ts.childSnapshot(), c.id));
    }

    @Test public void currentRevisionFinishedChildStillVisible() {
        TaskState ts = new TaskState();
        ts.begin("任务", true);
        TaskState.Child c = ts.startChild("chunk_1");
        ts.finishChild(c.id, "已完成", true, null);
        assertTrue("当前修订的待复核子agent可见（旧语义）", contains(ts.childSnapshot(), c.id));
    }

    @Test public void ackPhraseDoesNotBumpRevision() {
        TaskState ts = new TaskState();
        ts.begin("任务", true);
        TaskState.Child c = ts.startChild("chunk_1");
        ts.addGuidance("继续");
        assertEquals(1L, ts.revision());
        assertTrue(contains(ts.childSnapshot(), c.id));
    }

    @Test public void multipleRunningChildrenAllKept() {
        TaskState ts = new TaskState();
        ts.begin("任务", true);
        TaskState.Child c1 = ts.startChild("chunk_1");
        TaskState.Child c2 = ts.startChild("chunk_2");
        ts.coordinateChild(c1.id, "step_1");
        ts.coordinateChild(c2.id, "step_2");
        ts.addGuidance("新要求");
        ts.addGuidance("又一条新要求");
        assertEquals(3L, ts.revision());
        assertTrue(contains(ts.childSnapshot(), c1.id));
        assertTrue(contains(ts.childSnapshot(), c2.id));
        assertTrue(ts.render(new TodoList()).contains("当前任务子Agent数量: 2"));
    }

    @Test public void runningOldAttemptStillDedupedByNewerRunningAttempt() {
        TaskState ts = new TaskState();
        ts.begin("任务", true);
        TaskState.Child c1 = ts.startChild("旧尝试");
        ts.coordinateChild(c1.id, "step_1", 1);
        TaskState.Child c2 = ts.startChild("新尝试");
        ts.coordinateChild(c2.id, "step_1", 2);
        ts.addGuidance("新要求");
        assertEquals(2L, ts.revision());
        assertTrue("最新attempt的running修订推进后仍可见", contains(ts.childSnapshot(), c2.id));
        assertFalse("最新attempt去重语义保留：旧attempt让位新attempt", contains(ts.childSnapshot(), c1.id));
    }
}