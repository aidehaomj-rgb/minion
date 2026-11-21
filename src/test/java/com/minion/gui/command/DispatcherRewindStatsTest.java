package com.minion.gui.command;

import com.minion.core.agent.Session;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** /rewind 与 /stats 命令路由验收（§9.3/§13.3；假执行器隔离 SessionManager 依赖） */
public class DispatcherRewindStatsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final StringBuilder executed = new StringBuilder();

    private CommandDispatcher dispatcher() {
        return new CommandDispatcher()
                .stats(() -> "STATS-TEXT")
                .rewind(new CommandDispatcher.RewindApi() {
                    public String list(Session s) { return "LIST-OK"; }
                    public String execute(Session s, String eventId, String mode) {
                        executed.append(eventId).append('|').append(mode);
                        return "REWOUND " + eventId;
                    }
                });
    }

    private com.minion.gui.session.SessionHandle handle() throws Exception {
        Session s = Session.create(tmp.newFolder().toPath().toString(), "m");
        s.messages = new ArrayList<Message>();
        Message m = Message.user("hello");
        m.eventId = "e1";
        s.messages.add(m);
        return new com.minion.gui.session.SessionHandle("sid123456789", "ws", s,
                null, new com.minion.gui.session.SessionController(), "t", false, new FakeLlmClient());
    }

    @Test public void statsCommandRoutesToSupplier() throws Exception {
        assertEquals("STATS-TEXT", dispatcher().dispatch(handle(), "/stats"));
        assertEquals("STATS-TEXT", dispatcher().dispatch(handle(), "/统计"));
    }

    @Test public void rewindListsAndExecutes() throws Exception {
        CommandDispatcher d = dispatcher();
        assertEquals("LIST-OK", d.dispatch(handle(), "/rewind"));
        assertEquals("REWOUND e1", d.dispatch(handle(), "/rewind e1 conversation"));
        assertEquals("e1|conversation", executed.toString());
        // 默认 mode=conversation
        executed.setLength(0);
        assertEquals("REWOUND e2", d.dispatch(handle(), "/rewind e2"));
        assertEquals("e2|conversation", executed.toString());
        // 中文别名
        assertEquals("LIST-OK", d.dispatch(handle(), "/回退"));
    }

    /** 未接线时明确不可用（不抛异常） */
    @Test public void unwiredCommandsReportUnavailable() throws Exception {
        CommandDispatcher bare = new CommandDispatcher();
        assertTrue(bare.dispatch(handle(), "/stats").contains("不可用"));
        assertTrue(bare.dispatch(handle(), "/rewind").contains("不可用"));
    }
}
