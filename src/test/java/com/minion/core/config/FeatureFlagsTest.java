package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * FeatureFlags 验收（v2.4 §13.1）：统一读取、来源追溯（默认/显式/会话覆盖三层）、
 * 未知键告警不炸、/config 视图、缓存失效（清除覆盖）。
 */
public class FeatureFlagsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 注入纯净默认资源（classpath）+ 显式外部覆盖，隔离本机真实配置 */
    private Config configWith(String externalLines) throws Exception {
        Path dir = tmp.newFolder().toPath();
        Config c = Config.load(dir, "/featureflags-test.properties");
        if (externalLines != null && !externalLines.isEmpty()) {
            // load() 首次会生成外部文件（内容=默认资源）；追加显式覆盖后重载
            Path ext = dir.resolve("config.properties");
            Files.write(ext, (new String(Files.readAllBytes(ext), StandardCharsets.UTF_8)
                    + externalLines).getBytes(StandardCharsets.UTF_8));
            c = Config.load(dir, "/featureflags-test.properties");
        }
        return c;
    }

    @Test public void catalogCoversSwitchTable() {
        // 14.3 总表抽查：安全例外与 legacy 默认
        assertTrue(FeatureFlags.known("permission.engine.v2"));
        assertTrue(FeatureFlags.known("read.ccSemantics"));
        assertTrue(FeatureFlags.known("session.transcript.jsonl"));
        for (FeatureFlags.Flag f : FeatureFlags.catalog()) {
            assertNotNull(f.section);
            assertNotNull(f.scope);
            assertFalse(f.key.isEmpty());
        }
        FeatureFlags.Flag secret = find("secret.store.v2");
        assertTrue(secret.securityException);
        assertEquals("true", secret.legacyDefault);
        FeatureFlags.Flag read = find("read.ccSemantics");
        assertFalse(read.securityException);
        assertEquals("false", read.legacyDefault);
    }

    private static FeatureFlags.Flag find(String key) {
        for (FeatureFlags.Flag f : FeatureFlags.catalog()) if (f.key.equals(key)) return f;
        return null;
    }

    @Test public void readsDefaultValueWithSourceTrace() throws Exception {
        FeatureFlags flags = new FeatureFlags(configWith(null));
        assertFalse(flags.enabled("read.ccSemantics"));
        assertEquals("默认值（legacy）", flags.source("read.ccSemantics"));
        assertTrue(flags.enabled("secret.store.v2"));
        assertEquals("默认值（legacy）", flags.source("secret.store.v2"));
    }

    @Test public void explicitExternalOverrideWinsAndTraces() throws Exception {
        FeatureFlags flags = new FeatureFlags(configWith("read.ccSemantics=true\nhooks.timeoutBehavior=deny\n"));
        assertTrue(flags.enabled("read.ccSemantics"));
        assertEquals("config.properties（显式）", flags.source("read.ccSemantics"));
        assertEquals("deny", flags.value("hooks.timeoutBehavior"));
        assertEquals("config.properties（显式）", flags.source("hooks.timeoutBehavior"));
        // 未动过的键仍是默认来源
        assertEquals("默认值（legacy）", flags.source("secret.store.v2"));
    }

    /** 会话内存覆盖 = 最高层：压过显式配置；清除后回落（缓存失效语义） */
    @Test public void sessionOverrideIsHighestLayerAndClearable() throws Exception {
        FeatureFlags flags = new FeatureFlags(configWith("hooks.enabled=true\n"));
        assertTrue(flags.enabled("hooks.enabled"));
        flags.override("hooks.enabled", "false");
        assertFalse(flags.enabled("hooks.enabled"));
        assertEquals("会话内存覆盖", flags.source("hooks.enabled"));
        // 清除覆盖 → 回落到 config.properties 显式值
        flags.override("hooks.enabled", null);
        assertTrue(flags.enabled("hooks.enabled"));
        assertEquals("config.properties（显式）", flags.source("hooks.enabled"));
        // 全量清除（新会话语义）
        flags.override("bash.analyzer.enabled", "false");
        flags.clearOverrides();
        assertTrue(flags.enabled("bash.analyzer.enabled"));
        assertEquals("默认值（legacy）", flags.source("bash.analyzer.enabled"));
    }

    /** 未知键：返回 Config 原始读取（不炸），目录不含该键 */
    @Test public void unknownKeyWarnsButDoesNotThrow() throws Exception {
        FeatureFlags flags = new FeatureFlags(configWith(null));
        assertFalse(FeatureFlags.known("no.such.flag"));
        assertNull(flags.value("no.such.flag"));   // config.properties 也没有 → null
        assertEquals("默认值（legacy）", flags.source("no.such.flag"));
    }

    /** /config 视图：含值/来源/生效范围/安全例外标注；过滤词生效 */
    @Test public void renderShowsValueSourceScopeAndFilter() throws Exception {
        FeatureFlags flags = new FeatureFlags(configWith(null));
        String all = flags.render(null);
        assertTrue(all.contains("read.ccSemantics"));
        assertTrue(all.contains("默认值（legacy）"));
        assertTrue(all.contains("安全修复例外"));
        String filtered = flags.render("secret");
        assertTrue(filtered.contains("secret.store.v2"));
        assertFalse(filtered.contains("read.ccSemantics"));
    }
}
