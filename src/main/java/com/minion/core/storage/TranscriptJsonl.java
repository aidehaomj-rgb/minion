package com.minion.core.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import com.minion.core.diagnostics.DiagnosticLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Transcript JSONL 追加层（开发说明 v2.4 §9.1）：`{transcriptsRoot}/{sessionId}.jsonl` 每行一个事件。
 *
 * <p>与既有 SessionEventJournal（.events/{id}/*.json 写前日志）并存：<b>过渡期唯一权威源仍是
 * JSON 全量快照 + journal</b>；本层是可重放的追加日志副本，供崩溃校验与外部工具消费。
 * 双写顺序（开关开启时）：会话锁 → journal 写 → JSONL 追加并 flush → JSON 快照原子替换
 * （记录 lastTranscriptSeq）→ SessionIndex。
 *
 * <p>行 schema：{schemaVersion, eventId, sessionId, seq, parentUuid, timestamp, type, payload}。
 * seq 会话内严格递增；恢复只接受最后一个完整换行事件；半行、seq 缺口、重复 eventId 中断
 * 的尾部或未知主版本 → 停止重放、先备份损坏文件（.corrupt-时间戳）再告警，绝不静默跳过
 * 中间事件或伪造完成状态。恢复数据流以 JSON+journal 为准，本层恢复仅做一致性校验。
 */
final class TranscriptJsonl {

    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new Gson();

    private final Path file;
    private long tailSeq;              // 文件内最后一行完整事件的 seq（0=空）
    private String tailEventId;
    private boolean initialized;
    /** initialize/readLines 期间发现损坏（已备份）则为 false；损坏文件被移走后二次读取不可再发现 */
    private boolean consistent = true;

    TranscriptJsonl(Path transcriptsRoot, String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Invalid session ID");
        this.file = transcriptsRoot.resolve(sessionId + ".jsonl");
    }

    /** 追加一条事件（message 或 compact）；seq/parentUuid 由本类维护；写后 flush 保证崩溃可见 */
    synchronized void append(Session session, String type, Object payloadPayload) throws IOException {
        initialize();
        JsonObject line = new JsonObject();
        line.addProperty("schemaVersion", SCHEMA_VERSION);
        String eventId = UUID.randomUUID().toString();
        line.addProperty("eventId", eventId);
        line.addProperty("sessionId", session.id);
        line.addProperty("seq", tailSeq + 1);
        if (tailEventId != null) line.addProperty("parentUuid", tailEventId);
        line.addProperty("timestamp", System.currentTimeMillis());
        line.addProperty("type", type);
        line.add("payload", GSON.toJsonTree(payloadPayload));
        Files.createDirectories(file.getParent());
        String json = GSON.toJson(line);
        // 追加单行：先写到行缓冲再 force；文件系统崩溃最多产生半行（恢复时按损坏处理）
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file.toFile(), true)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.getFD().sync();
        }
        tailSeq++;
        tailEventId = eventId;
    }

    /** 快照元数据记录的 lastTranscriptSeq 之后是否存在未落快照的尾部（供恢复告警） */
    synchronized List<JsonObject> tailAfter(long snapshotLastSeq) throws IOException {
        initialize();
        List<JsonObject> tail = new ArrayList<JsonObject>();
        for (JsonObject line : readLines()) {
            if (line.has("seq") && line.get("seq").getAsLong() > snapshotLastSeq) tail.add(line);
        }
        return tail;
    }

    long tailSeq() throws IOException { initialize(); return tailSeq; }

    /**
     * 一致性校验（恢复路径）：校验文件可重放性——seq 从 1 连续递增、eventId 唯一、
     * parentUuid 链不断裂。损坏时备份为 .corrupt-&lt;millis&gt; 并告警，返回 false。
     */
    synchronized boolean verifyConsistent() throws IOException {
        initialize();
        return consistent && readLines() != null;
    }

    synchronized void delete() throws IOException {
        Files.deleteIfExists(file);
        tailSeq = 0;
        tailEventId = null;
        initialized = false;
    }

    // ==================== 内部 ====================

    private void initialize() throws IOException {
        if (initialized) return;
        if (Files.isRegularFile(file)) {
            List<JsonObject> lines = readLines();
            if (lines == null) { consistent = false; /* 损坏已备份告警；从当前空状态继续追加 */ }
            else if (!lines.isEmpty()) {
                JsonObject last = lines.get(lines.size() - 1);
                tailSeq = last.has("seq") ? last.get("seq").getAsLong() : 0;
                tailEventId = last.has("eventId") ? last.get("eventId").getAsString() : null;
            }
        }
        initialized = true;
    }

    /**
     * 逐行读取并校验：只接受最后一个完整换行事件；半行（文件尾无换行）→ 备份+告警+当其不存在；
     * seq 缺口 / 重复 eventId / parentUuid 断链 / 未知主版本 → 备份整个文件并返回 null（fail-closed）。
     */
    private List<JsonObject> readLines() throws IOException {
        if (!Files.isRegularFile(file)) return new ArrayList<JsonObject>();
        String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        List<JsonObject> out = new ArrayList<JsonObject>();
        Set<String> eventIds = new HashSet<String>();
        long expected = 0;
        String parent = null;
        int lineNo = 0;
        for (int pos = 0; pos < raw.length(); ) {
            int nl = raw.indexOf('\n', pos);
            String line;
            if (nl < 0) {   // 半行：文件尾未换行（崩溃写入），备份后忽略
                corrupt("tail-partial-line");
                break;
            }
            line = raw.substring(pos, nl);
            pos = nl + 1;
            lineNo++;
            if (line.trim().isEmpty()) continue;
            JsonObject obj;
            try { obj = GSON.fromJson(line, JsonObject.class); }
            catch (RuntimeException bad) { return corrupt("line-" + lineNo + "-unparseable"); }
            if (obj == null || !obj.has("schemaVersion") || obj.get("schemaVersion").getAsInt() != SCHEMA_VERSION)
                return corrupt("line-" + lineNo + "-schema");
            long seq = obj.has("seq") ? obj.get("seq").getAsLong() : -1;
            if (seq != expected + 1) return corrupt("line-" + lineNo + "-seq-gap:" + seq + "!=" + (expected + 1));
            String eventId = obj.has("eventId") ? obj.get("eventId").getAsString() : null;
            if (eventId == null || !eventIds.add(eventId)) return corrupt("line-" + lineNo + "-duplicate-or-missing-eventId");
            if (parent != null && (!obj.has("parentUuid") || !parent.equals(obj.get("parentUuid").getAsString())))
                return corrupt("line-" + lineNo + "-parent-chain-break");
            expected = seq;
            parent = eventId;
            out.add(obj);
        }
        return out;
    }

    /** 备份损坏文件并告警；返回 null 供调用方 fail-closed */
    private List<JsonObject> corrupt(String reason) throws IOException {
        if (Files.isRegularFile(file)) {
            Path backup = file.resolveSibling(file.getFileName().toString() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            DiagnosticLog.error("transcript", "JSONL 损坏（" + reason + "），已备份到 " + backup
                    + "；恢复以 JSON 快照+journal 为准，损坏尾部不参与重放");
        }
        return null;
    }

    /** 消息体适配：Message 直接作 payload（含 role/content/toolCalls 等） */
    static final class MessagePayload {
        public final Message message;
        MessagePayload(Message message) { this.message = message; }
    }
}
