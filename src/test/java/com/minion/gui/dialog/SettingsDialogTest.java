package com.minion.gui.dialog;

import org.junit.Test;

import static org.junit.Assert.*;

/** 设置窗纯逻辑辅助：MCP 表单解析、模型激活判定（无 JavaFX 环境可用） */
public class SettingsDialogTest {

    // ===== MCP 页表单辅助（纯逻辑，无 JavaFX） =====

    /** 参数文本：每行一个，trim 后去空行 */
    @Test
    public void splitLines_trimsAndDropsEmpty() {
        assertEquals(java.util.Arrays.asList("a", "b"),
                SettingsDialog.splitLines(" a \n\n b "));
        assertEquals(0, SettingsDialog.splitLines("  \n\n").size());
    }

    /** KEY=VALUE / K:V 混排逐行解析；非法行忽略 */
    @Test
    public void parsePairs_supportsEqAndColon() {
        java.util.Map<String, String> m = SettingsDialog.parsePairs("A=1\nB: 2\nbadline\nC=3");
        assertEquals(3, m.size());
        assertEquals("1", m.get("A"));
        assertEquals("2", m.get("B"));
        assertEquals("3", m.get("C"));
    }

    /** 失败原因列表显示：null→空、取首行并去首尾空白、超 40 字符截断加省略号 */
    @Test
    public void shorten_takesFirstLineAndTruncates() {
        assertEquals("", SettingsDialog.shorten(null));
        assertEquals("short", SettingsDialog.shorten("short"));
        assertEquals("first", SettingsDialog.shorten("first\nsecond line"));
        assertEquals("padded", SettingsDialog.shorten("  padded  \nsecond line"));   // 首行首尾空白剥掉
        assertEquals("", SettingsDialog.shorten("   \nsecond"));                     // 首行纯空白 → 空
        String longLine = "aVeryLongFailureReasonThatExceedsFortyCharactersByFar!!!";
        String s = SettingsDialog.shorten(longLine + "\nsecond");
        assertEquals(41, s.length()); // 40 字符 + …
        assertTrue(s.endsWith("…"));
    }

    // ===== 模型页激活判定（纯逻辑，无 JavaFX） =====

    /** 无选中（null）或选中的就是当前模型 → 不可激活 */
    @Test
    public void canActivate_rejectsNullOrCurrent() {
        assertFalse(SettingsDialog.canActivate(null, "deepseek-v4-flash"));
        assertFalse(SettingsDialog.canActivate("deepseek-v4-flash", "deepseek-v4-flash"));
    }

    /** 选中了其它模型 → 可激活 */
    @Test
    public void canActivate_allowsDifferentModel() {
        assertTrue(SettingsDialog.canActivate("qwen3-max", "deepseek-v4-flash"));
    }

    // ===== 三批 B4：备用模型链（纯逻辑 + 配置读写回环，无 JavaFX） =====

    /** 规范化：中英文逗号/空白分隔同口径，去空项，逗号连接；空输入→空串（默认不降级） */
    @Test
    public void normalizeFallbackChain_matchesAgentLoopSplit() {
        assertEquals("", SettingsDialog.normalizeFallbackChain(null));
        assertEquals("", SettingsDialog.normalizeFallbackChain("   "));
        assertEquals("a,b,c", SettingsDialog.normalizeFallbackChain(" a , b ， c "));
        assertEquals("a,b", SettingsDialog.normalizeFallbackChain("a,,，b,"));
        assertEquals("a,b", SettingsDialog.normalizeFallbackChain("a b"));
        // 与 AgentLoop.setModelFallback 的分隔正则同口径：规范化结果再split一致
        String normalized = SettingsDialog.normalizeFallbackChain("模型甲，模型乙 模型丙");
        assertArrayEquals(new String[]{"模型甲", "模型乙", "模型丙"}, normalized.split("[,，\\s]+"));
    }

    /** 配置读写回环：set 落盘，重新 load 仍可读回（新会话生效的数据基础） */
    @Test
    public void fallbackChainConfigRoundtrip() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("minion-b4-cfg");
        try {
            com.minion.core.config.Config config = com.minion.core.config.Config.load(dir);
            assertEquals("", config.get("model.fallbackChain", ""));   // 默认空=现状不变
            String chain = SettingsDialog.normalizeFallbackChain("backup-a，backup-b");
            config.set("model.fallbackChain", chain);
            assertEquals("backup-a,backup-b", config.get("model.fallbackChain", ""));
            com.minion.core.config.Config reloaded = com.minion.core.config.Config.load(dir);
            assertEquals("backup-a,backup-b", reloaded.get("model.fallbackChain", ""));
            config.set("model.fallbackChain", "");
            assertEquals("", com.minion.core.config.Config.load(dir).get("model.fallbackChain", ""));
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) { } });
            }
        }
    }
}
