package com.minion.core.llm;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RepetitionGuardTest {
    public static String loopText() {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<20;i++) b.append("我现在实际调用Bash工具校验补全result_1条数和顺序。破除冗长循环。direct执行。现在。good。\n");
        return b.toString();
    }
    private RepetitionGuard guard() { return new RepetitionGuard(new StreamHandler(){public void onFinish(String r,Usage u,List<ToolCall> t){}}); }
    @Test public void arbitraryChunksDetectScreenshotBeforeLengthLimit() {
        RepetitionGuard g=guard();String text=loopText();int received=0;
        for(int i=0;i<text.length()&&!g.shouldStopResponse();i+=7){g.onContent(text.substring(i,Math.min(text.length(),i+7)));received=i+7;}
        assertTrue(g.triggered());assertTrue(received<text.length());
    }
    @Test public void separateChannelsNeverCombine() {
        RepetitionGuard g=guard();String s=loopText().split("\n")[0]+"\n";
        for(int i=0;i<3;i++){g.onContent(s);g.onThinking(s);}assertFalse(g.triggered());
        for(int i=0;i<4;i++)g.onThinking(s);assertTrue(g.triggered());
    }
    @Test public void realToolDeltasDisableGuard() {RepetitionGuard g=guard();g.onToolCallDelta();g.onContent(loopText());assertFalse(g.triggered());}
    @Test public void reportsQuotesTablesAndCodeAreNotToolPromises() {
        for(String prefix:new String[]{"> ","| ","{"}) {RepetitionGuard g=guard();g.onContent(prefix+loopText().replace("\n","\n"+prefix));assertFalse(g.triggered());}
        RepetitionGuard g=guard();g.onContent("```text\n"+loopText()+"```\n");assertFalse(g.triggered());
        g=guard();for(int i=0;i<200;i++)g.onContent("已完成第"+i+"条记录，结果已保存，待人工复核。\n");assertFalse(g.triggered());
    }
    @Test public void shortPlansAreNotLoops() {RepetitionGuard g=guard();g.onContent("我现在调用Bash查看目录。\n");assertFalse(g.triggered());}
    @Test public void variantsAndMissingNewlinesAreDetected() {
        RepetitionGuard g=guard();
        for(int i=0;i<9;i++)g.onContent("好，我现在实际调用Bash工具校验补全result_1落地和条数。破除冗长循环。现在。"+(i%2==0?"direct。":"good。")+"\n");
        assertTrue(g.triggered());g=guard();g.onContent(loopText().replace("\n", " "));assertTrue(g.triggered());
    }
    @Test public void repeatedDocumentationIsNotAnExecutionPromise() {
        RepetitionGuard g=guard();for(int i=0;i<20;i++)g.onContent("使用Bash工具时需要提供合法的参数；调用工具后应检查返回值，执行流程参见文档。\n");assertFalse(g.triggered());
    }
    @Test public void terminationIsDistinctFromStop() {
        final String[] finish={null};RepetitionGuard g=new RepetitionGuard(new StreamHandler(){public void onFinish(String r,Usage u,List<ToolCall> t){finish[0]=r;}});
        g.onContent(loopText());g.onFinish("length",new Usage(),Collections.<ToolCall>emptyList());assertEquals(RepetitionGuard.FINISH,finish[0]);
    }
}
