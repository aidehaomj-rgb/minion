package com.minion.gui.session;

import com.minion.core.agent.SteeringReceipt;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class EventListSteeringReceiptTest {
    private static EventList.Ev event(EventList.Kind kind, String state, long updatedAt) {
        SteeringReceipt r = new SteeringReceipt();
        r.id = "g"; r.text = "回执节选"; r.state = state; r.updatedAt = updatedAt;
        return new EventList.Ev(kind, kind == EventList.Kind.USER_SUPPLEMENT ? "完整引导正文" : null, r);
    }

    @Test public void oldPageGetsLatestStateWithoutChangingOriginalEventOrBody() {
        EventList l = new EventList();
        l.add(event(EventList.Kind.USER_SUPPLEMENT, "unread", 1));
        for (int i = 0; i < 300; i++) l.add(new EventList.Ev(EventList.Kind.USER_MESSAGE, "m" + i, null));
        l.add(event(EventList.Kind.STEERING_UPDATE, "read", 2));
        EventList.Page old = l.pageBefore(1, 1);
        assertEquals(1, old.events.size());
        assertEquals("完整引导正文", old.events.get(0).text);
        assertTrue(((SteeringReceipt) old.events.get(0).data).isRead());
        assertEquals("unread", ((SteeringReceipt) l.snapshot().get(0).data).state);
        assertEquals(302, l.size());
    }

    @Test public void metadataUpdatesUseNoVisiblePageBudgetAndNeverRepeatGuidance() {
        EventList l = new EventList();
        l.add(event(EventList.Kind.USER_SUPPLEMENT, "unread", 1));
        for (int i = 0; i < 500; i++) l.add(event(EventList.Kind.STEERING_UPDATE, "sending", i + 2));
        l.add(event(EventList.Kind.STEERING_UPDATE, "read", 600));
        EventList.Page page = l.pageBefore(l.size(), 1);
        assertEquals(0, page.start);
        assertEquals(502, page.end);
        assertEquals(1, page.events.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, page.events.get(0).kind);
        assertTrue(((SteeringReceipt) page.events.get(0).data).isRead());
    }

    @Test public void stateUpdatesDoNotFragmentMainOrChildStreamingPages() {
        for (String child : new String[]{null, "child-1"}) {
            EventList l = new EventList();
            l.add(event(EventList.Kind.USER_SUPPLEMENT, "unread", 1));
            for (int i = 0; i < 1000; i++) {
                l.add(new EventList.Ev(EventList.Kind.CONTENT, "x", null, child));
                if (i % 10 == 0) l.add(event(EventList.Kind.STEERING_UPDATE, "sending", i + 2));
            }
            EventList.Page page = l.pageBefore(l.size(), 1);
            assertEquals(1, page.start);
            assertEquals(1, page.events.size());
            assertEquals(1000, page.events.get(0).text.length());
            assertEquals(child, page.events.get(0).subAgentId);
        }
    }

    @Test public void rebindRetainsRawCursorAndLiveUpdatesWithoutDuplicatingInput() {
        EventList l = new EventList();
        l.add(event(EventList.Kind.USER_SUPPLEMENT, "unread", 1));
        List<EventList.Ev> live = new ArrayList<EventList.Ev>();
        assertEquals(1, l.rebind(live::add, 0).size());
        l.add(event(EventList.Kind.STEERING_UPDATE, "inserted", 2));
        assertEquals(EventList.Kind.STEERING_UPDATE, live.get(0).kind);
        l.setActive(false, null);
        l.add(event(EventList.Kind.STEERING_UPDATE, "read", 3));
        List<EventList.Ev> tail = l.rebind(live::add, 2);
        assertEquals(1, tail.size());
        assertEquals(EventList.Kind.STEERING_UPDATE, tail.get(0).kind);
        assertEquals(3, l.size());
        assertEquals(1, l.pageBefore(l.size(), 10).events.size());
        assertTrue(l.steeringReceipt("g").isRead());
    }

    @Test public void snapshotsAreIsolatedAndClearRemovesReceiptIndex() {
        EventList l = new EventList();
        EventList.Ev source = event(EventList.Kind.USER_SUPPLEMENT, "unread", 1);
        l.add(source);
        ((SteeringReceipt) source.data).state = "read";
        assertFalse(l.steeringReceipt("g").isRead());
        SteeringReceipt copy = l.steeringReceipt("g"); copy.state = "read";
        assertFalse(l.steeringReceipt("g").isRead());
        ((SteeringReceipt) l.pageBefore(1, 1).events.get(0).data).state = "read";
        assertFalse(l.steeringReceipt("g").isRead());
        l.clear();
        assertNull(l.steeringReceipt("g"));
        assertEquals(0, l.size());
    }

    @Test public void olderMetadataCannotDowngradeHistoricalReadState() {
        EventList l = new EventList();
        l.add(event(EventList.Kind.USER_SUPPLEMENT, "read", 30));
        l.add(event(EventList.Kind.STEERING_UPDATE, "sending", 20));
        assertTrue(l.steeringReceipt("g").isRead());
        assertTrue(((SteeringReceipt) l.pageBefore(l.size(), 1).events.get(0).data).isRead());
        assertEquals(2, l.size());
    }
}
