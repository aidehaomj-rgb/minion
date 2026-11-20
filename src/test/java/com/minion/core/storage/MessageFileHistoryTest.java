package com.minion.core.storage;

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

/** 消息级文件快照验收（v2.4 §9.2）：跟踪/回退/哈希判变化/null 备份/上限淘汰/索引持久化 */
public class MessageFileHistoryTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private MessageFileHistory history() throws IOException { return new MessageFileHistory(tmp.newFolder("fh").toPath()); }

    private Path file(String name, String content) throws IOException {
        Path p = tmp.getRoot().toPath().resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    @Test public void trackSnapshotsAndRestoreModifiedFile() throws Exception {
        MessageFileHistory h = history();
        Path f = file("a.txt", "v1");
        h.track("m1", Arrays.asList(f));
        // 外部修改后，当前哈希与记录不一致 → 计划为 restore
        Files.write(f, "v2-changed".getBytes(StandardCharsets.UTF_8));
        List<MessageFileHistory.RestoreItem> plan = h.planRestore("m1");
        assertEquals(1, plan.size());
        assertEquals("restore", plan.get(0).status);
        h.restoreFile(plan.get(0));
        assertEquals("v1", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        // 未变化 → unchanged 不写盘
        assertEquals("unchanged", h.planRestore("m1").get(0).status);
    }

    /** null 备份：跟踪时不存在 → 回退时若已存在则删除；仍不存在 → already-absent */
    @Test public void nullBackupHandlesCreatedFile() throws Exception {
        MessageFileHistory h = history();
        Path f = tmp.getRoot().toPath().resolve("created-later.txt");
        h.track("m1", Arrays.asList(f));
        assertEquals("already-absent", h.planRestore("m1").get(0).status);
        Files.write(f, "created after".getBytes(StandardCharsets.UTF_8));
        MessageFileHistory.RestoreItem item = h.planRestore("m1").get(0);
        assertEquals("delete-on-restore", item.status);
        assertTrue(item.nullBackup);
        h.restoreFile(item);
        assertFalse(Files.exists(f));
    }

    /** mtime 变化但哈希一致 → unchanged（mtime 不能单独证明变化，v2.2 修订） */
    @Test public void mtimeOnlyChangeIsUnchanged() throws Exception {
        MessageFileHistory h = history();
        Path f = file("b.txt", "same");
        h.track("m1", Arrays.asList(f));
        Files.write(f, "same".getBytes(StandardCharsets.UTF_8));   // 同内容重写 → mtime 变
        assertEquals("unchanged", h.planRestore("m1").get(0).status);
    }

    /** 上限 100：淘汰最旧并清理物理备份 */
    @Test public void evictionKeepsHundredAndDeletesPhysical() throws Exception {
        MessageFileHistory h = history();
        Path f = file("c.txt", "x");
        h.track("m0", Arrays.asList(f));
        assertTrue(Files.isRegularFile(tmp.getRoot().toPath().resolve("fh").resolve(
                MessageFileHistory.pathKey(f) + "@v1")));
        for (int i = 1; i <= MessageFileHistory.MAX_SNAPSHOTS; i++) h.track("m" + i, new ArrayList<Path>());
        assertNull(h.snapshot("m0"));   // 被淘汰
        assertNotNull(h.snapshot("m" + MessageFileHistory.MAX_SNAPSHOTS));
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("fh").resolve(
                MessageFileHistory.pathKey(f) + "@v1")));   // 物理备份清理
    }

    /** 索引持久化：新实例读回同一状态 */
    @Test public void indexPersistsAcrossInstances() throws Exception {
        Path root = tmp.newFolder("fh2").toPath();
        MessageFileHistory h1 = new MessageFileHistory(root);
        Path f = file("d.txt", "persist");
        h1.track("m1", Arrays.asList(f));
        MessageFileHistory h2 = new MessageFileHistory(root);
        assertNotNull(h2.snapshot("m1"));
        assertEquals("unchanged", h2.planRestore("m1").get(0).status);
        // serial 继续：第二条快照序号不回退
        h2.track("m2", new ArrayList<Path>());
        assertEquals(2, h2.snapshot("m2").serial);
    }

    /** 损坏索引按空重建（fail-closed 不抛异常） */
    @Test public void corruptIndexRebuildsEmpty() throws Exception {
        Path root = tmp.newFolder("fh3").toPath();
        Files.createDirectories(root);
        Files.write(root.resolve("history-index.json"), "not json".getBytes(StandardCharsets.UTF_8));
        MessageFileHistory h = new MessageFileHistory(root);
        assertTrue(h.snapshots().isEmpty());
        h.track("m1", new ArrayList<Path>());   // 重建后可继续
        assertNotNull(h.snapshot("m1"));
    }
}
