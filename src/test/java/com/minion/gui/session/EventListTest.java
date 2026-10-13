package com.minion.gui.session;

import com.minion.gui.session.EventList.Ev;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class EventListTest {
    @Test
    public void childDeltasMergeIntoOneVisibleHistoryEntry() {
        EventList l = new EventList();
        l.add(new Ev(EventList.Kind.USER_MESSAGE, "older", null));
        for (int i = 0; i < 1000; i++) l.add(new Ev(EventList.Kind.SUB_AGENT_DELTA, "x", null));
        l.add(new Ev(EventList.Kind.RESPONSE_END, null, null));
        EventList.Page page = l.pageBefore(l.size(), 1);
        assertEquals(1, page.start);
        assertEquals(2, page.events.size());
        assertEquals(EventList.Kind.SUB_AGENT_DELTA, page.events.get(0).kind);
        assertEquals(1000, page.events.get(0).text.length());
        assertEquals("older", l.pageBefore(page.start, 1).events.get(0).text);
        assertEquals(1002, l.size());
    }

    @Test
    public void endMarkersDoNotConsumeVisiblePageBudget() {
        EventList l = new EventList();
        for (int i = 0; i < 310; i++) {
            l.add(new Ev(EventList.Kind.USER_MESSAGE, "m" + i, null));
            l.add(new Ev(EventList.Kind.RESPONSE_END, null, null));
        }
        EventList.Page page = l.pageBefore(l.size(), 150);
        assertEquals(320, page.start);
        assertEquals(300, page.events.size());
        assertEquals("m160", page.events.get(0).text);
        l.add(new Ev(EventList.Kind.CONTENT, "live", null));
        assertEquals(620, l.pageBefore(page.end, 150).end);
        assertEquals("live", l.pageBefore(l.size(), 150).events.get(298).text);
    }

    @Test
    public void childOutputDoesNotMergeAcrossTaskBoundaries() {
        EventList l = new EventList();
        l.add(new Ev(EventList.Kind.SUB_AGENT_DELTA, "first", null));
        l.add(new Ev(EventList.Kind.SUB_AGENT_DONE, "done", null));
        l.add(new Ev(EventList.Kind.SUB_AGENT_DELTA, "second", null));
        EventList.Page page = l.pageBefore(l.size(), 2);
        assertEquals(1, page.start);
        assertEquals("done", page.events.get(0).text);
        assertEquals("second", page.events.get(1).text);
    }

    @Test
    public void historyPagesMergeStreamChunksAndKeepOriginals() {
        EventList l = new EventList();
        l.add(new Ev(EventList.Kind.USER_MESSAGE, "first", null));
        for (int i = 0; i < 2500; i++) l.add(new Ev(EventList.Kind.CONTENT, "x", null));
        l.add(new Ev(EventList.Kind.TOOL_CALL, "Read", "args"));
        EventList.Page recent = l.pageBefore(l.size(), 2);
        assertEquals(1, recent.start);
        assertEquals(2502, recent.end);
        assertEquals(2500, recent.events.get(0).text.length());
        assertEquals(EventList.Kind.TOOL_CALL, recent.events.get(1).kind);
        EventList.Page old = l.pageBefore(recent.start, 2);
        assertEquals(0, old.start);
        assertEquals(recent.start, old.end);
        assertEquals("first", old.events.get(0).text);
        l.add(new Ev(EventList.Kind.CONTENT, "new", null));
        assertEquals(2502, l.pageBefore(recent.end, 2).end);
        assertEquals(2503, l.size());
        assertEquals("x", l.snapshot().get(1).text);
    }

    @Test
    public void historyPagingVisitsEveryEventOnce() {
        EventList l = new EventList();
        for (int i = 0; i < 2464; i++) l.add(new Ev(EventList.Kind.USER_MESSAGE, "m" + i, null));
        int cursor = l.size(), count = 0;
        while (cursor > 0) {
            EventList.Page p = l.pageBefore(cursor, 80);
            assertEquals(cursor, p.end);
            assertTrue(p.start < cursor);
            for (int i = 0; i < p.events.size(); i++) assertEquals("m" + (p.start + i), p.events.get(i).text);
            count += p.events.size();
            cursor = p.start;
        }
        assertEquals(2464, count);
        assertTrue(l.pageBefore(0, 80).events.isEmpty());
    }

    @Test
    public void emptyReasoningDeltasDoNotSplitReply() {
        EventList l = new EventList();
        l.add(new Ev(EventList.Kind.USER_MESSAGE, "question", null));
        for (int i = 0; i < 1000; i++) {
            l.add(new Ev(EventList.Kind.THINKING, "", null));
            l.add(new Ev(EventList.Kind.CONTENT, "x", null));
        }
        EventList.Page p = l.pageBefore(l.size(), 2);
        assertEquals(0, p.start);
        assertEquals(2, p.events.size());
        assertEquals(1000, p.events.get(1).text.length());
    }

    private EventList newList() { return new EventList(); }

    /** inactive 时事件只入缓冲，不直通 */
    @Test
    public void inactive_buffersEvents() {
        EventList l = newList();
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.add(new Ev(EventList.Kind.CONTENT, "b", null));
        assertEquals(2, l.size());
        assertTrue(seen.isEmpty());
    }

    /** 激活时重放全部存量，之后新事件直通 */
    @Test
    public void activate_replaysThenStreams() {
        EventList l = newList();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.add(new Ev(EventList.Kind.THINKING, "t", null));
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        assertEquals(2, seen.size());
        l.add(new Ev(EventList.Kind.ERROR, "e", null));
        assertEquals(3, seen.size());
        assertEquals("e", seen.get(2).text);
        assertEquals(3, l.size());
    }

    /** 事件顺序保持（流式 delta 顺序敏感） */
    @Test
    public void preservesOrder() {
        EventList l = newList();
        l.setActive(false, null);
        for (int i = 0; i < 5; i++) l.add(new Ev(EventList.Kind.CONTENT, "d" + i, null));
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        for (int i = 0; i < 5; i++) assertEquals("d" + i, seen.get(i).text);
    }

    /** 清空后重放为空 */
    @Test
    public void clear_emptiesBuffer() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.clear();
        assertEquals(0, l.size());
    }

    /** rebind 从 from 起增量快照，且注册直通（视图缓存增量重放路径） */
    @Test
    public void rebind_returnsTailFromAndStreamsNew() {
        EventList l = newList();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.USER_MESSAGE, "u1", null));
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.add(new Ev(EventList.Kind.CONTENT, "b", null));
        final List<Ev> seen = new ArrayList<Ev>();
        // 游标=2：只重放最后 1 个存量
        List<Ev> tail = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        }, 2);
        assertEquals(1, tail.size());
        assertEquals("b", tail.get(0).text);
        assertTrue("存量重放走快照不直通", seen.isEmpty());
        // 注册后新事件直通
        l.add(new Ev(EventList.Kind.CONTENT, "c", null));
        assertEquals(1, seen.size());
        assertEquals("c", seen.get(0).text);
    }

    /** 二次 rebind 以新游标为起点，不重复渲染已直通事件 */
    @Test
    public void rebind_secondTimeDoesNotRepeat() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        List<Ev> t1 = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 0);
        assertEquals(1, t1.size());
        // 切走：解绑；期间事件只入缓冲
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "b", null));
        // 切回：游标=上次 size(1)，只重放 b
        List<Ev> t2 = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 1);
        assertEquals(1, t2.size());
        assertEquals("b", t2.get(0).text);
    }

    /** from 越界/负数 clamp：不越界、不丢 */
    @Test
    public void rebind_clampsFrom() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        assertEquals(0, l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 99).size()); // 越界：空快照
        assertEquals(1, l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, -1).size()); // 负数：从 0 全量
    }

    /** SYSTEM（斜杠命令结果）与非激活缓冲机制兼容：不激活入缓冲，激活重放 */
    @Test
    public void system_buffersAndReplays() {
        EventList l = newList();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.SYSTEM, "已加载技能: x", null));
        assertEquals(1, l.size());
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        assertEquals(1, seen.size());
        assertEquals(EventList.Kind.SYSTEM, seen.get(0).kind);
        assertEquals("已加载技能: x", seen.get(0).text);
    }

    @Test
    public void rebindRecent_limitsLongHistoryButKeepsNewEvents() {
        EventList l = newList();
        for (int i = 0; i < 10000; i++) {
            l.add(new Ev(EventList.Kind.USER_MESSAGE, "history-" + i, null));
        }
        final List<Ev> live = new ArrayList<Ev>();
        EventList.Replay replay = l.rebindRecent(live::add, 0, 200, 300000);
        assertEquals(200, replay.events.size());
        assertEquals(9800, replay.skipped);
        assertEquals(10000, replay.nextIndex);
        assertEquals("history-9800", replay.events.get(0).text);
        assertEquals("history-9999", replay.events.get(199).text);
        assertEquals(10000, l.size()); // 原始历史未裁剪
        l.add(new Ev(EventList.Kind.CONTENT, "new", null));
        assertEquals(1, live.size());
        assertEquals("new", live.get(0).text);
    }

    @Test
    public void rebindRecent_appliesCharacterBudgetAndPreservesCursor() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.TOOL_RESULT, "a", "12345"));
        l.add(new Ev(EventList.Kind.TOOL_RESULT, "b", "12345"));
        l.add(new Ev(EventList.Kind.CONTENT, "c", null));
        EventList.Replay replay = l.rebindRecent(e -> { }, 0, 10, 7);
        assertEquals(2, replay.events.size());
        assertEquals("b", replay.events.get(0).text);
        assertEquals(1, replay.skipped);
        assertEquals(3, replay.nextIndex);
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "d", null));
        EventList.Replay second = l.rebindRecent(e -> { }, replay.nextIndex, 10, 7);
        assertEquals(1, second.events.size());
        assertEquals("d", second.events.get(0).text);
        assertEquals(0, second.skipped);
    }
}
