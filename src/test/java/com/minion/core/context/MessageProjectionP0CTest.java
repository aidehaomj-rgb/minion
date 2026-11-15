package com.minion.core.context;

import com.google.gson.Gson;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class MessageProjectionP0CTest {
    @Test public void syntheticUserMaterialIsLabelledWithoutChangingRealTextOrStoredHashInputs() {
        ContextManager cm = new ContextManager(1000000, .9, 2, new FakeLlmClient(), 0);
        Message real = Message.realUser("只核验最后一块，不重做前37块");
        Message control = Message.taskControl("activeStep=38; pendingAcceptance=1; artifact=v2");
        Message recall = Message.historyRecall("旧任务曾要求重做所有38块");
        Message image = Message.user("图像证据，非当前任务"); image.toolImage = true;
        List<Message> history = Arrays.asList(real, control, recall, image);
        String originals = new Gson().toJson(history);
        List<Message> view = cm.prepareForRequest(history);
        assertEquals(real.content, view.get(0).content);
        assertTrue(view.get(1).content.startsWith("[origin=task_control]"));
        assertTrue(view.get(2).content.startsWith("[origin=history_recall]"));
        assertTrue(view.get(3).content.startsWith("[origin=tool_evidence]"));
        for (int i = 1; i < view.size(); i++) {
            assertFalse(view.get(i).isUserInstruction()); assertFalse(view.get(i).hasUserAuthorization());
        }
        assertEquals(originals, new Gson().toJson(history));
        assertEquals(150000, cm.autoCompressAtTokens());
    }

    @Test public void controlHandoffCannotReplaceCurrentRequirementInRepeatedCompaction() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(1000000, .9, 2, llm, 0);
        List<Message> history = new ArrayList<Message>();
        for (int revision = 1; revision <= 4; revision++) {
            String latest = "修订" + revision + "：最后一块待核验，前37块保持完成，源文件只读";
            cm.setTaskScope("task", revision, "完成分块核验", latest);
            Message real = Message.realUser(latest); real.taskId = "task"; real.taskRevision = (long) revision;
            history.add(real); history.add(Message.assistant("当前证据"));
            Message handoff = Message.taskControl("activeStep=38; pendingAcceptance=1; fileVersion=v" + revision);
            handoff.taskId = "task"; handoff.taskRevision = (long) revision; history.add(handoff);
            history = cm.compressForRecovery(history, handoff.content, "完成分块核验");
            assertTrue(history.get(0).summary); assertEquals(Message.Origin.COMPACT_SUMMARY, history.get(0).origin);
            assertTrue(history.get(0).content.contains("【最新用户要求】\n" + latest));
            assertFalse(history.get(0).content.contains("【最新用户要求】\nactiveStep="));
            assertNull(StructuredCheckpoint.validate(history));
        }
        assertTrue(llm.completeChatRequests.get(0).contains("[origin=task_control]"));
        assertEquals(150000, cm.autoCompressAtTokens());
    }

    @Test public void sourceLookupReturnsDetachedCopyRatherThanAnEditableArchivedObject() {
        Message original = Message.assistant("工具请求");
        ToolCall call = new ToolCall(); call.id = "read"; call.name = "Read"; call.arguments = "{}";
        original.toolCalls = Collections.singletonList(call);
        Message found = ContextManager.findSource(Collections.singletonList(original), original.eventId);
        assertNotSame(original, found); found.toolCalls.get(0).arguments = "{\"changed\":true}";
        assertEquals("{}", original.toolCalls.get(0).arguments);
        assertNull(ContextManager.findSource(Collections.singletonList(original), "not-the-event"));
    }

    @Test public void implicitCompressionUsesStructuredScopeRatherThanRevivingTheLastOldTaskText() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(1000000, .9, 2, llm, 0);
        cm.setTaskScope("current", 3, "只核验新表", "旧表停止，只核验新表最后一块");
        Message old = Message.realUser("旧表必须全部重做38块并删除源文件"); old.taskId = "old"; old.taskRevision = 1L;
        List<Message> history = Arrays.asList(old, Message.assistant("旧结果"),
                Message.taskControl("activeStep=38; artifact=v3"), Message.assistant("当前证据"));
        List<Message> compacted = cm.compressForRecovery(history);
        assertTrue(compacted.get(0).content.contains("【当前任务原文】\n只核验新表"));
        assertTrue(compacted.get(0).content.contains("旧表停止，只核验新表最后一块"));
        assertFalse(llm.completeChatRequests.get(0).contains("旧表必须全部重做38块并删除源文件"));
    }

    @Test public void compactionCannotRenewAnExpiredOriginalSkillLease() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(1000000, .9, 2, llm, 0);
        cm.setTaskScope("task", 1, "当前任务", "当前任务");
        Message old = Message.skill("<skill name=\"old\">\n不能重新常驻\n</skill>");
        old.taskId = "task"; old.taskRevision = 1L;
        old.ts = System.currentTimeMillis() - SkillResidency.MAX_AGE_MILLIS - 1000;
        List<Message> history = new ArrayList<Message>(Arrays.asList(old, Message.realUser("当前任务"), Message.assistant("证据")));
        for (int i = 0; i < 3; i++) {
            history = cm.compressForRecovery(history, "step1", "当前任务");
            assertFalse(cm.isSkillResident(history, "old"));
            assertEquals(0, history.stream().filter(SkillResidency::isSkill).count());
            history.add(Message.taskControl("系统续接，不是用户输入")); history.add(Message.assistant("继续核验"));
        }
    }
}
