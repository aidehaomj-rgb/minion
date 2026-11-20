package com.minion.core.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.minion.core.diagnostics.DiagnosticLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息级文件快照（开发说明 v2.4 §9.2）：每条用户消息提交前对被跟踪文件建立聚合快照，
 * 供 /rewind 按"用户消息"粒度回退；与工具级 CheckpointStore 并存——本类是聚合索引，
 * 物理备份存 {@code .minion/file-history/{workspaceId}/{sessionId}/}。
 *
 * <p>正确性规则（v2.2 修订）：<b>只有当前整文件 SHA-256 与备份记录一致才确认未变化</b>；
 * size/mtime 仅作性能提示与冲突线索，不得作为未变化证明。备份名 = 路径 SHA-256 前 16 hex +
 * {@code @v<serial>}，序号单调递增；文件不存在记 null 备份（回滚时删除该文件）；超 50MB 跳过
 * （同 CheckpointStore 上限，跳过项标记 skipped）。快照上限 100，超出淘汰最旧并清理其物理备份。
 */
public final class MessageFileHistory {

    /** 单会话快照上限（§9.2） */
    public static final int MAX_SNAPSHOTS = 100;
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private static final Gson GSON = new Gson();

    /** 单文件备份记录；exists=false 为 null 备份（文件当时不存在） */
    public static final class Backup {
        public String path;          // 绝对规范化路径
        public String backupName;    // 物理备份文件名（null 备份为 null）
        public String sha256;        // 当时整文件哈希（null 备份为 null）
        public Long size;
        public Long mtime;
        public boolean exists;
        public boolean skipped;      // 超 50MB / 读取失败：不提供回退
    }

    /** 消息级快照：messageId + 时刻 + 追踪文件备份 */
    public static final class Snapshot {
        public String messageId;
        public long serial;          // 单调递增
        public long timestamp;
        public List<Backup> backups = new ArrayList<Backup>();
    }

    private final Path root;          // .minion/file-history/{workspaceId}/{sessionId}
    private final Path indexFile;
    private final Map<String, Snapshot> byMessage = new LinkedHashMap<String, Snapshot>();   // 插入序=serial 序
    private long serial;
    private boolean initialized;

    public MessageFileHistory(Path root) {
        this.root = root;
        this.indexFile = root.resolve("history-index.json");
    }

    /** 为一条用户消息建立快照；files 为消息提交前需跟踪的文件（可为空=空快照，仍占位供 /rewind 展示） */
    public synchronized Snapshot track(String messageId, List<Path> files) throws IOException {
        initialize();
        if (messageId == null || messageId.trim().isEmpty()) throw new IllegalArgumentException("messageId required");
        Snapshot s = new Snapshot();
        s.messageId = messageId;
        s.serial = ++serial;
        s.timestamp = System.currentTimeMillis();
        if (files != null) for (Path p : files) s.backups.add(backupOf(p, s.serial));
        byMessage.put(messageId, s);
        while (byMessage.size() > MAX_SNAPSHOTS) {
            Snapshot oldest = null;
            for (Snapshot v : byMessage.values()) { oldest = v; break; }   // LinkedHashMap 插入序=最旧
            if (oldest == null) break;
            removePhysical(oldest);
            byMessage.remove(oldest.messageId);
        }
        persist();
        return s;
    }

    /** 查询快照（无则 null） */
    public synchronized Snapshot snapshot(String messageId) throws IOException {
        initialize();
        return byMessage.get(messageId);
    }

    /** 全部快照（serial 升序；/rewind 选择器用） */
    public synchronized List<Snapshot> snapshots() throws IOException {
        initialize();
        return new ArrayList<Snapshot>(byMessage.values());
    }

    /**
     * 回退到目标快照：返回逐文件结果（不直接写盘——由调用方按 §9.3 冲突策略决策后执行 restoreFile）。
     * 一致才复用物理备份；skipped/null 备份产生对应结果项。
     */
    public synchronized List<RestoreItem> planRestore(String messageId) throws IOException {
        initialize();
        Snapshot s = byMessage.get(messageId);
        if (s == null) return null;
        List<RestoreItem> out = new ArrayList<RestoreItem>();
        for (Backup b : s.backups) {
            RestoreItem item = new RestoreItem();
            item.path = b.path;
            item.recordedSha256 = b.sha256;
            item.nullBackup = !b.exists;
            item.skipped = b.skipped;
            Path target = java.nio.file.Paths.get(b.path);
            if (b.skipped) item.status = "skipped";
            else if (!b.exists) {
                // null 备份：当时不存在；当前若已存在则回滚=删除（9.3 冲突确认）
                item.currentExists = Files.exists(target);
                item.status = item.currentExists ? "delete-on-restore" : "already-absent";
            } else {
                item.backupName = b.backupName;
                Path data = root.resolve(b.backupName);
                if (!Files.isRegularFile(data)) item.status = "backup-missing";
                else {
                    item.currentExists = Files.exists(target);
                    item.currentSha256 = item.currentExists ? sha256(target) : null;
                    // 当前与备份记录一致 → 无需写回；不一致 → 恢复将覆盖（9.3 需用户确认冲突）
                    item.status = item.currentSha256 != null && item.currentSha256.equals(b.sha256) ? "unchanged" : "restore";
                }
            }
            out.add(item);
        }
        return out;
    }

