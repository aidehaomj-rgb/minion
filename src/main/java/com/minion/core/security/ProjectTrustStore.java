package com.minion.core.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 项目可执行配置的唯一持久信任源（v2.4 §10.9）：按根目录存 {@code .minion/trust.json}，
 * 记录每项可执行配置的 SHA-256 指纹（{@link TrustedExecutable#fingerprint()}）。
 * 项目 Hook 用 workspace realpath 为根；jar 级用户配置（mcp.json、config.properties 命令）
 * 用 jarDir 为根（用户作用域配置的信任随配置本身而非随项目）。
 *
 * <p><b>失效规则</b>：git pull/外部编辑/插件升级导致指纹变化 → 条目立即失效，重新批准前不执行；
 * 其余未变化条目不受影响。符号链接指向替换或脚本内容变化都改变指纹。
 *
 * <p><b>fail-closed</b>：trust.json 损坏/无法解析/自身为符号链接 → {@link #corrupted()}=true，
 * 一律不信任；下次 {@link #approve} 整体重写即修复（旧记录作废，逐项重新批准——宁可多问一次）。
 * 内置组件不进本库，不因自身更新自动获得项目配置权限。
 */
public final class ProjectTrustStore {

    public static final String FILE_NAME = "trust.json";
    /** 单文件上限：畸形巨型文件直接判损坏 */
    private static final long MAX_FILE_BYTES = 1024 * 1024;

    /** 单条信任记录（trust.json 落盘结构） */
    public static final class TrustRecord {
        public String fingerprint;
        public String approvedAt;
        public String preview;
    }

    /** 复核结果：UI 展示来源/原因/新旧指纹（trusted=false 时 recorded/approvedPreview 描述旧值） */
    public static final class CheckResult {
        public final boolean trusted;
        public final String reason;
        public final String recorded;         // 已批准指纹（无记录 null）
        public final String actual;           // 当前指纹
        public final String approvedPreview;  // 批准时的命令摘要
        public final String approvedAt;

        CheckResult(boolean trusted, String reason, String recorded, String actual,
                String approvedPreview, String approvedAt) {
            this.trusted = trusted;
            this.reason = reason;
            this.recorded = recorded;
            this.actual = actual;
            this.approvedPreview = approvedPreview;
            this.approvedAt = approvedAt;
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path root;
    private final Path file;
    private final Map<String, Map<String, TrustRecord>> entries =
            new TreeMap<String, Map<String, TrustRecord>>();
    private volatile boolean corrupted;

    private ProjectTrustStore(Path root) {
        this.root = root;
        this.file = root.resolve(".minion").resolve(FILE_NAME);
    }

    /** 读取（缺失=空库；损坏=corrupted 空库）。root 取 realpath（符号链接进入同一项目共享信任）。 */
    public static ProjectTrustStore load(Path rootDir) {
        Path real;
        try { real = rootDir.toRealPath(); }
        catch (IOException e) { real = rootDir.toAbsolutePath().normalize(); }
        ProjectTrustStore s = new ProjectTrustStore(real);
        s.read();
        return s;
    }

    public Path root() { return root; }
    public Path file() { return file; }
    public boolean corrupted() { return corrupted; }

    public synchronized boolean isTrusted(TrustedExecutable spec) {
        if (corrupted) return false;
        TrustRecord r = recordOf(spec);
        return r != null && r.fingerprint != null && r.fingerprint.equals(spec.fingerprint());
    }

    /** 详细复核（信任对话框/doctor 用） */
    public synchronized CheckResult check(TrustedExecutable spec) {
        String actual = spec.fingerprint();
        if (corrupted) return new CheckResult(false, "信任存储损坏（fail-closed），需逐项重新批准",
                null, actual, null, null);
        TrustRecord r = recordOf(spec);
        if (r == null) return new CheckResult(false, "首次使用，未批准过", null, actual, null, null);
        if (r.fingerprint == null || !r.fingerprint.equals(actual))
            return new CheckResult(false, "内容已变化（git pull/外部编辑/升级），原批准失效",
                    r.fingerprint, actual, r.preview, r.approvedAt);
        return new CheckResult(true, "指纹匹配", r.fingerprint, actual, r.preview, r.approvedAt);
    }

    /** 批准并持久化（原子写）；损坏库上批准=整体重建修复 */
    public synchronized void approve(TrustedExecutable spec) {
        ensureWritable();
        put(spec);
        persist();
    }

    /** 整体替换某类型全部条目（单次原子写）：Hook 配置重批准时清除已删除条目的旧信任 */
    public synchronized void replaceType(String type, Iterable<TrustedExecutable> specs) {
        ensureWritable();
        entries.remove(type);
        for (TrustedExecutable spec : specs) put(spec);
        persist();
    }

    /** 撤销单条（UI「取消信任」） */
    public synchronized boolean remove(String type, String id) {
        Map<String, TrustRecord> m = entries.get(type);
        boolean removed = m != null && m.remove(id) != null;
        if (removed && m.isEmpty()) entries.remove(type);
        if (removed) persist();
        return removed;
    }

    /** 只读视图（UI/doctor 展示） */
    public synchronized Map<String, Map<String, TrustRecord>> entries() {
        Map<String, Map<String, TrustRecord>> copy =
                new LinkedHashMap<String, Map<String, TrustRecord>>();
        for (Map.Entry<String, Map<String, TrustRecord>> e : entries.entrySet())
            copy.put(e.getKey(), new LinkedHashMap<String, TrustRecord>(e.getValue()));
        return copy;
    }

    // ==================== 内部 ====================

    private TrustRecord recordOf(TrustedExecutable spec) {
        Map<String, TrustRecord> m = entries.get(spec.type);
        return m == null ? null : m.get(spec.id);
    }

    private void put(TrustedExecutable spec) {
        Map<String, TrustRecord> m = entries.get(spec.type);
        if (m == null) { m = new LinkedHashMap<String, TrustRecord>(); entries.put(spec.type, m); }
        TrustRecord r = new TrustRecord();
        r.fingerprint = spec.fingerprint();
        r.approvedAt = ZonedDateTime.now().toString();
        r.preview = spec.preview();
        m.put(spec.id, r);
    }

    /** 损坏后首次写入：清空重建（旧记录不可信，全部作废重新批准） */
    private void ensureWritable() {
        if (corrupted) { corrupted = false; entries.clear(); }
    }

    private void read() {
        entries.clear();
        corrupted = false;
        if (!Files.isRegularFile(file)) return;                 // 无文件=空库（正常首启）
        try {
            if (Files.isSymbolicLink(file) || Files.size(file) > MAX_FILE_BYTES) {
                corrupted = true;                               // 信任库自身被链接替换 → 拒绝
                return;
            }
            Map<String, Map<String, TrustRecord>> loaded = GSON.fromJson(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Map<String, TrustRecord>>>() { }.getType());
            if (loaded == null) { corrupted = true; return; }
            for (Map.Entry<String, Map<String, TrustRecord>> e : loaded.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                Map<String, TrustRecord> clean = new LinkedHashMap<String, TrustRecord>();
                for (Map.Entry<String, TrustRecord> r : e.getValue().entrySet()) {
                    if (r.getKey() == null || r.getValue() == null) continue;
                    if (r.getValue().fingerprint == null
                            || !r.getValue().fingerprint.matches("[0-9a-f]{64}")) continue;   // 畸形记录丢弃
                    clean.put(r.getKey(), r.getValue());
                }
                entries.put(e.getKey(), clean);
            }
        } catch (Exception malformed) {
            corrupted = true;                                   // 解析失败 fail-closed
        }
    }

    /** 原子写：tmp + ATOMIC_MOVE（失败退回普通 REPLACE_EXISTING）；写失败仅告警（下次批准重试） */
    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.write(tmp, GSON.toJson(entries).getBytes(StandardCharsets.UTF_8));
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException noAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[minion] 写入信任记录失败: " + e.getMessage());
        }
    }

    // ==================== 脚本内容哈希（带 mtime/size 缓存） ====================

    private static final class ScriptHash {
        final long mtime, size;
        final String hash;
        ScriptHash(long mtime, long size, String hash) { this.mtime = mtime; this.size = size; this.hash = hash; }
    }

    /** path → 内容哈希缓存（指纹高频复核避免每次全量读文件；容量有界） */
    private static final Map<Path, ScriptHash> SCRIPT_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<Path, ScriptHash>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Path, ScriptHash> eldest) {
                    return size() > 256;
                }
            });

    /** 脚本内容 SHA-256（hex）；缺失=MISSING；超限/读失败=BIG/ERR（指纹仍随内容稳定区分） */
    static String scriptHash(Path p) {
        try {
            if (!Files.isRegularFile(p)) return "MISSING";
            long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
            ScriptHash cached = SCRIPT_CACHE.get(p);
            if (cached != null && cached.size == size && cached.mtime == mtime) return cached.hash;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = Files.newInputStream(p);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            String hex = TrustedExecutable.sha256Hex(md.digest());
            SCRIPT_CACHE.put(p, new ScriptHash(mtime, size, hex));
            return hex;
        } catch (Exception e) {
            return "ERR";
        }
    }

    /** 测试辅助：清脚本哈希缓存 */
    static void clearScriptCache() { SCRIPT_CACHE.clear(); }
}
