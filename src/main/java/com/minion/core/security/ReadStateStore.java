package com.minion.core.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读状态存储（开发说明 v2.4 §4.5）：会话级 path → 最近一次成功读取的状态。
 *
 * <p>Entry 含 content（本次返回文本，可空）、contentHash（<b>整文件流式 SHA-256</b>，
 * 非切片哈希）、rangeHash（本次切片）、mtime、offset/limit、isPartialView、readAt。
 * LRU 上限 {@link #MAX_ENTRIES} 条且 content 总字节预算默认 {@link #DEFAULT_CONTENT_BUDGET}
 * （8GB 目标机口径），超限淘汰最旧。Read 成功写入；Edit/Write 校验消费；会话结束清理。
 * 与 V68 ToolExecutionGuard 的 observed 哈希表并存，不得删除。
 *
 * <p>内嵌会话级 {@link ContextReadLedger}：记录哪些 path+offset+limit 的正文仍在当前
 * 模型上下文。只有 ledger 命中时才允许 Read 返回未变化 stub；压缩/内容替换/上下文恢复/
 * 消息淘汰必须调 {@link ContextReadLedger#invalidateAll()} 同步失效——内部缓存不能
 * 替代模型上下文。所有方法线程安全（工具在调度线程池上执行）。
 */
public final class ReadStateStore {

    /** LRU 条目上限（规格 §4.5：1000 条） */
    public static final int MAX_ENTRIES = 1000;
    /** content 总字节预算默认 32MB（规格 §4.5） */
    public static final long DEFAULT_CONTENT_BUDGET = 32L * 1024 * 1024;

    /** 单个文件的读状态；contentHash 为整文件流式 SHA-256，rangeHash 为本次切片 */
    public static final class Entry {
        public final String path;
        public String content;
        public String contentHash;
        public String rangeHash;
        public long mtime;
        public int offset;
        public int limit;
        /** 仅自动注入类读取置 true（规格 §4.5）；显式读取（含切片）恒 false */
        public boolean isPartialView;
        /** 本次读取未覆盖全文件（offset>0 或截断）：Write 整文件覆盖时的风险提示依据 */
        public boolean partialRange;
        public long readAt;
        long contentBytes;

        Entry(String path) { this.path = path; }
    }

    /** 会话级"正文仍在模型上下文"账本：压缩/回退/恢复/淘汰必须整体失效 */
    public static final class ContextReadLedger {
        private final Map<String, Long> present = new HashMap<String, Long>();

        private static String key(String path, int offset, int limit) {
            return path + "|" + offset + "|" + limit;
        }

        /** Read 正文实际进入模型上下文时登记 */
        public synchronized void note(String path, int offset, int limit) {
            present.put(key(path, offset, limit), System.currentTimeMillis());
        }

        /** 该读取范围的正文是否仍在上下文（压缩等事件未失效） */
        public synchronized boolean present(String path, int offset, int limit) {
            return present.containsKey(key(path, offset, limit));
        }

        /** 压缩/microcompact、内容替换、上下文恢复、消息淘汰：整体失效 */
        public synchronized void invalidateAll() { present.clear(); }

        /** 单文件失效（该文件的 Read 回执被替换/淘汰） */
        public synchronized void invalidate(String path) {
            String prefix = path + "|";
            for (String k : new java.util.ArrayList<String>(present.keySet()))
                if (k.startsWith(prefix)) present.remove(k);
        }
    }

    private final long budget;
    private long usedBytes;
    /** accessOrder=true：get 命中也刷新新旧；淘汰从最旧端移除 */
    private final LinkedHashMap<String, Entry> lru =
            new LinkedHashMap<String, Entry>(64, 0.75f, true);
    private final ContextReadLedger ledger = new ContextReadLedger();

    public ReadStateStore() { this(DEFAULT_CONTENT_BUDGET); }

    public ReadStateStore(long contentBudgetBytes) { this.budget = contentBudgetBytes; }

    public ContextReadLedger contextLedger() { return ledger; }

    /** 登记一次成功读取（覆盖该 path 旧记录） */
    public synchronized Entry record(String path, String content, String contentHash, String rangeHash,
                                     long mtime, int offset, int limit, boolean isPartialView, boolean partialRange) {
        Entry e = new Entry(path);
        e.content = content;
        e.contentHash = contentHash;
        e.rangeHash = rangeHash;
        e.mtime = mtime;
        e.offset = offset;
        e.limit = limit;
        e.isPartialView = isPartialView;
        e.partialRange = partialRange;
        e.readAt = System.currentTimeMillis();
        e.contentBytes = content == null ? 0 : utf8Bytes(content);
        Entry old = lru.put(path, e);
        if (old != null) usedBytes -= old.contentBytes;
        usedBytes += e.contentBytes;
        evictOverBudget();
        return e;
    }

    /** 查询（命中刷新 LRU）；无记录返回 null */
    public synchronized Entry get(String path) { return lru.get(path); }

    public synchronized int size() { return lru.size(); }

    /**
     * Edit/Write 成功后的回写：时间戳与整文件哈希更新、content 清空
     * （写入后旧正文不再代表文件现状，防下次去重误判）。
     */
    public synchronized void updateAfterWrite(String path, String newContentHash, long mtime) {
        Entry e = lru.get(path);
        if (e == null) return;
        usedBytes -= e.contentBytes;
        e.content = null;
        e.contentBytes = 0;
        e.contentHash = newContentHash;
        e.mtime = mtime;
        e.readAt = System.currentTimeMillis();
    }

    /** 会话结束清理 */
    public synchronized void clear() { lru.clear(); usedBytes = 0; ledger.invalidateAll(); }

    private void evictOverBudget() {
        while ((lru.size() > MAX_ENTRIES || usedBytes > budget) && !lru.isEmpty()) {
            Map.Entry<String, Entry> oldest = lru.entrySet().iterator().next();
            usedBytes -= oldest.getValue().contentBytes;
            lru.remove(oldest.getKey());
        }
    }

    private static int utf8Bytes(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c < 0x80 ? 1 : (c < 0x800 ? 2 : (Character.isSurrogate(c) ? 2 : 3));
        }
        return n;
    }

    /** 三工具一致的路径键：已存在路径取真实路径（防符号链接别名绕过读状态），否则绝对规范化 */
    public static String keyOf(Path p) {
        try {
            if (Files.exists(p)) return p.toRealPath().toString();
        } catch (IOException ignored) { /* 真实路径解析失败退回词法规范化 */ }
        return p.toAbsolutePath().normalize().toString();
    }

    /** 整文件流式 SHA-256（hex）：不为大文件分配整文件 byte[]（规格 §3.1 写入二次校验同口径） */
    public static String sha256(Path file) throws IOException {        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    /** 字符串 UTF-8 SHA-256（hex）：切片 rangeHash 用 */
    public static String sha256(String text) {
        MessageDigest md = digest();
        md.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return hex(md.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
