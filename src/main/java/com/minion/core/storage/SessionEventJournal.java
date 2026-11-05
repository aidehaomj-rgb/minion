package com.minion.core.storage;

import com.google.gson.Gson;
import com.minion.core.agent.Session;
import com.minion.core.agent.TaskState;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable per-message write-ahead records; the session JSON is only a materialized checkpoint. */
final class SessionEventJournal {
    private final Gson gson = new Gson();
    private final Path root;
    private final Map<String, String> seen = new HashMap<String, String>();
    private long sequence;
    private boolean initialized;

    SessionEventJournal(Path sessions, String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid session ID");
        root = sessions.resolve(".events").resolve(id);
    }

    synchronized long sequence() throws IOException { initialize(); return sequence; }

    synchronized void recordMissing(Session session) throws IOException {
        initialize();
        if (session.messages == null) return;
        for (Message m : session.messages) record(session, m);
    }

    synchronized void record(Session session, Message message) throws IOException {
        initialize();
        if (message == null || message.role == Message.Role.SYSTEM) return;
        if (message.eventId == null || message.eventId.isEmpty()) message.eventId = UUID.randomUUID().toString();
        String json = gson.toJson(message);
        String hash = sha256(json);
        if (hash.equals(seen.get(message.eventId))) return;
        Event e = base(session, "message");
        e.eventId = message.eventId;
        e.message = gson.fromJson(json, Message.class);
        if (message.role == Message.Role.USER && session.taskState != null)
            e.taskState = gson.fromJson(gson.toJson(session.taskState), TaskState.class);
        e.filePaths = paths(session, message);
        write(e);
        seen.put(message.eventId, hash);
    }

    synchronized void compact(Session session, List<Message> after) throws IOException {
        initialize();
        Event e = base(session, "compact");
        e.messages = gson.fromJson(gson.toJson(after), Message[].class);
        if (session.taskState != null) e.taskState = gson.fromJson(gson.toJson(session.taskState), TaskState.class);
        write(e);
    }

    synchronized void replay(Session session) throws IOException {
        initialize();
        if (session.journalSequence > sequence)
            throw new IOException("Session snapshot refers to missing journal events");
        // 快照之前的原始事件只供按需回溯，不在每次启动时重读/重新哈希。
        // 去重只需当前工作上下文；快照之后的尾部仍逐项校验并恢复。
        for (Message m : session.messages) {
            if (m.eventId != null) seen.put(m.eventId, sha256(gson.toJson(m)));
        }
        for (long n = session.journalSequence + 1; n <= sequence; n++) {
            Event e = read(n);
            if ("compact".equals(e.kind)) {
                session.messages = new ArrayList<Message>();
                if (e.messages != null) for (Message m : e.messages) session.messages.add(m);
            } else if ("message".equals(e.kind)) {
                boolean replaced = false;
                for (int i = 0; i < session.messages.size(); i++) {
                    if (e.eventId.equals(session.messages.get(i).eventId)) {
                        session.messages.set(i, e.message);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) session.messages.add(e.message);
            }
            if ("message".equals(e.kind)) seen.put(e.eventId, sha256(gson.toJson(e.message)));
            if (e.taskState != null) session.taskState = e.taskState;
        }
        session.journalSequence = sequence;
    }

    synchronized void delete() throws IOException {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) Files.delete(file);
        }
        Files.delete(root);
        sequence = 0;
        initialized = false;
        seen.clear();
    }

    private Event base(Session session, String kind) {
        Event e = new Event();
        e.kind = kind;
        e.time = System.currentTimeMillis();
        e.taskId = session.taskState == null ? null : session.taskState.id;
        return e;
    }

    private List<String> paths(Session session, Message m) {
        LinkedHashSet<String> paths = new LinkedHashSet<String>();
        if (m.toolCalls != null) for (ToolCall c : m.toolCalls)
            if (c != null) paths.addAll(HistoryArchive.pathsFromArguments(c.arguments));
        if (m.toolCallId != null && session.messages != null) for (Message earlier : session.messages)
            if (earlier != null && earlier.toolCalls != null) for (ToolCall c : earlier.toolCalls)
                if (c != null && m.toolCallId.equals(c.id)) paths.addAll(HistoryArchive.pathsFromArguments(c.arguments));
        return new ArrayList<String>(paths);
    }

    private void initialize() throws IOException {
        if (initialized) return;
        if (Files.isDirectory(root)) {
            long count = 0, max = 0;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (!name.matches("[0-9]{16}\\.json")) throw new IOException("Invalid journal event: " + file);
                    max = Math.max(max, Long.parseLong(name.substring(0, 16)));
                    count++;
                }
            }
            if (max != count) throw new IOException("Missing journal event in " + root);
            sequence = count;
        }
        initialized = true;
    }

    private Event read(long n) throws IOException {
        Path file = root.resolve(String.format("%016d.json", n));
        if (!Files.isRegularFile(file)) throw new IOException("Missing journal event: " + file);
        try {
            Event e = gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Event.class);
            if (e == null || e.sequence != n || e.hash == null) throw new IOException("Invalid journal event: " + file);
            String stored = e.hash;
            e.hash = null;
            if (!stored.equals(sha256(gson.toJson(e)))) throw new IOException("Journal checksum mismatch: " + file);
            e.hash = stored;
            if (!("message".equals(e.kind) && e.eventId != null && e.message != null)
                    && !("compact".equals(e.kind) && e.messages != null))
                throw new IOException("Invalid journal payload: " + file);
            return e;
        } catch (RuntimeException bad) { throw new IOException("Invalid journal event: " + file, bad); }
    }

    private void write(Event e) throws IOException {
        Files.createDirectories(root);
        e.sequence = sequence + 1;
        e.hash = sha256(gson.toJson(e));
        Path file = root.resolve(String.format("%016d.json", e.sequence));
        Path temp = root.resolve(file.getFileName().toString() + ".tmp");
        Files.write(temp, gson.toJson(e).getBytes(StandardCharsets.UTF_8));
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        sequence++;
    }

    private static String sha256(String value) throws IOException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = "0123456789abcdef".toCharArray(), out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int b = bytes[i] & 255; out[i * 2] = hex[b >>> 4]; out[i * 2 + 1] = hex[b & 15];
            }
            return new String(out);
        } catch (Exception e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private static final class Event {
        long sequence;
        String kind;
        long time;
        String taskId;
        TaskState taskState;
        String eventId;
        List<String> filePaths;
        Message message;
        Message[] messages;
        String hash;
    }
}
