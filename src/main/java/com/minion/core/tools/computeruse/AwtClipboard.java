package com.minion.core.tools.computeruse;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

/** 系统剪贴板 AWT 实现：仅 stringFlavor，best-effort。 */
public final class AwtClipboard implements ClipboardAccess {
    @Override public String readText() {
        try {
            Clipboard c = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (c.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                Object data = c.getData(DataFlavor.stringFlavor);
                return data instanceof String ? (String) data : null;
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("读取剪贴板失败: " + e.getMessage(), e);
        }
    }

    @Override public void writeText(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception e) {
            throw new IllegalStateException("写入剪贴板失败: " + e.getMessage(), e);
        }
    }
}