package com.minion.core.security;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机用户绑定的 AES 密钥库（开发说明 v2.4 §4.6 加固）。
 *
 * <p><b>v2（默认，secret.store.v2=true）</b>：32 字节随机主密钥，首选 DPAPI
 * （Crypt32.CryptProtectData，当前用户 scope）包裹后存 {jarDir}/.minion/master.key；
 * DPAPI 不可用回退主密码 PBKDF2WithHmacSHA256（150000 迭代 + 随机 salt + 校验密文）。
 * 条目 AES/GCM（随机 12 字节 IV，认证标签防篡改），格式 {@code gcm:base64(iv||ct||tag)}。
 * 旧格式（可预测密钥 CBC）启动时检测并迁移：解密→新密钥重加密→原子替换；
 * 任一条目迁移失败保留旧文件并告警，不丢数据（该条目仍按旧格式惰性解密读取）。
 *
 * <p><b>legacy（secret.store.v2=false，仅应急）</b>：完整回退旧实现——可预测密钥
 * （user.name|user.home|COMPUTERNAME 派生）+ AES/CBC，格式与旧文件逐字节兼容。
 * 密文可备份，但换 Windows 用户/电脑后不能直接解密（DPAPI 同理，绑定当前用户）。
 */
public final class SecretStore {
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() { }.getType();
    private static final String GCM_PREFIX = "gcm:";
    private static final int GCM_IV = 12, GCM_TAG_BITS = 128, MASTER_KEY_BYTES = 32, PBKDF2_ITERS = 150000;
    private static final String CHECK_PLAINTEXT = "minion-master-key-check";

    /** DPAPI 抽象（JNA 接口可注入，规格 §4.6 验收：DPAPI mock） */
    public interface Dpapi {
        byte[] protect(byte[] plain) throws Exception;
        byte[] unprotect(byte[] blob) throws Exception;
    }

    private final Path file;
    private final Path keyFile;
    private final boolean v2;
    private final Dpapi dpapi;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();
    /** 主密码（PBKDF2 回退模式）：GUI 启动输入一次，会话内缓存 */
    private char[] masterPassword;
    private SecretKeySpec masterKey;       // v2 当前主密钥（懒加载）
    private boolean keyLoaded;

    public SecretStore(Path jarDir) { this(jarDir, true, null); }

    public SecretStore(Path jarDir, boolean v2, Dpapi dpapi) {
        this.file = jarDir.resolve(".minion").resolve("secrets.json");
        this.keyFile = jarDir.resolve(".minion").resolve("master.key");
        this.v2 = v2;
        this.dpapi = dpapi != null ? dpapi : defaultDpapi();
    }

    /** 静默探测 DPAPI：加载失败返回 null（回退主密码模式），不抛异常 */
    private static Dpapi defaultDpapi() {
        try { return new Crypt32Dpapi(); } catch (Throwable unavailable) { return null; }
    }

    /** 设置/更新主密码（PBKDF2 回退模式）；对已存在的 pbkdf2 master.key 立即校验 */
    public synchronized void setMasterPassword(char[] password) throws Exception {
        this.masterPassword = password == null ? null : password.clone();
        this.keyLoaded = false;
        if (v2) masterKey();   // 立即校验：错误密码在此失败
    }

    public synchronized void set(String name, String value) throws Exception {
        Map<String, String> map = load();
        map.put(valid(name), encrypt(value));
        save(map);
    }

    public synchronized String get(String name) throws Exception {
        String raw = load().get(valid(name));
        return raw == null ? null : decrypt(raw);
    }

    public synchronized boolean delete(String name) throws Exception {
        Map<String, String> map = load();
        boolean existed = map.remove(valid(name)) != null;
        if (existed) save(map);
        return existed;
    }

    public synchronized List<String> list() throws Exception {
        return new ArrayList<String>(load().keySet());
    }

    public Path file() { return file; }

    // ==================== v2 主密钥 ====================

