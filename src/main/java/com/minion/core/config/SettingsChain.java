package com.minion.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §13.1 settings.json 通用分层链（低 → 高）：企业策略（只读）→ 用户（jarDir/.minion）→
 * 项目（workspace/.minion）→ 本地（settings.local.json）→ CLI --settings → 环境变量
 * （显式映射 {@link EnvMapping}）。会话内存态由 FeatureFlags 覆盖层承担（更高）。
 * config.properties 作为既有用户级通用配置，位于企业策略之下（企业策略须能压过用户层）。
 *
 * <p>路径口径与 RuleStore（§4.1 权限规则）完全一致——同一文件同一层，不建第二套来源。
 * 领域文件（model.json/tools.json/mcp.json/workspace.json）保持唯一真源，本链只承载
 * 通用行为/权限/hooks/FeatureFlags 键；未识别键不告警只列示（doctor 可见）。
 *
 * <p>CC 风格键经 {@link #canonicalKey} 显式映射到 minion 键（如 permissions.defaultMode →
 * permission.mode）；无映射的键原样保留。损坏/无法解析的层跳过并记录（不阻断其余层）。
 */
public final class SettingsChain {

    /** 单层：名称/文件/展平键值（dotted）+ 是否成功装载 */
    public static final class Layer {
        public final String name;
        public final Path file;
        public final Map<String, String> flat;
        public final boolean present;
        public final String note;      // 未装载原因（损坏/不存在）

        Layer(String name, Path file, Map<String, String> flat, boolean present, String note) {
            this.name = name;
            this.file = file;
            this.flat = flat == null ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(flat));
            this.present = present;
            this.note = note;
        }
    }

    private static final long MAX_FILE_BYTES = 512 * 1024;

    private final List<Layer> layers;       // 低 → 高

    private SettingsChain(List<Layer> layers) { this.layers = layers; }

    /**
     * 装配全链。workspace 可为 null（仅全局层）；cliSettings 为 --settings 文件（可多个，后者覆盖前者）。
     */
    public static SettingsChain load(Path jarDir, Path workspace, List<Path> cliSettings) {
        return load(jarDir, workspace, cliSettings, System.getenv());
    }

    /** 同上，env 可注入（测试；企业策略层仅在 ProgramData 存在时加入） */
    static SettingsChain load(Path jarDir, Path workspace, List<Path> cliSettings,
            Map<String, String> env) {
        List<Layer> layers = new ArrayList<Layer>();
        String programData = env.get("ProgramData");
        if (programData != null && !programData.trim().isEmpty())
            layers.add(fileLayer("企业策略", Paths.get(programData, "minion", "settings.json")));
        layers.add(fileLayer("用户", jarDir.resolve(".minion").resolve("settings.json")));
        if (workspace != null) {
            layers.add(fileLayer("项目", workspace.resolve(".minion").resolve("settings.json")));
            layers.add(fileLayer("本地", workspace.resolve(".minion").resolve("settings.local.json")));
        }
        if (cliSettings != null)
            for (Path p : cliSettings) layers.add(fileLayer("CLI", p));
        layers.add(new Layer("环境变量", null, EnvMapping.resolved(env), true, null));
        return new SettingsChain(layers);
    }

    /** 高 → 低首个命中；未命中 null */
    public String resolve(String key) {
        String canonical = canonicalKey(key);
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer l = layers.get(i);
            String v = l.flat.get(canonical);
            if (v == null) v = l.flat.get(key);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** 命中层名（来源追溯）；未命中 null */
    public String sourceOf(String key) {
        String canonical = canonicalKey(key);
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer l = layers.get(i);
            if (l.flat.containsKey(canonical) || l.flat.containsKey(key)) return l.name;
        }
        return null;
    }

    /** 低 → 高层列表（只读副本） */
    public List<Layer> layers() { return new ArrayList<Layer>(layers); }

    /** doctor / /config 展示：每层名称 + 装载状态 + 键数 */
    public String describe() {
        StringBuilder sb = new StringBuilder("settings 分层（低 → 高）：");
        for (Layer l : layers) {
            sb.append(String.format("%n  %-6s %-3d 键  %s", l.name, l.flat.size(),
                    l.present ? (l.file == null ? "(env)" : l.file.toString())
                            : "(" + l.note + ")"));
        }
        return sb.toString();
    }

    /** 链上出现的全部键（含未识别；doctor 列示用） */
    public List<String> allKeys() {
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        for (Layer l : layers)
            for (String k : l.flat.keySet()) seen.put(k, true);
        return new ArrayList<String>(seen.keySet());
    }

    /**
     * CC 风格 settings.json 键 → minion 配置键（显式表；未列出者原样）。
     * 新增映射必须在此登记——保持「读取旧值→写唯一目标→回读校验」的可追溯性。
     */
    public static String canonicalKey(String flatKey) {
        if (flatKey == null) return null;
        if ("permissions.defaultMode".equals(flatKey)) return "permission.mode";
        if ("permissions.managedOnly".equals(flatKey)) return "permissions.managedOnly";
        if ("disableAllHooks".equals(flatKey)) return "hooks.disableAll";
        if ("effortLevel".equals(flatKey)) return "model.reasoningEffort";
        if ("outputStyle".equals(flatKey)) return "output.style";
        if ("language".equals(flatKey)) return "output.language";
        if ("cleanupPeriodDays".equals(flatKey)) return "session.cleanupPeriodDays";
        if ("respectGitignore".equals(flatKey)) return "glob.respectGitignore";
        if ("attribution".equals(flatKey)) return "attribution.commit";
        if ("spinnerTips".equals(flatKey)) return "ui.spinnerTips";
        if ("syntaxHighlightingDisabled".equals(flatKey)) return "ui.syntaxHighlightingDisabled";
        if ("autoMemoryEnabled".equals(flatKey)) return "memory.autoEnabled";
        return flatKey;
    }

    // ==================== 内部 ====================

    private static Layer fileLayer(String name, Path file) {
        Map<String, String> flat = new LinkedHashMap<String, String>();
        if (!Files.isRegularFile(file)) return new Layer(name, file, flat, false, "不存在");
        try {
            if (Files.size(file) > MAX_FILE_BYTES)
                return new Layer(name, file, flat, false, "超过 512KiB 上限");
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonObject())
                return new Layer(name, file, flat, false, "非 JSON 对象");
            flatten("", root.getAsJsonObject(), flat);
            // CC 风格键同时以 minion 规范键登记（permissions.defaultMode → permission.mode），
            // 使 resolve(规范键) 直接命中；原键保留（RuleStore 等按原文消费）
            Map<String, String> canonical = new LinkedHashMap<String, String>(flat);
            for (Map.Entry<String, String> e : flat.entrySet()) {
                String k = canonicalKey(e.getKey());
                if (!k.equals(e.getKey())) canonical.put(k, e.getValue());
            }
            return new Layer(name, file, canonical, true, null);
        } catch (Exception broken) {
            return new Layer(name, file, flat, false, "损坏: " + broken.getMessage());
        }
    }

    /** 嵌套对象 → dotted 键；数组/原始值 → 字符串（数组保留 JSON 文本） */
    private static void flatten(String prefix, JsonObject obj, Map<String, String> out) {
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonElement v = e.getValue();
            if (v.isJsonObject()) {
                flatten(key, v.getAsJsonObject(), out);
            } else if (v.isJsonArray()) {
                out.put(key, jsonArrayText(v.getAsJsonArray()));
            } else if (v.isJsonPrimitive() && !v.getAsJsonPrimitive().isJsonNull()) {
                out.put(key, v.getAsString());
            }
        }
    }

    private static String jsonArrayText(JsonArray a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) sb.append(',');
            JsonElement e = a.get(i);
            sb.append(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? "\"" : "");
            sb.append(e.getAsString());
            sb.append(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? "\"" : "");
        }
        return sb.append(']').toString();
    }
}
