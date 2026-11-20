package com.minion.core.tools.computeruse;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.ArrayList;
import java.util.List;

/** Win32 窗口操作（纯 JNA 自声明接口，Win7/JDK8 离线可用，与 ProcessCleanup 同模式）：
 *  枚举可见窗口 / 前台激活 / 最小化 / 温和关闭。仅 user32/kernel32 公开 API，不注入；
 *  close 走 WM_CLOSE（等同点关闭按钮，应用可弹保存确认），绝不 WM_TERMINATE。 */
public final class Win32Gui {
    private Win32Gui() {}

    /** 纯 jna 的 user32 声明（不依赖 jna-platform：本地离线仓库只有 jna 核心） */
    interface User32Lib extends StdCallLibrary {
        interface EnumWindowsProc extends Callback {
            boolean callback(Pointer hwnd, Pointer lParam);
        }
        boolean EnumWindows(EnumWindowsProc proc, Pointer lParam);
        int GetWindowTextW(Pointer hwnd, char[] text, int max);
        boolean IsWindowVisible(Pointer hwnd);
        boolean GetWindowRect(Pointer hwnd, RECT rect);
        int GetWindowThreadProcessId(Pointer hwnd, IntByReference pid);
        Pointer GetForegroundWindow();
        boolean SetForegroundWindow(Pointer hwnd);
        boolean ShowWindow(Pointer hwnd, int cmd);
        boolean PostMessageW(Pointer hwnd, int msg, Pointer wParam, Pointer lParam);
        boolean AttachThreadInput(int idAttach, int idAttachTo, boolean attach);
    }
    interface Kernel32Lib extends StdCallLibrary { int GetCurrentThreadId(); }

    public static class RECT extends Structure {
        public int left, top, right, bottom;
    }

    private static final User32Lib U;
    private static final Kernel32Lib K;
    static {
        User32Lib u; Kernel32Lib k;
        try {
            com.minion.core.tools.NativeLibraryBootstrap.prepare();
            u = (User32Lib) Native.loadLibrary("user32", User32Lib.class);
            k = (Kernel32Lib) Native.loadLibrary("kernel32", Kernel32Lib.class);
        } catch (Throwable unavailable) {
            u = null; k = null;   // 非 Windows：窗口功能不可用，列表为空/操作报错
        }
        U = u; K = k;
    }

    private static final int WM_CLOSE = 0x0010;
    private static final int SW_RESTORE = 9, SW_MINIMIZE = 6;

    public static boolean available() { return U != null; }

    public static final class WindowInfo {
        public final Pointer hwnd;
        public final String title;
        public final int pid;
        public final int x, y, w, h;
        WindowInfo(Pointer hwnd, String title, int pid, int x, int y, int w, int h) {
            this.hwnd = hwnd; this.title = title; this.pid = pid;
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    /** 可见顶层窗口（有标题、在屏幕内）；EnumWindows 按 z 序回调，越先越在前台 */
    public static List<WindowInfo> visibleWindows() {
        final List<WindowInfo> out = new ArrayList<WindowInfo>();
        if (U == null) return out;
        U.EnumWindows(new User32Lib.EnumWindowsProc() {
            @Override public boolean callback(Pointer hwnd, Pointer lParam) {
                char[] buf = new char[512];
                int n = U.GetWindowTextW(hwnd, buf, buf.length);
                if (n > 0 && U.IsWindowVisible(hwnd)) {
                    RECT r = new RECT();
                    if (U.GetWindowRect(hwnd, r) && r.right > r.left && r.bottom > r.top && r.left > -30000) {
                        IntByReference pid = new IntByReference();
                        U.GetWindowThreadProcessId(hwnd, pid);
                        out.add(new WindowInfo(hwnd, new String(buf, 0, n), pid.getValue(), r.left, r.top,
                                r.right - r.left, r.bottom - r.top));
                    }
                }
                return true;
            }
        }, null);
        return out;
    }

    /** 按标题模糊匹配找第一个窗口（包含子串，忽略大小写）；找不到返回 null */
    public static WindowInfo findWindow(String titlePart) {
        if (titlePart == null || titlePart.trim().isEmpty()) return null;
        String needle = titlePart.trim().toLowerCase(java.util.Locale.ROOT);
        for (WindowInfo w : visibleWindows())
            if (w.title.toLowerCase(java.util.Locale.ROOT).contains(needle)) return w;
        return null;
    }

    /** 前台激活：SetForegroundWindow 受 Windows 前台锁定限制，AttachThreadInput 绑定输入队列后再置前 */
    public static boolean activate(WindowInfo w) {
        if (U == null) return false;
        Pointer fg = U.GetForegroundWindow();
        if (fg != null && fg.equals(w.hwnd)) return true;
        boolean attached = false;
        int thisThread = K.GetCurrentThreadId();
        if (fg != null) {
            IntByReference unused = new IntByReference();
            int fgThread = U.GetWindowThreadProcessId(fg, unused);
            int targetThread = U.GetWindowThreadProcessId(w.hwnd, unused);
            if (fgThread != 0) {
                attached = U.AttachThreadInput(thisThread, fgThread, true);
                U.AttachThreadInput(targetThread, fgThread, true);
            }
        }
        try {
            U.ShowWindow(w.hwnd, SW_RESTORE);
            return U.SetForegroundWindow(w.hwnd);
        } finally {
            if (attached) U.AttachThreadInput(thisThread, 0, false);   // 解绑自身即可，不扰动目标对
        }
    }

    /** 温和关闭（WM_CLOSE）：等同点击关闭按钮，应用可取消（未保存弹窗等） */
    public static boolean close(WindowInfo w) {
        return U != null && U.PostMessageW(w.hwnd, WM_CLOSE, null, null);
    }

    /** 最小化 */
    public static void minimize(WindowInfo w) {
        if (U != null) U.ShowWindow(w.hwnd, SW_MINIMIZE);
    }
}