    /** master.key JSON：{v:1, mode:"dpapi"|"pbkdf2", wrap|salt+iters+check} */
    private static final class MasterKeyFile {
        String mode;
        String wrap;      // dpapi: base64(CryptProtectData(主密钥))
        String salt;      // pbkdf2: base64(salt)
        int iters = PBKDF2_ITERS;
        String check;     // pbkdf2: gcm:base64(校验密文)——错误主密码在此失败，不猜
    }

    private SecretKeySpec masterKey() throws Exception {
        if (!v2) throw new IllegalStateException("legacy 模式无主密钥");
        if (keyLoaded) return masterKey;
        MasterKeyFile meta;
        String json = Files.exists(keyFile) ? new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8) : null;
        if (json == null) {
            meta = createMasterKeyFile();
        } else {
            meta = gson.fromJson(json, MasterKeyFile.class);
            if (meta == null || meta.mode == null) throw new IllegalStateException("master.key 损坏（可删除后重建，旧密钥将需手动迁移）");
        }
        if ("dpapi".equals(meta.mode)) {
            if (dpapi == null) throw new IllegalStateException("master.key 为 DPAPI 模式但当前 DPAPI 不可用；请在本机解密或删除后重建");
            masterKey = new SecretKeySpec(dpapi.unprotect(Base64.getDecoder().decode(meta.wrap)), "AES");
        } else {
            if (masterPassword == null || masterPassword.length == 0)
                throw new IllegalStateException("密钥库使用主密码保护，请先设置主密码（setMasterPassword）");
            byte[] salt = Base64.getDecoder().decode(meta.salt);
            masterKey = pbkdf2(masterPassword, salt, meta.iters);
            // 校验密文：错误主密码立即失败（AEADBadTagException），不进入后续解密
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] all = Base64.getDecoder().decode(meta.check.substring(GCM_PREFIX.length()));
            c.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, all, 0, GCM_IV));
            c.doFinal(all, GCM_IV, all.length - GCM_IV);
        }
        keyLoaded = true;
        return masterKey;
    }

    private MasterKeyFile createMasterKeyFile() throws Exception {
        byte[] raw = new byte[MASTER_KEY_BYTES];
        random.nextBytes(raw);
        MasterKeyFile meta = new MasterKeyFile();
        if (dpapi != null) {
            try {
                meta.mode = "dpapi";
                meta.wrap = Base64.getEncoder().encodeToString(dpapi.protect(raw));
                atomicWrite(keyFile, gson.toJson(meta).getBytes(StandardCharsets.UTF_8));
                return meta;
            } catch (Exception dpapiFailed) {
                // DPAPI 调用失败（≠加载失败）：同样回退主密码模式，不静默降级到可预测密钥
                com.minion.core.diagnostics.DiagnosticLog.warn("secrets",
                        "DPAPI 包裹主密钥失败，回退主密码模式: " + dpapiFailed.getMessage());
                meta = new MasterKeyFile();
            }
        }
        if (masterPassword == null || masterPassword.length == 0)
            throw new IllegalStateException("DPAPI 不可用且未设置主密码；密钥库拒绝用可预测密钥落盘（应急可设 secret.store.v2=false）");
        meta.mode = "pbkdf2";
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        meta.salt = Base64.getEncoder().encodeToString(salt);
        SecretKeySpec key = pbkdf2(masterPassword, salt, meta.iters);
        meta.check = GCM_PREFIX + Base64.getEncoder().encodeToString(gcmSeal(key, CHECK_PLAINTEXT.getBytes(StandardCharsets.UTF_8)));
        atomicWrite(keyFile, gson.toJson(meta).getBytes(StandardCharsets.UTF_8));
        return meta;
    }

    /** PBKDF2WithHmacSHA256（JDK8 8u161+ 支持）派生 256 位 AES 主密钥 */
    private static SecretKeySpec pbkdf2(char[] password, byte[] salt, int iters) throws Exception {
        javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iters, MASTER_KEY_BYTES * 8);
        try {
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    // ==================== 条目加密 ====================

    private String encrypt(String plain) throws Exception {
        if (!v2) return legacyEncrypt(plain);
        return GCM_PREFIX + Base64.getEncoder().encodeToString(gcmSeal(masterKey(), plain.getBytes(StandardCharsets.UTF_8)));
    }

    private String decrypt(String raw) throws Exception {
        if (v2 && raw.startsWith(GCM_PREFIX)) {
            byte[] all = Base64.getDecoder().decode(raw.substring(GCM_PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, masterKey(), new GCMParameterSpec(GCM_TAG_BITS, all, 0, GCM_IV));
            return new String(c.doFinal(all, GCM_IV, all.length - GCM_IV), StandardCharsets.UTF_8);
        }
        // legacy 条目：旧可预测密钥 CBC（v2=false 全量走此路径；v2 迁移失败的条目惰性走此路径）
        return legacyDecrypt(raw);
    }

    private byte[] gcmSeal(SecretKeySpec key, byte[] plain) throws Exception {
        byte[] iv = new byte[GCM_IV];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plain);
        byte[] all = new byte[GCM_IV + ct.length];
        System.arraycopy(iv, 0, all, 0, GCM_IV);
        System.arraycopy(ct, 0, all, GCM_IV, ct.length);
        return all;
    }

    // ==================== 装载 + 迁移 ====================

    /** 读取 secrets.json；v2 模式顺带把旧 CBC 条目迁移为 GCM（失败保留旧文件并告警） */
    private Map<String, String> load() throws Exception {
        if (!Files.exists(file)) return new LinkedHashMap<String, String>();
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Map<String, String> map = gson.fromJson(json, MAP_TYPE);
        if (map == null) return new LinkedHashMap<String, String>();
        if (!v2) return map;
        boolean hasLegacy = false;
        for (String v : map.values()) if (v != null && !v.startsWith(GCM_PREFIX)) { hasLegacy = true; break; }
        if (!hasLegacy) return map;
        // 迁移：全部旧条目可解才原子替换；任一失败保留旧文件（该条目仍可惰性按旧格式读取，不丢数据）
        Map<String, String> migrated = new LinkedHashMap<String, String>(map);
        try {
            SecretKeySpec key = masterKey();
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getValue() == null || e.getValue().startsWith(GCM_PREFIX)) continue;
                migrated.put(e.getKey(), GCM_PREFIX + Base64.getEncoder()
                        .encodeToString(gcmSeal(key, legacyDecrypt(e.getValue()).getBytes(StandardCharsets.UTF_8))));
            }
            save(migrated);
            return migrated;
        } catch (Exception failure) {
            com.minion.core.diagnostics.DiagnosticLog.warn("secrets",
                    "旧密钥条目迁移失败，保留原文件（该条目仍按旧格式读取）: " + failure.getMessage());
            return map;
        }
    }

    private void save(Map<String, String> map) throws Exception {
        atomicWrite(file, gson.toJson(map).getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicWrite(Path target, byte[] data) throws Exception {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String valid(String name) {
        String n = name == null ? "" : name.trim();
        if (!n.matches("[A-Za-z0-9._-]{1,80}"))
            throw new IllegalArgumentException("密钥名称仅允许字母、数字、点、下划线和横线");
        return n;
    }

    // ==================== legacy（可预测密钥 CBC，v2=false 应急回退 + 迁移源） ====================

    private static String legacyEncrypt(String plain) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, legacyKey(), new IvParameterSpec(iv));
        byte[] data = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(data, 0, all, iv.length, data.length);
        return Base64.getEncoder().encodeToString(all);
    }

    private static String legacyDecrypt(String raw) throws Exception {
        byte[] all = Base64.getDecoder().decode(raw);
        if (all.length < 17) throw new IllegalArgumentException("密钥库数据损坏");
        byte[] iv = java.util.Arrays.copyOfRange(all, 0, 16), data = java.util.Arrays.copyOfRange(all, 16, all.length);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, legacyKey(), new IvParameterSpec(iv));
        return new String(c.doFinal(data), StandardCharsets.UTF_8);
    }

    private static SecretKeySpec legacyKey() throws Exception {
        String seed = System.getProperty("user.name", "") + "|" + System.getProperty("user.home", "")
                + "|" + System.getenv("COMPUTERNAME") + "|minion-secret-v1";
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(java.util.Arrays.copyOf(hash, 16), "AES");
    }
}
