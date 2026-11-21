package com.minion.core.context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** §6.2 MemoryConsolidator 验收：三重门各自拦截、锁抢占/回滚、整理合并+索引重建 */
public class MemoryConsolidatorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path root() throws Exception { return tmp.newFolder().toPath(); }

    /** 时间门：上次整理 <24h 拦截 */
    @Test public void timeGateBlocksFreshConsolidation() throws Exception {
        Path project = root();
        MemoryConsolidator c = new MemoryConsolidator(project, null, 24);
        Path sessions = project.resolve("sessions");
        Files.createDirectories(sessions);
        for (int i = 0; i < 6; i++)
            Files.write(sessions.resolve("s" + i + ".json"), "{}".getBytes(StandardCharsets.UTF_8));
        // 首跑无锁：时间门过、会话门过、拿锁执行（空记忆目录无操作）
        String first = c.tryConsolidate(sessions, System.currentTimeMillis());
        assertNull(first);
        // 立即再跑：时间门拦截
        MemoryConsolidator again = new MemoryConsolidator(project, null, 24);
        String blocked = again.blockedBy(sessions, System.currentTimeMillis());
        assertNotNull(blocked);
        assertTrue(blocked.contains("时间门"));
    }

    /** 会话门：新会话 <5 拦截；排除当前会话 */
    @Test public void sessionGateRequiresFiveFreshSessions() throws Exception {
        Path project = root();
        Path sessions = project.resolve("sessions");
        Files.createDirectories(sessions);
        MemoryConsolidator c = new MemoryConsolidator(project, "current", 24);
        String blocked = c.blockedBy(sessions, System.currentTimeMillis());
        assertNotNull(blocked);
        assertTrue(blocked.contains("会话门"));
        // 5 个其他 + 1 个当前（不计入）
        for (int i = 0; i < 6; i++)
            Files.write(sessions.resolve((i == 0 ? "current" : "s" + i) + ".json"),
                    "{}".getBytes(StandardCharsets.UTF_8));
        String pass = c.sessionGate(sessions, System.currentTimeMillis());
        // 扫描节流：第二次 blockedBy 会命中节流——用新实例验证会话门通过
        assertNull(new MemoryConsolidator(project, "current", 24)
                .sessionGate(sessions, System.currentTimeMillis()));
    }

    /** 进程锁：死 PID（不存在的进程）可抢占；锁内容为本机 PID */
    @Test public void lockGateSeizesDeadHolderPid() throws Exception {
        Path project = root();
        Path lock = project.resolve(".minion").resolve(".consolidate-lock");
        Files.createDirectories(lock.getParent());
        Files.write(lock, "999999999".getBytes(StandardCharsets.UTF_8));   // 必死 PID
        Files.setLastModifiedTime(lock, java.nio.file.attribute.FileTime
                .fromMillis(System.currentTimeMillis() - 2 * 3600_000L));  // >1h 也允许抢占
        MemoryConsolidator c = new MemoryConsolidator(project, null, 24);
        String gate = c.lockGate(System.currentTimeMillis());
        assertNull(gate);
        String holder = new String(Files.readAllBytes(lock), StandardCharsets.UTF_8).trim();
        assertEquals(java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0],
                holder);
    }

    /** 整理：合并重复描述条目、删空文件、重建 MEMORY.md 索引 */
    @Test public void consolidateMergesDuplicatesAndRebuildsIndex() throws Exception {
        Path project = root();
        Path memory = project.resolve("memory");
        Files.createDirectories(memory);
        Files.write(memory.resolve("a.md"),
                "---\ndescription: 数据库连接约定\n---\n主机 10.0.0.1".getBytes(StandardCharsets.UTF_8));
        Files.write(memory.resolve("b.md"),
                "---\ndescription: 数据库连接约定\n---\n端口 3306（重复条目）".getBytes(StandardCharsets.UTF_8));
        Files.write(memory.resolve("empty.md"), "   ".getBytes(StandardCharsets.UTF_8));
        Files.write(memory.resolve("c.md"),
                "---\ndescription: 独立事实\n---\n内容 C".getBytes(StandardCharsets.UTF_8));
        new MemoryConsolidator(project, null, 24).consolidate();
        assertTrue(Files.isRegularFile(memory.resolve("a.md")));
        assertFalse(Files.isRegularFile(memory.resolve("b.md")));   // 并入 a
        assertFalse(Files.isRegularFile(memory.resolve("empty.md")));   // 空删除
        String merged = new String(Files.readAllBytes(memory.resolve("a.md")), StandardCharsets.UTF_8);
        assertTrue(merged.contains("3306"));
        assertTrue(merged.contains("合并自 b.md"));
        String index = new String(Files.readAllBytes(memory.resolve("MEMORY.md")), StandardCharsets.UTF_8);
        assertTrue(index.contains("数据库连接约定"));
        assertTrue(index.contains("独立事实"));
        assertFalse(index.contains("empty"));
    }
}
