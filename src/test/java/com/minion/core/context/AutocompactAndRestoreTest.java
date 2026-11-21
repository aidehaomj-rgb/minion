package com.minion.core.context;

import com.minion.core.llm.Message;
import com.minion.core.security.ReadStateStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * §5.2/§5.3 验收：公式阈值与警告线、熔断（3 连败停自动、成功清零）、压缩后重读
 * （≤5 文件、按时间倒序、预算截断、读状态刷新、无可读返回空）。
 */
public class AutocompactAndRestoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ==================== §5.2 公式与熔断 ====================

    @Test public void formulaThresholds() {
        ContextManager cm = new ContextManager(131072, 0.8, 10, null, 0);
        cm.setOutputReserveTokens(8192);
        int legacy = cm.autoCompressAtTokens();
        cm.setAutocompactFormula(true);
        int formula = cm.autoCompressAtTokens();
        // 公式：131072 − min(8192,20000) − 13000 = 109880
        assertEquals(131072 - 8192 - 13000, formula);
        // 警告线 = 触发 − 20000（公式制）；legacy 与触发线相同
        assertEquals(formula - 20000, cm.warnAtTokens());
        cm.setAutocompactFormula(false);
        assertEquals(legacy, cm.warnAtTokens());
        assertTrue(legacy > 0);
    }

    @Test public void circuitBreakerTripsAfterThreeAndResetsOnSuccess() {
        ContextManager cm = new ContextManager(131072, 0.8, 10, null, 0);
        List<Message> big = new ArrayList<Message>();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 500; i++) longText.append("很长的一段中文文本用于撑大估算");
        for (int i = 0; i < 200; i++) big.add(Message.user(longText.toString()));
        assertTrue(cm.shouldCompress(big));   // 大历史 + 未熔断 → 判定为真
        cm.noteAutoCompressOutcome(false);
        cm.noteAutoCompressOutcome(false);
        assertFalse(cm.autoCompressTripped());
        cm.noteAutoCompressOutcome(false);
        assertTrue(cm.autoCompressTripped());
        // 熔断后 shouldCompress 恒 false（自动压缩停摆；手动 /compact 不走此判定）
        assertFalse(cm.shouldCompress(big));
        // 成功一次即恢复
        cm.noteAutoCompressOutcome(true);
        assertFalse(cm.autoCompressTripped());
        assertTrue(cm.shouldCompress(big));
    }

    // ==================== §5.3 压缩后重读 ====================

    private Path file(String name, String content) throws Exception {
        Path p = tmp.getRoot().toPath().resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    @Test public void restoresRecentFilesInRecencyOrderWithRefresh() throws Exception {
        ReadStateStore store = new ReadStateStore();
        Path older = file("older.txt", "旧文件内容");
        Path newer = file("newer.txt", "新文件内容");
        Thread.sleep(5);
        store.record(ReadStateStore.keyOf(older), null, "h1", null, 1L, 0, 1, false, false);
        Thread.sleep(5);
        store.record(ReadStateStore.keyOf(newer), null, "h2", null, 2L, 0, 1, false, false);
        List<Message> out = CompactionRestore.restoreRecentFiles(store, new ArrayList<Message>());
        assertEquals(1, out.size());
        String text = out.get(0).content;
        // 倒序：newer 在前
        assertTrue(text.contains("压缩后重读"));
        assertTrue(text.indexOf("newer.txt") < text.indexOf("older.txt"));
        assertTrue(text.contains("新文件内容"));
        assertTrue(text.contains("旧文件内容"));
        // 读状态刷新：content 不再为 null（重读已入账）
        assertNotNull(store.get(ReadStateStore.keyOf(newer)).content);
        assertEquals("新文件内容\n", store.get(ReadStateStore.keyOf(newer)).content);
    }

    /** 无读状态/全部不可读：返回空不注入 */
    @Test public void emptyStoreOrUnreadableFilesProduceNothing() {
        assertTrue(CompactionRestore.restoreRecentFiles(new ReadStateStore(), null).isEmpty());
        ReadStateStore store = new ReadStateStore();
        store.record("Z:\\不存在\\ghost.txt", null, "h", null, 1L, 0, 1, false, false);
        assertTrue(CompactionRestore.restoreRecentFiles(store, null).isEmpty());
    }

    /** 上限 5 个文件：第 6 个最近文件不注入 */
    @Test public void capsAtFiveFiles() throws Exception {
        ReadStateStore store = new ReadStateStore();
        for (int i = 0; i < 8; i++) {
            Path f = file("f" + i + ".txt", "内容" + i);
            store.record(ReadStateStore.keyOf(f), null, "h" + i, null, i, 0, 1, false, false);
            Thread.sleep(3);
        }
        List<Message> out = CompactionRestore.restoreRecentFiles(store, null);
        assertEquals(1, out.size());
        String text = out.get(0).content;
        assertTrue(text.contains("f7.txt"));
        assertTrue(text.contains("f3.txt"));
        assertFalse(text.contains("f2.txt"));   // 第 6 个起跳过
    }
}
