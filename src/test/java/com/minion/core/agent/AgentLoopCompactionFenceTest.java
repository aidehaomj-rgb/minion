package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.Assert.*;

public class AgentLoopCompactionFenceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private AgentLoop loop;
    private Runnable during=()->{};
    @After public void close(){if(loop!=null)loop.shutdown();}
    private AgentLoop fixture()throws Exception {
        Path root=temp.getRoot().toPath();FakeLlmClient model=new FakeLlmClient();
        ContextManager context=new ContextManager(200000,.8,2,model,0){
            @Override public List<Message> compress(List<Message> messages,String state,String objective){
                during.run();Message m=Message.assistant("【最新用户要求】当前任务\n【已完成及证据】无\n【未完成】当前任务\n【关键决定及原因】无\n【证据位置】原文\n【失败尝试】无\n【下一步】当前任务\n【不确定事项】无");m.summary=true;return new ArrayList<Message>(Collections.singletonList(m));
            }
        };
        Session s=Session.create(root.toString(),"fake");s.taskState.begin("当前任务",true);s.messages.add(Message.realUser("当前任务"));
        loop=new AgentLoop(model,new ToolRegistry(),new SystemPromptBuilder(null),new ConfirmGate(Config.load(root),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new RecordingUi(),context,new Workspace(root.toString()),s);return loop;
    }
    @SuppressWarnings("unchecked") private List<Message> compress(List<Message> old)throws Exception {
        Method m=AgentLoop.class.getDeclaredMethod("compressSnapshot",List.class,boolean.class);m.setAccessible(true);return (List<Message>)m.invoke(loop,old,false);
    }
    private boolean adopt(List<Message> old,List<Message> next)throws Exception {
        Method m=AgentLoop.class.getDeclaredMethod("adoptCompaction",List.class,List.class);m.setAccessible(true);return (Boolean)m.invoke(loop,old,next);
    }
    @Test public void steeringArrivingDuringCompressionCannotBeOverwrittenOrMarkedRead()throws Exception {
        fixture();List<Message> original=loop.messages();during=()->loop.offerSteering("改为最新任务，不做旧目标",null);
        assertFalse(adopt(original,compress(original)));assertSame(original,loop.messages());
        assertEquals(1,loop.session().pendingSupplements.size());assertEquals("unread",loop.session().steeringSnapshot().get(0).state);
    }
    @Test public void modelSwitchDiscardsOldCompactorResult()throws Exception {
        fixture();List<Message> original=loop.messages();during=()->loop.setLlm(new FakeLlmClient());
        assertFalse(adopt(original,compress(original)));assertSame(original,loop.messages());
    }
    @Test public void contextReplacementDiscardsOldCompactorResult()throws Exception {
        fixture();List<Message> original=loop.messages();during=()->loop.setContextManager(new ContextManager(200000,.8,2,new FakeLlmClient(),0));
        assertFalse(adopt(original,compress(original)));assertSame(original,loop.messages());
    }
    @Test public void cancelledCompressionCannotReplaceHistory()throws Exception {
        fixture();List<Message> original=loop.messages();during=()->loop.interrupt();
        assertFalse(adopt(original,compress(original)));assertSame(original,loop.messages());
    }
    @Test public void changedHistorySizeRejectsEvenWithoutRevisionChange()throws Exception {
        fixture();List<Message> original=loop.messages();List<Message> result=compress(original);original.add(Message.assistant("晚到工具事实"));
        assertFalse(adopt(original,result));assertSame(original,loop.messages());
    }
    @Test public void stableCompressionCanCommit()throws Exception {
        fixture();List<Message> original=loop.messages();assertTrue(adopt(original,compress(original)));assertNotSame(original,loop.messages());
    }
}
