package com.minion.core.tools.computeruse;

import com.minion.core.diagnostics.DiagnosticLog;

/** 剪贴板粘贴编排：保存原文本 → 写入新文本 → 粘贴 → 延迟缓冲 → 恢复。
 *  Robot Ctrl+V 是异步投递，立即写回会让目标读到旧剪贴板，故恢复前必须缓冲（默认 120ms）。
 *  保存/恢复失败只记 DiagnosticLog，绝不让 type 因此失败；写入新文本失败照常抛出（与旧路径一致）。 */
final class ClipboardPaste {
    private ClipboardPaste() {}

    interface Delay { void ms(int millis); }

    static void pastePreserving(ClipboardAccess clipboard, String text, Runnable paste, Delay delay, int restoreDelayMs) {
        String saved = null;
        try {
            saved = clipboard.readText();
        } catch (Exception e) {
            DiagnosticLog.warn("computeruse", "粘贴前保存剪贴板失败，跳过恢复: " + e.getMessage());
        }
        clipboard.writeText(text);
        paste.run();
        if (saved != null) {
            delay.ms(restoreDelayMs);
            try {
                clipboard.writeText(saved);
            } catch (Exception e) {
                DiagnosticLog.warn("computeruse", "粘贴后恢复剪贴板失败: " + e.getMessage());
            }
        }
    }
}