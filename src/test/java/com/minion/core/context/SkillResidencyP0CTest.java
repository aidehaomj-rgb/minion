package com.minion.core.context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class SkillResidencyP0CTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static Message scoped(Message m, String task, long revision) {
        m.taskId = task; m.taskRevision = revision; return m;
    }
    private static Message skill(String name, String body, String task, long revision) {
        return scoped(Message.skill("<skill name=\"" + name + "\">\n" + body + "\n</skill>",
                name, "skills/" + name + "/SKILL.md", SkillResidency.contentVersion(body)), task, revision);
    }
    private static ContextManager context(FakeLlmClient llm) {
        ContextManager cm = new ContextManager(1000000, .9, 2, llm, 0);
        cm.setTaskScope("task", 1, "只完成当前任务", "只完成当前任务"); return cm;
    }
    private static long bodies(List<Message> messages) {
        return messages.stream().filter(SkillResidency::isSkill).count();
    }
    private static long references(List<Message> messages) {
        return messages.stream().filter(SkillResidency::isReference).count();
    }
    private static String wire(List<Message> messages) {
        JsonArray out = new JsonArray(); for (Message m : messages) out.add(m.toApiJson()); return out.toString();
    }
    private static String repeat(char c, int count) {
        char[] out = new char[count]; Arrays.fill(out, c); return new String(out);
    }

    @Test public void manyHistoricalSkillsHaveBoundedBodiesAndReferencesWithoutRewritingOldEvents() {
        FakeLlmClient llm = new FakeLlmClient(); ContextManager cm = context(llm);
        List<Message> history = new ArrayList<Message>();
        for (int i = 0; i < 40; i++) history.add(skill("s" + i, "PRIVATE_BODY_" + i, "task", 1));
        String originals = new Gson().toJson(history);
        List<Message> view = cm.prepareForRequest(history);
        assertEquals(2, bodies(view)); assertEquals(8, references(view));
        assertTrue(cm.isSkillResident(history, "s39")); assertFalse(cm.isSkillResident(history, "s0"));
        assertFalse(wire(view).contains("PRIVATE_BODY_0"));
        assertTrue(wire(view).contains("PRIVATE_BODY_39"));
        for (Message m : view) {
            assertFalse(m.isUserInstruction()); assertFalse(m.hasUserAuthorization());
            if (SkillResidency.isReference(m)) {
                assertFalse(m.pinned); assertNotNull(m.sourceEventId);
                assertTrue(m.content.contains(m.sourceEventId)); assertTrue(m.content.contains(m.sourceVersion));
                assertNotNull(ContextManager.findSource(history, m.sourceEventId));
            }
        }
        assertEquals(originals, new Gson().toJson(history));
        assertEquals(wire(view), wire(cm.prepareForRequest(view))); // repeated projection is idempotent
        assertEquals(0, llm.completeChatRequests.size()); assertEquals(150000, cm.autoCompressAtTokens());
    }

    @Test public void oldTaskAndRevisionCannotPinSkillsOrShadowAValidCurrentLoad() {
        ContextManager cm = context(new FakeLlmClient());
        Message old = skill("s", "OLD_TASK", "old", 1);
        Message stale = skill("s", "OLD_REVISION", "task", 1);
        cm.setActiveSkills(Collections.singletonList("s"));
        cm.setTaskScope("task", 2, "新要求", "停止旧要求，只处理最后一块");
        List<Message> history = new ArrayList<Message>(Arrays.asList(old, stale));
        assertEquals(0, bodies(cm.prepareForRequest(history)));
        Message latest = skill("s", "CURRENT_REVISION", "task", 2); history.add(latest);
        history.add(skill("s", "LATE_OLD_TASK", "old", 1));
        assertEquals(1, bodies(cm.prepareForRequest(history)));
        assertTrue(cm.isSkillResident(history, "S", latest.sourceVersion));
        assertFalse(cm.isSkillResident(history, "S", stale.sourceVersion));
        assertFalse(wire(cm.prepareForRequest(history)).contains("OLD_REVISION"));
    }

    @Test public void lifetimeAndExplicitCurrentStepSelectionAreBoundedAndDeterministic() {
        ContextManager cm = context(new FakeLlmClient());
        List<Message> history = new ArrayList<Message>(); history.add(skill("one", "one", "task", 1));
        for (int i = 0; i <= SkillResidency.MAX_USER_TURNS; i++)
            history.add(scoped(Message.realUser("补充" + i), "task", 1));
        assertFalse(cm.isSkillResident(history, "one"));
        cm.setActiveSkills(Collections.singletonList("ONE")); assertFalse(cm.isSkillResident(history, "one"));
        history.add(skill("one", "fresh one", "task", 1)); assertTrue(cm.isSkillResident(history, "one"));
        cm.setActiveSkills(Collections.<String>emptyList()); assertEquals(0, bodies(cm.prepareForRequest(history)));
        cm.setActiveSkills(Collections.singletonList("one"));
        cm.setTaskScope("new", 1, "新任务", "新任务");
        assertFalse(cm.isSkillResident(history, "one"));
    }

    @Test(expected = IllegalArgumentException.class) public void explicitSelectionCannotBypassResidentCountLimit() {
        context(new FakeLlmClient()).setActiveSkills(Arrays.asList("one", "two", "three"));
    }

    @Test public void explicitStepFilterCanSwitchOnlyToFreshBodiesAndNeverRenewsAnExpiredBody() {
        ContextManager cm = context(new FakeLlmClient());
        Message old = skill("old", "old", "task", 1);
        old.ts = System.currentTimeMillis() - SkillResidency.MAX_AGE_MILLIS - 1;
        List<Message> history = new ArrayList<Message>(Collections.singletonList(old));
        cm.setActiveSkills(Collections.<String>emptyList()); cm.activateSkill("old");
        assertFalse(cm.isSkillResident(history, "old"));
        history.add(skill("new", "new", "task", 1));
        assertFalse(cm.isSkillResident(history, "new"));
        cm.activateSkill("new"); assertTrue(cm.isSkillResident(history, "new"));
        assertEquals(1, bodies(cm.prepareForRequest(history)));
    }

    @Test public void aggregateBodyBudgetAndOversizeSkillCannotBecomePermanentPinnedContent() {
        ContextManager cm = context(new FakeLlmClient());
        List<Message> history = Arrays.asList(skill("a", repeat('A', 14000), "task", 1),
                skill("b", repeat('B', 14000), "task", 1), skill("huge", repeat('H', 30000), "task", 1));
        List<Message> view = cm.prepareForRequest(history);
        assertEquals(1, bodies(view)); assertTrue(cm.isSkillResident(history, "b"));
        assertFalse(cm.isSkillResident(history, "huge")); assertFalse(wire(view).contains(repeat('H', 100)));
        assertTrue(wire(view).contains("skills/huge/SKILL.md"));
    }

    @Test public void unscopedLegacyPinnedContentIsNotReactivatedForAnExplicitCurrentTask() {
        ContextManager cm = context(new FakeLlmClient());
        Message legacy = new Message(); legacy.role = Message.Role.USER; legacy.pinned = true;
        legacy.content = "<skill name=\"s\">\nlegacy body\n</skill>";
        String original = new Gson().toJson(legacy);
        assertEquals(0, bodies(cm.prepareForRequest(Collections.singletonList(legacy))));
        assertEquals(original, new Gson().toJson(legacy)); assertNull(legacy.origin);
        Message userText = scoped(Message.realUser("<skill name=\"s\">这只是用户引文</skill>"), "task", 1);
        assertFalse(cm.isSkillResident(Collections.singletonList(userText), "s"));
        assertEquals(userText.content, cm.prepareForRequest(Collections.singletonList(userText)).get(0).content);
    }

    @Test public void repeatedCompactionDoesNotReaccumulateRetiredReferencesOrSummarizeSkillRulesAsRequirements() {
        FakeLlmClient llm = new FakeLlmClient(); ContextManager cm = context(llm);
        List<Message> history = new ArrayList<Message>();
        for (int i = 0; i < 38; i++) {
            history.add(skill("s" + i, "SKILL_RULE_SHOULD_NOT_BE_SUMMARY_" + i, "task", 1));
            history.add(scoped(Message.realUser("证据记录" + i), "task", 1));
            history.add(scoped(Message.assistant("阶段已核验" + i), "task", 1));
        }
        for (int round = 0; round < 4; round++) {
            String name = "current" + round;
            cm.setActiveSkills(Collections.singletonList(name));
            history.add(skill(name, "CURRENT_SKILL_RULE_" + round, "task", 1));
            history.add(scoped(Message.realUser("38块仅剩最后一块，其他37块不得重做"), "task", 1));
            history.add(scoped(Message.assistant("核对最后一块验收项"), "task", 1));
            history = cm.compressForRecovery(history, "step38 active；只剩最后一项验收", "只完成当前任务");
            assertNull(StructuredCheckpoint.validate(history));
            assertEquals(1, bodies(history)); assertTrue(references(history) <= 8);
            assertTrue(cm.isSkillResident(history, name));
            assertEquals(Message.Origin.COMPACT_SUMMARY, history.get(0).origin);
            assertFalse(history.get(0).isUserInstruction());
        }
        for (String batch : llm.completeChatRequests) {
            assertFalse(batch.contains("SKILL_RULE_SHOULD_NOT_BE_SUMMARY_"));
            assertFalse(batch.contains("CURRENT_SKILL_RULE_"));
        }
        assertEquals(150000, cm.autoCompressAtTokens());
    }

    @Test public void changedDiskFileIsNotRereadOrRelabelledAsTheHistoricalSkillSnapshot() throws Exception {
        Path path = tmp.newFile("SKILL.md").toPath();
        Files.write(path, "version-one".getBytes(StandardCharsets.UTF_8));
        Message historical = skill("s", "version-one", "task", 1); historical.sourcePath = path.toString();
        String oldVersion = historical.sourceVersion;
        Files.write(path, "version-two".getBytes(StandardCharsets.UTF_8));
        ContextManager cm = context(new FakeLlmClient());
        List<Message> history = Collections.singletonList(historical);
        String projected = wire(cm.prepareForRequest(history));
        assertTrue(projected.contains("version-one")); assertTrue(projected.contains(oldVersion));
        assertFalse(projected.contains("version-two"));
        assertFalse(cm.isSkillResident(history, "s", SkillResidency.contentVersion("version-two")));
        assertEquals(oldVersion, historical.sourceVersion);
    }

    @Test public void dedupAndTruncationKeepRetrievableSourcesAndExplicitFailureEvidence() {
        ContextManager cm = context(new FakeLlmClient());
        Message original = scoped(Message.toolResult("read", "Read", repeat('X', 30000), true), "task", 1);
        Message copy = cm.prepareForRequest(Collections.singletonList(original)).get(0);
        assertEquals(original.eventId, copy.sourceEventId); assertTrue(copy.content.contains(original.eventId));
        assertEquals(original.content, ContextManager.findSource(Collections.singletonList(original), copy.sourceEventId).content);
        Message failure = scoped(Message.toolResult("err", "Read", repeat('E', 30000) + "UNRESOLVED", false), "task", 1);
        Message success = scoped(Message.toolResult("ok", "Read", failure.content, true), "task", 1);
        List<Message> view = cm.prepareForRequest(Arrays.asList(failure, success));
        assertEquals(failure.content, view.get(0).content); assertEquals(Boolean.FALSE, view.get(0).toolSucceeded);
        assertEquals(30000, original.content.length());
    }

    @Test public void removingOldSkillBodiesNeverSplitsMultiToolCallBatchesDuringCompression() {
        FakeLlmClient llm = new FakeLlmClient(); ContextManager cm = context(llm);
        List<Message> history = new ArrayList<Message>();
        for (int round = 0; round < 20; round++) {
            history.add(scoped(Message.realUser("当前步骤" + round), "task", 1));
            Message assistant = scoped(Message.assistant(null), "task", 1);
            List<ToolCall> calls = new ArrayList<ToolCall>();
            for (int i = 0; i < 3; i++) {
                ToolCall call = new ToolCall(); call.id = "c" + round + "_" + i; call.name = "Read"; call.arguments = "{}";
                calls.add(call);
            }
            assistant.toolCalls = calls; history.add(assistant);
            for (ToolCall call : calls) history.add(scoped(Message.toolResult(call.id, call.name, "证据", true), "task", 1));
            history.add(skill("s" + round, "技能规则", "task", 1));
            history.add(scoped(Message.assistant("本步结束"), "task", 1));
        }
        assertPaired(cm.prepareForRequest(history));
        assertPaired(cm.compress(history));
        assertPaired(cm.compressForRecovery(history));
        assertEquals(150000, cm.autoCompressAtTokens());
    }

    private static void assertPaired(List<Message> messages) {
        Set<String> pending = new HashSet<String>();
        for (Message m : messages) {
            if (m.role == Message.Role.TOOL) { assertTrue("orphan result " + m.toolCallId, pending.remove(m.toolCallId)); continue; }
            assertTrue("material inserted inside an incomplete batch", pending.isEmpty());
            if (m.toolCalls != null) for (ToolCall call : m.toolCalls) assertTrue(pending.add(call.id));
        }
        assertTrue(pending.isEmpty());
    }
}
