package com.minion.gui.session;

import com.minion.core.agent.SteeringReceipt;
import com.minion.core.llm.Message;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SessionControllerSteeringReceiptTest {
    private static SteeringReceipt receipt(String id, String text, String state, long updatedAt) {
        SteeringReceipt r = new SteeringReceipt();
        r.id = id; r.text = text; r.state = state; r.createdAt = 10; r.updatedAt = updatedAt;
        r.eventId = "event-" + id; r.requestId = "request-" + id; r.taskId = "task-" + id;
        r.revision = 2L;
        if ("read".equals(state)) r.readAt = updatedAt;
        return r;
    }

    @Test public void firstReceiptUsesSupplementAndFurtherStatesOnlyUpdateSameId() {
        SessionController c = new SessionController();
        List<SteeringReceipt> heard = new ArrayList<SteeringReceipt>();
        c.setSteeringReceiptListener(heard::add);
        SteeringReceipt r = receipt("g1", "完整的立即引导", "unread", 10);
        c.onSteeringReceipt(r);
        r.state = "inserted"; r.updatedAt = 11; c.onSteeringReceipt(r);
        r.state = "sending"; r.updatedAt = 12; c.onSteeringReceipt(r);
        r.state = "read"; r.updatedAt = 13; r.readAt = 13; c.onSteeringReceipt(r);
        List<EventList.Ev> events = c.eventList().snapshot();
        assertEquals(4, events.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, events.get(0).kind);
        assertEquals("完整的立即引导", events.get(0).text);
        assertEquals("unread", ((SteeringReceipt) events.get(0).data).state);
        for (int i = 1; i < events.size(); i++) {
            assertEquals(EventList.Kind.STEERING_UPDATE, events.get(i).kind);
            assertNull(events.get(i).text);
            assertEquals("g1", ((SteeringReceipt) events.get(i).data).id);
        }
        assertEquals(4, heard.size());
        assertNotSame(r, heard.get(3));
        assertEquals("read", heard.get(3).state);
        assertEquals(13L, heard.get(3).readAt);
        assertEquals("event-g1", heard.get(3).eventId);
        assertEquals("request-g1", heard.get(3).requestId);
        assertEquals("task-g1", heard.get(3).taskId);
        assertEquals(Long.valueOf(2), heard.get(3).revision);
        heard.get(3).state = "unread";
        assertEquals("read", c.eventList().steeringReceipt("g1").state);
    }

    @Test public void listenerSeesPublishedEventButCannotMutateEventSnapshot() {
        SessionController c = new SessionController();
        c.setSteeringReceiptListener(r -> {
            assertEquals(1, c.eventList().size());
            r.text = "listener修改"; r.state = "read";
        });
        SteeringReceipt r = receipt("g", "原文", "unread", 1);
        c.onSteeringReceipt(r);
        r.text = "producer修改";
        assertEquals("原文", c.eventList().snapshot().get(0).text);
        assertEquals("原文", ((SteeringReceipt) c.eventList().snapshot().get(0).data).text);
        assertEquals("unread", c.eventList().steeringReceipt("g").state);
    }

    @Test public void staleAndInvalidReceiptsDoNotDowngradeOrAppendPhantomInput() {
        SessionController c = new SessionController();
        List<SteeringReceipt> heard = new ArrayList<SteeringReceipt>();
        c.setSteeringReceiptListener(heard::add);
        c.onSteeringReceipt(receipt("g", "引导", "read", 30));
        c.onSteeringReceipt(receipt("g", "节选", "sending", 20));
        c.onSteeringReceipt(null);
        c.onSteeringReceipt(receipt(" ", "无ID", "unread", 40));
        assertEquals(1, c.eventList().size());
        assertEquals(1, heard.size());
        assertTrue(c.eventList().steeringReceipt("g").isRead());
    }

    @Test public void replayMatchesOriginalUserPositionAndKeepsFullActualText() {
        String full = String.join("", Collections.nCopies(6000, "完整原文"));
        Message ordinary = Message.user("普通任务");
        Message guidance = Message.userSupplement(full); guidance.steeringId = "g";
        Message reply = Message.assistant("结果");
        List<Message> messages = Arrays.asList(ordinary, guidance, reply);
        SteeringReceipt read = receipt("g", "完整原文…", "read", 30);
        SessionController c = new SessionController();
        c.setSteeringReceiptListener(r -> fail("历史恢复不应冒充新的运行时回执"));
        c.replayHistory(messages, Collections.singletonList(read));
        List<EventList.Ev> events = c.eventList().snapshot();
        assertEquals(4, events.size());
        assertEquals(EventList.Kind.USER_MESSAGE, events.get(0).kind);
        assertNull(events.get(0).data);
        assertEquals(EventList.Kind.USER_SUPPLEMENT, events.get(1).kind);
        assertEquals(full, events.get(1).text);
        assertTrue(((SteeringReceipt) events.get(1).data).isRead());
        assertEquals(EventList.Kind.CONTENT, events.get(2).kind);
        assertEquals(EventList.Kind.RESPONSE_END, events.get(3).kind);
        assertEquals(3, messages.size());
        assertEquals(full, guidance.content);
        assertEquals("g", guidance.steeringId);
        assertEquals("完整原文…", read.text);
    }

    @Test public void duplicateReceiptAndMessageIdsDoNotRepeatBodyAndPendingIsUiOnly() {
        Message inserted = Message.user("实际引导原文"); inserted.steeringId = "g";
        Message repeated = Message.userSupplement("重试相同引导"); repeated.steeringId = "g";
        SteeringReceipt pending = receipt("pending", "未插入引导", "unread", 1);
        SessionController c = new SessionController();
        List<Message> source = new ArrayList<Message>(Arrays.asList(inserted, repeated));
        c.replayHistory(source, Arrays.asList(receipt("g", "节选", "sending", 20),
                receipt("g", "节选", "read", 30), pending, pending.copy()));
        List<EventList.Ev> events = c.eventList().snapshot();
        assertEquals(2, events.size());
        assertEquals("实际引导原文", events.get(0).text);
        assertEquals("read", ((SteeringReceipt) events.get(0).data).state);
        assertEquals("未插入引导", events.get(1).text);
        assertEquals("pending", ((SteeringReceipt) events.get(1).data).id);
        assertEquals(EventList.Kind.USER_SUPPLEMENT, events.get(1).kind);
        assertEquals(2, source.size());
        c.onSteeringReceipt(receipt("pending", "节选", "inserted", 2));
        assertEquals(EventList.Kind.STEERING_UPDATE, c.eventList().snapshot().get(2).kind);
    }

    @Test public void oldOverloadAndOrdinarySupplementsKeepLegacySemantics() {
        SessionController c = new SessionController();
        Message m = Message.user("没有回执的普通用户消息"); m.steeringId = "unmatched";
        c.replayHistory(Arrays.asList(m, Message.userSupplement("旧版补充")));
        c.onUserSupplement("提问回答");
        List<EventList.Ev> events = c.eventList().snapshot();
        assertEquals(3, events.size());
        assertEquals(EventList.Kind.USER_MESSAGE, events.get(0).kind);
        for (EventList.Ev e : events) assertNull(e.data);
        assertNull(c.eventList().steeringReceipt("unmatched"));
    }

    @Test public void sameReceiptIdInDifferentControllersIsSessionIsolated() {
        SessionController a = new SessionController(), b = new SessionController();
        a.onSteeringReceipt(receipt("same", "会话A", "read", 30));
        b.onSteeringReceipt(receipt("same", "会话B", "unread", 10));
        assertTrue(a.eventList().steeringReceipt("same").isRead());
        assertFalse(b.eventList().steeringReceipt("same").isRead());
        assertEquals("会话B", b.eventList().snapshot().get(0).text);
    }
}