    /** 执行单文件回退写盘（覆盖前由调用方完成冲突确认）；null 备份且当前存在 → 删除 */
    public synchronized void restoreFile(RestoreItem item) throws IOException {
        if (item == null || "skipped".equals(item.status) || "unchanged".equals(item.status)
                || "already-absent".equals(item.status) || "backup-missing".equals(item.status)) return;
        Path target = java.nio.file.Paths.get(item.path);
        if (item.nullBackup) {
            if (Files.exists(target)) Files.delete(target);
            return;
        }
        Path data = root.resolve(item.backupName);
        Files.createDirectories(target.getParent());
        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void deleteAll() throws IOException {
        if (Files.isDirectory(root)) {
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path p : ds) Files.deleteIfExists(p);
            }
        }
        byMessage.clear();
        serial = 0;
        initialized = true;
    }

    // ==================== 内部 ====================

    private Backup backupOf(Path p, long snapSerial) {
        Backup b = new Backup();
        Path normalized = p.toAbsolutePath().normalize();
        b.path = normalized.toString();
        try {
            if (!Files.exists(normalized)) { b.exists = false; return b; }
            long size = Files.size(normalized);
            if (size > MAX_FILE_BYTES) { b.exists = true; b.skipped = true; return b; }
            b.exists = true;
            b.size = size;
            b.mtime = Files.getLastModifiedTime(normalized).toMillis();
            b.sha256 = sha256(normalized);
            b.backupName = pathKey(normalized) + "@v" + snapSerial;
            Files.createDirectories(root);
            Files.copy(normalized, root.resolve(b.backupName), StandardCopyOption.REPLACE_EXISTING);
            return b;
        } catch (Exception unavailable) {
            b.exists = Files.exists(normalized);
            b.skipped = true;   // 读取/拷贝失败：不承诺回退
            return b;
        }
    }

    private void removePhysical(Snapshot s) {
        if (s == null || s.backups == null) return;
        for (Backup b : s.backups) {
            if (b.backupName == null) continue;
            try { Files.deleteIfExists(root.resolve(b.backupName)); }
            catch (IOException ignored) { }
        }
    }

    private void persist() throws IOException {
        Files.createDirectories(root);
        Path tmp = root.resolve("history-index.json.tmp");
        StringBuilder sb = new StringBuilder("{\"serial\":" + serial + ",\"snapshots\":[");
        boolean first = true;
        for (Snapshot s : byMessage.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(GSON.toJson(s));
        }
        sb.append("]}");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void initialize() throws IOException {
        if (initialized) return;
        if (Files.isRegularFile(indexFile)) {
            try {
                Map<String, Object> raw = GSON.fromJson(new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() { }.getType());
                if (raw != null && raw.get("serial") instanceof Number) serial = ((Number) raw.get("serial")).longValue();
                Object snaps = raw == null ? null : raw.get("snapshots");
                if (snaps instanceof List) {
                    for (Object o : (List<?>) snaps) {
                        try {
                            Snapshot s = GSON.fromJson(GSON.toJson(o), Snapshot.class);
                            if (s != null && s.messageId != null) byMessage.put(s.messageId, s);
                        } catch (RuntimeException ignored) { }
                    }
                }
            } catch (RuntimeException corrupt) {
                DiagnosticLog.warn("file-history", "消息级快照索引损坏，按空重建: " + indexFile);
                byMessage.clear();
                serial = 0;
            }
        }
        initialized = true;
    }

    /** 路径 SHA-256 前 16 hex（备份名前缀） */
    static String pathKey(Path p) {
        return shaHex(p.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** 流式整文件 SHA-256（大文件不进内存） */
    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String shaHex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] table = "0123456789abcdef".toCharArray(), out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 255;
            out[i * 2] = table[b >>> 4];
            out[i * 2 + 1] = table[b & 15];
        }
        return new String(out);
    }

    /** /rewind 冲突决策用的单文件回退计划 */
    public static final class RestoreItem {
        public String path;
        public String backupName;       // planRestore 填充（restoreFile 用）
        public String recordedSha256;   // 快照时哈希
        public String currentSha256;   // 当前哈希（存在时）
        public boolean nullBackup;
        public boolean skipped;
        public boolean currentExists;
        public String status;           // restore/unchanged/delete-on-restore/already-absent/backup-missing/skipped
    }
}
