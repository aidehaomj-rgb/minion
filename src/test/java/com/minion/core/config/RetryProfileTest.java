package com.minion.core.config;

import com.minion.core.agent.RetryPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** §1.2 retry.profile 验收：cc 参数（500ms×2^（n-1）/32s 封顶/10 次/0–25% 抖动）、legacy 默认不变 */
public class RetryProfileTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Config configWith(String lines) throws Exception {
        java.nio.file.Path dir = tmp.newFolder().toPath();
        Config c = Config.load(dir, "/config-test.properties");
        if (lines != null) {
            java.nio.file.Path ext = dir.resolve("config.properties");
            java.nio.file.Files.write(ext, (new String(java.nio.file.Files.readAllBytes(ext),
                    java.nio.charset.StandardCharsets.UTF_8) + lines)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            c = Config.load(dir, "/config-test.properties");
        }
        return c;
    }

    @Test public void ccProfileBackoffAndCap() {
        RetryPolicy cc = RetryPolicy.ccProfile(10, 500, 32000);
        assertEquals(500, cc.delayMs(1));
        assertEquals(1000, cc.delayMs(2));
        assertEquals(4000, cc.delayMs(4));
        assertEquals(32000, cc.delayMs(7));    // 500×2^6=32000 恰好封顶
        assertEquals(32000, cc.delayMs(10));
        // 抖动上限 25%：实际等待 ∈ [base×0.75, base×1.25]，恒非负
        for (int i = 0; i < 200; i++) {
            long d = cc.nextDelayMs(3);
            assertTrue("抖动越界: " + d, d >= 1500 && d <= 2500);
        }
    }

    @Test public void ccAttemptsCap() {
        RetryPolicy cc = RetryPolicy.ccProfile(10, 500, 32000);
        assertFalse(cc.attemptsExhausted(9));
        assertTrue(cc.attemptsExhausted(10));
        assertTrue(cc.attemptsExhausted(11));
        // legacy：不限次数（仅墙钟）
        RetryPolicy legacy = RetryPolicy.transientErrors();
        assertEquals(0, legacy.maxAttempts);
        assertFalse(legacy.attemptsExhausted(1000));
    }

    @Test public void fromProfileLegacyDefaultUnchanged() throws Exception {
        RetryPolicy legacy = RetryPolicy.fromProfile(configWith(null));
        assertEquals(5000, legacy.initialDelayMs);
        assertEquals(60000, legacy.maxDelayMs);
        assertEquals(0, legacy.maxAttempts);
        assertEquals(2.0, legacy.multiplier, 0.0001);
    }

    @Test public void fromProfileCcAndOverrides() throws Exception {
        RetryPolicy cc = RetryPolicy.fromProfile(configWith("retry.profile=cc\n"));
        assertEquals(500, cc.initialDelayMs);
        assertEquals(32000, cc.maxDelayMs);
        assertEquals(10, cc.maxAttempts);
        // 显式覆盖参数
        RetryPolicy custom = RetryPolicy.fromProfile(configWith(
                "retry.profile=cc\nretry.max=3\nretry.baseMs=100\nretry.capMs=1000\n"));
        assertEquals(3, custom.maxAttempts);
        assertEquals(100, custom.initialDelayMs);
        assertEquals(1000, custom.maxDelayMs);
        // 未知 profile 值回落 legacy
        assertEquals(0, RetryPolicy.fromProfile(configWith("retry.profile=whatever\n")).maxAttempts);
    }
}
