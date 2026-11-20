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
import java.util.List;

import static org.junit.Assert.*;

/**
 * Transcript JSONL 层验收（v2.4 §9.1）：追加与 seq 连续、半行/缺口/重复 eventId 的
 * fail-closed 恢复、跨 workspace 隔离、SessionStore 双写（开关开）与 legacy 零行为变化。
 */
public class TranscriptJsonlTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Session session(String id) {
        Session s = Session.create(tmp.getRoot().getAbsolutePath(), "test-model");
        s.id = id;
        return s;
    }

    private Message user(String text) {
        Message m = Message.user(text);
        return m;
    }

    @Test public void appendAssignsSequentialSeqAndParentChain() throws Exception {
        Path root = tmp.newFolder("transcripts").toPath();
        TranscriptJsonl t = new TranscriptJsonl(root, "s1");
        Session s = session("s1");
        t.append(s, "message", new TranscriptJsonl.MessagePayload(user("a")));
        t.append(s, "message", new TranscriptJsonl.MessagePayload(user("b")));
        t.append(s, "message", new TranscriptJsonl.MessagePayload(user("c")));
        assertEquals(3, t.tailSeq());
        assertTrue(t.verifyConsistent());
        // tailAfter：快照 seq=1 之后有 2 条未落快照
        assertEquals(2, t.tailAfter(1).size());
        assertEquals(0, t.tailAfter(3).size());
    }

    /** 半行（文件尾无换行，崩溃写入）：备份+忽略，其余完整行可恢复 */
    @Test public void partialTailLineIsBackedUpAndIgnored() throws Exception {
        Path root = tmp.newFolder("transcripts").toPath();
        TranscriptJsonl t = new TranscriptJsonl(root, "s2");
        Session s = session("s2");
        t.append(s, "message", new TranscriptJsonl.MessagePayload(user("a")));
        t.append(s, "message", new TranscriptJsonl.MessagePayload(user("b")));
        // 注入半行
        Files.write(root.resolve("s2.jsonl"), "{\"schemaVersion\":1,\"seq\":3".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        assertTrue(t.verifyConsistent());          // 完整的 2 行仍可重放；半行已备份
        assertEquals(2, t.tailSeq());             // 半行不计入
        // 备份文件存在
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root, "s2.jsonl.corrupt-*")) {
            assertTrue(ds.iterator().hasNext());
        }
    }

    /** seq 缺口 / 重复 eventId：整体 fail-closed（备份整个文件，返回不可重放） */
    @Test public void seqGapAndDuplicateEventIdAreFailClosed() throws Exception {
        Path root = tmp.newFolder("t3").toPath();
        Session s = session("s3");
        // 手工构造缺口文件（seq 1,3）
        Files.write(root.resolve("s3.jsonl"), (
                line(1) + "\n" + line(3) + "\n").getBytes(StandardCharsets.UTF_8));
        assertFalse(new TranscriptJsonl(root, "s3").verifyConsistent());
        // 重复 eventId
        Files.write(root.resolve("s4.jsonl"), (
                line(1) + "\n" + line(1) + "\n").getBytes(StandardCharsets.UTF_8));
        assertFalse(new TranscriptJsonl(root, "s4").verifyConsistent());
        // 未知主版本
        Files.write(root.resolve("s5.jsonl"), "{\"schemaVersion\":9}\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(new TranscriptJsonl(root, "s5").verifyConsistent());
        // 损坏文件都被备份
        for (String id : new String[]{"s3", "s4", "s5"}) {
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root, id + ".jsonl.corrupt-*")) {
                assertTrue(id, ds.iterator().hasNext());
            }
        }
    }

    /** 跨 workspace 隔离：同 sessionId 在不同 root 互不可见 */
    @Test public void workspacesAreIsolatedByIdDirectory() throws Exception {
        Path wsA = tmp.newFolder("wsA").toPath();
        Path wsB = tmp.newFolder("wsB").toPath();
        new TranscriptJsonl(wsA, "dup").append(session("dup"), "message", new TranscriptJsonl.MessagePayload(user("in-a")));
        assertEquals(1, new TranscriptJsonl(wsA, "dup").tailSeq());
        assertEquals(0, new TranscriptJsonl(wsB, "dup").tailSeq());   // B 里没有
    }

    /** SessionStore 双写（开关开）：appendMessage 写 journal+jsonl；load 校验一致性 */
    @Test public void sessionStoreDualWriteAndLegacyNeutral() throws Exception {
        Path sessions = tmp.newFolder("sessions").toPath();
        Path transcripts = tmp.newFolder("transcripts").toPath();
        SessionStore store = new SessionStore(sessions, transcripts);
        Session s = session("dual-1");
        Message m = user("hello");
        store.appendMessage(s, m);                 // 首条会触发 save → journal.record → jsonl.append
        assertTrue(Files.isRegularFile(transcripts.resolve("dual-1.jsonl")));
        Session loaded = store.load("dual-1");
        assertEquals(1, loaded.messages.size());
        assertEquals("hello", loaded.messages.get(0).content);

        // legacy：transcriptsRoot=null 零行为变化（无 jsonl 文件产生，load/save 照常）
        Path legacySessions = tmp.newFolder("legacy").toPath();
        SessionStore legacy = new SessionStore(legacySessions);
        Session ls = session("legacy-1");
        legacy.appendMessage(ls, user("old"));
        assertEquals(1, legacy.load("legacy-1").messages.size());
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(legacySessions)) {
            for (Path p : ds) assertTrue(p.getFileName().toString(), !p.getFileName().toString().endsWith(".jsonl"));
        }
    }

    /** delete 同步清理 jsonl */
    @Test public void deleteRemovesTranscriptToo() throws Exception {
        Path sessions = tmp.newFolder("s6").toPath();
        Path transcripts = tmp.newFolder("t6").toPath();
        SessionStore store = new SessionStore(sessions, transcripts);
        Session s = session("del-1");
        store.appendMessage(s, user("x"));
        assertTrue(Files.isRegularFile(transcripts.resolve("del-1.jsonl")));
        store.delete("del-1");
        assertFalse(Files.exists(transcripts.resolve("del-1.jsonl")));
    }

    /** 构造一行最小合法事件（固定 eventId 便于构造重复；seq 指定便于构造缺口） */
    private static String line(long seq) {
        return "{\"schemaVersion\":1,\"eventId\":\"fixed-" + seq + "\",\"sessionId\":\"s\",\"seq\":" + seq
                + ",\"timestamp\":1,\"type\":\"message\",\"payload\":{}}";
    }
}
