package com.minion.core.checkpoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** 会话级文件检查点；元数据与内容分离，支持原文件不存在时恢复为删除。 */
public final class CheckpointStore {
    public static final long MAX_SNAPSHOT_BYTES = 50L * 1024L * 1024L;
    private final Path workspaceRoot;
    private final Path root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public CheckpointStore(Path workspaceRoot, Path root) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.root = root.toAbsolutePath().normalize();
    }

    public synchronized Entry create(Path target, String action) throws IOException {
        Path normalized = safeTarget(target);
        boolean existed = Files.exists(normalized);
        if (existed && Files.isDirectory(normalized)) throw new IOException("无法为目录创建检查点: " + normalized);
        if (existed && Files.size(normalized) > MAX_SNAPSHOT_BYTES) {
            throw new IOException("文件超过检查点上限 50 MB: " + normalized);
        }
        Files.createDirectories(root);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        String id = stamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        Entry e = new Entry();
        e.id = id;
        e.target = normalized.toString();
        e.existed = existed;
        e.createdAt = System.currentTimeMillis();
        e.action = action == null ? "modify" : action;
        e.dataFile = existed ? id + ".bin" : "";
        if (existed) Files.copy(normalized, root.resolve(e.dataFile), StandardCopyOption.REPLACE_EXISTING);
        writeEntry(e);
        return e;
    }

    public synchronized List<Entry> list(int limit) throws IOException {
        List<Entry> out = new ArrayList<Entry>();
        if (!Files.isDirectory(root)) return out;
        java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*.json");
        try {
            for (Path p : stream) {
                try { out.add(gson.fromJson(new String(Files.readAllBytes(p), StandardCharsets.UTF_8), Entry.class)); }
                catch (Exception ignored) { }
            }
        } finally { stream.close(); }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(b.createdAt, a.createdAt); }
        });
        if (out.size() > Math.max(1, limit)) return new ArrayList<Entry>(out.subList(0, Math.max(1, limit)));
        return out;
    }

    public synchronized Entry get(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9-]+")) throw new IOException("检查点标识非法");
        Path meta = root.resolve(id + ".json").normalize();
        if (!meta.startsWith(root) || !Files.isRegularFile(meta)) throw new IOException("检查点不存在: " + id);
        return gson.fromJson(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8), Entry.class);
    }

    public synchronized Path restore(String id) throws IOException {
        Entry e = get(id);
        Path target = safeTarget(Paths.get(e.target));
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        if (e.existed) {
            Path data = root.resolve(e.dataFile).normalize();
            if (!data.startsWith(root) || !Files.isRegularFile(data)) throw new IOException("检查点内容丢失: " + id);
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(target);
        }
        return target;
    }

    public synchronized int clear() throws IOException {
        if (!Files.isDirectory(root)) return 0;
        int count = 0;
        java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        try {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    Files.deleteIfExists(p);
                    count++;
                }
            }
        } finally { stream.close(); }
        return count;
    }

    public Path root() { return root; }

    private Path safeTarget(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) throw new IOException("检查点路径越出工作区: " + normalized);
        Path probe = Files.exists(normalized) ? normalized : normalized.getParent();
        while (probe != null && !Files.exists(probe)) probe = probe.getParent();
        if (probe != null && Files.exists(workspaceRoot)
                && !probe.toRealPath().startsWith(workspaceRoot.toRealPath())) {
            throw new IOException("检查点路径经链接越出工作区: " + normalized);
        }
        return normalized;
    }

    private void writeEntry(Entry e) throws IOException {
        Path meta = root.resolve(e.id + ".json");
        Path tmp = root.resolve(e.id + ".json.tmp");
        Files.write(tmp, gson.toJson(e).getBytes(StandardCharsets.UTF_8));
        try { Files.move(tmp, meta, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException ex) { Files.move(tmp, meta, StandardCopyOption.REPLACE_EXISTING); }
    }

    public static final class Entry {
        public String id;
        public String target;
        public boolean existed;
        public long createdAt;
        public String action;
        public String dataFile;
    }
}
