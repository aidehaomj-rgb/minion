package com.minion.core.storage;

import com.google.gson.Gson;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 会话落盘：原子写（tmp+rename），列表按时间倒序 */
public class SessionStore {

    private final Gson gson = new Gson();
    private final Path dir;
    private final SessionIndex index;
    private final Map<String, SessionEventJournal> journals = new HashMap<String, SessionEventJournal>();

    public SessionStore(Path dir) { this.dir = dir; this.index = new SessionIndex(dir); }

    private synchronized SessionEventJournal journal(String id) {
        SessionEventJournal j = journals.get(id);
        if (j == null) { j = new SessionEventJournal(dir, id); journals.put(id, j); }
        return j;
    }

    /** Write ahead of mutating the live message list. */
    public void appendMessage(Session session, Message message) throws IOException {
        if (!Files.exists(dir.resolve(session.id + ".json"))) save(session);
        journal(session.id).record(session, message);
    }

    /** Archive first, then write the replacement context before swapping it in memory. */
    public void recordCompactionTransition(Session session, List<Message> after) throws IOException {
        journal(session.id).compact(session, after);
    }

    public int archiveCompacted(Session session, List<Message> before, List<Message> after) throws IOException {
        return new HistoryArchive(dir, session.id, session.workDir == null ? null
                : java.nio.file.Paths.get(session.workDir)).recordCompaction(before, after,
                session.taskState == null ? null : session.taskState.id);
    }

    public List<HistoryArchive.Hit> searchHistory(String sessionId, String query, int limit) throws IOException {
        return new HistoryArchive(dir, sessionId).search(query, limit);
    }

    public List<HistoryArchive.Hit> searchHistory(String sessionId, String query, int limit,
                                                  String taskId) throws IOException {
        return new HistoryArchive(dir, sessionId).search(query, limit, taskId);
    }

    public List<HistoryArchive.Hit> searchHistory(String sessionId, String query, int limit,
                                                  String taskId, String pathFilter) throws IOException {
        return new HistoryArchive(dir, sessionId).search(query, limit, taskId, pathFilter);
    }

    public String readHistory(String sessionId, String messageId, int offset, int length) throws IOException {
        return new HistoryArchive(dir, sessionId).read(messageId, offset, length);
    }

    /** 项目内会话的手动顺序，单独保存，避免改动会话内容或时间戳。 */
    public List<String> loadOrder() throws IOException {
        Path file = dir.resolve("session-order.txt");
        if (!Files.exists(file)) return new ArrayList<String>();
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    public void saveOrder(List<String> ids) throws IOException {
        Files.createDirectories(dir);
        Path temp = dir.resolve("session-order.txt.tmp");
        Path file = dir.resolve("session-order.txt");
        Files.write(temp, ids, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public Path save(Session session) throws IOException {
        Files.createDirectories(dir);
        SessionEventJournal events = journal(session.id);
        events.recordMissing(session);
        session.journalSequence = events.sequence();
        String json;
        synchronized(session.queuedTasks) {
            synchronized(session.taskState) {
                synchronized(session.todos) { json = gson.toJson(session); }
            }
        }
        Path tmp = dir.resolve(session.id + ".json.tmp");
        Path target = dir.resolve(session.id + ".json");
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        index.saved(session, target);
        return target;
    }

    public Session load(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IOException("Invalid session ID");
        Path f = dir.resolve(id + ".json");
        if (!Files.exists(f)) throw new IOException("会话不存在: " + id);
        Session session;
        try (java.io.Reader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            session = gson.fromJson(reader, Session.class);
        }
        if (session == null || !id.equals(session.id)) throw new IOException("Invalid session snapshot: " + id);
        if (session.messages == null) session.messages = new ArrayList<Message>();
        journal(id).replay(session);
        if (session.taskState == null) session.taskState = new com.minion.core.agent.TaskState();
        if (session.queuedTasks == null) session.queuedTasks = new ArrayList<Session.QueuedTask>();
        Path queueFile = dir.resolve(id + ".queue.json");
        if (Files.exists(queueFile)) {
            Session.QueuedTask[] queued = gson.fromJson(new String(Files.readAllBytes(queueFile), StandardCharsets.UTF_8), Session.QueuedTask[].class);
            session.queuedTasks = new ArrayList<Session.QueuedTask>();
            if(queued!=null) java.util.Collections.addAll(session.queuedTasks,queued);
        }
        return session;
    }

    /** Small independent journal: GUI can persist queued work without touching live message arrays. */
    public void saveQueue(Session session) throws IOException {
        synchronized(session.queuedTasks) {
            Files.createDirectories(dir);
            Path target=dir.resolve(session.id + ".queue.json"), temp=dir.resolve(session.id + ".queue.json.tmp");
            Files.write(temp,gson.toJson(session.queuedTasks).getBytes(StandardCharsets.UTF_8));
            Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }
    }

    public List<SessionMeta> list() throws IOException {
        return index.list();
    }

    public Session latest() throws IOException {
        List<SessionMeta> metas = list();
        if (metas.isEmpty()) return null;
        return load(metas.get(0).id);
    }

    /** 删除会话文件（不存在也静默成功） */
    public void delete(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))
            return; // No generated session can have this ID; avoid traversal and preserve no-op semantics.
        Files.deleteIfExists(dir.resolve(id + ".json"));
        Files.deleteIfExists(dir.resolve(id + ".queue.json"));
        new HistoryArchive(dir, id).delete();
        journal(id).delete();
        synchronized (this) { journals.remove(id); }
        index.deleted(id);
    }

    public static class SessionMeta {
        public int indexVersion = 1;
        public long fileSize, modifiedMillis, lastMessageTs;
        public String workDir, modelName, modelDisplayName, lastSummary;
        public final String id;
        public final String createdAt;
        public final String preview;
        public final String title;
        public final boolean pinned;
        public final boolean archived;

        public SessionMeta(String id, String createdAt, String preview) {
            this(id, createdAt, preview, null, false, false);
        }

        public SessionMeta(String id, String createdAt, String preview, String title, boolean pinned, boolean archived) {
            this.id = id;
            this.createdAt = createdAt;
            this.preview = preview;
            this.title = title;
            this.pinned = pinned;
            this.archived = archived;
        }
    }
}
