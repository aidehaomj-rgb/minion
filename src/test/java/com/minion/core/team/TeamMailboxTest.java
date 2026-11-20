package com.minion.core.team;

import com.minion.core.team.TeamMailbox.Message;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 团队文件邮箱验收（v2.4 §8.3）：创建/收发/广播/游标去重/损坏重建/名称校验/删除。
 */
public class TeamMailboxTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private TeamMailbox box(String team) throws IOException {
        TeamMailbox mb = new TeamMailbox(tmp.newFolder("teams").toPath(), team);
        mb.create(Arrays.asList("lead", "dev1", "dev2"));
        return mb;
    }

    @Test public void createIsIdempotentAndListsMembers() throws Exception {
        TeamMailbox mb = box("alpha");
        mb.create(Arrays.asList("lead"));   // 重复创建不破坏
        assertEquals(Arrays.asList("dev1", "dev2", "lead"), mb.members());
    }

    @Test public void sendAndReceiveWithCursorDedup() throws Exception {
        TeamMailbox mb = box("alpha");
        mb.send("lead", "dev1", "text", "任务A");
        mb.send("lead", "dev1", "shutdown_request", "收尾");
        List<Message> first = mb.receive("dev1");
        assertEquals(2, first.size());
        assertEquals("任务A", first.get(0).text);
        assertEquals("shutdown_request", first.get(1).type);
        // ack 到最后一条后重读为空（游标持久化，重启不重复消费）
        mb.ack("dev1", first.get(1).messageId);
        assertTrue(mb.receive("dev1").isEmpty());
        // 新实例（模拟重启）同样不重复
        TeamMailbox reopened = new TeamMailbox(tmp.getRoot().toPath().resolve("teams"), "alpha");
        assertTrue(reopened.receive("dev1").isEmpty());
        // 未参与的成员不受影响
        assertTrue(mb.receive("dev2").isEmpty());
    }

    /** ack 前重读返回同批（at-least-once） */
    @Test public void receiveWithoutAckReplays() throws Exception {
        TeamMailbox mb = box("beta");
        mb.send("dev1", "lead", "text", "done");
        assertEquals(1, mb.receive("lead").size());
        assertEquals(1, mb.receive("lead").size());
    }

    /** 广播：除发送者外全送达，每人独立 messageId（各自 inbox 一份） */
    @Test public void broadcastReachesAllButSender() throws Exception {
        TeamMailbox mb = box("gamma");
        int n = mb.send("lead", TeamMailbox.BROADCAST, "text", "standup");
        assertEquals(2, n);
        assertEquals(0, mb.receive("lead").size());
        assertEquals(1, mb.receive("dev1").size());
        assertEquals(1, mb.receive("dev2").size());
        // 两条 messageId 不同（独立投递，不能因为去重互相吞）
        assertFalse(mb.receive("dev1").get(0).messageId.equals(mb.receive("dev2").get(0).messageId));
    }

    /** 发给不存在的成员：拒绝 */
    @Test public void sendToUnknownMemberFails() throws Exception {
        TeamMailbox mb = box("delta");
        try { mb.send("lead", "ghost", "text", "x"); fail(); }
        catch (IOException expected) { }
    }

    /** 损坏 inbox：按空重建（fail-closed 告警，不抛异常不丢团队） */
    @Test public void corruptInboxRebuildsAsEmpty() throws Exception {
        TeamMailbox mb = box("eps");
        mb.send("lead", "dev1", "text", "old");
        Path inbox = tmp.getRoot().toPath().resolve("teams").resolve("eps")
                .resolve("inboxes").resolve("dev1.json");
        Files.write(inbox, "not json".getBytes(StandardCharsets.UTF_8));
        assertTrue(mb.receive("dev1").isEmpty());
        // 重建后可继续收发
        mb.send("lead", "dev1", "text", "new");
        assertEquals(1, mb.receive("dev1").size());
    }

    /** 非法团队/成员名拒绝（路径穿越防护） */
    @Test public void invalidNamesRejected() throws Exception {
        try { new TeamMailbox(tmp.newFolder().toPath(), "../evil"); fail(); }
        catch (IllegalArgumentException expected) { }
        TeamMailbox mb = box("zeta");
        try { mb.send("lead", "a/b", "text", "x"); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    /** 游标指向已被淘汰/不存在的 messageId：返回全量（fail-open 到全读，由调用方幂等处理） */
    @Test public void staleCursorFallsBackToFullRead() throws Exception {
        TeamMailbox mb = box("eta");
        mb.send("lead", "dev1", "text", "m1");
        List<Message> got = mb.receive("dev1");
        mb.ack("dev1", got.get(0).messageId);
        // 手工重置 inbox（模拟历史清理）后 cursor 悬空
        writeInbox("eta", "dev1", "[]");
        mb.send("lead", "dev1", "text", "m2");
        List<Message> after = mb.receive("dev1");
        assertEquals(1, after.size());
        assertEquals("m2", after.get(0).text);
    }

    /** 删除团队：目录整体清除 */
    @Test public void deleteTeamRemovesEverything() throws Exception {
        TeamMailbox mb = box("theta");
        mb.send("lead", "dev1", "text", "bye");
        mb.deleteTeam();
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("teams").resolve("theta")));
        assertTrue(mb.members().isEmpty());
    }

    private void writeInbox(String team, String agent, String json) throws IOException {
        Path inbox = tmp.getRoot().toPath().resolve("teams").resolve(team)
                .resolve("inboxes").resolve(agent + ".json");
        Files.write(inbox, json.getBytes(StandardCharsets.UTF_8));
    }
}
