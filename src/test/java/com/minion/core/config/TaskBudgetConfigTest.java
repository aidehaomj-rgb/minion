package com.minion.core.config;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TaskBudgetConfigTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void continuationWindowsRetainFiniteDefaults() {
        Config c=Config.load(temp.getRoot().toPath());assertEquals(96,c.taskContinuationWindows());
        c.set("agent.task.continuationWindows","-1");
        assertEquals(96,c.taskContinuationWindows());
        c.set("agent.task.continuationWindows","48");
        assertEquals(48,c.taskContinuationWindows());
    }
}
