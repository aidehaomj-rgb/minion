package com.minion.gui.session;

import com.minion.core.agent.Session;
import com.minion.core.config.*;
import com.minion.core.llm.*;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class StartupLazyTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Path jar;
    private WorkspaceManager ws;
    private SessionStore store;
    @Before public void setup() throws Exception {
        jar=tmp.getRoot().toPath(); ws=WorkspaceManager.load(jar);
        store=new SessionStore(WorkspaceManager.sessionDirFor(jar,ws.currentName()));
    }
    private Session save(String title) throws Exception {
        Session s=Session.create(jar.toString(),"deepseek-v4-flash"); s.title=title;
        s.messages.add(Message.user("原始用户要求"));
        s.messages.add(Message.assistant("已保存的正文"));
        Session.QueuedTask q=new Session.QueuedTask(); q.text="还未执行的队列"; s.queuedTasks.add(q);
        store.save(s); return s;
    }
    private static class Manager extends SessionManager {
        int clients;
        Manager(Path jar,WorkspaceManager ws) throws Exception {
            super(message->ConfirmUi.Decision.REJECT,Config.load(jar),jar,ws,
                    ModelManager.load(jar),new ArrayList<Skill>(),null,null);
        }
        @Override public LlmClient newLlm(ModelConfig mc) { clients++; return new FakeLlmClient(); }
    }
    @Test public void startupCreatesNoClientsAndRestoresOnlyOpenedHistory() throws Exception {
        Session first=save("第一项"); for(int i=0;i<12;i++)save("其他"+i);
        Manager m=new Manager(jar,ws);
        try {
            assertEquals(13,m.sessions().size()); assertEquals(0,m.clients);
            for(SessionHandle h:m.sessions()) {
                assertFalse(h.isLoaded()); assertTrue(h.session.messages.isEmpty());
                assertEquals("已保存的正文",h.listSummary);
                assertTrue(h.controller.eventList().snapshot().isEmpty());
            }
            SessionHandle h=m.findSession(first.id); m.activateSession(h);
            assertTrue(h.isLoaded()); assertSame(h,m.currentSession()); assertEquals(1,m.clients);
            assertEquals(2,h.session.messages.size()); assertEquals(1,h.queuedSnapshot().size());
            m.activateSession(h); assertEquals(1,m.clients);
            m.applyModelChanged();
            assertEquals("设置刷新只应重建打开过的客户端",2,m.clients);
        } finally {m.shutdown();}
        assertEquals(1,store.load(first.id).queuedTasks.size());
    }
    @Test public void renameAndPinDoNotOverwriteUnopenedMessageOrQueue() throws Exception {
        Session s=save("原名称"); Manager m=new Manager(jar,ws);
        try {
            SessionHandle h=m.findSession(s.id); m.renameSession(h,"新名称"); m.setPinned(h,true);
            Session loaded=store.load(s.id);
            assertEquals("新名称",loaded.title); assertTrue(loaded.pinned);
            assertEquals(2,loaded.messages.size()); assertEquals(1,loaded.queuedTasks.size());
        } finally {m.shutdown();}
    }
    @Test public void deletingUnopenedSessionDoesNotCreateRuntime() throws Exception {
        Session s=save("删除"); Manager m=new Manager(jar,ws);
        try {m.deleteSession(m.findSession(s.id)); assertEquals(0,m.clients); assertTrue(m.sessions().isEmpty());}
        finally {m.shutdown();}
        assertTrue(store.list().isEmpty());
    }
    @Test public void damagedTailDoesNotBlockStartupAndCannotOverwriteSnapshot() throws Exception {
        Session s=save("有问题的会话"); store.appendMessage(s,Message.user("未写快照的尾部"));
        Path event=WorkspaceManager.sessionDirFor(jar,ws.currentName()).resolve(".events").resolve(s.id).resolve("0000000000000003.json");
        Files.write(event,"broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] before=Files.readAllBytes(WorkspaceManager.sessionDirFor(jar,ws.currentName()).resolve(s.id+".json"));
        Manager m=new Manager(jar,ws);
        try {
            SessionHandle h=m.findSession(s.id); assertNotNull(h); assertEquals(0,m.clients);
            assertFalse(m.ensureLoaded(h)); m.renameSession(h,"不能覆盖");
            assertNull(m.currentSession()); assertFalse(h.isLoaded());
        } finally {m.shutdown();}
        assertArrayEquals(before,Files.readAllBytes(WorkspaceManager.sessionDirFor(jar,ws.currentName()).resolve(s.id+".json")));
    }
}
