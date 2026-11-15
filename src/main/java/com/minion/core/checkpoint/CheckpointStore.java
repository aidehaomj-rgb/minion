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
    private static final ThreadLocal<String> GROUP=new ThreadLocal<String>();
    private static final ThreadLocal<List<Runnable>> SEALS=new ThreadLocal<List<Runnable>>();
    public static void beginGroup(String id){GROUP.set(id);SEALS.set(new ArrayList<Runnable>());}
    public static void endGroup(boolean success){try{if(success&&SEALS.get()!=null)for(Runnable r:SEALS.get())r.run();}finally{GROUP.remove();SEALS.remove();}}

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
        e.groupId=GROUP.get()==null?id:GROUP.get();
        e.target = normalized.toString();
        e.existed = existed;
        e.createdAt = System.currentTimeMillis();
        e.action = action == null ? "modify" : action;
        e.dataFile = existed ? id + ".bin" : "";
        e.beforeHash=hash(normalized);
        if (existed) Files.copy(normalized, root.resolve(e.dataFile), StandardCopyOption.REPLACE_EXISTING);
        writeEntry(e);
        if(SEALS.get()!=null)SEALS.get().add(()->{try{seal(e);}catch(IOException ex){throw new IllegalStateException("检查点写后版本登记失败",ex);}});
        return e;
    }
    private static String hash(Path p)throws IOException{try{return com.minion.core.agent.FileEvidence.digest(p);}catch(Exception e){throw new IOException("无法核验文件版本",e);}}
    public synchronized void seal(Entry e)throws IOException {e.afterHash=hash(safeTarget(Paths.get(e.target)));writeEntry(e);}

    /** Read-only review; hashes for binaries, bounded before/after text for small UTF-8 files. */
    public synchronized String diff(String id)throws IOException {
        Entry e=get(id);Path p=safeTarget(Paths.get(e.target));String current=hash(p);
        StringBuilder b=new StringBuilder("文件: ").append(p).append("\n分组: ").append(e.groupId).append("\n修改前: ").append(e.beforeHash).append("\n登记修改后: ").append(e.afterHash).append("\n当前: ").append(current);
        if(e.existed&&Files.size(root.resolve(e.dataFile))<100000&&Files.isRegularFile(p)&&Files.size(p)<100000){
            String before=new String(Files.readAllBytes(root.resolve(e.dataFile)),StandardCharsets.UTF_8),after=new String(Files.readAllBytes(p),StandardCharsets.UTF_8);
            if(before.indexOf('\0')<0&&after.indexOf('\0')<0)b.append("\n--- 修改前预览 ---\n").append(before.substring(0,Math.min(10000,before.length()))).append("\n+++ 当前预览 +++\n").append(after.substring(0,Math.min(10000,after.length())));
        }return b.toString();
    }
    public synchronized List<Entry> group(String id)throws IOException {
        Entry anchor=get(id);List<Entry> selected=new ArrayList<Entry>();String key=anchor.groupId==null?anchor.id:anchor.groupId;
        for(Entry e:list(Integer.MAX_VALUE))if(key.equals(e.groupId==null?e.id:e.groupId))selected.add(e);
        // For multiple edits of one file, retain the earliest before-image and latest after-version.
        java.util.Map<String,Entry> merged=new java.util.LinkedHashMap<String,Entry>();selected.sort(Comparator.comparingLong(e->e.createdAt));
        for(Entry e:selected){Entry first=merged.get(e.target);if(first==null)merged.put(e.target,e);else first.afterHash=e.afterHash;}return new ArrayList<Entry>(merged.values());
    }
    public synchronized String preflight(String id)throws IOException {
        StringBuilder b=new StringBuilder();for(Entry e:group(id)){
            String actual=hash(safeTarget(Paths.get(e.target)));
            b.append(e.target).append(" | ").append(e.afterHash==null?"unknown: 无修改后版本，需人工审阅":e.afterHash.equals(actual)?"ready":"conflict: 当前文件已改变").append('\n');
        }return b.toString();
    }
    /** User-only approval for legacy/uncertain checkpoints; not exposed to the agent. */
    public synchronized void approveCurrent(String id)throws IOException {for(Entry e:group(id)){
        if(e.beforeHash==null&&e.existed){Path data=root.resolve(e.dataFile).normalize();if(!data.startsWith(root)||!Files.isRegularFile(data))throw new IOException("旧检查点原始内容缺失");e.beforeHash=hash(data);}
        if(e.beforeHash==null)e.beforeHash="<missing>";
        e.afterHash=hash(safeTarget(Paths.get(e.target)));writeEntry(e);
    }}
    public synchronized String restoreGroup(String id)throws IOException {
        java.util.concurrent.locks.Lock workspaceLock=com.minion.core.agent.ToolExecutionGuard.exclusiveWorkspaceLock();
        try{workspaceLock.lockInterruptibly();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("恢复被中断",e);}
        try{return restoreGroupLocked(id);}finally{workspaceLock.unlock();}
    }
    private String restoreGroupLocked(String id)throws IOException {
        List<Entry> entries=group(id);List<java.util.concurrent.locks.ReentrantLock> locks=com.minion.core.tools.PathLocks.forPaths(entries.stream().map(e->Paths.get(e.target)).collect(java.util.stream.Collectors.toList()));
        for(java.util.concurrent.locks.ReentrantLock lock:locks)lock.lock();
        try {
            for(Entry e:entries){Path p=safeTarget(Paths.get(e.target));if(e.afterHash==null||!e.afterHash.equals(hash(p)))throw new IOException("恢复预检失败，当前版本未知或冲突: "+p);
                if(e.existed&&(e.beforeHash==null||!e.beforeHash.equals(hash(root.resolve(e.dataFile)))))throw new IOException("检查点内容损坏或旧版缺少哈希: "+e.id);}
            List<Entry> backups=new ArrayList<Entry>();for(Entry e:entries)backups.add(create(Paths.get(e.target),"restore-backup"));
            List<String> done=new ArrayList<String>();try{for(Entry e:entries){restore(e.id);done.add(e.target);}for(Entry e:backups)seal(e);}
            catch(IOException failure){throw new IOException("分组恢复部分失败；已恢复 "+done+"。当前备份保存在检查点中: "+backups.stream().map(e->e.id).collect(java.util.stream.Collectors.toList()),failure);}
            return "已恢复 "+done.size()+" 个文件；恢复前备份ID: "+backups.stream().map(e->e.id).collect(java.util.stream.Collectors.toList());
        }finally{for(int i=locks.size()-1;i>=0;i--)locks.get(i).unlock();}
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
        public String groupId,beforeHash,afterHash;
    }
}
