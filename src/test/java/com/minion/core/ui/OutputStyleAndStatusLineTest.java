package com.minion.core.ui;

import com.minion.core.security.ProjectTrustStore;
import com.minion.core.security.TrustGate;
import com.minion.core.ui.outputstyles.OutputStyle;
import com.minion.core.ui.outputstyles.OutputStyleCatalog;
import com.minion.core.ui.statusline.StatusLineRunner;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * v2.4 §10.4/§10.5 验收（headless）：内置三样式与优先级覆盖（built-in&lt;user&lt;project）、
 * frontmatter 解析、default 兜底；statusLine stdin JSON/首行/超时/非零忽略/信任拒绝零子进程。
 */
public class OutputStyleAndStatusLineTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 内置三样式 + default 兜底 */
    @Test public void builtinStylesAndDefaultFallback() throws Exception {
        OutputStyleCatalog catalog = OutputStyleCatalog.load(tmp.newFolder().toPath(), null);
        assertEquals(3, catalog.all().size());
        assertEquals(OutputStyle.DEFAULT, catalog.resolve(null).name);
        assertEquals(OutputStyle.DEFAULT, catalog.resolve("不存在的样式").name);   // 兜底
        assertTrue(catalog.resolve(OutputStyle.EXPLANATORY).body.contains("原理说明"));
        assertTrue(catalog.resolve(OutputStyle.LEARNING).body.contains("TODO(human)"));
        assertTrue(catalog.resolve(OutputStyle.EXPLANATORY).keepCodingInstructions);
    }

    /** 自定义样式：user(jarDir) 与 project(workspace) 层；同名 project 覆盖 user 覆盖 built-in */
    @Test public void customStylesPriority() throws Exception {
        Path jarDir = tmp.newFolder("jar").toPath();
        Path workspace = tmp.newFolder("ws").toPath();
        write(jarDir.resolve(".minion/output-styles/custom.md"),
                "---\nname: custom\ndescription: 用户层\n---\n用户层风格");
        write(jarDir.resolve(".minion/output-styles/Explanatory.md"),
                "---\nname: Explanatory\ndescription: 覆盖内置\n---\n用户覆盖解释");
        write(workspace.resolve(".minion/output-styles/custom.md"),
                "---\nname: custom\ndescription: 项目层\nkeep-coding-instructions: false\n---\n项目层风格");
        OutputStyleCatalog catalog = OutputStyleCatalog.load(jarDir, workspace);
        OutputStyle custom = catalog.resolve("custom");
        assertEquals("project", custom.source);
        assertEquals("项目层风格", custom.body);
        assertFalse("project 层 keep=false 生效", custom.keepCodingInstructions);
        assertEquals("user", catalog.resolve("Explanatory").source);
        assertEquals("用户覆盖解释", catalog.resolve("Explanatory").body);
        // 未被覆盖的内置保留
        assertEquals("built-in", catalog.resolve("Learning").source);
    }

    /** statusLine：首行输出语义、payload 字段口径 */
    @Test public void statusLineFirstLineAndPayload() throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!win) { System.out.println("[skip] 非 Windows：cmd 用例跳过"); return; }
        JsonObject payload = StatusLineRunner.payload("sess-1", "deepseek", "D:/ws",
                "¥0.01", 42, "Explanatory", "NORMAL");
        assertTrue(payload.toString().contains("\"context_window\":42"));
        assertTrue(payload.toString().contains("vim.mode"));
        assertTrue(payload.toString().contains("Explanatory"));
        String result = StatusLineRunner.run("cmd /c echo statusline-ok & echo second-line",
                payload, null, ".");
        assertNotNull("首行应返回", result);
        assertEquals("只取 stdout 首行", "statusline-ok", result);
    }

    /** statusLine：非零退出忽略；未配置忽略 */
    @Test public void statusLineNonZeroIgnored() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!win) { System.out.println("[skip] 非 Windows"); return; }
        assertNull(StatusLineRunner.run(null, new JsonObject(), null, "."));
        assertNull("exit≠0 忽略", StatusLineRunner.run(
                "cmd /c exit /b 3", new JsonObject(), null, "."));
    }

    /** statusLine：§10.9 信任拒绝 → 不执行子进程（零子进程/无输出） */
    @Test public void statusLineTrustDenyNoSubprocess() throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!win) { System.out.println("[skip] 非 Windows"); return; }
        Path root = tmp.newFolder().toPath();
        Path marker = root.resolve("spawned.txt");
        ProjectTrustStore trust = ProjectTrustStore.load(root);
        TrustGate deny = new TrustGate(trust, true, TrustGate.DENY);
        String result = StatusLineRunner.run("cmd /c echo x > \"" + marker + "\"",
                new JsonObject(), deny, root.toString());
        assertNull("信任拒绝返回 null", result);
        assertFalse("拒绝时子进程数量必须为零", Files.exists(marker));
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
