package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * §13.1 settings.json 分层验收：企业→用户→项目→本地→CLI→env 逐层覆盖、嵌套展平、
 * 损坏层跳过不阻断、CC 键显式映射、未装配时 Config 零影响（V68 非回归）。
 */
public class SettingsChainTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(Path file, String json) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> emptyEnv() throws java.io.IOException {
        Map<String, String> env = new HashMap<String, String>();
        env.put("ProgramData", tmp.newFolder().toString());   // 空企业目录（无 settings.json）
        return env;
    }

    /** 逐层覆盖：本地 > 项目 > 用户；CLI > 本地；env > CLI */
    @Test public void layerPrecedenceLowToHigh() throws Exception {
        Path jar = tmp.newFolder().toPath();
        Path ws = tmp.newFolder().toPath();
        write(jar.resolve(".minion").resolve("settings.json"),
                "{\"test.chain.key\":\"user\",\"only.user\":\"u\"}");
        write(ws.resolve(".minion").resolve("settings.json"),
                "{\"test.chain.key\":\"project\"}");
        write(ws.resolve(".minion").resolve("settings.local.json"),
                "{\"test.chain.key\":\"local\"}");
        Path cli = tmp.newFile().toPath();
        write(cli, "{\"test.chain.key\":\"cli\"}");
        Map<String, String> env = emptyEnv();
        env.put("MINION_TEST_CHAIN", "");   // 无此映射；env 层测试用已登记键
        // 无 env 覆盖：CLI 最高
        SettingsChain noEnv = SettingsChain.load(jar, ws,
                Collections.singletonList(cli), emptyEnv());
        assertEquals("cli", noEnv.resolve("test.chain.key"));
        assertEquals("CLI", noEnv.sourceOf("test.chain.key"));
        assertEquals("u", noEnv.resolve("only.user"));
        // env 层（已登记键 permission.mode）最高
        env.put("MINION_PERMISSION_MODE", "read-only");
        SettingsChain withEnv = SettingsChain.load(jar, ws,
                Collections.singletonList(cli), env);
        assertEquals("read-only", withEnv.resolve("permission.mode"));
        // 去掉 CLI 层：本地最高
        SettingsChain noCli = SettingsChain.load(jar, ws, null, emptyEnv());
        assertEquals("local", noCli.resolve("test.chain.key"));
        assertEquals("本地", noCli.sourceOf("test.chain.key"));
    }

    /** 嵌套展平：对象→dotted 键；数组保留 JSON 文本；CC 键显式映射 */
    @Test public void flattenAndCanonicalMapping() throws Exception {
        Path jar = tmp.newFolder().toPath();
        Path ws = tmp.newFolder().toPath();
        write(ws.resolve(".minion").resolve("settings.json"),
                "{\"permissions\":{\"defaultMode\":\"full\",\"allow\":[\"Bash(git *)\",\"WebFetch\"]},"
                        + "\"hooks\":{\"Stop\":1},\"disableAllHooks\":true,\"effortLevel\":\"low\"}");
        SettingsChain chain = SettingsChain.load(jar, ws, null, emptyEnv());
        // CC 键 → minion 键
        assertEquals("full", chain.resolve("permission.mode"));
        assertEquals("true", chain.resolve("hooks.disableAll"));
        assertEquals("low", chain.resolve("model.reasoningEffort"));
        // 数组保留 JSON 文本（规则表由 RuleStore 消费）
        assertEquals("[\"Bash(git *)\",\"WebFetch\"]", chain.resolve("permissions.allow"));
        // 未映射嵌套键原样
        assertEquals("1", chain.resolve("hooks.Stop"));
        assertNull(chain.resolve("not.set.anywhere"));
        assertEquals("项目", chain.sourceOf("permission.mode"));
    }

    /** 损坏层跳过不阻断其余层；describe/unknownKeys 展示 */
    @Test public void corruptedLayerSkippedOthersUsable() throws Exception {
        Path jar = tmp.newFolder().toPath();
        Path ws = tmp.newFolder().toPath();
        write(jar.resolve(".minion").resolve("settings.json"), "{ not json");
        write(ws.resolve(".minion").resolve("settings.json"),
                "{\"test.chain.key\":\"project-ok\"}");
        SettingsChain chain = SettingsChain.load(jar, ws, null, emptyEnv());
        assertEquals("project-ok", chain.resolve("test.chain.key"));   // 项目层仍可用
        boolean userBroken = false;
        for (SettingsChain.Layer l : chain.layers())
            if ("用户".equals(l.name)) { userBroken = !l.present; }
        assertTrue("损坏层须标记未装载", userBroken);
        assertTrue(chain.describe().contains("损坏"));
    }

    /** Config 集成：链值优先于 config.properties；卸下链=V68 原值 */
    @Test public void configIntegrationChainOverProperties() throws Exception {
        Path jar = tmp.newFolder().toPath();
        Config config = Config.load(jar, "/config.properties");
        Path ws = tmp.newFolder().toPath();
        write(ws.resolve(".minion").resolve("settings.json"),
                "{\"permissions\":{\"defaultMode\":\"read-only\"}}");
        // 未装配：V68 默认（workspace-write）
        assertEquals("workspace-write", config.permissionMode());
        // 装配：项目层覆盖
        config.attachSettingsChain(SettingsChain.load(jar, ws, null, emptyEnv()));
        assertEquals("read-only", config.permissionMode());
        // 未命中键走原 props 路径
        assertEquals("true", config.get("cost.tracking.enabled", "true"));
        // 卸下：恢复
        config.attachSettingsChain(null);
        assertEquals("workspace-write", config.permissionMode());
    }

    /** 无任何 settings 文件、无 env：全链空解析（V68 零影响） */
    @Test public void emptyChainZeroImpact() throws Exception {
        Path jar = tmp.newFolder().toPath();
        Path ws = tmp.newFolder().toPath();
        SettingsChain chain = SettingsChain.load(jar, ws,
                Arrays.asList(tmp.getRoot().toPath().resolve("nope.json")), emptyEnv());
        assertNull(chain.resolve("anything"));
        assertTrue(chain.allKeys().isEmpty());
        assertNotNull(chain.describe());
    }

    /** FeatureFlags 来源追溯：链命中 → settings:层名（不再误报 config.properties） */
    @Test public void featureFlagsSourceReportsChainLayer() throws Exception {
        Path jar = tmp.newFolder().toPath();
        Path ws = tmp.newFolder().toPath();
        write(ws.resolve(".minion").resolve("settings.json"),
                "{\"hooks\":{\"enabled\":true}}");
        Config config = Config.load(jar, "/config.properties");
        FeatureFlags flags = new FeatureFlags(config);
        assertEquals("默认值（legacy）", flags.source("hooks.enabled"));
        config.attachSettingsChain(SettingsChain.load(jar, ws, null, emptyEnv()));
        assertEquals("settings:项目", flags.source("hooks.enabled"));
        assertEquals("true", flags.value("hooks.enabled"));
        // 会话覆盖仍最高
        flags.override("hooks.enabled", "false");
        assertEquals("会话内存覆盖", flags.source("hooks.enabled"));
    }
}
