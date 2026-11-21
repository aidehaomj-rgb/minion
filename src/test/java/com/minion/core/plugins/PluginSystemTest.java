package com.minion.core.plugins;

import com.minion.core.security.ProjectTrustStore;
import com.minion.core.skills.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * v2.4 §7.2 Plugins 验收：本地目录市场安装→启用→技能/命令生效；禁用完全消失；
 * 版本升级缓存隔离；缺依赖失败保留旧版本；未信任声明不执行（§10.9）。
 */
public class PluginSystemTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path dir(String... path) throws Exception {
        Path p = tmp.getRoot().toPath();
        for (String s : path) p = p.resolve(s);
        Files.createDirectories(p);
        return p;
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 造一个本地市场插件（manifest+技能+命令+hook 声明） */
    private Path marketWithPlugin(String marketName, String plugin, String version,
            String hookCommand, String... extraDeps) throws Exception {
        Path pluginDir = dir("market-" + marketName, plugin);
        write(pluginDir.resolve(".minion-plugin/plugin.json"),
                "{\"name\":\"" + plugin + "\",\"version\":\"" + version + "\",\"description\":\"测试插件\""
                + (extraDeps.length > 0 ? ",\"dependencies\":{\"" + extraDeps[0] + "\":\"*\"}" : "")
                + (hookCommand != null ? ",\"statusLine\":\"" + hookCommand + "\"" : "") + "}");
        write(pluginDir.resolve("skills/inner/SKILL.md"),
                "---\nname: inner\ndescription: " + plugin + " 内置技能\n---\n内置技能正文");
        write(pluginDir.resolve("commands/deploy.md"), "执行部署：按项目 README 步骤");
        if (hookCommand != null)
            write(pluginDir.resolve("hooks/hooks.json"),
                    "{\"hooks\":{\"Stop\":[{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\","
                            + "\"command\":\"" + hookCommand + "\"}]}]}}");
        return pluginDir;
    }

    private PluginMarketplace marketplace(Path jarDir, String marketDir) throws Exception {
        PluginMarketplace m = new PluginMarketplace(jarDir);
        m.add("local", "directory", marketDir);
        return m;
    }

    /** 安装→启用→技能/命令装载生效（SOURCE_PLUGIN 默认禁 shell） */
    @Test public void installEnableLoadEffective() throws Exception {
        Path jarDir = dir("jar");
        marketWithPlugin("m1", "demo", "1.0.0", null);
        PluginInstaller installer = new PluginInstaller(jarDir,
                marketplace(jarDir, tmp.getRoot().toPath().resolve("market-m1").toString()));

        String result = installer.install("demo");
        assertTrue(result, result.contains("已安装 demo@1.0.0"));
        // 版本化缓存目录
        assertEquals(1, installer.installed().size());
        assertTrue(installer.installed().get(0).cacheDir.toString().contains("demo")
                && installer.installed().get(0).cacheDir.toString().contains("1.0.0"));
        assertTrue(installer.installed().get(0).enabled);

        PluginLoader loader = new PluginLoader(installer, ProjectTrustStore.load(jarDir));
        PluginLoader.Runtime runtime = loader.loadEnabled();
        assertEquals(1, runtime.skills.size());
        assertEquals("inner", runtime.skills.get(0).name);
        assertEquals(Skill.SOURCE_PLUGIN, runtime.skills.get(0).source);
        assertFalse("插件技能默认禁内联 shell", runtime.skills.get(0).shellInjectionAllowed());
        assertEquals(1, runtime.commands.size());
        assertEquals("deploy", runtime.commands.get(0).name);
        assertTrue(runtime.commands.get(0).promptBody.contains("部署"));
        assertTrue(runtime.pendingTrust.isEmpty());
    }

    /** 禁用后完全消失（loadEnabled 不再装载） */
    @Test public void disableRemovesEverything() throws Exception {
        Path jarDir = dir("jar2");
        marketWithPlugin("m2", "demo", "1.0.0", null);
        PluginInstaller installer = new PluginInstaller(jarDir,
                marketplace(jarDir, tmp.getRoot().toPath().resolve("market-m2").toString()));
        installer.install("demo");
        assertTrue(installer.setEnabled("demo", false).contains("已停用"));

        PluginLoader loader = new PluginLoader(installer, ProjectTrustStore.load(jarDir));
        PluginLoader.Runtime runtime = loader.loadEnabled();
        assertTrue("禁用=技能完全消失", runtime.skills.isEmpty());
        assertTrue("禁用=命令完全消失", runtime.commands.isEmpty());
        assertFalse(installer.installed().get(0).enabled);
    }

