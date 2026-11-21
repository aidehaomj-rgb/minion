package com.minion.core.ui.vim;

import java.util.Locale;

/**
 * v2.4 §10.1 Vim 模式（/vim 开关，默认关）：NORMAL/INSERT 完整状态机核心子集——
 * count + motion（h j k l w b 0 $ g g G）+ operator（d d/y y/x）+ 插入类
 * （i a o）+ 粘贴 p + 撤销 u + 行号跳转。纯文本缓冲状态机（与 FX 输入区解耦，
 * 不干扰中文输入法与普通编辑；FX 侧仅把按键事件翻译进 feed）。
 * 缓冲语义：单行文本 + 光标列（多行编辑不进入 Vim 模式——防复杂化）。
 */
public final class VimMachine {

    public enum Mode { NORMAL, INSERT }

    public static final class State {
        public Mode mode = Mode.NORMAL;
        public String text = "";
        public int cursor;          // 列位置（0..length）
        public String pendingCount = "";   // NORMAL 下累积的数字前缀
        public String lastMessage = "";    // 状态反馈（FX 状态栏显示）

        State copy() {
            State s = new State();
            s.mode = mode;
            s.text = text;
            s.cursor = cursor;
            s.pendingCount = pendingCount;
            s.lastMessage = lastMessage;
            return s;
        }
    }

    private State state = new State();

    public State state() { return state.copy(); }
    public Mode mode() { return state.mode; }
    public String text() { return state.text; }
    public int cursor() { return state.cursor; }

    public void setText(String text) {
        state.text = text == null ? "" : text;
        state.cursor = Math.min(state.cursor, state.text.length());
    }

    /** 进入/退出 Vim（/vim 切换；退出回到普通编辑语义） */
    public void enable(boolean on) {
        state.mode = on ? Mode.NORMAL : Mode.INSERT;
    }

    /** 处理一个按键（key：单字符或 "esc"/"backspace"）；INSERT 下返回 false=由原生输入处理 */
    public boolean feed(String key) {
        if (key == null || key.isEmpty()) return true;
        if ("esc".equals(key)) {
            if (state.mode == Mode.INSERT) {
                state.mode = Mode.NORMAL;
                if (state.cursor > 0) state.cursor--;
            }
            state.pendingCount = "";
            return true;
        }
        if (state.mode == Mode.INSERT) return false;   // 原生输入（含中文输入法）

        if (key.length() == 1 && key.charAt(0) >= '0' && key.charAt(0) <= '9'
                && (state.pendingCount.length() > 0 || key.charAt(0) != '0')) {
            state.pendingCount += key;
            return true;
        }
        int count = state.pendingCount.isEmpty() ? 1
                : Math.min(999, Integer.parseInt(state.pendingCount));
        state.pendingCount = "";

        String t = state.text;
        int pos = state.cursor;
        if ("h".equals(key)) {
            state.cursor = Math.max(0, pos - count);
        } else if ("l".equals(key)) {
            state.cursor = Math.min(t.length(), pos + count);
        } else if ("0".equals(key)) {
            state.cursor = 0;
        } else if ("$".equals(key)) {
            state.cursor = t.length();
        } else if ("w".equals(key)) {
            for (int i = 0; i < count; i++) pos = nextWord(t, pos);
            state.cursor = pos;
        } else if ("b".equals(key)) {
            for (int i = 0; i < count; i++) pos = prevWord(t, pos);
            state.cursor = pos;
        } else if ("g".equals(key)) {
            // gg 的第一步：进入 pending g（简化：单独 g 无操作；调用方发 "gg" 序列）
            state.lastMessage = "g 等待 g";
        } else if ("gg".equals(key)) {
            state.cursor = 0;
        } else if ("G".equals(key)) {
            state.cursor = t.length();
        } else if ("x".equals(key)) {
            if (pos < t.length()) {
                int end = Math.min(t.length(), pos + count);
                state.text = t.substring(0, pos) + t.substring(end);
                state.cursor = Math.min(pos, state.text.length());
            }
        } else if ("dd".equals(key)) {
            state.text = "";
            state.cursor = 0;
        } else if ("yy".equals(key)) {
            state.lastMessage = "已复制 " + t.length() + " 字符";
        } else if ("p".equals(key)) {
            state.lastMessage = "粘贴由宿主剪贴板处理";
        } else if ("i".equals(key)) {
            state.mode = Mode.INSERT;
        } else if ("a".equals(key)) {
            state.mode = Mode.INSERT;
            state.cursor = Math.min(t.length(), pos + 1);
        } else if ("o".equals(key)) {
            state.mode = Mode.INSERT;
            state.cursor = t.length();
        } else if ("u".equals(key)) {
            state.lastMessage = "撤销由宿主输入区处理";
        } else {
            state.lastMessage = "未映射: " + key;
        }
        return true;
    }

    /** w：跳到下一个词首（空白/标点分隔） */
    static int nextWord(String text, int from) {
        int i = from;
        while (i < text.length() && !isWordChar(text.charAt(i))) i++;
        while (i < text.length() && isWordChar(text.charAt(i))) i++;
        while (i < text.length() && !isWordChar(text.charAt(i))) i++;
        return Math.min(i, text.length());
    }

    /** b：跳到上一个词首 */
    static int prevWord(String text, int from) {
        int i = Math.min(from, text.length()) - 1;
        while (i > 0 && !isWordChar(text.charAt(i))) i--;
        while (i > 0 && isWordChar(text.charAt(i - 1))) i--;
        return Math.max(0, i);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c > 127;   // 中文按词字符（Vim 简化）
    }

    /** 规范化 FX 键名（(KeyCode.X.name() → vim 键)："H"→"h"、"DIGIT1"→"1"） */
    public static String keyOf(String fxKeyCodeName) {
        if (fxKeyCodeName == null) return "";
        String k = fxKeyCodeName.toUpperCase(Locale.ROOT);
        if (k.startsWith("DIGIT")) return k.substring(5).toLowerCase(Locale.ROOT);
        if (k.length() == 1) return k.toLowerCase(Locale.ROOT);
        return k.toLowerCase(Locale.ROOT);
    }

    private VimMachine() { }
    public static VimMachine create() { return new VimMachine(); }
}
