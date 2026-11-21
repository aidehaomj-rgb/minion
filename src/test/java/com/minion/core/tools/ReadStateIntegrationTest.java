package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.security.ReadStateStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * §4.5/§3.1 Read/Edit/Write 与读状态集成验收：去重 stub（上下文账本）、read-before-write、
 * 整文件哈希冲突、压缩失效重读、legacy 开关（false=V68 行为不变）。
 */
public class ReadStateIntegrationTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private String work;
    private Workspace ws;
    private ReadStateStore store;

    @Before public void setup() {
        work = tmp.getRoot().getAbsolutePath();
        ws = new Workspace(work);
        store = new ReadStateStore();
    }

    private ReadTool read(boolean cc) { return new ReadTool(ws, null, null, null, store, cc); }
    private EditTool edit(boolean cc) { return new EditTool(ws, null, null, null, null, store, cc); }
    private WriteTool write(boolean cc) { return new WriteTool(ws, null, null, null, null, store, cc); }
    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private Path p(String rel) { return Paths.get(work, rel); }

    private void put(String rel, String content) throws IOException {
        Files.write(p(rel), content.getBytes(StandardCharsets.UTF_8));
    }

    /** 去重：同范围正文仍在上下文且整文件未变 → stub；账本失效（模拟压缩）→ 必须回正文 */
    @Test public void readDedupStubThenCompactionForcesBody() throws Exception {
        put("a.txt", "hello\nworld\n");
        ReadTool r = read(true);
        String body = r.execute(args("{\"path\":\"a.txt\"}")).output;
        assertFalse(body.contains("文件未变化"));
        // 未变化重读 → stub
        String stub = r.execute(args("{\"path\":\"a.txt\"}")).output;
        assertTrue(stub.contains("文件未变化"));
        // §4.5 规格场景：压缩清理正文 → 账本失效 → 再次读取必须返回正文
        store.contextLedger().invalidateAll();
        String again = r.execute(args("{\"path\":\"a.txt\"}")).output;
        assertFalse(again.contains("文件未变化"));
        assertEquals(body, again);
    }

    /** 文件外部变化：整文件哈希不一致 → 不回 stub，返回新正文 */
    @Test public void changedFileNeverStubbed() throws Exception {
        put("b.txt", "v1\n");
        ReadTool r = read(true);
        r.execute(args("{\"path\":\"b.txt\"}"));
        put("b.txt", "v2\n");
        String out = r.execute(args("{\"path\":\"b.txt\"}")).output;
        assertFalse(out.contains("文件未变化"));
        assertTrue(out.contains("v2"));
    }

    /** read-before-write：未读拒绝；切片读算已读；外部修改被整文件哈希拦截；成功后回写可继续编辑 */
    @Test public void editRequiresReadAndBlocksStaleHash() throws Exception {
        put("c.txt", "alpha\nbeta\n");
        EditTool e = edit(true);
        ToolResult denied = e.execute(args(
                "{\"path\":\"c.txt\",\"oldString\":\"alpha\",\"newString\":\"ALPHA\"}"));
        assertFalse(denied.ok);
        assertTrue(denied.output.contains("Read"));
        // 切片读取（offset）算已读（规格：消除大文件不可编辑死区）
        assertTrue(read(true).execute(args("{\"path\":\"c.txt\",\"offset\":1,\"limit\":1}")).ok);
        // 切片读后整文件其他位置变化 → 哈希冲突拦截
        put("c.txt", "alpha\nbeta-external\n");
        ToolResult conflict = e.execute(args(
                "{\"path\":\"c.txt\",\"oldString\":\"alpha\",\"newString\":\"ALPHA\"}"));
        assertFalse(conflict.ok);
        assertTrue(conflict.output.contains("SHA-256"));
        // 重新 Read 后编辑成功；成功回写读状态 → 连续第二次编辑不再要求重读
        read(true).execute(args("{\"path\":\"c.txt\"}"));
        assertTrue(e.execute(args(
                "{\"path\":\"c.txt\",\"oldString\":\"alpha\",\"newString\":\"ALPHA\"}")).ok);
        assertTrue(e.execute(args(
                "{\"path\":\"c.txt\",\"oldString\":\"ALPHA\",\"newString\":\"alpha\"}")).ok);
        assertEquals("alpha\nbeta-external\n",
                new String(Files.readAllBytes(p("c.txt")), StandardCharsets.UTF_8));
    }

    /** isPartialView（自动注入类）不许编辑——正文未真正进上下文 */
    @Test public void partialViewBlocksEdit() throws Exception {
        put("d.txt", "x\n");
        String key = ReadStateStore.keyOf(p("d.txt"));
        store.record(key, null, ReadStateStore.sha256(p("d.txt")), null, 0L, 0, 1, true, false);
        ToolResult denied = edit(true).execute(args(
                "{\"path\":\"d.txt\",\"oldString\":\"x\",\"newString\":\"y\"}"));
        assertFalse(denied.ok);
        assertTrue(denied.output.contains("显式 Read"));
    }

    /** Write：新建放行；未读覆盖拒绝；切片读后覆盖放行但提示整文件覆盖风险 */
    @Test public void writeOverwriteGateAndPartialReadWarning() throws Exception {
        WriteTool w = write(true);
        assertTrue(w.execute(args("{\"path\":\"new.txt\",\"content\":\"first\"}")).ok);
        // 未读覆盖 → 拒绝
        ToolResult denied = w.execute(args("{\"path\":\"new.txt\",\"content\":\"second\"}"));
        assertFalse(denied.ok);
        assertTrue(denied.output.contains("Read"));
        // 切片读（partialRange=true）后覆盖 → 放行 + 风险提示
        put("big.txt", "l1\nl2\nl3\n");
        read(true).execute(args("{\"path\":\"big.txt\",\"offset\":1,\"limit\":1}"));
        ToolResult overwrite = w.execute(args("{\"path\":\"big.txt\",\"content\":\"replaced\"}"));
        assertTrue(overwrite.ok);
        assertTrue(overwrite.output.contains("整文件覆盖"));
        assertEquals("replaced", new String(Files.readAllBytes(p("big.txt")), StandardCharsets.UTF_8));
        // 覆盖后读状态回写：紧接的 Edit 不再被拒
        ToolResult after = edit(true).execute(args(
                "{\"path\":\"big.txt\",\"oldString\":\"replaced\",\"newString\":\"edited\"}"));
        assertTrue(after.ok);
    }

    /** legacy：read.ccSemantics=false（默认）→ 无去重 stub、无 read-before-write（V68 逐项不变） */
    @Test public void legacyOffKeepsV68Behavior() throws Exception {
        put("e.txt", "one\ntwo\n");
        ReadTool r = read(false);
        String body = r.execute(args("{\"path\":\"e.txt\"}")).output;
        String again = r.execute(args("{\"path\":\"e.txt\"}")).output;
        assertEquals(body, again);   // 无 stub
        // 未读直接编辑 → 放行（V68 主 Agent 行为）
        assertTrue(edit(false).execute(args(
                "{\"path\":\"e.txt\",\"oldString\":\"one\",\"newString\":\"ONE\"}")).ok);
        // 未读直接覆盖 → 放行（V68）
        assertTrue(write(false).execute(args("{\"path\":\"e.txt\",\"content\":\"rewritten\"}")).ok);
    }

    /** 登记簿记（store 接线但 cc=false）：读后状态存在，供后续诊断/开关热切换 */
    @Test public void registrationHappensEvenWhenFlagOff() throws Exception {
        put("f.txt", "content\n");
        read(false).execute(args("{\"path\":\"f.txt\"}"));
        ReadStateStore.Entry e = store.get(ReadStateStore.keyOf(p("f.txt")));
        assertNotNull(e);
        assertEquals(ReadStateStore.sha256(p("f.txt")), e.contentHash);
        assertFalse(e.isPartialView);
    }
}
