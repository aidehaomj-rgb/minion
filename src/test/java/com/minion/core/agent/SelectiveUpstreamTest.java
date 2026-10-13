package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.llm.ToolCall;
import com.minion.gui.session.EventList;
import com.minion.gui.session.SessionController;
import org.junit.Test;
import static org.junit.Assert.*;

public class SelectiveUpstreamTest {
    @Test public void childrenDoNotEmitMainEventsOrRetryState() {
        SessionController controller=new SessionController();
        final int[] retries={0};controller.setRetryStateListener(p->retries[0]++);
        ChildAgentUi a=new ChildAgentUi(controller,"child-a"),b=new ChildAgentUi(controller,"child-b");
        controller.onContent("main");a.onThinking("thinking-a");b.onSubAgentDelta("reply-b");
        a.onToolCall("Read",new JsonObject());b.onWarning("child-warning");a.onRetryProgress(RetryProgress.none());
        assertEquals(0,retries[0]);
        java.util.List<EventList.Ev> list=controller.eventList().snapshot();
        assertNull(list.get(0).subAgentId);
        assertEquals("child-a",list.get(1).subAgentId);
        assertEquals(EventList.Kind.THINKING,list.get(1).kind);
        assertEquals("child-b",list.get(2).subAgentId);
        assertEquals("child-a",list.get(3).subAgentId);
        assertEquals("child-b",list.get(4).subAgentId);
    }
    @Test public void pagingDoesNotMergeDifferentAgents() {
        EventList list=new EventList();
        list.add(new EventList.Ev(EventList.Kind.CONTENT,"main",null));
        list.add(new EventList.Ev(EventList.Kind.CONTENT,"a1",null,"a"));
        list.add(new EventList.Ev(EventList.Kind.CONTENT,"a2",null,"a"));
        list.add(new EventList.Ev(EventList.Kind.CONTENT,"b1",null,"b"));
        EventList.Page p=list.pageBefore(list.size(),150);
        assertEquals(3,p.events.size());assertEquals("a1a2",p.events.get(1).text);
        assertEquals("a",p.events.get(1).subAgentId);assertEquals("b",p.events.get(2).subAgentId);
    }
    @Test public void fullReadIsExplicitAndDoesNotChangeNormalBudget() {
        String text=new String(new char[50000]).replace('\0','x');
        ToolOutputStore store=new ToolOutputStore(System.getProperty("java.io.tmpdir"),"full-read-test");
        ToolCall c=new ToolCall();c.name="Read";c.arguments="{\"full\":true}";
        assertEquals(text,store.forContext(text,c));
        c.arguments="{}";assertNotEquals(text,store.forContext(text,c));
        c.name="Bash";c.arguments="{\"full\":true}";assertNotEquals(text,store.forContext(text,c));
    }
}
