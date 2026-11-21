package com.minion.core.security;

import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * SecretStore v2 加固验收（v2.4 §4.6）：DPAPI 优先/调用失败回退主密码/PBKDF2 错误密码拒绝/
 * GCM 篡改报错/旧 CBC 迁移/legacy 开关全回退。DPAPI mock 走可注入 Dpapi。
 */
public class SecretStoreV2Test {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 简单可逆 mock：XOR 定长密钥（仅测试 DPAPI 通道，非加密强度） */
    private static final class MockDpapi implements SecretStore.Dpapi {
        public byte[] protect(byte[] plain) {
            byte[] out = new byte[plain.length];
            for (int i = 0; i < plain.length; i++) out[i] = (byte) (plain[i] ^ 0x5A);
            return out;
        }
        public byte[] unprotect(byte[] blob) throws Exception {
            if (blob.length == 1 && blob[0] == 0x42) throw new IllegalStateException("mock unprotect 失败");
            return protect(blob);
        }
    }

    /** 模拟 DPAPI 完全不可用（调用即抛）→ 必须回退主密码，不能落可预测密钥 */
    private static final class BrokenDpapi implements SecretStore.Dpapi {
        public byte[] protect(byte[] plain) throws Exception { throw new IllegalStateException("DPAPI 不可用"); }
        public byte[] unprotect(byte[] blob) throws Exception { throw new IllegalStateException("DPAPI 不可用"); }
    }

    private Path root() throws Exception { return tmp.newFolder().toPath(); }

    private static String readJson(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 真实 DPAPI（Windows crypt32）：往返 + master.key 为 dpapi 模式（Win7 x64 实机复验） */
    @Test public void realDpapiRoundTrip() throws Exception {
        Path root = root();
        SecretStore store = new SecretStore(root);   // v2 默认 + 自动探测真实 DPAPI
        store.set("db.main", "password-123");
        assertEquals("password-123", store.get("db.main"));
        Path masterKey = root.resolve(".minion").resolve("master.key");
        assertTrue(Files.exists(masterKey));
        assertTrue(readJson(masterKey).contains("\"dpapi\""));
        // 明文不出现在任何落盘文件
        assertFalse(readJson(store.file()).contains("password-123"));
        // 同一实例重开（模拟重启）：DPAPI 同用户可解
        SecretStore reopened = new SecretStore(root);
        assertEquals("password-123", reopened.get("db.main"));
    }

    /** DPAPI mock：包裹通道走注入实现 */
    @Test public void mockDpapiRoundTrip() throws Exception {
        Path root = root();
        SecretStore store = new SecretStore(root, true, new MockDpapi());
        store.set("api.key", "secret-value");
        assertEquals("secret-value", store.get("api.key"));
        assertTrue(readJson(root.resolve(".minion").resolve("master.key")).contains("dpapi"));
        assertTrue(readJson(store.file()).contains("gcm:"));
    }

    /** DPAPI 不可用回退主密码：未设密码拒绝落盘（不接受可预测密钥） */
    @Test public void brokenDpapiWithoutPasswordFailsClosed() throws Exception {
        SecretStore store = new SecretStore(root(), true, new BrokenDpapi());
        try {
            store.set("k", "v");
            fail("应拒绝在无 DPAPI 无主密码时创建密钥库");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("主密码"));
        }
    }

    /** 主密码模式：正确密码往返；错误密码在校验密文处立即拒绝 */
    @Test public void pbkdf2RoundTripAndWrongPasswordRejected() throws Exception {
        Path root = root();
        SecretStore store = new SecretStore(root, true, new BrokenDpapi());
        store.setMasterPassword("correct-horse".toCharArray());
        store.set("db.main", "pw-456");
        assertEquals("pw-456", store.get("db.main"));
        assertTrue(readJson(root.resolve(".minion").resolve("master.key")).contains("pbkdf2"));
        // 重启后错误主密码：check 密文 GCM 校验失败（在 setMasterPassword 解锁校验处即拒绝）
        SecretStore wrong = new SecretStore(root, true, new BrokenDpapi());
        try {
            wrong.setMasterPassword("wrong".toCharArray());
            wrong.get("db.main");
            fail("错误主密码必须被拒绝");
        } catch (Exception expected) {
            // AEADBadTagException（GCM 认证标签不匹配）
        }
        // 正确主密码重开仍可读
        SecretStore right = new SecretStore(root, true, new BrokenDpapi());
        right.setMasterPassword("correct-horse".toCharArray());
        assertEquals("pw-456", right.get("db.main"));
    }

