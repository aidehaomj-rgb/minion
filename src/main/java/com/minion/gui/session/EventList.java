package com.minion.gui.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话 UI 事件流：会话激活时事件直通监听器（FX 线程包装由监听器负责），
 * 未激活（切到其他会话/工作空间）时只入缓冲；切换回来 setActive(true) 重放全部存量。
 * 纯逻辑、无 JavaFX 依赖，可单测。
 */
public class EventList {

    public enum Kind {
        USER_MESSAGE, USER_SUPPLEMENT, THINKING, CONTENT, TOOL_CALL, TOOL_RESULT, RESPONSE_END,
        SUB_AGENT_START, SUB_AGENT_DELTA, SUB_AGENT_DONE, STATS, SYSTEM, ERROR, WARNING
    }

    public static class Ev {
        public final Kind kind;
        public final String text;
        public final Object data;
        public final String subAgentId;

        public Ev(Kind kind, String text, Object data) {
            this(kind,text,data,null);
        }
        public Ev(Kind kind, String text, Object data, String subAgentId) {
            this.kind = kind;
            this.text = text;
            this.data = data;
            this.subAgentId = subAgentId;
        }
    }

    public interface Listener {
        void onEvent(Ev e);
    }

    private final List<Ev> events = new ArrayList<Ev>();
    private volatile boolean active = false;
    private volatile Listener listener;

    /** 激活：重放存量后直通；去激活：listener 置空，事件只入缓冲 */
    public synchronized void setActive(boolean active, Listener listener) {
        this.active = active;
        this.listener = active ? listener : null;
        if (active && listener != null) {
            for (Ev e : events) listener.onEvent(e);
        }
    }

    /**
     * 增量重放 + 注册直通（原子）：锁内快照 [from, size) 并设 active/listener。
     * 拆两步有竞态——先取尾部快照后注册，间隙 add 的事件会丢（进缓冲但 listener 未注册）；
     * 先注册后取快照，间隙 add 的事件重复（既入缓冲又直通）。锁内一步完成两者，add 同锁互斥。
     * 返回 [from, size) 的副本，调用方锁外渲染（新增直通事件经 runLater 入队，顺序由 FX 队列 FIFO 保证）。
     */
    public synchronized List<Ev> rebind(Listener listener, int from) {
        this.active = true;
        this.listener = listener;
        int n = events.size();
        if (from >= n) return new ArrayList<Ev>();
        return new ArrayList<Ev>(events.subList(Math.max(0, from), n));
    }

    /**
     * 聊天视图只显示最近的事件。限制首次/重绑重放量，避免长会话一次创建成千上万个
     * JavaFX TextArea；完整事件和会话消息仍保留，模型上下文不受影响。
     * 快照、游标和直通监听器在同一把锁下确定，避免重绑间隙丢失或重复事件。
     */
    public synchronized Replay rebindRecent(Listener listener, int from, int maxEvents, int maxChars) {
        int end = events.size();
        int requested = Math.min(end, Math.max(0, from));
        int start = Math.max(requested, end - Math.max(1, maxEvents));
        long chars = 0;
        for (int i = end - 1; i >= start; i--) {
            Ev e = events.get(i);
            long cost = (e.text == null ? 0 : e.text.length())
                    + (e.data == null ? 0 : e.data.toString().length());
            if (i < end - 1 && chars + cost > Math.max(1, maxChars)) {
                start = i + 1;
                break;
            }
            chars += cost;
        }
        this.active = true;
        this.listener = listener;
        return new Replay(new ArrayList<Ev>(events.subList(start, end)), end, start - requested);
    }

    public static class Replay {
        public final List<Ev> events;
        public final int nextIndex;
        public final int skipped;

        Replay(List<Ev> events, int nextIndex, int skipped) {
            this.events = events;
            this.nextIndex = nextIndex;
            this.skipped = skipped;
        }
    }

    public synchronized void add(Ev e) {
        events.add(e);
        if (active && listener != null) listener.onEvent(e);
    }

    /** Page boundaries never split a contiguous thinking/content stream into token fragments. */
    public synchronized Page pageBefore(int before, int limit) {
        int end = Math.min(Math.max(0, before), events.size());
        int start = end;
        List<Ev> result = new ArrayList<Ev>();
        int visible = 0;
        while (start > 0 && visible < Math.max(1, limit)) {
            int last = start - 1;
            Ev e = events.get(last);
            if (emptyDelta(e)) { start--; continue; }
            int first = last;
            boolean stream = e.kind == Kind.CONTENT || e.kind == Kind.THINKING
                    || e.kind == Kind.SUB_AGENT_DELTA;
            if (stream) {
                while (first > 0 && java.util.Objects.equals(events.get(first - 1).subAgentId,e.subAgentId)
                        && (events.get(first - 1).kind == e.kind || emptyDelta(events.get(first - 1)))) first--;
                StringBuilder text = new StringBuilder();
                for (int i = first; i <= last; i++) {
                    if (events.get(i).kind == e.kind && events.get(i).text != null) text.append(events.get(i).text);
                }
                result.add(0, new Ev(e.kind, text.toString(), e.data,e.subAgentId));
            } else result.add(0, e);
            // 结束标记只维护流边界，不占用一条可见内容的预算。
            if (e.kind != Kind.RESPONSE_END) visible++;
            start = first;
        }
        return new Page(result, start, end);
    }

    private static boolean emptyDelta(Ev e) {
        return (e.kind == Kind.CONTENT || e.kind == Kind.THINKING || e.kind == Kind.SUB_AGENT_DELTA)
                && (e.text == null || e.text.isEmpty());
    }

    public static class Page {
        public final List<Ev> events;
        public final int start, end;
        Page(List<Ev> events, int start, int end) {
            this.events = events;
            this.start = start;
            this.end = end;
        }
    }

    public synchronized List<Ev> snapshot() { return new ArrayList<Ev>(events); }

    public synchronized void clear() { events.clear(); }

    public synchronized int size() { return events.size(); }
}
