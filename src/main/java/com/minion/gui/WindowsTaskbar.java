package com.minion.gui;

import com.minion.core.tools.NativeLibraryBootstrap;
import com.minion.gui.icon.AppIcons;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import javafx.stage.Stage;
import java.util.Locale;

/** Restore standard Shell minimize semantics for the undecorated main HWND only. */
public final class WindowsTaskbar {
    private WindowsTaskbar() {}
    public interface User extends StdCallLibrary {
        int GetWindowLongW(Pointer hwnd, int index);
        int SetWindowLongW(Pointer hwnd, int index, int value);
        boolean SetWindowPos(Pointer hwnd, Pointer after, int x, int y, int w, int h, int flags);
    }
    public static int minimizeStyle(int style) {
        return style | 0x00020000 | 0x00080000; // WS_MINIMIZEBOX | WS_SYSMENU, no caption/frame
    }
    public static boolean install(Stage stage) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return false;
        long handle = AppIcons.nativeHandle(stage);
        if (handle == 0) return false;
        try {
            NativeLibraryBootstrap.prepare();
            User user = Native.load("user32", User.class);
            Pointer hwnd = new Pointer(handle);
            int old = user.GetWindowLongW(hwnd, -16);
            int updated = minimizeStyle(old);
            if (old != updated) {
                Native.setLastError(0);
                if (user.SetWindowLongW(hwnd, -16, updated) == 0 && Native.getLastError() != 0) return false;
                // Style is a 32-bit LONG even on x64. Preserve size, placement, focus and z-order.
                if (!user.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x37)) return false;
            }
            return (user.GetWindowLongW(hwnd, -16) & 0x20000) != 0;
        } catch (Exception | LinkageError unavailable) { return false; }
    }
}
