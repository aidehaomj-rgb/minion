package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.RecordingUi;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 三批 D2：AskUserQuestion 批量提问（questions 真数组形状判别）。
 * 规约：真批量=questions 为真 JSON 数组、1-4 项、每项为含非空字符串 question 的对象；
 * 其余一律落旧容错路径（字符串化数组=旧畸形别名，零收紧）。单元素批量与单问完全等价。
 * 回答按行首 Qn: 分段，全部到齐才包装 batchAnswers；缺号/重号/越界/自由文本原文回传不猜测。
 */
public class AskUserBatchQuestionTest {

    private static JsonObject q(String question, boolean multi, String... labels) {
        JsonObject o = new JsonObject();
        o.addProperty("question", question);
        if (labels.length > 0) {
            JsonArray opts = new JsonArray();
            for (String l : labels) {
                JsonObject op = new JsonObject();
                op.addProperty("label", l);
                opts.add(op);
            }
            o.add("options", opts);
        }
        if (multi) o.addProperty("multiSelect", true);
        return o;
    }

    private static JsonObject batch(JsonObject... qs) {
        JsonArray arr = new JsonArray();
        for (JsonObject x : qs) arr.add(x);
        JsonObject args = new JsonObject();
        args.add("questions", arr);
        return args;
    }

    private static String answer(JsonObject args, String raw) {
        return AskUserQuestionTool.answerForModel(AskUserQuestionTool.normalize(args), raw);
    }

