package com.minion.core.ui.keybindings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * v2.4 §10.1 快捷键注册表：动作名 → 组合键（ctrl/alt/shift + key）；默认表与参考
 * 动作名对齐；用户覆盖 {jarDir}/.minion/keybindings.json（{"动作名":"ctrl+r",...}，
 * 只覆盖出现的键）。匹配接口与 FX 无关（headless 可测；MainWindow 把 KEY_PRESSED
 * 翻译进 match）。冲突（同键多动作）以先注册者为准并在 describe 标注。
 */
public final class KeyBindingRegistry {

    /** 默认绑定（动作名 → 组合） */
    private static final Map<String, String> DEFAULTS = buildDefaults();

    private static Map<String, String> buildDefaults() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("interrupt", "escape");
        m.put("rewind_selector", "escape,escape");          // 双击（调用方计窗）
        m.put("exit", "ctrl+c,ctrl+c");                      // 限时双击
        m.put("history_search", "ctrl+r");
        m.put("transcript_toggle", "ctrl+o");
        m.put("task_panel", "ctrl+t");
        m.put("background_panel", "ctrl+b");
        m.put("permission_mode_cycle", "shift+tab");
        m.put("model_select", "alt+p");
        m.put("thinking_toggle", "alt+t");
        m.put("undo_input", "ctrl+z");
        m.put("stash_prompt", "ctrl+s");
        m.put("external_editor", "ctrl+g");
        m.put("scroll_top", "ctrl+home");
        m.put("scroll_bottom", "ctrl+end");
        m.put("copy_selection", "ctrl+shift+c");
        return m;
    }

    private final Map<String, String> bindings = new LinkedHashMap<String, String>();

    public KeyBindingRegistry() {
        bindings.putAll(DEFAULTS);
    }

    /** 用户覆盖（损坏文件忽略保持默认；只接受已注册动作） */
    public KeyBindingRegistry loadOverrides(Path file) {
        try {
            if (!Files.isRegularFile(file)) return this;
            JsonObject root = JsonParser.parseString(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : root.entrySet()) {
                if (!bindings.containsKey(e.getKey()) || !e.getValue().isJsonPrimitive()) continue;
                String combo = normalize(e.getValue().getAsString());
                if (!combo.isEmpty()) bindings.put(e.getKey(), combo);
            }
        } catch (Exception ignored) {   // 损坏覆盖不阻断（保持默认）
        }
        return this;
    }

    /** 事件匹配：返回命中的动作名（单键组合；序列键 escape,escape 由调用方计窗后查 shortcutOf） */
    public String match(boolean ctrl, boolean alt, boolean shift, String key) {
        if (key == null || key.isEmpty()) return null;
        String combo = normalize((ctrl ? "ctrl+" : "") + (alt ? "alt+" : "")
                + (shift ? "shift+" : "") + key);
        for (Map.Entry<String, String> e : bindings.entrySet())
            if (!e.getValue().contains(",") && e.getValue().equals(combo)) return e.getKey();
        return null;
    }

    /** 序列绑定取值（如 "escape,escape"）；无序列返回 null */
    public String sequenceOf(String action) {
        String combo = bindings.get(action);
        return combo != null && combo.contains(",") ? combo : null;
    }

    public String shortcutOf(String action) { return bindings.get(action); }

    /** /keybindings 展示 */
    public String describe() {
        StringBuilder sb = new StringBuilder("快捷键（keybindings.json 可覆盖）：");
        for (Map.Entry<String, String> e : bindings.entrySet())
            sb.append("\n  ").append(e.getValue()).append("  →  ").append(e.getKey());
        return sb.toString();
    }

    /** "Ctrl+R" → "ctrl+r"（空白/大小写归一） */
    static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
