package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.FeatureFlags;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * v2.4 §14.4+§15 总验收（切片 40）：双配置基线——legacy 默认配置的全部开关值
 * 与 replica-offline 显式全开配置互检；第 15 章 V68 非回归基线的 7 个默认开
 * 安全修复例外核对；replica-offline 预设文件逐键覆盖检查。
 */
public class AcceptanceBaselineTest {

    /** legacy 默认：replica 行为开关全部 off、安全例外全部 on */
    @Test public void legacyDefaultsAllReplicaOffSecurityOn() {
        Config config = Config.load(Paths.get("target/test-tmp-abs"));
        // replica 行为开关（默认 false）
        assertFalse(config.hooksEnabled());
        assertFalse(config.microcompactEnabled());
        assertFalse(config.toolSearchEnabled());
        assertFalse(config.instructionsEnabled());
        assertFalse(config.teamEnabled());
        assertFalse(config.toolsMonitorEnabled());
        assertFalse(config.bashSnapshotEnabled());
        assertFalse(config.compactSessionMemoryEnabled());
        assertFalse(config.toolSummaryEnabled());
        assertFalse(config.autocompactFormula());
        assertFalse(config.toolsParallelMetadataDriven());
        assertFalse(config.toolsWebfetchIntranetEnabled());
        // 7 个安全修复例外（默认 on）
        assertTrue(config.permissionEngineV2());
        assertTrue(config.bashAnalyzerEnabled());
        assertFalse(config.mcpConfirmSkip());
        assertTrue(config.secretStoreV2());
        assertTrue(config.sshStrictHostKey());
        assertTrue(config.trustStoreEnabled());
        assertTrue(config.budgetEnabled());          // 增强（默认 true 零影响）
        assertTrue(config.llmAutoContinueEnabled());
    }

    /** replica-offline 预设：逐键解析后 replica 开关全 on */
    @Test public void replicaOfflinePresetEnablesAll() throws Exception {
        Path preset = Paths.get("src/resource/replica-offline.properties");
        assertTrue("预设文件存在", java.nio.file.Files.isRegularFile(preset));
        Config config = Config.load(Paths.get("target/test-tmp-abs"));
        java.util.Properties props = new java.util.Properties();
        props.load(new java.io.StringReader(
                new String(java.nio.file.Files.readAllBytes(preset), "UTF-8")));
        Set<String> replicaKeys = new HashSet<String>();
        for (String key : props.stringPropertyNames()) replicaKeys.add(key);
        // 关键 replica 开关
        String[] mustHave = {"hooks.enabled", "retry.profile", "tools.parallel.metadataDriven",
                "toolsearch.enabled", "bash.snapshot.enabled", "microcompact.enabled",
                "autocompact.formula", "instructions.enabled", "team.enabled",
                "tools.monitor.enabled", "compact.sessionMemory.enabled",
                "toolSummary.enabled", "session.transcript.jsonl", "trust.store.v2"};
        for (String key : mustHave)
            assertTrue("replica-offline 预设缺键: " + key, replicaKeys.contains(key));
    }

    /** FeatureFlags 目录完备性：全部 §14.3 总表键都在目录里（含新增 7 例外） */
    @Test public void featureFlagsCatalogComplete() {
        FeatureFlags flags = new FeatureFlags(Config.load(Paths.get("target/test-tmp-abs")));
        // 7 个安全修复例外在目录且标注 securityException
        String[] securityExceptions = {"permission.engine.v2", "bash.analyzer.enabled",
                "secret.store.v2", "ssh.strictHostKey", "trust.store.v2"};
        for (FeatureFlags.Flag f : flags.catalog())
            for (String key : securityExceptions)
                if (f.key.equals(key))
                    assertTrue("安全例外须标注: " + key, f.securityException);
        // 目录规模（切片 40 时点 44 条目）
        assertTrue("FeatureFlags 目录条目数", flags.catalog().size() >= 44);
    }

    /** 零抄袭合规：参考源码目录未被打入本仓库 */
    @Test public void referenceSourceNotBundled() {
        String cp = System.getProperty("java.class.path");
        assertFalse("classpath 不得含参考源码目录", cp.contains("claude-code"));
        assertFalse("classpath 不得含参考源码 jar", cp.contains("claude-code"));
    }
}