    /** 真批量 2/3/4 问：正文按 Qn 堆叠，旧字段=首问快照 */
    @Test
    public void trueBatch_stacksQuestionsAndKeepsFirstAsLegacyFields() {
        for (int n = 2; n <= 4; n++) {
            JsonObject[] qs = new JsonObject[n];
            for (int i = 0; i < n; i++) qs[i] = q("问题" + (i + 1) + "？", false, "甲", "乙");
            AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(batch(qs));
            assertNotNull(a.batch);
            assertEquals(n, a.batch.size());
            assertEquals("问题1？", a.question);
            assertEquals(2, a.options.size());
            StringBuilder expect = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (expect.length() > 0) expect.append("\n\n");
                expect.append("Q").append(i + 1).append(": 问题").append(i + 1)
                        .append("？\n[1] 甲\n[2] 乙");
            }
            assertEquals(expect.toString(), a.renderText());
            assertFalse(a.isEmpty());
        }
    }

    /** 单元素批量与扁平单问完全等价（渲染逐字一致，旧字段一致） */
    @Test
    public void singleElementBatch_equivalentToFlatSingleQuestion() {
        JsonObject item = q("选哪个？", true, "方案A", "方案B");
        item.addProperty("header", "方案");
        AskUserQuestionTool.Ask viaBatch = AskUserQuestionTool.normalize(batch(item));
        AskUserQuestionTool.Ask flat = AskUserQuestionTool.normalize(item);
        assertEquals(flat.renderText(), viaBatch.renderText());
        assertEquals(flat.question, viaBatch.question);
        assertEquals(flat.header, viaBatch.header);
        assertEquals(flat.multiSelect, viaBatch.multiSelect);
        assertEquals(flat.options.size(), viaBatch.options.size());
        assertEquals(flat.placeholder(), viaBatch.placeholder());
        // 单元素仍走单问展开口径
        assertEquals(answer(item, "1,2"), answer(batch(item), "1,2"));
    }

    /** 畸形形状一律落旧路径：batch 为 null，旧容错行为不变 */
    @Test
    public void malformedShapes_fallBackToLegacyPath() {
        // 字符串化的选项数组（线上实证 B 形态）：不是真批量
        JsonArray opts = new JsonArray();
        JsonObject o1 = new JsonObject(); o1.addProperty("label", "① 仅 UI 兜底"); opts.add(o1);
        JsonObject strOpts = new JsonObject();
        strOpts.addProperty("questions", opts.toString());
        strOpts.addProperty("question", "这次修到哪一层？");
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(strOpts);
        assertNull(a.batch);
        assertEquals(1, a.options.size());

        // 元素缺 question 键
        JsonObject noQ = new JsonObject(); noQ.addProperty("header", "只有标题");
        assertNull(AskUserQuestionTool.normalize(batch(noQ)).batch);
        // question 为空串
        assertNull(AskUserQuestionTool.normalize(batch(q("", false, "甲"))).batch);
        // 超过 4 项
        assertNull(AskUserQuestionTool.normalize(batch(q("1？", false), q("2？", false),
                q("3？", false), q("4？", false), q("5？", false))).batch);
        // 元素不是对象
        JsonArray mixed = new JsonArray();
        mixed.add(q("1？", false));
        mixed.add("纯字符串");
        JsonObject args = new JsonObject(); args.add("questions", mixed);
        assertNull(AskUserQuestionTool.normalize(args).batch);
        // 空数组
        assertNull(AskUserQuestionTool.normalize(batch()).batch);
    }

    /** 顶层 question 与真批量并存：批量优先（顶层键被模型混写时不丢批量） */
    @Test
    public void topLevelQuestionCoexisting_batchWins() {
        JsonObject args = batch(q("批量问1？", false, "甲"), q("批量问2？", false, "乙"));
        args.addProperty("question", "顶层旧问？");
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertNotNull(a.batch);
        assertEquals("批量问1？", a.question);
        assertTrue(a.renderText().startsWith("Q1: 批量问1？"));
    }

    /** 占位短文本：批量报总数 + 首问；永不为空 */
    @Test
    public void placeholder_reportsCountAndFirstQuestion() {
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(
                batch(q("第一问？", false), q("第二问？", false)));
        assertEquals("共2问：第一问？", a.placeholder());
    }

    /** schema 增量：questions 数组入口；required 仍只锁 question（旧契约不变） */
    @Test
    public void schema_addsQuestionsButKeepsLegacyContract() {
        JsonObject schema = new AskUserQuestionTool(new RecordingUi()).schema();
        assertEquals(1, schema.getAsJsonArray("required").size());
        assertEquals("question", schema.getAsJsonArray("required").get(0).getAsString());
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("questions"));
        JsonObject questions = props.getAsJsonObject("questions");
        assertEquals("array", questions.get("type").getAsString());
        JsonObject items = questions.getAsJsonObject("items");
        assertEquals("object", items.get("type").getAsString());
        assertTrue(items.getAsJsonObject("properties").has("question"));
        assertTrue(items.getAsJsonObject("properties").has("options"));
        assertEquals("question", items.getAsJsonArray("required").get(0).getAsString());
    }

    /** 逐题回答展开：数字带选项含义，自由文本保持原文；显示层回用户原文 */
    @Test
    public void batchAnswer_expandsPerQuestionAndKeepsRawForDisplay() {
        JsonObject args = batch(q("如何排查？", false, "查日志", "查任务", "两者都做"),
                q("用哪个方案？", false, "方案甲", "方案乙"));
        String raw = "Q1: 3\nQ2: 方案乙";
        JsonObject result = JsonParser.parseString(answer(args, raw)).getAsJsonObject();
        assertEquals(raw, result.get("answer").getAsString());
        JsonArray entries = result.getAsJsonArray("batchAnswers");
        assertEquals(2, entries.size());
        JsonObject e1 = entries.get(0).getAsJsonObject();
        assertEquals(1, e1.get("index").getAsInt());
        assertEquals("如何排查？", e1.get("question").getAsString());
        assertEquals("两者都做", e1.getAsJsonArray("selectedOptions").get(0)
                .getAsJsonObject().get("label").getAsString());
        JsonObject e2 = entries.get(1).getAsJsonObject();
        assertEquals(2, e2.get("index").getAsInt());
        assertEquals("方案乙", e2.get("answer").getAsString());
        assertFalse("自由文本不得被猜测成选择", e2.has("selectedOptions"));
        assertEquals(raw, AskUserQuestionTool.answerForDisplay(result.toString()));
    }

    /** 全角冒号；multiSelect 按子问各自生效；续行并入上一段（含续行即自由文本，原文保留） */
    @Test
    public void batchAnswer_fullWidthColonContinuationAndPerQuestionMultiSelect() {
        JsonObject args = batch(q("单选？", false, "甲", "乙"), q("多选？", true, "A", "B", "C"));
        String raw = "Q1：1\nQ2: 1,3";
        JsonObject result = JsonParser.parseString(answer(args, raw)).getAsJsonObject();
        JsonArray entries = result.getAsJsonArray("batchAnswers");
        assertEquals(1, entries.get(0).getAsJsonObject().getAsJsonArray("selectedOptions").size());
        assertEquals(2, entries.get(1).getAsJsonObject().getAsJsonArray("selectedOptions").size());
        // 续行并入 Q2 段：整段不再是纯编号 → 按自由文本原文保留，不猜测
        String withTail = "Q1: 1\nQ2: 1,3\n补充说明";
        JsonObject r3 = JsonParser.parseString(answer(args, withTail)).getAsJsonObject();
        JsonObject e2 = r3.getAsJsonArray("batchAnswers").get(1).getAsJsonObject();
        assertEquals("1,3\n补充说明", e2.get("answer").getAsString());
        assertFalse(e2.has("selectedOptions"));
        // 单选子问收到多编号：该段原文保留，不发明多选
        String single = "Q1: 1,2\nQ2: 2";
        JsonObject r2 = JsonParser.parseString(answer(args, single)).getAsJsonObject();
        JsonObject e1 = r2.getAsJsonArray("batchAnswers").get(0).getAsJsonObject();
        assertEquals("1,2", e1.get("answer").getAsString());
        assertFalse(e1.has("selectedOptions"));
    }

    /** 缺号/重号/越界/前置正文/空段：整段原文回传，不包装不猜测 */
    @Test
    public void incompleteOrAmbiguousBatchAnswer_remainsUntouched() {
        JsonObject args = batch(q("问1？", false, "甲", "乙"), q("问2？", false, "丙", "丁"));
        for (String raw : new String[]{
                "Q1: 1",                       // 缺 Q2
                "1\n2",                        // 无 Qn 头
                "Q1: 1\nQ1: 2",                // 重号
                "Q1: 1\nQ3: 2",                // 越界
                "好的\nQ1: 1\nQ2: 2",          // 首段前有正文
                "Q1: \nQ2: 2",                 // 空段
                "Q2: 2\nQ1: 1\nQ1: 1"}) {      // 重号（乱序后）
            assertEquals(raw, answer(args, raw));
        }
        // 乱序但齐全有效：允许（编号即归属）
        String out = answer(args, "Q2: 2\nQ1: 1");
        JsonObject r = JsonParser.parseString(out).getAsJsonObject();
        assertEquals(2, r.getAsJsonArray("batchAnswers").size());
        assertEquals("丁", r.getAsJsonArray("batchAnswers").get(1).getAsJsonObject()
                .getAsJsonArray("selectedOptions").get(0).getAsJsonObject().get("label").getAsString());
    }

    /** 真实挂起链路：占位报总数，回答包装 batchAnswers，UI 收到原文 */
    @Test
    public void execute_batchSuspendsAndReturnsStructuredAnswer() throws Exception {
        final JsonObject args = batch(q("如何排查？", false, "查日志", "两者都做"),
                q("用哪个方案？", false, "方案甲", "方案乙"));
        RecordingUi ui = new RecordingUi();
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        final CountDownLatch ready = new CountDownLatch(1);
        final ToolResult[] result = new ToolResult[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    result[0] = tool.execute(args);
                } catch (Exception e) {
                    result[0] = ToolResult.error("异常: " + e.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, ui.asksStarted.size());
        assertEquals("共2问：如何排查？", ui.asksStarted.get(0));
        String raw = "Q1: 2\nQ2: 方案乙";
        assertTrue(tool.complete(raw));
        t.join(5000);
        assertFalse(t.isAlive());
        assertNotNull(result[0]);
        assertTrue(result[0].ok);
        assertTrue(result[0].output.contains("batchAnswers"));
        assertTrue(result[0].output.contains("两者都做"));
        assertEquals(raw, ui.asksDone.get(0));
        assertEquals(raw, AskUserQuestionTool.answerForDisplay(result[0].output));
    }
}