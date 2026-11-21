package com.minion.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §13.1 环境变量显式映射表：配置键 ↔ MINION_* 环境变量。
 * 刻意不用机械点号/驼峰转换（易冲突、难追溯）；每个映射显式登记于此。
 * 未知 MINION_* 变量（未登记）在 doctor 中告警——可能是拼写错误或已废弃键。
 */
public final class EnvMapping {

    /** 配置键 → 环境变量名（LinkedHashMap 保展示顺序） */
    private static final Map<String, String> TABLE = build();

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("permission.mode", "MINION_PERMISSION_MODE");
        m.put("permission.engine.v2", "MINION_PERMISSION_ENGINE_V2");
        m.put("toolsearch.enabled", "MINION_TOOLSEARCH_ENABLED");
        m.put("hooks.enabled", "MINION_HOOKS_ENABLED");
        m.put("microcompact.enabled", "MINION_MICROCOMPACT_ENABLED");
        m.put("instructions.enabled", "MINION_INSTRUCTIONS_ENABLED");
        m.put("lsp.enabled", "MINION_LSP_ENABLED");
        m.put("team.enabled", "MINION_TEAM_ENABLED");
        m.put("notify.channel", "MINION_NOTIFY_CHANNEL");
        m.put("trust.store.v2", "MINION_TRUST_STORE_V2");
        m.put("model.url", "MINION_MODEL_URL");
        m.put("model.key", "MINION_MODEL_KEY");
        return Collections.unmodifiableMap(m);
    }

    /** 已登记映射（doctor/config 展示） */
    public static Map<String, String> table() { return TABLE; }

    /** 环境变量名 → 配置键 */
    public static String configKeyOf(String envVar) {
        for (Map.Entry<String, String> e : TABLE.entrySet())
            if (e.getValue().equals(envVar)) return e.getKey();
        return null;
    }

    /** 已设置的映射层（env 值非空才进链；可注入 env 供测试） */
    public static Map<String, String> resolved(Map<String, String> env) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : TABLE.entrySet()) {
            String v = env.get(e.getValue());
            if (v != null && !v.trim().isEmpty()) out.put(e.getKey(), v.trim());
        }
        return out;
    }

    public static Map<String, String> resolved() { return resolved(System.getenv()); }

    /** 未登记的 MINION_* 变量（doctor 告警；排除非配置用途的保留前缀） */
    public static List<String> unknownMinionEnv(Map<String, String> env) {
        List<String> unknown = new ArrayList<String>();
        for (String name : env.keySet())
            if (name.startsWith("MINION_") && configKeyOf(name) == null
                    && !name.startsWith("MINION_PYTHON"))   // MINION_PYTHON：解释器探测保留
                unknown.add(name);
        Collections.sort(unknown);
        return unknown;
    }

    public static List<String> unknownMinionEnv() { return unknownMinionEnv(System.getenv()); }

    private EnvMapping() { }
}
