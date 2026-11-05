package com.minion.core.storage;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 可丢弃的轻量会话索引。正文/事件仍是事实来源，索引丢失时流式重建。 */
final class SessionIndex {
    private final Path dir;
    private final Gson gson = new Gson();
    private final Map<String, SessionStore.SessionMeta> entries = new HashMap<String, SessionStore.SessionMeta>();
    private boolean loaded;

    SessionIndex(Path dir) { this.dir = dir; }

    private void load() {
        if (loaded) return;
        loaded = true;
        Path file = dir.resolve(".session-index");
        if (!Files.isRegularFile(file)) return;
        try (java.io.Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            SessionStore.SessionMeta[] cached = gson.fromJson(r, SessionStore.SessionMeta[].class);
            if (cached != null) for (SessionStore.SessionMeta m : cached)
                if (m != null && m.indexVersion == 1 && m.id != null) entries.put(m.id, m);
        } catch (Exception ignored) { entries.clear(); }
    }

    synchronized List<SessionStore.SessionMeta> list() throws IOException {
        load();
        List<SessionStore.SessionMeta> result = new ArrayList<SessionStore.SessionMeta>();
        if (!Files.isDirectory(dir)) return result;
        Set<String> live = new HashSet<String>();
        boolean changed = false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                String filename = f.getFileName().toString();
                if (filename.endsWith(".queue.json")) continue;
                String id = filename.substring(0, filename.length() - 5);
                if (!id.matches("[A-Za-z0-9_-]{1,100}")) continue;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);
                    SessionStore.SessionMeta m = entries.get(id);
                    if (m == null || m.fileSize != attrs.size() || m.modifiedMillis != attrs.lastModifiedTime().toMillis()) {
                        m = readHeader(f, id);
                        m.fileSize = attrs.size(); m.modifiedMillis = attrs.lastModifiedTime().toMillis();
                        entries.put(id, m); changed = true;
                    }
                    live.add(id); result.add(m);
                } catch (Exception ignored) { /* 损坏的单个快照不影响其余列表。 */ }
            }
        }
        if (entries.keySet().retainAll(live)) changed = true;
        if (changed) flush();
        result.sort(Comparator.comparing((SessionStore.SessionMeta m) -> m.pinned).reversed()
                .thenComparing(Comparator.comparing((SessionStore.SessionMeta m) -> m.createdAt).reversed()));
        return result;
    }

    synchronized void saved(Session s, Path file) {
        load();
        SessionStore.SessionMeta m = new SessionStore.SessionMeta(s.id, s.createdAt, s.preview(), s.title, s.pinned, s.archived);
        m.workDir = s.workDir; m.modelName = s.modelName; m.modelDisplayName = s.modelDisplayName;
        boolean timestampSet = false;
        for (int i = s.messages.size() - 1; i >= 0; i--) {
            Message msg = s.messages.get(i);
            if (msg.role == Message.Role.TOOL) continue;
            if (!timestampSet) { m.lastMessageTs = msg.ts; timestampSet = true; }
            if (msg.content != null && !msg.content.trim().isEmpty()) { m.lastSummary = summary(msg.content); break; }
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            m.fileSize = attrs.size(); m.modifiedMillis = attrs.lastModifiedTime().toMillis();
            entries.put(s.id, m); flush();
        } catch (IOException ignored) { /* 可选缓存失败不能把已成功保存的会话判失败。 */ }
    }

    synchronized void deleted(String id) { load(); if (entries.remove(id) != null) flush(); }

    private void flush() {
        try {
            Path file = dir.resolve(".session-index"), tmp = dir.resolve(".session-index.tmp");
            Files.write(tmp, gson.toJson(entries.values()).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) { }
    }

    private static String value(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return null; }
        return r.nextString();
    }

    private static String summary(String s) {
        // 只处理短片段，不为一条超长消息另复制整段字符串。
        String t = s.substring(0, Math.min(s.length(), 120)).replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 40 ? t.substring(0, 40) + "..." : t;
    }

    private SessionStore.SessionMeta readHeader(Path file, String expectedId) throws IOException {
        String id = null, created = null, title = null, preview = "(空会话)", work = null, model = null, display = null, last = null;
        long ts = 0; boolean pinned = false, archived = false;
        try (JsonReader r = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                switch (name) {
                    case "id": id = value(r); break;
                    case "createdAt": created = value(r); break;
                    case "title": title = value(r); break;
                    case "workDir": work = value(r); break;
                    case "modelName": model = value(r); break;
                    case "modelDisplayName": display = value(r); break;
                    case "pinned": pinned = r.nextBoolean(); break;
                    case "archived": archived = r.nextBoolean(); break;
                    case "messages":
                        if (r.peek() == JsonToken.NULL) { r.nextNull(); break; }
                        r.beginArray();
                        while (r.hasNext()) {
                            String role = null, content = null; long messageTs = 0;
                            boolean skipPreview = false;
                            r.beginObject();
                            while (r.hasNext()) {
                                String key = r.nextName();
                                if ("role".equals(key)) role = value(r);
                                else if ("content".equals(key)) {
                                    if ("TOOL".equals(role)) r.skipValue(); else content = value(r);
                                } else if ("ts".equals(key)) messageTs = r.nextLong();
                                else if ("pinned".equals(key) || "toolImage".equals(key)) skipPreview |= r.nextBoolean();
                                else r.skipValue();
                            }
                            r.endObject();
                            if (!"TOOL".equals(role)) {
                                ts = messageTs;
                                if (content != null && !content.trim().isEmpty()) last = summary(content);
                            }
                            if ("USER".equals(role) && !skipPreview) {
                                String text = content == null ? "" : content.substring(0, Math.min(content.length(), 51)).replace('\n', ' ');
                                preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
                            }
                        }
                        r.endArray(); break;
                    default: r.skipValue();
                }
            }
            r.endObject();
            if (r.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid session snapshot");
        }
        if (!expectedId.equals(id) || created == null) throw new IOException("Invalid session header");
        SessionStore.SessionMeta m = new SessionStore.SessionMeta(id, created, preview, title, pinned, archived);
        m.workDir = work; m.modelName = model; m.modelDisplayName = display; m.lastMessageTs = ts; m.lastSummary = last;
        return m;
    }
}
