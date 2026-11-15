package com.minion.core.agent;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class TaskStateChildProjectionTest {
    @Test public void rowsAreBoundedDetachedAndDoNotLoseOriginals(){
        TaskState state=new TaskState();state.begin("当前任务",true);
        String large=String.join("",Collections.nCopies(100000,"大段结果"));
        for(int i=0;i<70;i++){TaskState.Child child=state.startChild("分工"+i);child.result=large;child.transcript="receipt-"+i+".json";}
        List<TaskState.Child> rows=state.childRows(1000);assertEquals(64,rows.size());
        assertTrue(rows.get(0).result.length()<4200);assertEquals("receipt-6.json",rows.get(0).transcript);
        rows.get(0).status="forged";assertEquals("running",state.children.get(6).status);
        assertSame(large,state.children.get(6).result);assertTrue(state.render(null).contains("70"));
    }
    @Test public void latestAttemptAndCurrentTaskFilteringArePreserved(){
        TaskState state=new TaskState();state.begin("当前任务",true);
        TaskState.Child old=state.startChild("旧尝试");old.coordinatedStep="a";old.coordinatedAttempt=1;
        TaskState.Child latest=state.startChild("新尝试");latest.coordinatedStep="a";latest.coordinatedAttempt=2;
        TaskState.Child other=state.startChild("其他项目旧任务");other.taskId="old-task";
        List<TaskState.Child> rows=state.childRows(10);assertEquals(1,rows.size());assertEquals(latest.id,rows.get(0).id);
        assertEquals(1,state.childSnapshot().size());
    }
}
