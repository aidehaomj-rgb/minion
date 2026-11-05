package com.minion.core.context;

import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestTokenCalibrationTest {
    private ContextManager manager(){return new ContextManager(1000000,.9,12,new FakeLlmClient(),100);}
    @Test public void hugeSchemaIsAdditiveRatherThanAmplifyingEveryNewHistoryCharacter(){
        ContextManager cm=manager();List<Message> history=new ArrayList<Message>();history.add(Message.user("短问题"));
        List<Message> request=new ArrayList<Message>();request.add(Message.system("系统要求"));request.addAll(history);
        int schema=30000,local=TokenCounter.estimateMessages(request)+schema;
        cm.observeRequestTokens(request,history,schema,local);
        assertEquals(local,cm.estimate(history));
        history.add(Message.user("再补一条信息"));
        assertEquals(local+TokenCounter.estimateMessages(Collections.singletonList(history.get(1))),cm.estimate(history));
        assertEquals(150000,cm.autoCompressAtTokens());
    }
    @Test public void changingToolCatalogueDoesNotTrainTextScaleOrOverrideTaskAnchor(){
        ContextManager cm=manager();cm.setTaskAnchorTokens(40);
        List<Message> history=Collections.singletonList(Message.user("历史"));
        List<Message> request=Arrays.asList(Message.system("真实系统和任务"),history.get(0));
        int total=TokenCounter.estimateMessages(request)+4000;
        cm.observeRequestTokens(request,history,4000,total*2);
        assertEquals(total*2,cm.estimate(history));
        cm.requestOverhead(request,history,400);
        assertEquals((total-3600)*2,cm.estimate(history));
        cm.setTaskAnchorTokens(60);assertEquals((total-3580)*2,cm.estimate(history));
        cm.setLlm(new FakeLlmClient());assertEquals(160+TokenCounter.estimateMessages(history),cm.estimate(history));
    }
    @Test public void transientHandoffIsNotDuplicatedWithHistoryAndLowUsageIsConservative(){
        ContextManager cm=manager();List<Message> history=Collections.singletonList(Message.user("当前指令"));
        List<Message> wire=Arrays.asList(Message.system("系统"),history.get(0),Message.user("压缩交接，只做未完成部分"));
        int local=TokenCounter.estimateMessages(wire)+900;
        cm.observeRequestTokens(wire,history,900,local/2);assertEquals(local,cm.estimate(history));
        assertTrue(cm.calibrationStatus().contains("分项实际token=unknown"));
        assertEquals(150000,cm.autoCompressAtTokens());
    }
}
