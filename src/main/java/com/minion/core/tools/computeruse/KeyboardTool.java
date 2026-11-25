package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 计算机使用·键盘：文本输入与组合键。
 *  关键实现：Robot.keyPress 只能发 USB 键码（英文/数字/控制键），**中文必须走剪贴板+Ctrl+V**
 *  ——type 对纯可打印 ASCII 逐键输入（不占用剪贴板），含非 ASCII 文本先写剪贴板再粘贴（会覆盖剪贴板内容）。
 *  输入类动作一律高危确认。 */
public final class KeyboardTool implements Tool {
    private final ClipboardAccess clipboard;
    private final int restoreDelayMs;

    public KeyboardTool() { this(new AwtClipboard(), 120); }
    /** 三批 C2：剪贴板访问可注入（headless 测试 fake）；restoreDelayMs=粘贴后恢复前缓冲 */
    public KeyboardTool(ClipboardAccess clipboard, int restoreDelayMs) {
        this.clipboard = clipboard;
        this.restoreDelayMs = restoreDelayMs;
    }

    @Override public String name() { return "Keyboard"; }

    @Override public String description() {
        return "计算机使用-键盘。action=type：输入文本（纯英文逐键；含中文自动走剪贴板+Ctrl+V，粘贴后延迟缓冲并恢复原剪贴板内容）；"
                + "action=pressKey：组合键，如 enter / esc / tab / ctrl+a / ctrl+shift+s / alt+f4 / win+r / ctrl+v。"
                + "输入前建议先点击目标输入框，输入后 Screen 截图验证。";
    }

    @Override public JsonObject schema() {
        return com.minion.core.tools.SchemaGenerator.objectSchema(description(),
                new String[]{"action", "text", "key"}, new String[]{"action"});
    }

    @Override public boolean isHighRisk(JsonObject args) { return true; }   // 键盘输入一律确认

    @Override public ToolResult execute(JsonObject args) {
        String action = str(args, "action");
        try {
            Robot robot = new Robot();
            if ("type".equals(action)) {
                String text = str(args, "text");
                if (text.isEmpty()) return ToolResult.error("text 不能为空");
                if (isDirectTypeable(text)) {
                    typeAscii(robot, text);
                    return ToolResult.success("已逐键输入 " + text.length() + " 个 ASCII 字符（未占用剪贴板）");
                }
                ClipboardPaste.pastePreserving(clipboard, text, new Runnable() {
                    @Override public void run() {
                        robot.delay(120);
                        robot.keyPress(KeyEvent.VK_CONTROL);
                        robot.keyPress(KeyEvent.VK_V);
                        robot.delay(60);
                        robot.keyRelease(KeyEvent.VK_V);
                        robot.keyRelease(KeyEvent.VK_CONTROL);
                    }
                }, new ClipboardPaste.Delay() {
                    @Override public void ms(int millis) { robot.delay(millis); }
                }, restoreDelayMs);
                return ToolResult.success("文本含非 ASCII 字符，已通过剪贴板+Ctrl+V 粘贴 " + text.length() + " 字（原剪贴板内容已尝试恢复）");
            }
            if ("pressKey".equals(action)) {
                String key = str(args, "key").toLowerCase(Locale.ROOT);
                if (key.isEmpty()) return ToolResult.error("key 不能为空");
                int[] combo = parseCombo(key);
                if (combo == null) return ToolResult.error("无法识别按键: " + key + "（可用 enter/esc/tab/space/backspace/delete/up/down/left/right/home/end/pageup/pagedown/f1-f12/a-z/0-9/ctrl/alt/shift/win 组合）");
                for (int kc : combo) robot.keyPress(kc);
                robot.delay(50);
                for (int i = combo.length - 1; i >= 0; i--) robot.keyRelease(combo[i]);
                return ToolResult.success("已按下 " + key);
            }
            return ToolResult.error("action 必须是 type 或 pressKey");
        } catch (Exception e) {
            return ToolResult.error("键盘操作失败: " + e.getMessage());
        }
    }

