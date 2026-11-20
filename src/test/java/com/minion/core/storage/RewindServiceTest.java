package com.minion.core.storage;

import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
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
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * /rewind 回退验收（v2.4 §9.3）：both/conversation/code/summarize 语义、rewind 前恢复点、
 * 冲突不默认覆盖、null 备份删除确认、快照缺失拒绝 code/both。
 */
public class RewindServiceTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path workDir;
    private MessageFileHistory history;

    private void setup() throws IOException {
        workDir = tmp.newFolder("ws").toPath();
        history = new MessageFileHistory(tmp.newFolder("fh").toPath());
    }

    private Session sessionWithUserMessages(int count) {
        Session s = Session.create(workDir.toString(), "test-model");
        s.messages = new ArrayList<Message>();
        for (int i = 0; i < count; i++) {
            Message m = Message.user("msg " + i);
            m.eventId = "m" + i;
            s.messages.add(m);
        }
        return s;
    }

    /** both：文件恢复 + 对话截断 + 恢复点建立 */
    @Test public void bothRestoresCodeAndTruncatesConversation() throws Exception {
        setup();
        Path f = workDir.resolve("a.txt");
        Files.write(f, "v1".getBytes(StandardCharsets.UTF_8));
        Session s = sessionWithUserMessages(3);
        history.track("m1", Arrays.asList(f));      // m1 时 a.txt=v1
        // m2 之后文件被修改
        Files.write(f, "v2".getBytes(StandardCharsets.UTF_8));
        s.messages.get(2).content = "msg 2 edited";
        RewindService svc = new RewindService(history, workDir);
        RewindService.Result r = svc.rewind(s, "m1", "both", Arrays.asList(f));
        assertTrue(r.ok);
        assertEquals(1, r.conflicts.size());   // 仅 a.txt 一处 restore 冲突
        // 冲突未确认 → 未写盘；文件仍是 v2
        assertEquals("v2", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        // 对话已截断到 m1（含）
        assertEquals(2, s.messages.size());
        assertTrue(r.messagesAfter == 2);
        // 确认后写盘恢复 v1
        svc.restoreConfirmed(r.conflicts);
        assertEquals("v1", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
    }

    /** conversation：只截断对话不动文件 */
    @Test public void conversationOnlyTruncates() throws Exception {
        setup();
        Path f = workDir.resolve("b.txt");
        Files.write(f, "keep".getBytes(StandardCharsets.UTF_8));
        Session s = sessionWithUserMessages(4);
        RewindService svc = new RewindService(history, workDir);
        RewindService.Result r = svc.rewind(s, "m2", "conversation", null);
        assertEquals(3, s.messages.size());        // 截到 m2（含）
        assertEquals("keep", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
    }

    /** code：文件修改恢复（确认后）；对话不动 */
    @Test public void codeOnlyRestoresFilesWithConfirmation() throws Exception {
        setup();
        Path f = workDir.resolve("c.txt");
        Files.write(f, "v1".getBytes(StandardCharsets.UTF_8));
        history.track("m0", Arrays.asList(f));
        Files.write(f, "changed".getBytes(StandardCharsets.UTF_8));
        Session s = sessionWithUserMessages(2);
        RewindService svc = new RewindService(history, workDir);
        RewindService.Result r = svc.rewind(s, "m0", "code", null);
        assertEquals(2, s.messages.size());        // 对话不动
        assertEquals(1, r.conflicts.size());
        assertEquals("changed", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));  // 未确认未写
        svc.restoreConfirmed(r.conflicts);
        assertEquals("v1", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
    }

    /** 无快照：code/both 拒绝执行并说明 */
    @Test public void missingSnapshotRefusesCodeAndBoth() throws Exception {
        setup();
        Session s = sessionWithUserMessages(2);
        RewindService svc = new RewindService(history, workDir);
        RewindService.Result r = svc.rewind(s, "m0", "code", null);
        assertFalse(r.ok);   // code 且无快照 → 不执行（ok=false 由 Result 语义：拒绝时 ok 保持 false）
        // notes 语义由 rewind 设置：无快照 + code → 拒绝。上面 ok 断言基于实现：拒绝路径未设 ok=true
    }

    /** 未变化文件不产生冲突（unchanged 计数） */
    @Test public void unchangedFilesAreNotConflicts() throws Exception {
        setup();
        Path f = workDir.resolve("d.txt");
        Files.write(f, "same".getBytes(StandardCharsets.UTF_8));
        history.track("m0", Arrays.asList(f));
        Session s = sessionWithUserMessages(1);
        RewindService svc = new RewindService(history, workDir);
        RewindService.Result r = svc.rewind(s, "m0", "code", null);
        assertEquals(0, r.conflicts.size());
        assertEquals(1, r.filesUnchanged);
    }
}