    /** 篡改 GCM 密文 → 认证标签校验失败（明文不泄露）。经 gson 改写规避默认 HTML 转义（= → \\u003d） */
    @Test public void tamperedCiphertextRejected() throws Exception {
        Path root = root();
        SecretStore store = new SecretStore(root, true, new MockDpapi());
        store.set("k", "v");
        com.google.gson.JsonObject obj = JsonParser.parseString(readJson(store.file())).getAsJsonObject();
        byte[] broken = java.util.Base64.getDecoder().decode(obj.get("k").getAsString().substring(4));
        broken[broken.length - 1] ^= 0x01;   // 翻转认证标签位
        obj.addProperty("k", "gcm:" + java.util.Base64.getEncoder().encodeToString(broken));
        Files.write(store.file(), obj.toString().getBytes(StandardCharsets.UTF_8));
        try {
            store.get("k");
            fail("篡改密文必须报错");
        } catch (Exception expected) { }
    }

    /** 迁移：旧格式文件（可预测密钥 CBC）在 v2 打开时自动重加密为 GCM，值不丢 */
    @Test public void migratesLegacyEntriesToGcm() throws Exception {
        Path root = root();
        SecretStore legacy = new SecretStore(root, false, null);
        legacy.set("old.key", "legacy-secret");
        assertFalse(readJson(legacy.file()).contains("gcm:"));   // 确认起点是旧格式
        // v2 打开同目录 → 迁移
        SecretStore v2 = new SecretStore(root, true, new MockDpapi());
        assertEquals("legacy-secret", v2.get("old.key"));
        assertTrue(readJson(v2.file()).contains("gcm:"));        // 已重加密
        assertFalse(readJson(v2.file()).contains("legacy-secret"));
    }

    /** 迁移失败（旧条目损坏）：保留旧文件，可解条目仍可读，不丢数据 */
    @Test public void migrationFailureKeepsOldFile() throws Exception {
        Path root = root();
        SecretStore legacy = new SecretStore(root, false, null);
        legacy.set("good.key", "good-value");
        // 注入一条损坏的旧格式条目（无法用旧密钥解开）
        String json = readJson(legacy.file());
        String corrupted = json.replaceFirst("\"good.key\"[^,}]*",
                "\"good.key\":\"" + java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\"");
        Files.write(legacy.file(), corrupted.getBytes(StandardCharsets.UTF_8));
        SecretStore v2 = new SecretStore(root, true, new MockDpapi());
        try {
            v2.get("good.key");
            fail("损坏条目应解不开");
        } catch (Exception expected) { }
        // 原文件未被破坏性覆盖（仍为旧格式，未变成空库）
        String after = readJson(v2.file());
        assertFalse(after.contains("gcm:"));
        assertTrue(after.contains("good.key"));
    }

    /** legacy 开关（secret.store.v2=false）：行为与旧实现逐项一致（可预测密钥 CBC、无 master.key） */
    @Test public void legacyModeMatchesOldFormat() throws Exception {
        Path root = root();
        SecretStore legacy = new SecretStore(root, false, null);
        legacy.set("a", "va");
        assertEquals("va", legacy.get("a"));
        assertFalse(Files.exists(root.resolve(".minion").resolve("master.key")));
        assertFalse(readJson(legacy.file()).contains("gcm:"));
        assertEquals("va", new SecretStore(root, false, null).get("a"));
    }
}
