package com.minion.core.context;

import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class TaskScopedCompactionTest {
    private static String checkpoint(String latest, String next) {
        return "【最新用户要求】"+latest+"\n【已完成及证据】report.xlsx 已存在，需复核\n"
                +"【未完成】旧任务剩余38个分块\n【关键决定及原因】金额不舍入\n"
                +"【证据位置】report.xlsx 第1页\n【失败尝试】无\n【下一步】"+next+"\n【不确定事项】金额待复核";
    }
    private static Message summary(String task, long revision) {
        Message m=Message.user("【历史对话摘要】\n【当前任务原文】\n处理旧表\n【历史背景】\n"
                +checkpoint("旧目标删除源文件","重新执行旧任务全部分块")
                +"\n【任务清单快照：已完成项不要重复】\n旧任务快照要删除目录");
        m.summary=true;m.taskId=task;m.taskRevision=revision;return m;
    }

    @Test public void previousTaskSummaryCannotContributeObjectiveTodoOrNextStep() {
        Message old=summary("old-task",1);String original=old.content;
        String projected=StructuredCheckpoint.forTask(old,"current-task",1,"只处理新表","只处理新表");
        assertFalse(projected.contains("旧目标删除源文件"));
        assertFalse(projected.contains("旧任务剩余38个分块"));
        assertFalse(projected.contains("重新执行旧任务全部分块"));
        assertFalse(projected.contains("旧任务快照要删除目录"));
        assertTrue(projected.contains("report.xlsx"));assertTrue(projected.contains("金额待复核"));
        assertEquals(original,old.content);
    }

    @Test public void steeringInvalidatesOldProceduresEvenWithinSameTask() {
        Message old=summary("task",1);
        String projected=StructuredCheckpoint.forTask(old,"task",2,"处理旧表","源文件只读，只导出新表");
        assertTrue(projected.contains("旧计划已失效"));
        assertFalse(projected.contains("重新执行旧任务全部分块"));
        assertFalse(projected.contains("旧目标删除源文件"));
    }

    @Test public void currentSummaryLatestRequirementIsNotChosenBySummarizer() {
        Message old=summary("task",3);
        String projected=StructuredCheckpoint.forTask(old,"task",3,"处理旧表","只读源文件，不删除");
        assertTrue(projected.contains("【最新用户要求】\n只读源文件，不删除"));
        assertFalse(projected.contains("旧目标删除源文件"));
        assertFalse(projected.contains("旧任务快照要删除目录"));
    }

    @Test public void unscopedLegacySummaryIsOnlyActiveWhenOriginalObjectiveMatches() {
        Message old=summary(null,0);
        assertFalse(StructuredCheckpoint.forTask(old,"task",1,"只处理新表","只处理新表").contains("旧任务剩余38个分块"));
        assertTrue(StructuredCheckpoint.forTask(old,"task",1,"处理旧表","处理旧表").contains("旧任务剩余38个分块"));
        assertFalse(StructuredCheckpoint.forTask(old,"task",2,"处理旧表","取消旧分块").contains("旧任务剩余38个分块"));
    }

    @Test public void repeatedCompactionScopesSummariesAndBindsExactLatestRequirement() {
        FakeLlmClient llm=new FakeLlmClient();llm.rawCompressResult=true;
        llm.compressResult=checkpoint("错误沿用旧要求","核对当前交付物");
        ContextManager cm=new ContextManager(1000000,.9,0,llm,0);
        List<Message> history=new ArrayList<Message>(Arrays.asList(summary("old-task",1),Message.user("只处理B表"),Message.assistant("当前证据")));
        for(int revision=1;revision<=4;revision++) {
            String latest="第"+revision+"次纠正：只处理B表，A表停止";
            cm.setTaskScope("task-b",revision,"只处理B表",latest);
            history=cm.compressForRecovery(history,"当前任务清单", "只处理B表");
            assertNull(StructuredCheckpoint.validate(history));
            Message compacted=history.get(0);
            assertEquals("task-b",compacted.taskId);assertEquals(revision,compacted.revision());
            assertTrue(compacted.content.contains("【最新用户要求】\n"+latest));
            assertFalse(compacted.content.contains("错误沿用旧要求"));
            history=new ArrayList<Message>(history);history.add(Message.user("继续"));history.add(Message.assistant("新证据"));
        }
        assertFalse(llm.completeChatRequests.get(0).contains("旧任务剩余38个分块"));
        assertFalse(llm.completeChatRequests.get(0).contains("重新执行旧任务全部分块"));
    }

    @Test public void olderTaskRawInstructionsAreNotSummarizedAsCurrentPendingWork() {
        FakeLlmClient llm=new FakeLlmClient();ContextManager cm=new ContextManager(1000000,.9,0,llm,0);
        cm.setTaskScope("new",1,"现在只核对B表","现在只核对B表");
        Message old=Message.user("旧任务危险指令：删掉全部源文件");old.taskId="old";
        Message current=Message.user("现在只核对B表");current.taskId="new";
        List<Message> history=Arrays.asList(old,Message.assistant("旧回复"),current,Message.assistant("新结果"));
        cm.compressForRecovery(history,"只核对B表","现在只核对B表");
        assertFalse(llm.completeChatRequests.get(0).contains("删掉全部源文件"));
        assertTrue(llm.completeChatRequests.get(0).contains(current.eventId));
    }

    @Test public void requestProjectionPreservesMetadataWithoutMutatingStoredSummary() {
        Message old=summary("old",1);String original=old.content;
        ContextManager cm=new ContextManager(1000000,.9,12,new FakeLlmClient(),0);
        cm.setTaskScope("new",2,"当前目标","最新纠正");
        Message copy=cm.prepareForRequest(Collections.singletonList(old)).get(0);
        assertNotSame(old,copy);assertEquals("old",copy.taskId);assertEquals(1,copy.revision());
        assertFalse(copy.content.contains("旧任务剩余38个分块"));assertEquals(original,old.content);
        assertEquals(150000,cm.autoCompressAtTokens());
    }

    @Test public void switchingModelsDoesNotLoseTaskScopeOrReactivateOldRevision() {
        ContextManager cm=new ContextManager(1000000,.9,12,new FakeLlmClient(),0);
        cm.setTaskScope("task",3,"当前目标","最新纠正");cm.setLlm(new FakeLlmClient());
        Message old=summary("task",1);
        assertFalse(cm.prepareForRequest(Collections.singletonList(old)).get(0).content.contains("重新执行旧任务全部分块"));
        assertEquals(150000,cm.autoCompressAtTokens());
    }

    @Test public void rawSpeculativeReasoningIsArchivedButNotUsedAsSummaryEvidence() {
        FakeLlmClient llm=new FakeLlmClient();ContextManager cm=new ContextManager(1000000,.9,0,llm,0);
        cm.setTaskScope("task",1,"核对B表","核对B表");
        Message thought=Message.assistant("B表读取成功");thought.reasoningContent="废弃猜想：要把很久以前的任务重新全部执行";
        cm.compressForRecovery(Arrays.asList(Message.user("核对B表"),thought),"当前清单","核对B表");
        assertFalse(llm.completeChatRequests.get(0).contains("废弃猜想"));
        assertEquals("废弃猜想：要把很久以前的任务重新全部执行",thought.reasoningContent);
    }

    @Test public void retainedEarlierUserMessagesAreQuotedNotReissuedAndMetadataStaysLocal() {
        Message earlier=Message.user("旧任务要读取38个分块");earlier.taskId="old";earlier.taskRevision=1L;
        Message current=Message.user("当前只处理B表");current.taskId="new";current.taskRevision=2L;
        ContextManager cm=new ContextManager(1000000,.9,12,new FakeLlmClient(),0);
        cm.setTaskScope("new",2,"只处理B表","当前只处理B表");
        List<Message> view=cm.prepareForRequest(Arrays.asList(earlier,current));
        assertTrue(view.get(0).content.startsWith("【较早任务消息引用"));
        assertEquals("当前只处理B表",view.get(1).content);
        assertEquals("旧任务要读取38个分块",earlier.content);
        assertFalse(view.get(0).toApiJson().has("taskRevision"));assertFalse(view.get(0).toApiJson().has("taskId"));
    }
}
