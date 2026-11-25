package com.minion.core.tools.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.minion.core.tools.TextFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 知识库 BM25 倒排索引（第三批 B1，零新依赖）。
 *  打分：score(t,d)=idf(t)*tf*(k1+1)/(tf+k1*(1-b+b*len/avgdl))，idf=ln(1+(N-df+0.5)/(df+0.5))，
 *  k1=1.5/b=0.75（Lucene 标准）；整串命中 +20 bonus 由调用方在读到正文后叠加（保留旧语义）。
 *  增量：mtime+size 判脏只重读变更文件；持久化工作区 .session/kb-index.json。
 *  共享知识库只读：本类只读共享目录文件，索引文件永远写在项目工作区内。
 *  任何异常由调用方（KnowledgeTool.search）捕获后回退旧全量扫描，旧路径逐字保留。 */
public final class KnowledgeIndex {

    static final double K1 = 1.5, B = 0.75;
    static final int MAX_FILES = 5000;
    static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    /** 规模护栏：超过即放弃索引走旧扫描（可调小不可调大过默认） */
    public static final class IndexTooLargeException extends Exception {
        public IndexTooLargeException(String message) { super(message); }
    }

    /** 持久化形态（Gson 字段名即 JSON 键） */
    static final class Persisted {
        int version = 1;
        List<Doc> docs = new ArrayList<Doc>();
    }

    static final class Doc {
        String path;
        long mtime, size;
        int length;
        Map<String, Integer> tf = new LinkedHashMap<String, Integer>();
    }

    /** 排序后的候选命中（BM25 基础分，未含整串 bonus） */
    public static final class Hit {
        public final Path path;
        public final double score;
        Hit(Path path, double score) { this.path = path; this.score = score; }
    }

    private final Path indexFile;
    private final Map<String, Doc> docs = new LinkedHashMap<String, Doc>();
    /** term → (docPath → tf)；load/sync 后重建 */
    private final Map<String, Map<String, Integer>> inverted = new HashMap<String, Map<String, Integer>>();

    private KnowledgeIndex(Path indexFile) { this.indexFile = indexFile; }

    /** 读取持久化索引；文件不存在 → 空索引；损坏 → 备份 .bak 后抛 IOException
     *  （本次搜索回退旧路径，下次搜索自动重建，不会永久卡在损坏文件上） */
    public static KnowledgeIndex load(Path indexFile) throws IOException {
        KnowledgeIndex index = new KnowledgeIndex(indexFile);
        if (!Files.exists(indexFile)) return index;
        Persisted persisted;
        try {
            String json = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
            persisted = new Gson().fromJson(json, Persisted.class);
            if (persisted == null || persisted.docs == null) throw new IOException("索引内容为空");
            for (Doc doc : persisted.docs) {
                if (doc == null || doc.path == null || doc.tf == null) throw new IOException("索引条目畸形");
            }
        } catch (Exception corrupt) {
            backupCorrupt(indexFile);
            throw new IOException("知识库索引损坏（已备份 .bak，本次回退全量扫描）: " + corrupt.getMessage());
        }
        for (Doc doc : persisted.docs) index.docs.put(doc.path, doc);
        index.rebuildInverted();
        return index;
    }

