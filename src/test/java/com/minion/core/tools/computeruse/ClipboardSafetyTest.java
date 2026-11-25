package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 三批 C2 剪贴板安全：pastePreserving 保存/延迟/恢复编排（恢复失败不致败）、
 * 延迟参数边界（80–150 越界回退 120）、ClipboardTool 双高危分类与门禁拦截。
 * 全部 headless 可测（fake ClipboardAccess，不触碰真实系统剪贴板）。
 */
public class ClipboardSafetyTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 记录操作序列的 fake；可注入读/写失败 */
    private static class FakeClipboard implements ClipboardAccess {
        final List<String> ops = new ArrayList<String>();
        String current;
        boolean readFails, writeFails;
        int writeCount;

        FakeClipboard(String initial) { this.current = initial; }

        @Override public String readText() {
            ops.add("read");
            if (readFails) throw new IllegalStateException("读失败");
            return current;
        }
        @Override public void writeText(String text) {
            ops.add("write:" + text);
            writeCount++;
            if (writeFails) throw new IllegalStateException("写失败");
            current = text;
        }
    }

    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    /** 正常编排：读原文 → 写新文 → 粘贴 → 缓冲 120ms → 恢复原文 */
    @Test
    public void pastePreserving_savesDelaysThenRestores() {
        FakeClipboard cb = new FakeClipboard("原文");
        final List<String> trace = new ArrayList<String>();
        ClipboardPaste.pastePreserving(cb, "新文", new Runnable() {
            @Override public void run() { trace.add("paste"); }
        }, new ClipboardPaste.Delay() {
            @Override public void ms(int millis) { trace.add("delay:" + millis); }
        }, 120);
        assertEquals("[read, write:新文, write:原文]", cb.ops.toString());
        assertEquals("[paste, delay:120]", trace.toString());
        assertEquals("原文", cb.current);
    }

    /** 恢复失败只记日志，绝不让 type 因此失败 */
    @Test
    public void restoreFailure_doesNotFailPaste() {
        FakeClipboard cb = new FakeClipboard("原文") {
            @Override public void writeText(String text) {
                if (writeCount >= 1) throw new IllegalStateException("恢复写失败");
                super.writeText(text);
            }
        };
        final boolean[] pasted = {false};
        ClipboardPaste.pastePreserving(cb, "新文", new Runnable() {
            @Override public void run() { pasted[0] = true; }
        }, new ClipboardPaste.Delay() {
            @Override public void ms(int millis) { }
        }, 120);
        assertTrue("粘贴必须已执行", pasted[0]);
    }

    /** 保存失败：跳过恢复（无原文可还），粘贴照常 */
    @Test
    public void saveFailure_skipsRestoreButPastes() {
        FakeClipboard cb = new FakeClipboard("原文");
        cb.readFails = true;
        final List<Integer> delays = new ArrayList<Integer>();
        ClipboardPaste.pastePreserving(cb, "新文", new Runnable() {
            @Override public void run() { }
        }, new ClipboardPaste.Delay() {
            @Override public void ms(int millis) { delays.add(millis); }
        }, 120);
        assertEquals("[read, write:新文]", cb.ops.toString());
        assertTrue("无原文不得空转缓冲", delays.isEmpty());
    }

    /** 剪贴板原本无文本：不恢复不缓冲 */
    @Test
    public void noPriorText_noRestoreNoDelay() {
        FakeClipboard cb = new FakeClipboard(null);
        final List<Integer> delays = new ArrayList<Integer>();
        ClipboardPaste.pastePreserving(cb, "新文", new Runnable() {
            @Override public void run() { }
        }, new ClipboardPaste.Delay() {
            @Override public void ms(int millis) { delays.add(millis); }
        }, 120);
        assertEquals("[read, write:新文]", cb.ops.toString());
        assertTrue(delays.isEmpty());
    }

    /** 写入新文本失败照常抛出（与旧 setClipboard 路径一致，type 回错误结果） */
    @Test
    public void writeNewTextFailure_propagates() {
        FakeClipboard cb = new FakeClipboard("原文");
        cb.writeFails = true;
        try {
            ClipboardPaste.pastePreserving(cb, "新文", new Runnable() {
                @Override public void run() { fail("写入失败不得执行粘贴"); }
            }, new ClipboardPaste.Delay() {
                @Override public void ms(int millis) { }
            }, 120);
            fail("写入新文本失败应抛出");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("写失败"));
        }
    }

    /** 延迟参数边界：80–150 合法，越界回退 120 */
    @Test
    public void restoreDelayClamp_boundaries() {
        ComputerUseConfig c = new ComputerUseConfig();
        assertEquals(120, c.clipboardRestoreDelayMsSafe()); // 默认
        c.clipboardRestoreDelayMs = 80;  assertEquals(80, c.clipboardRestoreDelayMsSafe());
        c.clipboardRestoreDelayMs = 150; assertEquals(150, c.clipboardRestoreDelayMsSafe());
        c.clipboardRestoreDelayMs = 79;  assertEquals(120, c.clipboardRestoreDelayMsSafe());
        c.clipboardRestoreDelayMs = 151; assertEquals(120, c.clipboardRestoreDelayMsSafe());
        c.clipboardRestoreDelayMs = 0;   assertEquals(120, c.clipboardRestoreDelayMsSafe());
        c.clipboardRestoreDelayMs = -5;  assertEquals(120, c.clipboardRestoreDelayMsSafe());
    }

    /** ClipboardTool：read/write 一律高危；read 只读、write 破坏性 */
    @Test
    public void clipboardTool_bothActionsHighRisk() {
        ClipboardTool tool = new ClipboardTool(new FakeClipboard("x"));
        assertTrue(tool.isHighRisk(args("{\"action\":\"read\"}")));
        assertTrue(tool.isHighRisk(args("{\"action\":\"write\",\"text\":\"x\"}")));
        assertTrue("未知动作也不得降危", tool.isHighRisk(new JsonObject()));
        assertTrue(tool.isReadOnly(args("{\"action\":\"read\"}")));
        assertFalse(tool.isReadOnly(args("{\"action\":\"write\",\"text\":\"x\"}")));
        assertFalse(tool.isDestructive(args("{\"action\":\"read\"}")));
        assertTrue(tool.isDestructive(args("{\"action\":\"write\",\"text\":\"x\"}")));
    }

    /** ClipboardTool 执行语义（fake，headless） */
    @Test
    public void clipboardTool_executeSemantics() {
        FakeClipboard cb = new FakeClipboard("敏感文本");
        ClipboardTool tool = new ClipboardTool(cb);
        ToolResult read = tool.execute(args("{\"action\":\"read\"}"));
        assertTrue(read.ok);
        assertTrue(read.output.contains("敏感文本"));
        assertTrue(tool.execute(args("{\"action\":\"write\",\"text\":\"新内容\"}")).ok);
        assertEquals("新内容", cb.current);
        assertFalse("空文本写入应拒绝", tool.execute(args("{\"action\":\"write\",\"text\":\"\"}")).ok);
        assertFalse(tool.execute(args("{\"action\":\"paste\"}")).ok);
        cb.current = null;
        assertTrue(tool.execute(args("{\"action\":\"read\"}")).output.contains("无文本"));
        cb.readFails = true;
        ToolResult err = tool.execute(args("{\"action\":\"read\"}"));
        assertFalse("访问异常应回错误结果而非抛出", err.ok);
        assertTrue(err.output.contains("剪贴板操作失败"));
    }

    /** 门禁拦截：两动作都进 ConfirmGate 询问；拒绝即拦截 */
    @Test
    public void confirmGate_interceptsBothActions() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        ClipboardTool tool = new ClipboardTool(new FakeClipboard("x"));
        FakeConfirmUi reject = new FakeConfirmUi(ConfirmUi.Decision.REJECT, ConfirmUi.Decision.REJECT);
        ConfirmGate gate = new ConfirmGate(config, reject);
        assertFalse("write 拒绝应拦截", gate.check(tool, args("{\"action\":\"write\",\"text\":\"x\"}")));
        assertFalse("read 拒绝应拦截", gate.check(tool, args("{\"action\":\"read\"}")));
        assertEquals("两动作都应询问用户", 2, reject.asked.size());
        FakeConfirmUi approve = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        assertTrue(new ConfirmGate(config, approve).check(tool, args("{\"action\":\"read\"}")));
        assertEquals(1, approve.asked.size());
    }
}