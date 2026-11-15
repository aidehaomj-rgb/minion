package com.minion.core.storage;

import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class HistoryArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void archivesEntirePreCompactionStreamAndReadsExactText() throws Exception {
        Path dir = temp.newFolder("sessions").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "test");
        session.taskState.id = "task-1";
        Message removed = Message.user("海关规则需要核对原文，不能凭摘要下结论");
        Message kept = Message.user("最新要求：继续执行");
        Message summary = Message.user("旧历史摘要"); summary.summary = true;
        assertEquals(1, store.archiveCompacted(session, Arrays.asList(removed, kept),
                Arrays.asList(summary, kept)));
        List<HistoryArchive.Hit> hits = store.searchHistory(session.id, "海关规则", 5);
        assertEquals(1, hits.size());
        assertEquals("task-1", hits.get(0).taskId);
        assertTrue(store.readHistory(session.id, hits.get(0).messageId, 0, 12000)
                .contains(removed.content));
        List<HistoryArchive.Hit> keptHits = store.searchHistory(session.id, "最新要求", 5);
        assertEquals(1, keptHits.size());
        assertEquals(kept.eventId, keptHits.get(0).eventId);
    }
    @Test public void compactionAcrossTasksPreservesPerMessageTaskScope() throws Exception {
        Path dir=temp.newFolder("mixed-task-history").toPath();
        SessionStore store=new SessionStore(dir);Session s=Session.create(temp.getRoot().getPath(),"test");
        s.taskState.id="task-new";
        Message old=Message.user("旧任务私人预算结论");old.taskId="task-old";
        Message current=Message.user("新任务项目资料结论");current.taskId="task-new";
        store.archiveCompacted(s,Arrays.asList(old,current),Collections.singletonList(current));
        assertTrue(store.searchHistory(s.id,"旧任务私人预算",5,"task-new").isEmpty());
        assertEquals("task-old",store.searchHistory(s.id,"旧任务私人预算",5,"task-old").get(0).taskId);
        assertEquals("task-old",store.readHistory(s.id,store.searchHistory(s.id,"旧任务私人预算",5).get(0).messageId,0,100).split("task=")[1].split(" ")[0]);
    }

    @Test public void laterCompactionDoesNotArchiveOldSummaryAgain() throws Exception {
        Path dir = temp.newFolder("later").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "test");
        Message first = Message.user("第一阶段独有证据");
        Message second = Message.user("第二阶段独有证据");
        Message summary1 = Message.user("第一阶段摘要"); summary1.summary = true;
        Message summary2 = Message.user("第二阶段摘要"); summary2.summary = true;
        assertEquals(1, store.archiveCompacted(session, Arrays.asList(first, second),
                Arrays.asList(summary1, second)));
        assertEquals(2, store.archiveCompacted(session, Arrays.asList(summary1, second),
                Collections.singletonList(summary2)));
        assertEquals(1, store.searchHistory(session.id, "第一阶段独有", 10).size());
        assertEquals(1, store.searchHistory(session.id, "第二阶段独有", 10).size());
        store.delete(session.id);
        assertTrue(store.searchHistory(session.id, "第一阶段独有", 10).isEmpty());
    }

    @Test public void toolResultCarriesPathMetadataAndCanBeFiltered() throws Exception {
        Path dir = temp.newFolder("paths").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "test");
        com.minion.core.llm.ToolCall call = new com.minion.core.llm.ToolCall();
        call.id = "call-1"; call.name = "Excel";
        call.arguments = "{\"action\":\"read\",\"path\":\"资料/销售.xlsx\"}";
        Message assistant = Message.assistant(null);
        assistant.toolCalls = Collections.singletonList(call);
        Message result = Message.toolResult("call-1", "Excel", "合计金额 1200 元");
        Message latest = Message.user("请继续核对原文件");
        store.archiveCompacted(session, Arrays.asList(assistant, result, latest),
                Collections.singletonList(latest));
        HistoryArchive archive = new HistoryArchive(dir, session.id);
        List<HistoryArchive.Hit> hits = archive.search("合计金额", 5, null, "销售.xlsx");
        assertEquals(1, hits.size());
        assertEquals(result.eventId, hits.get(0).eventId);
        assertTrue(hits.get(0).filePaths.contains("资料/销售.xlsx"));
        assertTrue(archive.search("合计金额", 5, null, "其它.xlsx").isEmpty());
    }

    @Test public void changedSourceFileInvalidatesOldHistoryEvidence() throws Exception {
        Path project = temp.newFolder("project").toPath();
        Path input = project.resolve("input.csv");
        Files.write(input, "amount\n1200\n".getBytes(StandardCharsets.UTF_8));
        Path sessions = temp.newFolder("stale-sessions").toPath();
        Session session = Session.create(project.toString(), "test");
        SessionStore store = new SessionStore(sessions);
        com.minion.core.llm.ToolCall call = new com.minion.core.llm.ToolCall();
        call.id = "read-1"; call.name = "Excel";
        call.arguments = "{\"action\":\"read\",\"path\":\"input.csv\"}";
        Message request = Message.assistant(null); request.toolCalls = Collections.singletonList(call);
        Message result = Message.toolResult("read-1", "Excel", "旧合计为1200");
        Message current = Message.user("继续核对");
        store.archiveCompacted(session, Arrays.asList(request, result, current), Collections.singletonList(current));
        HistoryArchive archive = new HistoryArchive(sessions, session.id);
        assertEquals("unchanged", archive.search("旧合计", 5).get(0).sourceState);
        Files.write(input, "amount\n9999\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("changed", archive.search("旧合计", 5).get(0).sourceState);
    }

    @Test public void sourceChangedBeforeCompactionIsAlreadyStale() throws Exception {
        Path project = temp.newFolder("late-capture").toPath();
        Path input = project.resolve("report.csv");
        Files.write(input, "old\n".getBytes(StandardCharsets.UTF_8));
        Session session = Session.create(project.toString(), "test");
        SessionStore store = new SessionStore(temp.newFolder("late-sessions").toPath());
        com.minion.core.llm.ToolCall call = new com.minion.core.llm.ToolCall();
        call.id = "read-late"; call.name = "Excel";
        call.arguments = "{\"path\":\"report.csv\"}";
        Message request = Message.assistant(null); request.toolCalls = Collections.singletonList(call);
        Message result = Message.toolResult("read-late", "Excel", "报告结论为旧值");
        result.ts = 1000L;
        Files.write(input, "new\n".getBytes(StandardCharsets.UTF_8));
        Message latest = Message.user("现在复核");
        store.archiveCompacted(session, Arrays.asList(request, result, latest), Collections.singletonList(latest));
        assertEquals("changed", store.searchHistory(session.id, "报告结论", 5).get(0).sourceState);
    }

    @Test public void middleOfLongToolResultRemainsSearchableAndPageable() throws Exception {
        Path dir = temp.newFolder("long-output").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "test");
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 8000; i++) output.append("普通历史工具数据第").append(i).append("行。\n");
        int markerOffset = output.length();
        output.append("第三十八分块独有证据XYZ\n");
        for (int i = 8000; i < 16000; i++) output.append("普通历史工具数据第").append(i).append("行。\n");
        Message result = Message.toolResult("child-38", "Task", output.toString());
        Message latest = Message.user("新指令只处理B表");
        store.archiveCompacted(session, Arrays.asList(result, latest), Collections.singletonList(latest));
        HistoryArchive.Hit hit = store.searchHistory(session.id, "第三十八分块独有证据XYZ", 5).get(0);
        assertTrue(hit.offset > 16000);
        assertTrue(store.readHistory(session.id, hit.messageId, markerOffset, 100)
                .contains("第三十八分块独有证据XYZ"));
    }

    @Test public void damagedIndexCannotClaimOriginalEventsWereSafelyArchived() throws Exception {
        Path dir = temp.newFolder("tampered").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "test");
        Message first = Message.user("必须保留的原始要求");
        Message kept = Message.user("继续");
        store.archiveCompacted(session, Arrays.asList(first, kept), Collections.singletonList(kept));
        Path history = dir.resolve(".history").resolve(session.id);
        Path index;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(history, "*.idx.json")) {
            index = stream.iterator().next();
        }
        String text = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
        Files.write(index, text.replace(first.eventId, java.util.UUID.randomUUID().toString())
                .getBytes(StandardCharsets.UTF_8));
        try {
            store.archiveCompacted(session, Arrays.asList(first, kept, Message.user("新证据")),
                    Collections.singletonList(kept));
            fail("damaged index must block compaction");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("checksum") || expected.getMessage().contains("mismatch"));
        }
    }

    @Test public void tamperedIndexCannotForgeSourceStatusInSearch() throws Exception {
        Path dir = temp.newFolder("tampered-search").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "test");
        Message old = Message.user("独有历史原文");
        Message current = Message.user("继续");
        store.archiveCompacted(session, Arrays.asList(old, current), Collections.singletonList(current));
        Path history = dir.resolve(".history").resolve(session.id);
        Path index;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(history, "*.idx.json")) {
            index = stream.iterator().next();
        }
        String text = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
        Files.write(index, text.replace("独有历史原文", "伪造历史原文").getBytes(StandardCharsets.UTF_8));
        assertTrue(store.searchHistory(session.id, "伪造历史原文", 5).isEmpty());
    }

    @Test public void thirtyEightChildResultsSurviveRepeatedCompactionsWithoutDuplication() throws Exception {
        Path dir = temp.newFolder("children").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "test");
        java.util.ArrayList<Message> before = new java.util.ArrayList<Message>();
        for (int i = 1; i <= 38; i++)
            before.add(Message.toolResult("child-" + i, "Task", "第" + i + "分块原始结果证据"));
        Message current = Message.user("现在只汇总38块，不重新执行");
        before.add(current);
        Message summary = Message.user("结构化摘要"); summary.summary = true;
        assertEquals(38, store.archiveCompacted(session, before, Arrays.asList(summary, current)));
        store.archiveCompacted(session, Arrays.asList(summary, current, Message.user("继续汇总")),
                Collections.singletonList(current));
        assertEquals(1, store.searchHistory(session.id, "第38分块原始结果", 10).size());
        assertEquals(1, store.searchHistory(session.id, "现在只汇总38块", 10).size());
    }

    @Test public void modelSwitchAndSessionReloadKeepArchiveEvidence() throws Exception {
        Path dir = temp.newFolder("model-switch").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "qwen-old");
        Message evidence = Message.toolResult("call", "Read", "模型切换前核验依据");
        Message current = Message.user("改用新模型后继续");
        store.archiveCompacted(session, Arrays.asList(evidence, current), Collections.singletonList(current));
        session.messages.add(current);
        session.modelName = "qwen-new";
        store.save(session);
        Session restored = store.load(session.id);
        assertEquals(current.eventId, restored.messages.get(0).eventId);
        HistoryArchive.Hit hit = store.searchHistory(restored.id, "模型切换前核验依据", 5).get(0);
        assertEquals(evidence.eventId, hit.eventId);
        assertTrue(store.readHistory(restored.id, hit.messageId, hit.offset, 100)
                .contains("模型切换前核验依据"));
    }

    @Test public void editedLiveMessageCreatesNewImmutableEventRevision() throws Exception {
        Path dir = temp.newFolder("revision").toPath();
        SessionStore store = new SessionStore(dir);
        Session session = Session.create(temp.getRoot().getPath(), "test");
        Message old = Message.user("较早步骤");
        Message current = Message.user("原要求处理A表");
        store.archiveCompacted(session, Arrays.asList(old, current), Collections.singletonList(current));
        String originalId = current.eventId;
        current.content = "新要求只处理B表";
        store.archiveCompacted(session, Arrays.asList(current, Message.user("继续")), Collections.<Message>emptyList());
        assertNotEquals(originalId, current.eventId);
        assertEquals(1, store.searchHistory(session.id, "原要求处理A表", 5).size());
        assertEquals(1, store.searchHistory(session.id, "新要求只处理B表", 5).size());
    }
}
