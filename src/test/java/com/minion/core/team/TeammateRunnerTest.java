package com.minion.core.team;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** §8.3 TeammateRunner 验收：启动/状态转换/shutdown_request 停止/stopAll（不跑实际 LLM） */
public class TeammateRunnerTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 启动 teammate → 邮箱收到 shutdown_request → 线程停止 */
    @Test public void teammateLifecycleWithShutdown() throws Exception {
        Path work = tmp.newFolder().toPath();
        com.minion.core.config.Config config =
                com.minion.core.config.Config.load(work);
        TeammateRunner runner = new TeammateRunner(work.toString(), config);

        // 建团队 + 启动 teammate
        TeamMailbox mailbox = new TeamMailbox(
                work.resolve(".minion").resolve("teams"), "test");
        mailbox.create(java.util.Arrays.asList("lead", "worker1"));
        TeammateRunner.Teammate t = runner.start("test", "worker1");
        assertNotNull(t);
        assertTrue(t.thread.isAlive());
        assertEquals("worker1", t.name);

        // 发 shutdown_request → 等线程退出
        mailbox.send("lead", "worker1", "shutdown_request", "收工");
        long deadline = System.currentTimeMillis() + 10000;
        while (t.thread.isAlive() && System.currentTimeMillis() < deadline)
            Thread.sleep(200);
        assertFalse("teammate 应在 shutdown_request 后停止", t.thread.isAlive());
        assertEquals("stopped", t.status);
        runner.stopAll();
    }

    /** start 同名 teammate 幂等（活线程不重复启动） */
    @Test public void startSameNameIsIdempotent() throws Exception {
        Path work = tmp.newFolder().toPath();
        com.minion.core.config.Config config =
                com.minion.core.config.Config.load(work);
        TeammateRunner runner = new TeammateRunner(work.toString(), config);
        com.minion.core.team.TeammateRunner.Teammate first = runner.start("test", "w");
        com.minion.core.team.TeammateRunner.Teammate second = runner.start("test", "w");
        assertSame(first, second);
        runner.stopAll();
    }
}
