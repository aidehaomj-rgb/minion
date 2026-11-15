package com.minion.core.tools;

import com.google.gson.*;
import com.minion.core.agent.RecordingUi;
import org.junit.Test;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** 数字选择必须带对应含义回传；保留原文，不猜测自由文本或越界编号。 */
public class AskUserAnswerV27Test {
    private JsonObject args(boolean multi) {
        JsonObject args=JsonParser.parseString("{\"question\":\"希望如何排查？\",\"options\":["
                +"{\"label\":\"查看事件日志\",\"description\":\"读取Windows系统日志\"},"
                +"{\"label\":\"检查计划任务\",\"description\":\"检查定时关机与电源\"},"
                +"{\"label\":\"两者都做\",\"description\":\"检查日志、计划任务和电源设置\"}]}" ).getAsJsonObject();
        args.addProperty("multiSelect",multi);return args;
    }
    private String answer(boolean multi,String raw) {
        return AskUserQuestionTool.answerForModel(AskUserQuestionTool.normalize(args(multi)),raw);
    }
    @Test public void threeMeansThirdOptionWithDescriptionNotMissing() {
        JsonObject result=JsonParser.parseString(answer(false,"3")).getAsJsonObject();
        assertEquals("3",result.get("answer").getAsString());
        assertEquals("selected",result.get("selectionStatus").getAsString());
        assertEquals("希望如何排查？",result.get("question").getAsString());
        JsonObject chosen=result.getAsJsonArray("selectedOptions").get(0).getAsJsonObject();
        assertEquals(3,chosen.get("index").getAsInt());
        assertEquals("两者都做",chosen.get("label").getAsString());
        assertEquals("检查日志、计划任务和电源设置",chosen.get("description").getAsString());
    }
    @Test public void unambiguousNumericFormsKeepExactOriginal() {
        for(String raw:new String[]{" 3 ","[3]","３","第3项","选择3"}) {
            JsonObject result=JsonParser.parseString(answer(false,raw)).getAsJsonObject();
            assertEquals(raw,result.get("answer").getAsString());
            assertEquals(3,result.getAsJsonArray("selectedOptions").get(0).getAsJsonObject().get("index").getAsInt());
            assertEquals(raw,AskUserQuestionTool.answerForDisplay(result.toString()));
        }
    }
    @Test public void multiSelectRequiresAllValidAndRemovesDuplicates() {
        JsonObject result=JsonParser.parseString(answer(true,"1、3,1")).getAsJsonObject();
        assertEquals(2,result.getAsJsonArray("selectedOptions").size());
        assertEquals("1、3,1",result.get("answer").getAsString());
        assertEquals("1,9",answer(true,"1,9"));
    }
    @Test public void singleChoiceDoesNotInventMultipleSelections() {
        assertEquals("1,3",answer(false,"1,3"));
    }
    @Test public void invalidOrAmbiguousAnswersRemainUntouched() {
        for(String raw:new String[]{"0","4","999999999999999999999","1,","可能3吧","每天3次","两者都做","3，但别改电源设置"})
            assertEquals(raw,answer(true,raw));
    }
    @Test public void noOptionsAndLegacyHistoryRemainUntouched() {
        assertEquals("3",AskUserQuestionTool.answerForModel(AskUserQuestionTool.normalize("{\"question\":\"几次？\"}"),"3"));
        assertEquals("旧回答",AskUserQuestionTool.answerForDisplay("旧回答"));
        assertEquals("{\"answer\":\"3\"}",AskUserQuestionTool.answerForDisplay("{\"answer\":\"3\"}"));
    }
    @Test public void nestedQuestionKeepsMultiSelectSetting() {
        JsonObject nested=new JsonObject();JsonArray questions=new JsonArray();questions.add(args(true));nested.add("questions",questions);
        AskUserQuestionTool.Ask ask=AskUserQuestionTool.normalize(nested);
        assertTrue(ask.multiSelect);
        assertEquals(2,JsonParser.parseString(AskUserQuestionTool.answerForModel(ask,"1,3")).getAsJsonObject().getAsJsonArray("selectedOptions").size());
    }
    @Test public void realWaitingToolReturnsMeaningButUiGetsRawAnswer() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        RecordingUi ui=new RecordingUi(){@Override public void onAskUserStart(String q){super.onAskUserStart(q);ready.countDown();}};
        AskUserQuestionTool tool=new AskUserQuestionTool(ui);
        CompletableFuture<ToolResult> result=new CompletableFuture<ToolResult>();
        Thread worker=new Thread(()->{try{result.complete(tool.execute(args(false)));}catch(Exception e){result.completeExceptionally(e);}});
        worker.setDaemon(true);worker.start();
        try {
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            assertFalse(tool.complete(" ")); // 不把空输入当成选择。
            assertTrue(tool.complete("3"));
            assertFalse(tool.complete("2"));
            ToolResult output=result.get(5,TimeUnit.SECONDS);
            assertTrue(output.ok);
            assertTrue(output.output.contains("两者都做"));
            assertEquals("3",ui.asksDone.get(0));
            assertEquals("3",AskUserQuestionTool.answerForDisplay(output.output));
        } finally {worker.interrupt();worker.join(5000);}
    }
}