    /** 版本升级缓存隔离 + 回退到旧版本 */
    @Test public void versionUpgradeIsolationAndRollback() throws Exception {
        Path jarDir = dir("jar3");
        Path pluginDir = marketWithPlugin("m3", "demo", "1.0.0", null);
        PluginInstaller installer = new PluginInstaller(jarDir,
                marketplace(jarDir, pluginDir.getParent().toString()));
        installer.install("demo");

        // 市场升级到 2.0.0
        write(pluginDir.resolve(".minion-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"2.0.0\",\"description\":\"测试插件 v2\"}");
        installer.install("demo");
        PluginInstaller.Installed current = installer.installed().get(0);
        assertEquals("2.0.0", current.version);
        assertTrue("旧版本目录保留（缓存隔离）",
                Files.isDirectory(current.cacheDir.getParent().resolve("1.0.0")));

        // 回退 2.0.0 → 1.0.0
        assertTrue(installer.rollback("demo").contains("1.0.0"));
        assertEquals("1.0.0", installer.installed().get(0).version);
    }

    /** 缺依赖：安装失败并保留旧版本（禁止联网解析） */
    @Test public void missingDependencyFailsKeepsOld() throws Exception {
        Path jarDir = dir("jar4");
        Path pluginDir = marketWithPlugin("m4", "demo", "1.0.0", null, "nonexistent-dep-x");
        PluginInstaller installer = new PluginInstaller(jarDir,
                marketplace(jarDir, pluginDir.getParent().toString()));

        String failed = installer.install("demo");
        assertTrue(failed, failed.contains("缺少依赖 nonexistent-dep-x"));
        assertTrue("失败不半安装", installer.installed().isEmpty());

        // 旧版本在装的插件升级缺依赖 → 保留旧版本
        write(pluginDir.resolve(".minion-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"1.0.0\",\"description\":\"无依赖\"}");
        installer.install("demo");
        write(pluginDir.resolve(".minion-plugin/plugin.json"),
                "{\"name\":\"demo\",\"version\":\"2.0.0\",\"dependencies\":{\"ghost\":\"*\"}}");
        String upgradeFailed = installer.install("demo");
        assertTrue(upgradeFailed, upgradeFailed.contains("缺少依赖 ghost"));
        assertEquals("旧版本保留", "1.0.0", installer.installed().get(0).version);
    }

    /** 未过 §10.9 信任的执行声明（hook/statusLine）不注册执行、进待信任清单 */
    @Test public void untrustedDeclarationsPendingNotExecuted() throws Exception {
        Path jarDir = dir("jar5");
        marketWithPlugin("m5", "demo", "1.0.0", "cmd /c echo status");
        PluginInstaller installer = new PluginInstaller(jarDir,
                marketplace(jarDir, tmp.getRoot().toPath().resolve("market-m5").toString()));
        installer.install("demo");

        PluginLoader loader = new PluginLoader(installer, ProjectTrustStore.load(jarDir));
        PluginLoader.Runtime runtime = loader.loadEnabled();
        // 资料（技能/命令）可装载；执行声明（hook + statusLine）待信任
        assertEquals(1, runtime.skills.size());
        assertEquals(2, runtime.pendingTrust.size());   // hook 命令 + statusLine
        assertTrue(runtime.pendingTrust.get(0).contains("hook"));

        // 批准后（模拟指纹已信任）：statusLine 声明的指纹写进信任库再装载
        java.nio.file.Path cacheDir = installer.installed().get(0).cacheDir;
        com.minion.core.security.TrustedExecutable spec = com.minion.core.security.TrustedExecutable.of(
                "plugin", com.minion.core.security.TrustedExecutable.shortId("demo statusLine|cmd /c echo status"),
                "plugin", "cmd /c echo status", java.util.Collections.<String>emptyList(),
                java.util.Collections.<String, String>emptyMap(), cacheDir.toString(),
                com.minion.core.security.TrustedExecutable.referencedScripts("cmd /c echo status", cacheDir));
        ProjectTrustStore trust = ProjectTrustStore.load(jarDir);
        trust.approve(spec);
        PluginLoader approved = new PluginLoader(installer, trust);
        assertTrue("批准后不再待信任",
                approved.loadEnabled().pendingTrust.size() < 2);
    }

    /** manifest 缺失：目录约定推断（name=目录名，version=0.0.0） */
    @Test public void manifestOptionalByConvention() throws Exception {
        Path pluginDir = dir("market-m6", "conventional");
        write(pluginDir.resolve("skills/x/SKILL.md"), "---\nname: x\ndescription: 约定技能\n---\n内容");
        PluginManifest m = PluginManifest.load(pluginDir);
        assertEquals("conventional", m.name);
        assertEquals("0.0.0", m.version);
        assertTrue(m.dependencies.isEmpty());
    }

    /** 在线源（github/url/npm）剔除：登记被拒或读回不含 */
    @Test public void onlineSourcesRejected() throws Exception {
        Path jarDir = dir("jar7");
        PluginMarketplace m = new PluginMarketplace(jarDir);
        m.add("web", "github", "https://github.com/x/y");
        assertTrue("github 源登记后读回剔除", m.known().isEmpty());
    }
}