    /** 全部为可直接逐键输入的字符（ASCII 可打印，不含需要剪贴板的中文等） */
    static boolean isDirectTypeable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    static void typeAscii(Robot robot, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean upper = Character.isUpperCase(c) || NEEDS_SHIFT.indexOf(c) >= 0;
            int base = Character.toUpperCase(c);
            Integer code = ASCII_KEYS.get(base);
            if (code == null) continue;   // 理论不会发生（isDirectTypeable 已过滤）
            if (upper) robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(code);
            robot.delay(20);
            robot.keyRelease(code);
            if (upper) robot.keyRelease(KeyEvent.VK_SHIFT);
            robot.delay(15);
        }
    }

    /** 解析组合键 → 键码序列（修饰键在前，主键在最后）；不识别返回 null */
    static int[] parseCombo(String key) {
        String[] parts = key.split("\\+");
        List<Integer> out = new ArrayList<Integer>();
        boolean hasMain = false;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) return null;
            Integer kc;
            if ("ctrl".equals(p)) kc = KeyEvent.VK_CONTROL;
            else if ("alt".equals(p)) kc = KeyEvent.VK_ALT;
            else if ("shift".equals(p)) kc = KeyEvent.VK_SHIFT;
            else if ("win".equals(p) || "meta".equals(p)) kc = KeyEvent.VK_WINDOWS;
            else {
                kc = MAIN_KEYS.get(p);
                if (kc == null) return null;
                if (hasMain) return null;   // 只允许一个主键
                hasMain = true;
                out.add(kc);
                continue;
            }
            if (i == parts.length - 1) return null;   // 修饰键不能是最后一个
            out.add(kc);
        }
        return hasMain ? toIntArray(out) : null;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    /** 这些字符需 Shift+基础键（JDK8 KeyEvent 缺 VK_PIPE/VK_TILDE 等常量，统一走基础键+Shift） */
    static final String NEEDS_SHIFT = "!@#$%^&*()_+{}|:\"<>?~";
    private static final Map<Integer, Integer> ASCII_KEYS = new HashMap<Integer, Integer>();
    static {
        for (char c = 'A'; c <= 'Z'; c++) { ASCII_KEYS.put((int) c, KeyEvent.VK_A + (c - 'A')); ASCII_KEYS.put((int) Character.toLowerCase(c), KeyEvent.VK_A + (c - 'A')); }
        for (char c = '0'; c <= '9'; c++) ASCII_KEYS.put((int) c, KeyEvent.VK_0 + (c - '0'));
        ASCII_KEYS.put((int) ' ', KeyEvent.VK_SPACE);
        ASCII_KEYS.put((int) '\t', KeyEvent.VK_TAB);
        ASCII_KEYS.put((int) '\n', KeyEvent.VK_ENTER);
        ASCII_KEYS.put((int) '.', KeyEvent.VK_PERIOD); ASCII_KEYS.put((int) ',', KeyEvent.VK_COMMA);
        ASCII_KEYS.put((int) ';', KeyEvent.VK_SEMICOLON); ASCII_KEYS.put((int) ':', KeyEvent.VK_SEMICOLON);
        ASCII_KEYS.put((int) '/', KeyEvent.VK_SLASH); ASCII_KEYS.put((int) '?', KeyEvent.VK_SLASH);
        ASCII_KEYS.put((int) '\\', KeyEvent.VK_BACK_SLASH); ASCII_KEYS.put((int) '|', KeyEvent.VK_BACK_SLASH);
        ASCII_KEYS.put((int) '[', KeyEvent.VK_OPEN_BRACKET); ASCII_KEYS.put((int) ']', KeyEvent.VK_CLOSE_BRACKET);
        ASCII_KEYS.put((int) '{', KeyEvent.VK_OPEN_BRACKET); ASCII_KEYS.put((int) '}', KeyEvent.VK_CLOSE_BRACKET);
        ASCII_KEYS.put((int) '-', KeyEvent.VK_MINUS); ASCII_KEYS.put((int) '_', KeyEvent.VK_MINUS);
        ASCII_KEYS.put((int) '=', KeyEvent.VK_EQUALS); ASCII_KEYS.put((int) '+', KeyEvent.VK_EQUALS);
        ASCII_KEYS.put((int) '\'', KeyEvent.VK_QUOTE); ASCII_KEYS.put((int) '"', KeyEvent.VK_QUOTE);
        ASCII_KEYS.put((int) '`', KeyEvent.VK_BACK_QUOTE); ASCII_KEYS.put((int) '~', KeyEvent.VK_BACK_QUOTE);
        ASCII_KEYS.put((int) '!', KeyEvent.VK_1); ASCII_KEYS.put((int) '@', KeyEvent.VK_2);
        ASCII_KEYS.put((int) '#', KeyEvent.VK_3); ASCII_KEYS.put((int) '$', KeyEvent.VK_4);
        ASCII_KEYS.put((int) '%', KeyEvent.VK_5); ASCII_KEYS.put((int) '^', KeyEvent.VK_6);
        ASCII_KEYS.put((int) '&', KeyEvent.VK_7); ASCII_KEYS.put((int) '*', KeyEvent.VK_8);
        ASCII_KEYS.put((int) '(', KeyEvent.VK_9); ASCII_KEYS.put((int) ')', KeyEvent.VK_0);
        ASCII_KEYS.put((int) '<', KeyEvent.VK_COMMA); ASCII_KEYS.put((int) '>', KeyEvent.VK_PERIOD);
    }

    private static final Map<String, Integer> MAIN_KEYS = new HashMap<String, Integer>();
    static {
        for (char c = 'a'; c <= 'z'; c++) MAIN_KEYS.put(String.valueOf(c), KeyEvent.VK_A + (c - 'a'));
        for (char c = '0'; c <= '9'; c++) MAIN_KEYS.put(String.valueOf(c), KeyEvent.VK_0 + (c - '0'));
        MAIN_KEYS.put("enter", KeyEvent.VK_ENTER); MAIN_KEYS.put("return", KeyEvent.VK_ENTER);
        MAIN_KEYS.put("esc", KeyEvent.VK_ESCAPE); MAIN_KEYS.put("escape", KeyEvent.VK_ESCAPE);
        MAIN_KEYS.put("tab", KeyEvent.VK_TAB); MAIN_KEYS.put("space", KeyEvent.VK_SPACE);
        MAIN_KEYS.put("backspace", KeyEvent.VK_BACK_SPACE); MAIN_KEYS.put("delete", KeyEvent.VK_DELETE);
        MAIN_KEYS.put("del", KeyEvent.VK_DELETE);
        MAIN_KEYS.put("up", KeyEvent.VK_UP); MAIN_KEYS.put("down", KeyEvent.VK_DOWN);
        MAIN_KEYS.put("left", KeyEvent.VK_LEFT); MAIN_KEYS.put("right", KeyEvent.VK_RIGHT);
        MAIN_KEYS.put("home", KeyEvent.VK_HOME); MAIN_KEYS.put("end", KeyEvent.VK_END);
        MAIN_KEYS.put("pageup", KeyEvent.VK_PAGE_UP); MAIN_KEYS.put("pagedown", KeyEvent.VK_PAGE_DOWN);
        for (int f = 1; f <= 12; f++) MAIN_KEYS.put("f" + f, KeyEvent.VK_F1 + (f - 1));
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
