package com.minion.core.ui;

import com.minion.core.ui.keybindings.KeyBindingRegistry;
import com.minion.core.ui.vim.VimMachine;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * v2.4 §10.1 验收（headless）：快捷键注册表默认表/用户覆盖/损坏容错/序列键；
 * Vim 状态机 NORMAL/INSERT/motion/count/operator/中文不干扰（INSERT 原生处理）。
 */
public class KeyBindingsAndVimTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 注册表：默认命中、覆盖生效、损坏文件保持默认、序列键独立取值 */
    @Test public void keyBindingDefaultsAndOverrides() throws Exception {
        KeyBindingRegistry registry = new KeyBindingRegistry();
        assertEquals("transcript_toggle", registry.match(true, false, false, "o"));
        assertEquals("history_search", registry.match(true, false, false, "r"));
        assertEquals("permission_mode_cycle", registry.match(false, false, true, "tab"));
        assertEquals("thinking_toggle", registry.match(false, true, false, "t"));
        assertNull("未绑定组合", registry.match(false, false, false, "z"));
        assertEquals("ctrl+r", registry.shortcutOf("history_search"));
        assertEquals("escape,escape", registry.sequenceOf("rewind_selector"));

        Path overrides = tmp.newFolder().toPath().resolve(".minion/keybindings.json");
        Files.createDirectories(overrides.getParent());
        Files.write(overrides, "{\"history_search\":\"ctrl+f\",\"unknown_action\":\"ctrl+q\"}"
                .getBytes(StandardCharsets.UTF_8));
        registry.loadOverrides(overrides);
        assertEquals("history_search", registry.match(true, false, false, "f"));
        assertNull("原键位释放", registry.match(true, false, false, "r"));
        assertNull("未知动作忽略", registry.match(true, false, false, "q"));

        Files.write(overrides, "{{{ broken".getBytes(StandardCharsets.UTF_8));
        KeyBindingRegistry corrupt = new KeyBindingRegistry().loadOverrides(overrides);
        assertEquals("损坏覆盖保持默认", "history_search", corrupt.match(true, false, false, "r"));
    }

    /** Vim：NORMAL motion（h/l/0/$/w/b/G）、count、operator（x/dd）、INSERT 原生回传 */
    @Test public void vimMotionsCountAndOperators() {
        VimMachine vim = VimMachine.create();
        vim.setText("hello vim 世界");
        assertEquals(VimMachine.Mode.NORMAL, vim.mode());

        vim.feed("l");
        assertEquals(1, vim.cursor());
        vim.feed("3");
        vim.feed("l");                      // count 与 motion 为独立按键事件
        assertEquals(4, vim.cursor());
        vim.feed("0");
        assertEquals(0, vim.cursor());
        vim.feed("w");
        assertEquals(6, vim.cursor());   // hello → vim 词首
        vim.feed("w");
        assertEquals(10, vim.cursor());  // vim → 世界 词首（中文按词字符）
        vim.feed("b");
        assertEquals(6, vim.cursor());
        vim.feed("$");
        assertEquals("hello vim 世界".length(), vim.cursor());
        vim.feed("0");
        vim.feed("2");
        vim.feed("w");
        assertEquals(10, vim.cursor());

        // operator：x 删一字符（含中文字符），dd 清行
        vim.setText("abc");
        vim.feed("0");
        vim.feed("x");
        assertEquals("bc", vim.text());
        vim.feed("x");
        assertEquals("c", vim.text());
        vim.feed("dd");
        assertEquals("", vim.text());

        // INSERT：单字符回传 false=原生输入处理（中文输入法不受干扰）；Esc 回 NORMAL
        vim.feed("i");
        assertEquals(VimMachine.Mode.INSERT, vim.mode());
        assertFalse("INSERT 下按键归原生输入", vim.feed("中"));
        vim.feed("esc");
        assertEquals(VimMachine.Mode.NORMAL, vim.mode());
        assertTrue(vim.feed("h"));
    }

    /** Vim：a/o 进入插入并移动光标；未映射键给出反馈不崩 */
    @Test public void vimInsertEntryAndUnknownKeys() {
        VimMachine vim = VimMachine.create();
        vim.setText("ab");
        vim.feed("$");
        vim.feed("a");
        assertEquals(VimMachine.Mode.INSERT, vim.mode());
        assertEquals(2, vim.cursor());
        vim.feed("esc");
        vim.feed("o");
        assertEquals(VimMachine.Mode.INSERT, vim.mode());
        assertEquals(2, vim.cursor());
        vim.feed("esc");
        assertTrue(vim.feed("Z"));
        assertTrue(vim.state().lastMessage.contains("未映射"));
        // enable(false) 退回普通编辑语义（/vim 关）
        vim.enable(false);
        assertEquals(VimMachine.Mode.INSERT, vim.mode());
        assertFalse(vim.feed("h"));
    }
}
