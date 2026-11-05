package com.minion.core.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class SessionEventJournalTest {
    @Test public void restoredSnapshotSeedsDedupAndStillRecordsChangedMessages() throws Exception {
        Session s=session(); SessionStore store=store(); s.messages.add(Message.user("原文")); store.save(s);
        SessionStore next=store(); Session restored=next.load(s.id); long cursor=restored.journalSequence;
        next.save(restored); assertEquals(cursor,restored.journalSequence);
        restored.messages.get(0).content="修改后的原文"; next.save(restored);
        assertEquals(cursor+1,restored.journalSequence);
        assertEquals("修改后的原文",store().load(s.id).messages.get(0).content);
    }

    @Test public void checkpointedOldEventsAreNotReparsedOnLoad() throws Exception {
        Session s=session(); SessionStore store=store(); s.messages.add(Message.user("快照已包含")); store.save(s);
        Path event=tmp.getRoot().toPath().resolve("sessions/.events/"+s.id+"/0000000000000001.json");
        Files.write(event,"old evidence unreadable".getBytes(StandardCharsets.UTF_8));
        // 历史原件是否完好留给按需回溯；不能让旧证据的全量校验阻塞打开已保存快照。
        assertEquals("快照已包含",store().load(s.id).messages.get(0).content);
    }
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private SessionStore store() { return new SessionStore(tmp.getRoot().toPath().resolve("sessions")); }
    private Session session() { return Session.create(tmp.getRoot().getPath(), "qwen-test"); }

    @Test public void crashBetweenEventAndSnapshotReplaysUserAssistantAndTool() throws Exception {
        SessionStore store = store();
        Session s = session();
        store.save(s);
        Message user = Message.user("分析此表");
        store.appendMessage(s, user);
        s.messages.add(user);
        ToolCall call = new ToolCall();
        call.id = "call-1"; call.name = "Read"; call.arguments = "{\"path\":\"data/report.xlsx\"}";
        Message assistant = Message.assistant(null); assistant.toolCalls = Collections.singletonList(call);
        store.appendMessage(s, assistant);
        s.messages.add(assistant);
        Message result = Message.toolResult("call-1", "Read", "销售额=42");
        store.appendMessage(s, result); // process stops before the in-memory append or next snapshot

        Session restored = store().load(s.id);
        assertEquals(3, restored.messages.size());
        assertEquals("分析此表", restored.messages.get(0).content);
        assertEquals("销售额=42", restored.messages.get(2).content);
        assertEquals(result.eventId, restored.messages.get(2).eventId);
        Path third = tmp.getRoot().toPath().resolve("sessions/.events/" + s.id + "/0000000000000003.json");
        JsonObject event = new JsonParser().parse(new String(Files.readAllBytes(third), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("data/report.xlsx", event.getAsJsonArray("filePaths").get(0).getAsString());
        assertTrue(event.get("time").getAsLong() > 0);
    }

    @Test public void compactionTransitionReplaysWithoutResurrectingOldContext() throws Exception {
        SessionStore store = store(); Session s = session();
        s.messages.add(Message.user("旧要求"));
        store.save(s);
        Message summary = Message.user("结构化摘要：新要求"); summary.summary = true;
        Message newest = Message.user("新要求");
        store.recordCompactionTransition(s, Arrays.asList(summary, newest));
        Session restored = store().load(s.id);
        assertEquals(2, restored.messages.size());
        assertEquals("新要求", restored.messages.get(1).content);
        assertEquals("结构化摘要：新要求", restored.messages.get(0).content);
        store().save(restored);
        Session twice = store().load(s.id);
        assertEquals(2, twice.messages.size());
    }

    @Test public void repeatedSaveDoesNotDuplicateEvents() throws Exception {
        SessionStore store = store(); Session s = session();
        s.messages.add(Message.user("一次"));
        store.save(s);
        long cursor = s.journalSequence;
        store.save(s);
        assertEquals(cursor, s.journalSequence);
        assertEquals(1, store().load(s.id).messages.size());
    }

    @Test public void userEventRestoresLatestTaskControlPlane() throws Exception {
        SessionStore store = store(); Session s = session(); store.save(s);
        s.taskState.begin("新任务：只分析今年", true);
        String taskId = s.taskState.id;
        store.appendMessage(s, Message.user("新任务：只分析今年"));
        Session restored = store().load(s.id);
        assertEquals(taskId, restored.taskState.id);
        assertEquals("新任务：只分析今年", restored.taskState.objective);
        assertEquals("新任务：只分析今年", restored.messages.get(0).content);
    }

    @Test public void corruptedEventBlocksRecoveryRatherThanInventingHistory() throws Exception {
        SessionStore store = store(); Session s = session(); store.save(s);
        store.appendMessage(s, Message.user("不可丢失"));
        Path event = tmp.getRoot().toPath().resolve("sessions/.events/" + s.id + "/0000000000000001.json");
        String text = new String(Files.readAllBytes(event), StandardCharsets.UTF_8);
        Files.write(event, text.replace("不可丢失", "已篡改").getBytes(StandardCharsets.UTF_8));
        try { store().load(s.id); fail("corrupt event must block recovery"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("checksum")); }
    }

    @Test public void deletingSessionRemovesItsJournalOnly() throws Exception {
        SessionStore store = store(); Session a = session(), b = session();
        a.messages.add(Message.user("A")); b.messages.add(Message.user("B"));
        store.save(a); store.save(b); store.delete(a.id);
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("sessions/.events/" + a.id)));
        assertEquals("B", store.load(b.id).messages.get(0).content);
    }

    @Test public void v43UserEventWithoutRevisionFieldsStillReplaysWithOriginalChecksum() throws Exception {
        SessionStore store=store();Session s=session();store.save(s);
        s.taskState.begin("旧版正在进行的任务",false);
        store.appendMessage(s,Message.user("旧版正在进行的任务"));
        Path file=tmp.getRoot().toPath().resolve("sessions/.events/"+s.id+"/0000000000000001.json");
        com.google.gson.Gson gson=new com.google.gson.Gson();
        JsonObject event=JsonParser.parseString(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getAsJsonObject();
        event.remove("hash");event.getAsJsonObject("message").remove("taskRevision");
        event.getAsJsonObject("taskState").remove("instructionRevision");
        event.getAsJsonObject("taskState").remove("checkpointRevision");
        event.getAsJsonObject("taskState").remove("latestUserRequirement");
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(gson.toJson(event).getBytes(StandardCharsets.UTF_8));
        StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format("%02x",b&255));
        event.addProperty("hash",hash.toString());Files.write(file,gson.toJson(event).getBytes(StandardCharsets.UTF_8));
        Session restored=store().load(s.id);
        assertEquals("旧版正在进行的任务",restored.taskState.objective);
        assertEquals(1,restored.taskState.revision());assertNull(restored.messages.get(0).taskRevision);
        assertFalse(gson.toJson(restored.messages.get(0)).contains("taskRevision"));
    }
}
