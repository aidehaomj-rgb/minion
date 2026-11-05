package com.minion.core.context;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LocalMemoryIndexTest {
    @Test public void chineseSynonymsAndWidthAreNormalized() {
        LocalMemoryIndex<String> i=new LocalMemoryIndex<String>();i.put("a","提交前运行单元测试，模块ＡＢＣ","a");
        assertEquals("a",i.search("单测").get(0).value);assertEquals("a",i.search("unit test").get(0).value);
        assertEquals("a",i.search("模块abc").get(0).value);
    }
    @Test public void unchangedTextOnlyRefreshesPayloadAndUpdatesAreIncremental() {
        LocalMemoryIndex<String> i=new LocalMemoryIndex<String>();i.put("a","中文路径规则","old");long built=i.updateCount();
        i.put("a","中文路径规则","new");assertEquals(built,i.updateCount());assertEquals("new",i.search("中文路径").get(0).value);
        i.put("b","独立新增证据","b");assertEquals(built+1,i.updateCount());i.retain(Collections.singleton("b"));
        assertTrue(i.search("中文路径规则").isEmpty());assertEquals(1,i.size());
    }
    @Test public void numericUnitsDoNotConfuse38With3Or138() {
        LocalMemoryIndex<String> i=new LocalMemoryIndex<String>();
        for(int n:new int[]{3,8,38,138})i.put(""+n,"第"+n+"分块原始结果证据",""+n);
        assertEquals(1,i.search("第38分块原始结果").size());assertEquals("38",i.search("第38分块原始结果").get(0).value);
    }
    @Test public void exactTermAndBm25PreferConciseRelevantEvidence() {
        LocalMemoryIndex<String> i=new LocalMemoryIndex<String>();i.put("a","独有验收规则","a");
        StringBuilder longText=new StringBuilder("独有验收规则 ");for(int n=0;n<100;n++)longText.append("噪声说明资料 ");
        i.put("b",longText.toString(),"b");assertEquals("a",i.search("独有验收规则").get(0).value);
        assertTrue(i.search("完全无关的气象台风").isEmpty());assertTrue(i.search(" ").isEmpty());
    }
    @Test public void singleChineseCharacterCanRecallWithoutPromotingScatteredCharactersForLongQueries() {
        LocalMemoryIndex<String> i=new LocalMemoryIndex<String>();i.put("a","关税核验采用原始提单证据","a");
        assertEquals("a",i.search("税").get(0).value);
        assertTrue(i.search("凭空编造的税务分析方案").isEmpty());
    }
}