    private static void backupCorrupt(Path indexFile) {
        try {
            Files.move(indexFile, indexFile.resolveSibling(indexFile.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    /** 与当前文件清单对账：mtime+size 一致沿用旧条目，变更/新增重读分词，已删除移除。
     *  有变化才写盘（原子 tmp+move）。共享目录文件只读。
     *  每文件仅一次 readAttributes（Windows 属性调用昂贵，合并 size/mtime 取数）。 */
    public void sync(List<Path> files) throws IOException, IndexTooLargeException {
        if (files.size() > MAX_FILES)
            throw new IndexTooLargeException("知识库文件数 " + files.size() + " 超过索引上限 " + MAX_FILES);
        long totalBytes = 0;
        Map<String, Path> byPath = new LinkedHashMap<String, Path>();
        Map<String, long[]> meta = new LinkedHashMap<String, long[]>();
        for (Path file : files) {
            java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            totalBytes += attrs.size();
            if (totalBytes > MAX_TOTAL_BYTES)
                throw new IndexTooLargeException("知识库总大小超过索引上限 200MB");
            String key = file.toAbsolutePath().normalize().toString();
            byPath.put(key, file);
            meta.put(key, new long[]{attrs.lastModifiedTime().toMillis(), attrs.size()});
        }
        boolean changed = docs.keySet().retainAll(byPath.keySet());
        for (Map.Entry<String, Path> entry : byPath.entrySet()) {
            long[] m = meta.get(entry.getKey());
            Doc doc = docs.get(entry.getKey());
            if (doc != null && doc.mtime == m[0] && doc.size == m[1]) continue;
            Doc fresh = tokenize(entry.getKey(), entry.getValue(), m[0], m[1]);
            docs.put(entry.getKey(), fresh);
            changed = true;
        }
        rebuildInverted();
        if (changed) save();
    }

    private static Doc tokenize(String key, Path file, long mtime, long size) throws IOException {
        String content = TextFiles.decode(Files.readAllBytes(file)).text.toLowerCase(Locale.ROOT);
        Doc doc = new Doc();
        doc.path = key; doc.mtime = mtime; doc.size = size;
        for (String term : KnowledgeTool.terms(content)) {
            int tf = 0, at = content.indexOf(term);
            while (at >= 0) { tf++; at = content.indexOf(term, at + term.length()); }
            if (tf > 0) doc.tf.put(term, tf);
        }
        int length = 0;
        for (int tf : doc.tf.values()) length += tf;
        doc.length = length;
        return doc;
    }

    private void rebuildInverted() {
        inverted.clear();
        for (Doc doc : docs.values()) {
            for (Map.Entry<String, Integer> entry : doc.tf.entrySet()) {
                Map<String, Integer> postings = inverted.get(entry.getKey());
                if (postings == null) {
                    postings = new HashMap<String, Integer>();
                    inverted.put(entry.getKey(), postings);
                }
                postings.put(doc.path, entry.getValue());
            }
        }
    }

    /** BM25 基础分排序（不含整串 bonus，调用方读正文后叠加）；只返回 score>0 的候选 */
    public List<Hit> rank(Set<String> terms) {
        int n = docs.size();
        if (n == 0) return Collections.emptyList();
        double avgdl = 0;
        for (Doc doc : docs.values()) avgdl += doc.length;
        avgdl /= n;
        Map<String, Double> scores = new HashMap<String, Double>();
        for (String term : terms) {
            Map<String, Integer> postings = inverted.get(term);
            if (postings == null || postings.isEmpty()) continue;
            double idf = Math.log(1 + (n - postings.size() + 0.5) / (postings.size() + 0.5));
            for (Map.Entry<String, Integer> posting : postings.entrySet()) {
                Doc doc = docs.get(posting.getKey());
                if (doc == null) continue;
                double tf = posting.getValue();
                double norm = tf + K1 * (1 - B + B * (avgdl <= 0 ? 1 : doc.length / avgdl));
                double score = idf * tf * (K1 + 1) / norm;
                Double old = scores.get(posting.getKey());
                scores.put(posting.getKey(), (old == null ? 0 : old) + score);
            }
        }
        List<Hit> hits = new ArrayList<Hit>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            if (entry.getValue() > 0) hits.add(new Hit(java.nio.file.Paths.get(entry.getKey()), entry.getValue()));
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) { return Double.compare(b.score, a.score); }
        });
        return hits;
    }

    public int docCount() { return docs.size(); }

    private void save() throws IOException {
        Persisted persisted = new Persisted();
        persisted.docs.addAll(docs.values());
        Files.createDirectories(indexFile.getParent());
        Path tmp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
        String json = new GsonBuilder().create().toJson(persisted);
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
    }
}