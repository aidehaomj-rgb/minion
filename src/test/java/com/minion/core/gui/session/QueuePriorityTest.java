package com.minion.gui.session;

import com.minion.core.agent.Session;
import com.minion.core.llm.FakeLlmClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** §2.3 三档排队验收：next 插队首 / later 队尾 / now 队首标记；/queue add 三档语法解析 */
public class QueuePriorityTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private SessionHandle handle() throws Exception {
        Session s = Session.create(tmp.newFolder().toPath().toString(), "m");
        return new SessionHandle("sid123456789", "ws", s, null,
                new SessionController(), "t", false, new FakeLlmClient());
    }

    @Test public void nextJumpsHeadAndLaterTails() throws Exception {
        SessionHandle h = handle();
        h.enqueue("L1", null, false, "later");
        h.enqueue("N1", null, false, "next");
        h.enqueue("L2", null, false, "later");
        List<SessionHandle.QueuedMessage> order = h.queuedSnapshot();
        assertEquals("N1", order.get(0).text);
        assertEquals("L1", order.get(1).text);
        assertEquals("L2", order.get(2).text);
        // 默认 enqueue = later；非法档位同样回落 later
        h.enqueue("L3");
        h.enqueue("X", null, false, "whenever");
        order = h.queuedSnapshot();
        assertEquals("L3", order.get(3).text);
        assertEquals("X", order.get(4).text);
        // now 与 next 同为队首
        h.enqueue("URGENT", null, false, "now");
        assertEquals("URGENT", h.queuedSnapshot().get(0).text);
    }

    /** /queue add [now|next|later] 解析：接线优先通道时按档位透传；未接线按档位入队 */
    @Test public void queueCommandParsesPriority() throws Exception {
        final List<String> routed = new CopyOnWriteArrayList<String>();
        com.minion.gui.command.CommandDispatcher dispatcher =
                new com.minion.gui.command.CommandDispatcher()
                        .queuePriority((h, text, priority) -> routed.add(priority + ":" + text));
        SessionHandle h = handle();
        assertEquals("已按 now 优先级提交",
                dispatcher.dispatch(h, "/queue add now 紧急任务"));
        assertEquals("已按 next 优先级提交",
                dispatcher.dispatch(h, "/queue add NEXT 次优先"));
        assertEquals("now:紧急任务", routed.get(0));
        assertEquals("next:次优先", routed.get(1));
        // 单词「next」无正文：无法区分档位词与文本，按正文处理（later）
        assertEquals("已按 later 优先级提交", dispatcher.dispatch(h, "/queue add next"));
        assertEquals("later:next", routed.get(2));
        // 缺省档位=later（无档位词时整句为任务文本）
        dispatcher.dispatch(h, "/queue add 普通任务");
        assertEquals("later:普通任务", routed.get(3));
        // 未接线通道：普通入队带档位
        com.minion.gui.command.CommandDispatcher bare = new com.minion.gui.command.CommandDispatcher();
        SessionHandle h2 = handle();
        String added = bare.dispatch(h2, "/queue add next 插队任务");
        assertTrue(added.contains("位置"));
        assertEquals("插队任务", h2.queuedSnapshot().get(0).text);
    }
}
