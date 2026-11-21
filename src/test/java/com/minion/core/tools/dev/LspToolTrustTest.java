package com.minion.core.tools.dev;

import com.minion.core.security.ProjectTrustStore;
import com.minion.core.security.TrustGate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * §10.9 LSP server 启动前持续信任验收：拒绝 → 工具报错且零子进程；批准 → 落盘续用；
 * 命令变化 → 旧批准不复用；未装配闸门（旧装配）行为不变。
 */
public class LspToolTrustTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 建真实 .py 文件（execute 在信任复核前校验文件存在）并生成参数 */
    private static com.google.gson.JsonObject args(String operation, Path work) throws Exception {
        Path file = work.resolve("a.py");
        if (!Files.exists(file)) Files.write(file, "x = 1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("operation", operation);
        o.addProperty("filePath", file.toString());
        o.addProperty("line", 1);
        o.addProperty("character", 1);
        return o;
    }

    /** 拒绝：execute 报错含「持续信任」；标记文件不出现（零子进程） */
    @Test public void denyRejectsBeforeSpawn() throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!win) { System.out.println("[skip] 非 Windows：marker 用例跳过"); return; }
        Path work = tmp.newFolder().toPath();
        Path marker = work.resolve("lsp-spawned.txt");
        Map<String, String> servers = new HashMap<String, String>();
        servers.put("python", "cmd.exe /c echo x > \"" + marker + "\"");
        ProjectTrustStore trust = ProjectTrustStore.load(work);
        LspTool lsp = new LspTool(work.toString(), servers,
                new TrustGate(trust, true, TrustGate.DENY));
        try {
            com.minion.core.tools.ToolResult r = lsp.execute(args("documentSymbol", work));
            assertNotNull(r);
            assertTrue("拒绝原因须可见: " + (r.output == null ? "" : r.output),
                    r.output != null && r.output.contains("持续信任"));
        } catch (IllegalStateException direct) {
            assertTrue(direct.getMessage().contains("持续信任"));
        }
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && !Files.exists(marker)) Thread.sleep(100);
        assertFalse("拒绝时子进程数量必须为零", Files.exists(marker));
    }

    /** 批准：落盘指纹；同命令第二次不再询问（计数=1） */
    @Test public void approvalPersistsSinglePrompt() throws Exception {
        Path work = tmp.newFolder().toPath();
        Map<String, String> servers = new HashMap<String, String>();
        servers.put("python", "pyright-langserver --stdio");
        ProjectTrustStore trust = ProjectTrustStore.load(work);
        int[] prompts = {0};
        LspTool lsp = new LspTool(work.toString(), servers, new TrustGate(trust, true,
                (s, r, m) -> { prompts[0]++; return true; }));
        // 第一次：批准（进程会因命令不存在而失败，但信任已落盘）
        try { lsp.execute(args("hover", work)); } catch (Exception expected) { }
        assertEquals(1, prompts[0]);
        assertTrue(trust.entries().containsKey("lsp"));
        // 第二次：同命令不再询问（指纹匹配）
        try { lsp.execute(args("hover", work)); } catch (Exception expected) { }
        assertEquals("同命令不得重复询问", 1, prompts[0]);
    }

    /** 命令变化（config.properties 改 lsp.servers.python）→ 旧批准不复用，重新询问 */
    @Test public void changedCommandRePrompts() throws Exception {
        Path work = tmp.newFolder().toPath();
        ProjectTrustStore trust = ProjectTrustStore.load(work);
        Map<String, String> v1 = new HashMap<String, String>();
        v1.put("python", "server-one");
        int[] prompts = {0};
        LspTool first = new LspTool(work.toString(), v1, new TrustGate(trust, true,
                (s, r, m) -> { prompts[0]++; return true; }));
        try { first.execute(args("hover", work)); } catch (Exception expected) { }
        assertEquals(1, prompts[0]);

        Map<String, String> v2 = new HashMap<String, String>();
        v2.put("python", "server-two");
        LspTool second = new LspTool(work.toString(), v2, new TrustGate(trust, true,
                (s, r, m) -> { prompts[0]++; return false; }));
        com.minion.core.tools.ToolResult r = null;
        try { r = second.execute(args("hover", work)); } catch (IllegalStateException direct) {
            assertTrue(direct.getMessage().contains("持续信任"));
        }
        if (r != null && r.output != null) assertTrue(r.output.contains("持续信任"));
        assertEquals("变化后必须重新询问", 2, prompts[0]);
    }

    /** 未装配闸门：零信任逻辑（V68 行为），工具错误来自命令本身而非信任 */
    @Test public void legacyNoGateUnchanged() throws Exception {
        Path work = tmp.newFolder().toPath();
        Map<String, String> servers = new HashMap<String, String>();
        servers.put("python", "definitely-not-a-lsp-server");
        LspTool lsp = new LspTool(work.toString(), servers);   // 两参构造=无闸门
        try {
            com.minion.core.tools.ToolResult r = lsp.execute(args("hover", work));
            // 报错应为启动/握手失败，不含信任字样
            if (r.output != null) assertFalse(r.output.contains("持续信任"));
        } catch (Exception e) {
            assertFalse(e.getMessage() == null ? false : e.getMessage().contains("持续信任"));
        }
    }

    /** 空命令表：isEnabled=false（既有语义不回归） */
    @Test public void emptyServersStaysDisabled() throws Exception {
        LspTool lsp = new LspTool(tmp.newFolder().toString(),
                Collections.<String, String>emptyMap(),
                new TrustGate(ProjectTrustStore.load(tmp.newFolder().toPath()), true, TrustGate.DENY));
        assertFalse(lsp.isEnabled());
    }
}
