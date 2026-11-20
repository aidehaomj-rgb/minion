package com.minion.core.team;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 团队文件邮箱（开发说明 v2.4 §8.3）：{teamsRoot}/{team}/inboxes/{agent}.json + lockfile。
 *
 * <p>消息类型：text、shutdown_request、plan_approval_request、plan_approval_response。
 * 每条消息唯一 messageId；<b>接收方持久化已读游标</b>（read-cursors/{agent}.json）防重启重复消费。
 * 写入在锁内「读取→追加→原子替换」；跨进程用 {@code .lock} 文件锁（java.nio FileLock）。
 * 广播（to="*"）逐 inbox 各写一份（每成员独立去重）。损坏 inbox 按空重建（fail-closed 告警），
 * 不静默合并他人数据。
 */
public final class TeamMailbox {

    public static final String BROADCAST = "*";

    private static final Gson GSON = new Gson();

    public static final class Message {
        public String messageId;
        public String from;
        public String to;
        public String type = "text";     // text / shutdown_request / plan_approval_request / plan_approval_response
        public String text;
        public long time;
    }

    private final Path teamDir;
    private final Path inboxes;
    private final Path cursors;

    public TeamMailbox(Path teamsRoot, String team) {
        if (team == null || !team.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid team name");
        this.teamDir = teamsRoot.resolve(team);
        this.inboxes = teamDir.resolve("inboxes");
        this.cursors = teamDir.resolve("read-cursors");
    }

    /** 创建团队目录与成员 inbox（幂等；members 至少含 lead） */
    public synchronized void create(List<String> members) throws IOException {
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("team needs at least one member");
        for (String m : members) requireAgent(m);
        Files.createDirectories(inboxes);
        Files.createDirectories(cursors);
        for (String m : members) {
            Path box = inboxes.resolve(m + ".json");
            if (!Files.exists(box)) writeLocked(box, new ArrayList<Message>());
        }
    }

    /** 成员列表（inbox 文件名） */
    public synchronized List<String> members() throws IOException {
        List<String> out = new ArrayList<String>();
        if (!Files.isDirectory(inboxes)) return out;
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(inboxes, "*.json")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.endsWith(".json")) out.add(n.substring(0, n.length() - 5));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 发送：to=* 广播到全部成员（不含发送者自己）；否则写入单个 inbox。返回送达 inbox 数 */
    public synchronized int send(String from, String to, String type, String text) throws IOException {
        requireAgent(from);
        List<String> targets = new ArrayList<String>();
        if (BROADCAST.equals(to)) {
            for (String m : members()) if (!m.equals(from)) targets.add(m);
        } else {
            requireAgent(to);
            Path box = inboxes.resolve(to + ".json");
            if (!Files.exists(box)) throw new IOException("目标成员不存在: " + to);
            targets.add(to);
        }
        for (String target : targets) {
            Message m = new Message();
            m.messageId = UUID.randomUUID().toString();
            m.from = from;
            m.to = target;
            m.type = type == null || type.trim().isEmpty() ? "text" : type;
            m.text = text;
            m.time = System.currentTimeMillis();
            deliver(m);
        }
        return targets.size();
    }

    /**
     * 读取：返回目标 inbox 中<b>已读游标之后</b>的消息（游标=已全部消费的最后 messageId），
     * 消费成功后由调用方调 {@link #ack}。崩溃在 ack 前重读会再次返回同批（at-least-once）。
     */
    public synchronized List<Message> receive(String agent) throws IOException {
        requireAgent(agent);
        List<Message> box = read(inboxes.resolve(agent + ".json"));
        String cursor = readCursor(agent);
        if (cursor == null) return box;
        int idx = -1;
        for (int i = box.size() - 1; i >= 0; i--)
            if (cursor.equals(box.get(i).messageId)) { idx = i; break; }
        return new ArrayList<Message>(box.subList(idx + 1, box.size()));
    }

    /** 确认消费到最后一条（游标原子持久化；重启不重复消费） */
    public synchronized void ack(String agent, String lastMessageId) throws IOException {
        requireAgent(agent);
        Files.createDirectories(cursors);
        Path cur = cursors.resolve(agent + ".json");
        Path tmp = cursors.resolve(agent + ".json.tmp");
        Files.write(tmp, ("{\"lastMessageId\":\"" + lastMessageId + "\"}").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, cur, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** 删除团队：停成员、清目录由调用方确认；本方法删除 inbox/cursor 数据（不可逆） */
    public synchronized void deleteTeam() throws IOException {
        deleteRecursively(teamDir);
    }

    // ==================== 内部 ====================

    private void deliver(Message m) throws IOException {
        Path box = inboxes.resolve(m.to + ".json");
        List<Message> current = read(box);
        current.add(m);
        writeLocked(box, current);
    }

    /** 锁内原子替换写：{file}.lock 文件锁 + tmp+rename */
    private void writeLocked(Path file, List<Message> messages) throws IOException {
        Files.createDirectories(file.getParent());
        Path lockFile = file.resolveSibling(file.getFileName().toString() + ".lock");
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, GSON.toJson(messages).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private List<Message> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return new ArrayList<Message>();
        try {
            List<Message> parsed = GSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                    new TypeToken<List<Message>>() { }.getType());
            if (parsed == null) return new ArrayList<Message>();
            // 去重（messageId 唯一）：重复投递不留副本
            java.util.LinkedHashMap<String, Message> unique = new java.util.LinkedHashMap<String, Message>();
            for (Message m : parsed) if (m != null && m.messageId != null) unique.put(m.messageId, m);
            return new ArrayList<Message>(unique.values());
        } catch (RuntimeException corrupt) {
            com.minion.core.diagnostics.DiagnosticLog.warn("team", "inbox 损坏按空重建: " + file);
            return new ArrayList<Message>();
        }
    }

    private String readCursor(String agent) throws IOException {
        Path cur = cursors.resolve(agent + ".json");
        if (!Files.isRegularFile(cur)) return null;
        try {
            Map<String, Object> raw = GSON.fromJson(new String(Files.readAllBytes(cur), StandardCharsets.UTF_8),
                    new TypeToken<Map<String, Object>>() { }.getType());
            Object v = raw == null ? null : raw.get("lastMessageId");
            return v instanceof String ? (String) v : null;
        } catch (RuntimeException corrupt) {
            return null;
        }
    }

    private static void requireAgent(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("Invalid agent name: " + name);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) {
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) deleteRecursively(c);
            }
        }
        Files.deleteIfExists(p);
    }
}
