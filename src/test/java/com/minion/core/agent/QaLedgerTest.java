package com.minion.core.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.config.FeatureFlags;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.tools.AskUserQuestionTool;
import com.minion.core.tools.ToolResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 三批 D4 问题台账：TaskState.qaLedger 持久化回环、规范化去重命中/未命中、
 * 压缩存活（render 进 compress 的 taskState 串）、dedupe=false 仅记录、有界与会话级存活。
 */
public class QaLedgerTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static AskUserQuestionTool.QaLedger ledgerOf(final TaskState state) {
        return new AskUserQuestionTool.QaLedger() {
            @Override public String answered(String question) {
                TaskState.QaEntry e = state.findAnswered(question);
                return e == null ? null : e.answer;
            }
            @Override public void record(String question, String answer, String questionId) {
                state.recordQa(question, answer, questionId);
            }
        };
    }

    private static JsonObject args(String question) {
        JsonObject a = new JsonObject();
        a.addProperty("question", question);
        return a;
    }

    /** 挂起→回答→落台账的完整链路；返回第二次同问的工具结果（应命中缓存不再挂起） */
    private ToolResult askOnceThenRepeat(AskUserQuestionTool tool, RecordingUi ui,
                                         JsonObject args, String answer) throws Exception {
        final ToolResult[] first = new ToolResult[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { first[0] = tool.execute(args); }
                catch (Exception e) { first[0] = ToolResult.error("异常: " + e.getMessage()); }
            }
        });
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals("首次提问应挂起", 1, ui.asksStarted.size());
        assertTrue(tool.complete(answer));
        t.join(5000);
        assertNotNull(first[0]);
        assertTrue(first[0].ok);
        return tool.execute(args); // 同问重发：命中台账立即返回
    }

    /** 持久化回环：Gson 序列化/反序列化后台账仍可命中（会话 JSON 同路径） */
    @Test
    public void gsonRoundtripPreservesLedger() {
        TaskState s = new TaskState();
        s.begin("开发程序", true);
        s.recordQa("选哪个？", "方案B", "qid-1");
        Gson gson = new Gson();
        TaskState back = gson.fromJson(gson.toJson(s), TaskState.class);
        assertNotNull(back.qaLedger);
        TaskState.QaEntry e = back.findAnswered("选哪个？");
        assertNotNull("反序列化后同问应命中", e);
        assertEquals("方案B", e.answer);
        assertEquals("qid-1", e.questionId);
        assertTrue(e.time > 0);
        // 旧记录无 qaLedger 键：null 兼容，查找不炸
        TaskState legacy = gson.fromJson("{\"objective\":\"旧任务\"}", TaskState.class);
        assertNull(legacy.qaLedger);
        assertNull(legacy.findAnswered("选哪个？"));
    }

    /** 规范化：大小写/空白/全角差异视为同一问题 */
    @Test
    public void normalizationTreatsVariantsAsSameQuestion() {
        TaskState s = new TaskState();
        s.recordQa("Which 方案?", "B", null);
        assertNotNull(s.findAnswered("which  方案？")); // 小写+双空格+全角问号
        assertNotNull(s.findAnswered(" Which 方案? "));
        assertNull("不同问题不得误命中", s.findAnswered("Which 方案A?"));
        assertEquals(TaskState.questionKey("选哪个？"), TaskState.questionKey("选哪个?"));
    }

    /** 去重命中：同问重发立即返回缓存+注明已回答，不再挂起；questionId 落台账 */
    @Test
    public void dedupeHitReturnsCachedWithoutSuspending() throws Exception {
        TaskState state = new TaskState();
        RecordingUi ui = new RecordingUi();
        AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        tool.setQaLedger(ledgerOf(state), true);
        JsonObject args = args("选哪个？");
        final ToolResult[] first = new ToolResult[1];
        final String[] askedId = new String[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { first[0] = tool.execute(args); }
                catch (Exception e) { first[0] = ToolResult.error("异常: " + e.getMessage()); }
            }
        });
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        askedId[0] = tool.pendingQuestionId();
        assertTrue(tool.complete("方案B"));
        t.join(5000);
        assertTrue(first[0].ok);
        TaskState.QaEntry e = state.findAnswered("选哪个？");
        assertNotNull("回答成功应写入台账", e);
        assertEquals("方案B", e.answer);
        assertEquals(askedId[0], e.questionId);

        ToolResult again = tool.execute(args);
        assertTrue(again.ok);
        assertTrue("应注明复用: " + again.output, again.output.contains("已回答过"));
        assertTrue(again.output.contains("方案B"));
        assertEquals("命中缓存不得再次挂起", 1, ui.asksStarted.size());
    }

    /** 去重未命中：不同问题照常挂起 */
    @Test
    public void dedupeMissDifferentQuestionStillSuspends() throws Exception {
        TaskState state = new TaskState();
        RecordingUi ui = new RecordingUi();
        AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        tool.setQaLedger(ledgerOf(state), true);
        askOnceThenRepeat(tool, ui, args("选哪个？"), "方案B");
        final ToolResult[] other = new ToolResult[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { other[0] = tool.execute(args("另一个问题？")); }
                catch (Exception e) { other[0] = ToolResult.error("异常: " + e.getMessage()); }
            }
        });
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals("不同问题应再次挂起", 2, ui.asksStarted.size());
        assertTrue(tool.complete("另答"));
        t.join(5000);
        assertTrue(other[0].ok);
        assertEquals("另答", other[0].output);
    }

    /** dedupe=false：仅记录不去重，同问仍挂起 */
    @Test
    public void dedupeFalseRecordsButStillAsks() throws Exception {
        TaskState state = new TaskState();
        RecordingUi ui = new RecordingUi();
        AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        tool.setQaLedger(ledgerOf(state), false);
        final JsonObject args = args("选哪个？");
        for (int round = 1; round <= 2; round++) {
            final int expected = round;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try { tool.execute(args); } catch (Exception ignored) { }
                }
            });
            t.setDaemon(true);
            t.start();
            long deadline = System.currentTimeMillis() + 5000;
            while (ui.asksStarted.size() < expected && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertEquals("dedupe=false 第" + round + "次同问仍应挂起", expected, ui.asksStarted.size());
            assertTrue(tool.complete("方案B"));
            t.join(5000);
            assertFalse(t.isAlive());
        }
        assertNotNull("仅记录模式也应写入台账", state.findAnswered("选哪个？"));
        assertEquals("方案B", state.findAnswered("选哪个？").answer);
    }

    /** 结构化缓存（数字展开 JSON）命中后仍保持 JSON 形态，显示层回用户原文 */
    @Test
    public void structuredCachedAnswerKeepsDisplayRaw() throws Exception {
        TaskState state = new TaskState();
        RecordingUi ui = new RecordingUi();
        AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        tool.setQaLedger(ledgerOf(state), true);
        JsonObject args = JsonParser.parseString("{\"question\":\"选哪个？\",\"options\":["
                + "{\"label\":\"方案甲\"},{\"label\":\"方案乙\"}]}").getAsJsonObject();
        ToolResult again = askOnceThenRepeat(tool, ui, args, "2");
        assertTrue(again.ok);
        JsonObject wrapped = JsonParser.parseString(again.output).getAsJsonObject();
        assertTrue("缓存应带台账注明", wrapped.has("ledgerNote"));
        assertEquals("2", AskUserQuestionTool.answerForDisplay(again.output));
        assertEquals(1, ui.asksStarted.size());
    }

    /** 压缩存活 fixture：render 出的台账随 taskState 串进入压缩摘要 */
    @Test
    public void ledgerSurvivesCompactionViaRenderedTaskState() {
        TaskState s = new TaskState();
        s.begin("开发程序", true);
        s.recordQa("选哪个？", "方案B", "qid-1");
        String rendered = s.render(null);
        assertTrue(rendered.contains("本会话已回答过的问题"));
        assertTrue(rendered.contains("方案B"));

        FakeLlmClient llm = new FakeLlmClient();
        llm.rawCompressResult = true;
        llm.compressResult = "【摘要】历史对话要点";
        ContextManager cm = new ContextManager(1000000, .9, 0, llm, 0);
        List<Message> history = new ArrayList<Message>(Arrays.asList(
                Message.user("开发程序"), Message.assistant("好的")));
        List<Message> compacted = cm.compressForRecovery(history, rendered, s.objective);
        assertTrue("压缩应产出摘要", compacted.get(0).summary);
        assertTrue("台账应随 taskState 进入摘要: " + compacted.get(0).content,
                compacted.get(0).content.contains("本会话已回答过的问题"));
        assertTrue(compacted.get(0).content.contains("方案B"));
        // recoverAfterRestart 不清台账（会话级存活）
        s.recoverAfterRestart();
        assertNotNull(s.findAnswered("选哪个？"));
    }

    /** 有界 64 条；新任务不清台账（会话级而非任务级） */
    @Test
    public void ledgerBoundedAndSessionScoped() {
        TaskState s = new TaskState();
        s.begin("任务一", true);
        for (int i = 0; i < 70; i++) s.recordQa("问题" + i + "？", "答" + i, null);
        assertEquals(64, s.qaLedger.size());
        assertNull("最旧条目应被淘汰", s.findAnswered("问题0？"));
        assertNotNull(s.findAnswered("问题69？"));
        s.begin("任务二", true); // 强制新任务
        assertNotNull("台账是会话级，新任务不清空", s.findAnswered("问题69？"));
    }

    /** 配置默认 true + 开关注册 */
    @Test
    public void configDefaultTrueAndFlagRegistered() throws Exception {
        Path dir = tmp.newFolder().toPath();
        Config config = Config.load(dir);
        assertTrue(config.intentLedgerDedupe());
        config.set("intent.ledger.dedupe", "false");
        assertFalse(Config.load(dir).intentLedgerDedupe());
        assertTrue(FeatureFlags.known("intent.ledger.dedupe"));
    }
}