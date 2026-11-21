package com.minion.core.security;

import com.minion.core.security.ReadStateStore.ContextReadLedger;
import com.minion.core.security.ReadStateStore.Entry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** 读状态存储验收（v2.4 §4.5）：LRU/预算淘汰、整文件哈希口径、账本失效语义 */
public class ReadStateStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path file(String name, String content) throws Exception {
        Path p = tmp.getRoot().toPath().resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    @Test public void contentHashIsWholeFileNotSlice() throws Exception {
        Path f = file("a.txt", "line1\nline2\nline3\n");
        ReadStateStore store = new ReadStateStore();
        store.record(ReadStateStore.keyOf(f), "line2\n", ReadStateStore.sha256(f),
                ReadStateStore.sha256("line2\n"), 0L, 1, 1, false, true);
        Entry e = store.get(ReadStateStore.keyOf(f));
        // contentHash = 整文件流式 SHA-256（切片外变化也能检出）
        assertEquals(ReadStateStore.sha256(f), e.contentHash);
        assertNotEquals(e.contentHash, e.rangeHash);
        // 切片外变化：整文件哈希变化
        Files.write(f, "line1\nline2-changed\nline3\n".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(ReadStateStore.sha256(f), e.contentHash);
    }

    @Test public void lruEvictsOverEntryLimit() throws Exception {
        ReadStateStore store = new ReadStateStore();
        Path f = file("b.txt", "x");
        String key = ReadStateStore.keyOf(f);
        store.record(key, "x", "h", "r", 0L, 0, 1, false, false);
        for (int i = 0; i < ReadStateStore.MAX_ENTRIES; i++)
            store.record("p" + i, null, "h", "r", 0L, 0, 1, false, false);
        assertEquals(ReadStateStore.MAX_ENTRIES, store.size());
        assertNull(store.get(key));   // 最旧（首条）被淘汰
    }

    @Test public void byteBudgetEvictsOldest() throws Exception {
        // 预算 100 字节：3 条 40 字节 content，第 3 条进入后第 1 条被淘汰
        ReadStateStore store = new ReadStateStore(100L);
        StringBuilder s40 = new StringBuilder();
        for (int i = 0; i < 40; i++) s40.append('a');
        store.record("k1", s40.toString(), "h", "r", 0L, 0, 1, false, false);
        store.record("k2", s40.toString(), "h", "r", 0L, 0, 1, false, false);
        store.record("k3", s40.toString(), "h", "r", 0L, 0, 1, false, false);
        assertNull(store.get("k1"));
        assertNotNull(store.get("k2"));
        assertNotNull(store.get("k3"));
    }

    @Test public void updateAfterWriteInvalidatesContentAndRefreshesHash() throws Exception {
        Path f = file("c.txt", "v1");
        ReadStateStore store = new ReadStateStore();
        String key = ReadStateStore.keyOf(f);
        store.record(key, "v1", ReadStateStore.sha256(f), "r", 1L, 0, 1, false, false);
        Files.write(f, "v2-longer".getBytes(StandardCharsets.UTF_8));
        store.updateAfterWrite(key, ReadStateStore.sha256(f), 2L);
        Entry e = store.get(key);
        assertNull(e.content);                                   // 旧正文作废
        assertEquals(ReadStateStore.sha256(f), e.contentHash);   // 新哈希
        assertTrue(e.readAt >= 0);
    }

    @Test public void ledgerInvalidateAllAndPerPath() {
        ContextReadLedger ledger = new ContextReadLedger();
        ledger.note("/a", 0, 10);
        ledger.note("/b", 5, 3);
        assertTrue(ledger.present("/a", 0, 10));
        ledger.invalidate("/a");
        assertFalse(ledger.present("/a", 0, 10));
        assertTrue(ledger.present("/b", 5, 3));
        ledger.invalidateAll();
        assertFalse(ledger.present("/b", 5, 3));
    }

    /** keyOf：符号链接/大小写别名不能绕过读状态（真实路径归一） */
    @Test public void keyOfNormalizesRealPath() throws Exception {
        Path f = file("d.txt", "x");
        Path alias = tmp.getRoot().toPath().resolve("alias-d.txt");
        try {
            Files.createSymbolicLink(alias, f);
            assertEquals(ReadStateStore.keyOf(f), ReadStateStore.keyOf(alias));
        } catch (java.nio.file.FileSystemException noSymlinkOnWin7NoPrivilege) {
            // 无符号链接权限的环境跳过本断言（Windows 创建符号链接需特权）
        }
        // 相对/绝对同源：normalize 后一致
        assertEquals(ReadStateStore.keyOf(f), ReadStateStore.keyOf(tmp.getRoot().toPath().resolve(".").resolve("d.txt")));
    }
}
