package com.minion.core.tools.ssh;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;
import com.minion.core.diagnostics.DiagnosticLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * SSH TOFU known_hosts 存储（开发说明 v2.4 §4.8，ssh.strictHostKey 默认 true）。
 *
 * <p>自实现 jsch {@link HostKeyRepository}（jsch 内置 KnownHosts 为包私有不可复用），
 * 文件格式 {@code host|type|base64(keyblob)}（host 含端口形如 host:port）。
 * check() 三态：OK（已知且一致）/ NOT_INCLUDED（首次，捕获待确认指纹）/ CHANGED（指纹变化=疑似中间人）。
 * 首次连接由 {@link SshExecutor.HostKeyDecider} 询问用户；确认后 add() 原子落盘。
 */
public final class SshKnownHosts implements HostKeyRepository {

    private static final class Entry {
        final String host;   // host 或 host:port
        final String type;   // ssh-rsa / ssh-ed25519 / ecdsa-*
        final byte[] key;
        Entry(String host, String type, byte[] key) { this.host = host; this.type = type; this.key = key; }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<Entry>();
    /** check() 捕获的最近一次待确认主机键（NOT_INCLUDED 时供上层询问后 add） */
    private HostKey pending;
    /** 最近一次 check 结果（OK/NOT_INCLUDED/CHANGED）——异常分流不依赖 jsch 消息文案 */
    private int lastCheck = NOT_INCLUDED;
    /** 最近一次 check 针对的主机（有效键），防跨连接陈旧状态张冠李戴 */
    private String lastCheckHost;
    /** 本次连接的别名映射：jsch 以纯主机名调 check，非 22 端口记为 host:port 防同主机多端口混淆 */
    private String aliasFrom, aliasTo;

    public SshKnownHosts(Path file) {
        this.file = file;
        load();
    }

    public HostKey pendingKey() { return pending; }

    /** 最近一次 check 是否针对该有效键（host 或 host:port）且得出了给定结果 */
    public boolean lastCheckWas(String effectiveKey, int result) {
        synchronized (entries) {
            return lastCheck == result && effectiveKey.equals(lastCheckHost);
        }
    }

    /** 建连前登记别名（如 repo.internal → repo.internal:2222）；null 清除 */
    public void setAlias(String plainHost, String effectiveKey) {
        this.aliasFrom = plainHost;
        this.aliasTo = effectiveKey;
    }

    private String effectiveKey(String host) {
        return aliasFrom != null && aliasFrom.equals(host) && aliasTo != null ? aliasTo : host;
    }

    @Override
    public int check(String host, byte[] key) {
        synchronized (entries) {
            pending = null;
            String keyName = effectiveKey(host);
            lastCheckHost = keyName;
            boolean hostKnown = false;
            for (Entry e : entries) {
                if (!e.host.equals(keyName)) continue;
                hostKnown = true;
                if (java.util.Arrays.equals(e.key, key)) { lastCheck = OK; return OK; }
            }
            if (!hostKnown) {
                try { pending = new HostKey(keyName, key); }   // 2 参构造从 blob 推断类型
                catch (Exception malformed) { pending = null; }
                lastCheck = NOT_INCLUDED;
                return NOT_INCLUDED;
            }
            lastCheck = CHANGED;
            return CHANGED;
        }
    }

    @Override
    public void add(HostKey hostkey, UserInfo ui) {
        if (hostkey == null) return;
        synchronized (entries) {
            try {
                // mwiede 分支 HostKey.getKey() 返回 base64 字符串
                entries.add(new Entry(hostkey.getHost(), hostkey.getType(),
                        Base64.getDecoder().decode(hostkey.getKey())));
            } catch (IllegalArgumentException bad64) {
                return;   // 坏键不入库（fail-closed 跳过）
            }
            persist();
        }
    }

    /** 指定主机的指纹（SHA-256 base64，无前缀）；未知主机返回 null */
    public String fingerprintOf(String host) {
        String keyName = effectiveKey(host);
        synchronized (entries) {
            for (Entry e : entries) if (e.host.equals(keyName)) return fingerprint(e.key);
        }
        return pending != null && pending.getHost().equals(keyName)
                ? fingerprint(Base64.getDecoder().decode(pending.getKey())) : null;
    }

    @Override
    public void remove(String host, String type) {
        synchronized (entries) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (e.host.equals(host) && (type == null || e.type.equals(type))) entries.remove(i);
            }
            persist();
        }
    }

    @Override
    public void remove(String host, String type, byte[] key) {
        synchronized (entries) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (e.host.equals(host) && (type == null || e.type.equals(type))
                        && (key == null || java.util.Arrays.equals(e.key, key))) entries.remove(i);
            }
            persist();
        }
    }

    @Override
    public String getKnownHostsRepositoryID() { return file.toString(); }

    @Override
    public HostKey[] getHostKey() { return snapshot(null); }

    @Override
    public HostKey[] getHostKey(String host, String type) { return snapshot(host, type); }

    private HostKey[] snapshot(String host) { return snapshot(host, null); }

    private HostKey[] snapshot(String host, String type) {
        synchronized (entries) {
            List<HostKey> out = new ArrayList<HostKey>();
            for (Entry e : entries) {
                if (host != null && !e.host.equals(host)) continue;
                if (type != null && !e.type.equals(type)) continue;
                try { out.add(new HostKey(e.host, e.key)); }
                catch (Exception malformed) { /* 跳过坏行 */ }
            }
            return out.toArray(new HostKey[0]);
        }
    }

    // ==================== 持久化 ====================

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", 3);
                if (p.length != 3 || p[0].isEmpty() || p[1].isEmpty()) continue;
                try { entries.add(new Entry(p[0], p[1], Base64.getDecoder().decode(p[2]))); }
                catch (IllegalArgumentException bad64) { /* 跳过坏行 */ }
            }
        } catch (IOException corrupt) {
            DiagnosticLog.warn("ssh", "known_hosts 读取失败按空处理（备份原文件）: " + corrupt.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries)
                sb.append(e.host).append('|').append(e.type).append('|')
                  .append(Base64.getEncoder().encodeToString(e.key)).append('\n');
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            DiagnosticLog.warn("ssh", "known_hosts 写入失败（内存态仍生效）: " + failure.getMessage());
        }
    }

    /** 主机键 blob 的算法名（blob 首个 SSH 字符串即算法，如 ssh-rsa/ssh-ed25519） */
    static String keyTypeOf(byte[] blob) {
        try {
            if (blob.length > 4) {
                int len = ((blob[0] & 0xff) << 24) | ((blob[1] & 0xff) << 16) | ((blob[2] & 0xff) << 8) | (blob[3] & 0xff);
                if (len > 0 && 4 + len <= blob.length)
                    return new String(blob, 4, len, StandardCharsets.US_ASCII);
            }
        } catch (RuntimeException ignored) { }
        return "unknown";
    }

    /** SHA-256 指纹（base64，SHA256: 前缀——OpenSSH 展示口径） */
    static String fingerprint(byte[] key) {
        try {
            return "SHA256:" + Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(key)).replaceFirst("=+$", "");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
