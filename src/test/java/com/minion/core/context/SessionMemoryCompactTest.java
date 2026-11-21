package com.minion.core.context;

import com.minion.core.agent.Session;
import com.minion.core.agent.TaskState;
import com.minion.core.llm.Message;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** §5.7 会话记忆压缩验收：缓存写入/读取/三条件复用判定/任务锚点变化拒绝 */
public class SessionMemoryCompactTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Session session(String objective) {
        try {
            Session s = Session.create(tmp.newFolder().toPath().toString(), "m");
            s.taskState = new TaskState();
            s.taskState.objective = objective;
            return s;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void storeLoadRoundTrip() throws Exception {
        SessionMemoryCompact smc = new SessionMemoryCompact(tmp.newFolder().toPath().toString());
        smc.store("摘要内容", "hash123", 50, "分析数据");
        SessionMemoryCompact.Cached cached = smc.load();
        assertNotNull(cached);
        assertEquals("摘要内容", cached.summary);
        assertEquals("hash123", cached.coversHash);
        assertEquals(50, cached.coversMessages);
        assertEquals("分析数据", cached.taskAnchor);
        assertTrue(cached.generatedAt > 0);
    }

    /** 三条件复用：覆盖范围够 + 任务锚点匹配 → true */
    @Test public void reusableWhenAllConditionsMet() throws Exception {
        Path root = tmp.newFolder().toPath();
        SessionMemoryCompact smc = new SessionMemoryCompact(root.toString());
        smc.store("摘要", "h", 100, "分析");
        Session s = session("分析数据的完整任务");
        assertTrue(smc.reusable(smc.load(), s, 80));   // 覆盖 100 ≥ 80
    }

    /** 覆盖不足 → 拒绝 */
    @Test public void insufficientCoverageRejected() throws Exception {
        SessionMemoryCompact smc = new SessionMemoryCompact(tmp.newFolder().toPath().toString());
        smc.store("摘要", "h", 50, "分析");
        assertFalse(smc.reusable(smc.load(), session("分析"), 80));   // 覆盖 50 < 80
    }

    /** 任务锚点变化 → 拒绝 */
    @Test public void taskAnchorChangeRejected() throws Exception {
        SessionMemoryCompact smc = new SessionMemoryCompact(tmp.newFolder().toPath().toString());
        smc.store("摘要", "h", 100, "分析旧数据");
        Session s = session("完全不同的新任务");   // 不含旧锚点
        assertFalse(smc.reusable(smc.load(), s, 80));
    }

    /** 无缓存 / 损坏文件 → load 返回 null 不炸 */
    @Test public void emptyOrCorruptLoadIsNull() throws Exception {
        Path root = tmp.newFolder().toPath();
        SessionMemoryCompact empty = new SessionMemoryCompact(root.toString());
        assertNull(empty.load());
        Files.createDirectories(root.resolve(".minion"));
        Files.write(root.resolve(".minion").resolve("session-memory.json"),
                "corrupt".getBytes(StandardCharsets.UTF_8));
        assertNull(empty.load());
    }

    /** hashOf：同列表同哈希、不同内容不同哈希 */
    @Test public void hashStableAndSensitive() {
        List<Message> a = new ArrayList<Message>();
        a.add(Message.user("hello"));
        a.add(Message.user("world"));
        List<Message> b = new ArrayList<Message>();
        b.add(Message.user("hello"));
        b.add(Message.user("world"));
        List<Message> c = new ArrayList<Message>();
        c.add(Message.user("hello"));
        c.add(Message.user("CHANGED"));
        assertEquals(SessionMemoryCompact.hashOf(a, 2), SessionMemoryCompact.hashOf(b, 2));
        assertNotEquals(SessionMemoryCompact.hashOf(a, 2), SessionMemoryCompact.hashOf(c, 2));
        // 范围截断：只哈希前 1 条
        assertEquals(SessionMemoryCompact.hashOf(a, 1), SessionMemoryCompact.hashOf(c, 1));
    }
}
