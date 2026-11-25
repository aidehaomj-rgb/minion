package com.minion.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FeatureFlags（开发说明 v2.4 §13.1）：全部行为开关的统一读取口。
 *
 * <p>读取层次（后覆盖前）：classpath 默认值 → config.properties（显式）→ 会话内存覆盖
 * （{@link #override}，最高层）。每个 flag 的<b>来源可追溯</b>（值来自哪一层），
 * <b>生效范围</b>标注在目录里（立即 / 下一轮 / 新会话 / 重启）。无远程求值。
 * 运行中修改：/config 或 {@link #override} 即时生效于后续读取；各消费方按自身节约定
 * 在下一轮或新会话应用（开关值读取不做缓存，天然失效）。未知键仅告警（doctor 口径），
 * 不抛异常——老配置文件里的已废弃键不能让启动失败。
 */
public final class FeatureFlags {

    /** 单个开关的目录条目（14.3 总表） */
    public static final class Flag {
        public final String key;
        public final String legacyDefault;
        public final String section;
        public final String scope;      // 生效范围：立即 / 下一轮 / 新会话 / 重启
        public final boolean securityException;   // 0.3 明示安全修复例外（默认值提高安全级别）

        Flag(String key, String legacyDefault, String section, String scope, boolean securityException) {
            this.key = key;
            this.legacyDefault = legacyDefault;
            this.section = section;
            this.scope = scope;
            this.securityException = securityException;
        }
    }

    /** 14.3 开关默认值总表（顺序即展示顺序；安全例外单独标注） */
    private static final Map<String, Flag> CATALOG = buildCatalog();

    private static Map<String, Flag> buildCatalog() {
        Map<String, Flag> m = new LinkedHashMap<String, Flag>();
        Object[][] rows = {
            {"cost.tracking.enabled", "true", "1.1", "新会话", false},
            {"cost.cachebreak.detect", "true", "1.1", "新会话", false},
            {"retry.profile", "legacy", "1.2", "新会话", false},
            {"model.fallbackChain", "", "1.3", "新会话", false},
            {"llm.autoContinue.enabled", "true", "1.4", "下一轮", false},
            {"budget.enabled", "true", "1.5", "下一轮", false},
            {"tools.parallel.metadataDriven", "false", "2.2", "下一轮", false},
            {"tools.maxConcurrency", "legacy", "2.2", "下一轮", false},
            {"bash.analyzer.enabled", "true", "4.4", "下一轮", true},
            {"bash.snapshot.enabled", "false", "3.2", "下一轮", false},
            {"bash.autoBackground", "false", "3.3", "下一轮", false},
            {"bash.sessionEnv.enabled", "false", "3.3", "下一轮", false},
            {"permission.engine.v2", "true", "4.1", "下一轮", true},
            {"mcp.confirm.skip", "false", "3.7", "下一轮", false},
            {"mcp.trustedConcurrencyServers", "", "3.7", "下一轮", false},
            {"secret.store.v2", "true", "4.6", "重启", true},
            {"hooks.enabled", "false", "4.7", "下一轮", false},
            {"hooks.timeoutBehavior", "ask", "4.7", "下一轮", false},
            {"ssh.strictHostKey", "true", "4.8", "下一轮", true},
            {"trust.store.v2", "true", "10.9", "重启", true},
            {"microcompact.enabled", "false", "5.1", "下一轮", false},
            {"autocompact.formula", "false", "5.2", "新会话", false},
            {"compact.restore.enabled", "true", "5.3", "新会话", false},
            {"toolsearch.enabled", "false", "5.4", "下一轮", false},
            {"toolResults.budget.enabled", "false", "5.6", "下一轮", false},
            {"compact.sessionMemory.enabled", "false", "5.7", "新会话", false},
            {"toolSummary.enabled", "false", "5.8", "下一轮", false},
            {"instructions.enabled", "false", "6.1", "新会话", false},
            {"memory.dream.enabled", "false", "6.2", "新会话", false},
            {"skills.enabled", "true", "7.1", "下一轮", false},
            {"skills.shellInjection", "true", "7.1", "下一轮", false},
            {"team.enabled", "false", "8.2", "重启", false},
            {"agent.coordinatorMode", "false", "8.3", "重启", false},
            {"session.transcript.jsonl", "false", "9.1", "新会话", false},
            {"checkpoint.messageLevel", "false", "9.2", "新会话", false},
            {"session.cleanupPeriodDays", "0", "9.1", "重启", false},
            {"lsp.enabled", "false", "3.1", "重启", false},
            {"ide.bridge.enabled", "false", "12.3", "重启", false},
            {"tools.monitor.enabled", "false", "3.2", "下一轮", false},
            {"tools.webfetch.intranet.enabled", "false", "3.9", "下一轮", false},
            {"glob.ccSemantics", "false", "3.1", "下一轮", false},
            {"read.ccSemantics", "false", "3.1", "下一轮", false},
            {"permissions.managedOnly", "false", "4.1", "新会话", false},
            {"notify.channel", "tray", "10.8", "立即", false},
            {"knowledge.search.index.enabled", "true", "三批B1", "立即", false},
            {"session.recall.enabled", "true", "三批B3", "新会话", false},
            {"intent.clarify.policy", "conservative", "三批D1", "新会话", false},
        };
        for (Object[] r : rows)
            m.put((String) r[0], new Flag((String) r[0], (String) r[1], (String) r[2], (String) r[3], (Boolean) r[4]));
        return Collections.unmodifiableMap(m);
    }

    public static List<Flag> catalog() { return new ArrayList<Flag>(CATALOG.values()); }

    public static boolean known(String key) { return CATALOG.containsKey(key); }

    private final Config config;
    /** 会话内存覆盖（最高层）：/config override 与测试注入用 */
    private final Map<String, String> overrides = new ConcurrentHashMap<String, String>();

    public FeatureFlags(Config config) { this.config = config; }

    /** 生效值：会话覆盖 → 显式 config.properties → 目录默认值；未知键返回 null 并告警 */
    public String value(String key) {
        String o = overrides.get(key);
        if (o != null) return o;
        Flag f = CATALOG.get(key);
        if (f == null) {
            com.minion.core.diagnostics.DiagnosticLog.warn("config", "未知 FeatureFlag 键（doctor 可见）: " + key);
            return config.get(key, null);
        }
        return config.get(key, f.legacyDefault);
    }

    public boolean enabled(String key) { return Boolean.parseBoolean(value(key)); }

    /** 来源追溯：会话内存覆盖 / settings 分层链（§13.1）/ config.properties（显式覆盖默认）/ 默认值（legacy） */
    public String source(String key) {
        if (overrides.containsKey(key)) return "会话内存覆盖";
        // §13.1 分层链命中 → 报告层名（企业策略/用户/项目/本地/CLI/环境变量）
        com.minion.core.config.SettingsChain chain = config.settingsChain();
        if (chain != null) {
            String layer = chain.sourceOf(key);
            if (layer != null) return "settings:" + layer;
        }
        Flag f = CATALOG.get(key);
        String def = f != null ? f.legacyDefault : null;
        String fromDefault = config.defaultOf(key);
        String effective = config.get(key, def);
        // 生效值 ≠ classpath 默认 → 外部文件显式设置过；等于默认（或默认资源里没有该键）→ 默认值
        if (fromDefault != null && !fromDefault.equals(effective)) return "config.properties（显式）";
        if (fromDefault == null && def != null && !def.equals(effective)) return "config.properties（显式）";
        return "默认值（legacy）";
    }

    /** 会话内存覆盖（最高层，不落盘）；null 值 = 清除该键覆盖 */
    public void override(String key, String value) {
        if (value == null) overrides.remove(key);
        else overrides.put(key, value);
    }

    /** 清除全部会话覆盖（config 重载/新会话时失效相关缓存） */
    public void clearOverrides() { overrides.clear(); }

    /** /config 展示：值 + 来源 + 生效范围（+ 安全例外标注）；keyFilter 为空展示全部 */
    public String render(String keyFilter) {
        StringBuilder sb = new StringBuilder("FeatureFlags（值 ← 来源 | 生效范围）\n");
        for (Flag f : CATALOG.values()) {
            if (keyFilter != null && !keyFilter.isEmpty() && !f.key.contains(keyFilter)) continue;
            sb.append(String.format("%-34s %-8s ← %-24s | %s%s%n", f.key, value(f.key), source(f.key), f.scope,
                    f.securityException ? "  [安全修复例外]" : ""));
        }
        return sb.toString();
    }
}
