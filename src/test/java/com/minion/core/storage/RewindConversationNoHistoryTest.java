package com.minion.core.storage;

import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;

import static org.junit.Assert.*;

/** §9.3 /rewind conversation 模式在无文件快照库（history=null）下可用——命令入口口径 */
public class RewindConversationNoHistoryTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void conversationModeWorksWithoutHistory() throws Exception {
        Session s = Session.create(tmp.newFolder().toPath().toString(), "m");
        s.messages = new ArrayList<Message>();
        for (int i = 0; i < 3; i++) {
            Message m = Message.user("消息 " + i);
            m.eventId = "e" + i;
            s.messages.add(m);
        }
        RewindService svc = new RewindService(null, tmp.newFolder().toPath());
        RewindService.Result r = svc.rewind(s, "e1", "conversation", null);
        assertTrue(r.ok);
        assertEquals(2, s.messages.size());   // 截到 e1（含）
        // code/both 在无快照库时拒绝执行（诚实失败）
        RewindService.Result denied = svc.rewind(s, "e1", "code", null);
        assertFalse(denied.ok);
    }
}
