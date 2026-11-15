package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.*;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class AgentLoopSteeringTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private interface Body {void run(int attempt,List<Message> messages,StreamHandler handler)throws LlmException;}
    private static class Model extends FakeLlmClient {
        int attempts;Body body;final List<List<Message>> wire=new ArrayList<List<Message>>();
        @Override public void streamChat(List<Message> messages,List<JsonObject> tools,StreamHandler handler)throws LlmException {
            wire.add(new ArrayList<Message>(messages));body.run(++attempts,messages,handler);
        }
    }
    private static class Ui extends RecordingUi {
        final List<SteeringReceipt> receipts=Collections.synchronizedList(new ArrayList<SteeringReceipt>());
        @Override public void onSteeringReceipt(SteeringReceipt r){receipts.add(r.copy());}
    }
    private class Fixture implements AutoCloseable {
        final Path root;final Session s;final Model model=new Model();final Ui ui=new Ui();
        final ToolRegistry tools=new ToolRegistry();final Workspace ws;final AgentLoop loop;final SessionStore store;
        Fixture()throws Exception {
            root=temp.newFolder().toPath();s=Session.create(root.toString(),"fake");s.taskState.begin("原任务",true);ws=new Workspace(root.toString());
            loop=new AgentLoop(model,tools,new SystemPromptBuilder(null),new ConfirmGate(Config.load(root),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),ui,null,ws,s);
            store=new SessionStore(root.resolve("sessions"));loop.setSessionStore(store);model.stopStatuses.add("completed");
        }
        SteeringReceipt receipt(String id){return s.steeringSnapshot().stream().filter(r->id.equals(r.id)).findFirst().get();}
        public void close(){loop.shutdown();}
    }
    private static void done(StreamHandler h){h.onContent("已按本轮要求回答。");h.onFinish("stop",new Usage(),Collections.<ToolCall>emptyList());}
    private static Message byId(List<Message> messages,String id){return messages.stream().filter(m->id.equals(m.steeringId)).findFirst().get();}
    private static ImagePart image(){ImagePart i=new ImagePart();i.name="图.png";i.mime="image/png";i.base64="iVBORw0KGgo=";return i;}
    private void request(Fixture f,List<Message> actual)throws Exception {
        Method method=AgentLoop.class.getDeclaredMethod("streamChat",List.class,StreamHandler.class,boolean.class,boolean.class);method.setAccessible(true);
        try {method.invoke(f.loop,actual,new StreamHandler(){public void onFinish(String r,Usage u,List<ToolCall> t){}},false,false);}
        catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    private Message inserted(Fixture f,String id)throws Exception {
        Message m=Message.realUser("要求");m.steeringId=id;m.supplement=true;m.taskId=f.s.taskState.id;m.taskRevision=f.s.taskState.revision();
        SteeringTracker.create(f.s,id,m.content,m.taskId,m.taskRevision);f.s.messages.add(m);SteeringTracker.inserted(f.s,m);return m;
    }

    @Test public void actualMainRequestBindsBeforeJournalAndPreservesFullTextImagesAndDurableRead()throws Exception {
        try(Fixture f=new Fixture()) {
            String full=String.join("",Collections.nCopies(7000,"原"));String id=f.loop.offerSteering(full,Collections.singletonList(image()));
            f.model.body=(n,m,h)->{Message steer=byId(m,id);assertEquals(full,steer.content);assertEquals(1,steer.images.size());
                String before=new Gson().toJson(steer);for(int i=0;i<20;i++)h.onThinking("真实思考");
                assertEquals(before,new Gson().toJson(steer));done(h);};
            f.loop.runUserTurn("继续");assertTrue(f.receipt(id).isRead());assertTrue(f.receipt(id).readAt>0);
            Message actual=byId(f.s.messages,id);assertEquals(actual.eventId,f.receipt(id).eventId);
            Session restored=new SessionStore(f.root.resolve("sessions")).load(f.s.id);
            assertTrue(restored.steeringSnapshot().get(0).isRead());assertEquals(full,byId(restored.messages,id).content);
            assertEquals(1,f.ui.receipts.stream().filter(r->id.equals(r.id)&&r.isRead()).count());
            assertTrue(f.receipt(id).text.contains("显示节选，原文保留"));
        }
    }
    @Test public void identicalMidstreamSteeringIsUnreadUntilNextActualRequest()throws Exception {
        try(Fixture f=new Fixture()) {
            String first=f.loop.offerSteering("同文",null);String[] second={null};
            f.model.body=(n,m,h)->{
                if(n==1){byId(m,first);second[0]=f.loop.offerSteering("同文",null);h.onContent("第一轮答复");
                    assertTrue(f.receipt(first).isRead());assertEquals("unread",f.receipt(second[0]).state);h.onFinish("stop",new Usage(),Collections.<ToolCall>emptyList());}
                else {byId(m,second[0]);done(h);}
            };
            f.loop.runUserTurn("继续");assertNotEquals(first,second[0]);assertEquals(2,f.model.attempts);
            assertTrue(f.receipt(first).isRead());assertTrue(f.receipt(second[0]).isRead());
            assertEquals(1,f.s.messages.stream().filter(m->second[0].equals(m.steeringId)).count());
        }
    }
    @Test public void failedAttemptUnconfirmedThenRealRetryConfirmsOnlyOnce()throws Exception {
        try(Fixture f=new Fixture()) {
            String id=f.loop.offerSteering("重试要求",null);f.loop.retryPolicy=new RetryPolicy(1,1,2,1000);
            f.model.body=(n,m,h)->{byId(m,id);if(n==1){h.onUsage(new Usage());throw new LlmException(LlmException.Type.TIMEOUT,"fixture",true);}
                assertTrue(f.ui.receipts.stream().anyMatch(r->"unconfirmed".equals(r.state)));done(h);};
            f.loop.runUserTurn("继续");assertEquals(2,f.model.attempts);assertTrue(f.receipt(id).isRead());
            assertEquals(1,f.ui.receipts.stream().filter(SteeringReceipt::isRead).count());
            assertEquals(2,f.ui.receipts.stream().filter(r->"sending".equals(r.state)).map(r->r.requestId).distinct().count());
        }
    }
    @Test public void authFailureWithoutResponseStaysUnconfirmed()throws Exception {
        try(Fixture f=new Fixture()) {
            String id=f.loop.offerSteering("要求",null);f.model.body=(n,m,h)->{throw new LlmException(LlmException.Type.AUTH,"fixture",false);};
            f.loop.runUserTurn("继续");assertEquals("unconfirmed",f.receipt(id).state);assertEquals(0,f.receipt(id).readAt);
        }
    }
    @Test public void cancelAndUnknownFinishNeverConfirmButRealFeedbackBeforeCancelStaysRead()throws Exception {
        for(String reason:new String[]{"cancelled","canceled","unknown","error","incomplete"})try(Fixture f=new Fixture()) {
            Message m=inserted(f,"id");f.model.body=(n,msg,h)->h.onFinish(reason,new Usage(),Collections.<ToolCall>emptyList());
            request(f,Collections.singletonList(m));assertEquals(reason,"unconfirmed",f.receipt("id").state);
        }
        try(Fixture f=new Fixture()) {
            Message m=inserted(f,"id");f.model.body=(n,msg,h)->{h.onContent("真实回应");f.loop.interrupt();h.onFinish("canceled",new Usage(),Collections.<ToolCall>emptyList());};
            request(f,Collections.singletonList(m));assertTrue(f.receipt("id").isRead());
        }
        try(Fixture f=new Fixture()) {
            Message m=inserted(f,"id");f.model.body=(n,msg,h)->{f.loop.interrupt();h.onFinish("stop",new Usage(),Collections.<ToolCall>emptyList());};
            request(f,Collections.singletonList(m));assertFalse(f.receipt("id").isRead());
        }
    }
    @Test public void newAndSameSessionRestoreFenceLateResponses()throws Exception {
        for(boolean restore:new boolean[]{false,true})try(Fixture f=new Fixture()) {
            Message m=inserted(f,"id");Session checkpoint=new Gson().fromJson(new Gson().toJson(f.s),Session.class);
            f.model.body=(n,msg,h)->{if(restore)f.loop.restoreSession(checkpoint);else f.loop.startNewSession();done(h);};
            request(f,Collections.singletonList(m));assertTrue(f.s.steeringSnapshot().stream().noneMatch(SteeringReceipt::isRead));
            assertTrue(f.ui.receipts.stream().noneMatch(SteeringReceipt::isRead));
        }
    }
    @Test public void imageOnlySteeringIsNotDroppedAndToolWaitDoesNotConfirmIt()throws Exception {
        try(Fixture f=new Fixture()) {
            CountDownLatch waiting=new CountDownLatch(1),release=new CountDownLatch(1);String[] id={null};
            f.tools.register(new Tool(){public String name(){return "WaitFixture";}public String description(){return "wait";}
                public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject a)throws Exception{waiting.countDown();release.await(5,TimeUnit.SECONDS);return ToolResult.success("实际工具返回");}});
            f.model.body=(n,m,h)->{if(n==1){ToolCall c=new ToolCall();c.id="wait";c.name="WaitFixture";c.arguments="{}";h.onFinish("tool_calls",new Usage(),Collections.singletonList(c));}
                else {Message steer=byId(m,id[0]);assertEquals(1,steer.images.size());assertTrue(steer.content==null||steer.content.isEmpty());done(h);}};
            Thread runner=new Thread(()->f.loop.runUserTurn("原任务"));runner.setDaemon(true);runner.start();
            try {assertTrue(waiting.await(5,TimeUnit.SECONDS));id[0]=f.loop.offerSteering(null,Collections.singletonList(image()));
                assertNotNull(id[0]);assertEquals("unread",f.receipt(id[0]).state);assertEquals(1,f.model.attempts);
            } finally {release.countDown();}
            runner.join(10000);assertFalse(runner.isAlive());assertTrue(f.receipt(id[0]).isRead());
        }
    }
    @Test public void journalTailReinsertionDoesNotDuplicateAndLegacyPendingTextsSurvive()throws Exception {
        try(Fixture f=new Fixture()) {
            String id=f.loop.offerSteering("保留原文",null);
            Message inserted=Message.realUser("保留原文");inserted.supplement=true;inserted.steeringId=id;
            inserted.taskId=f.s.taskState.id;inserted.taskRevision=f.s.taskState.revision();f.store.appendMessage(f.s,inserted);
            Session replayed=new SessionStore(f.root.resolve("sessions")).load(f.s.id);f.loop.restoreSession(replayed);
            f.model.body=(n,m,h)->{assertEquals(1,m.stream().filter(x->id.equals(x.steeringId)).count());done(h);};
            f.loop.runUserTurn("继续");assertTrue(f.receipt(id).isRead());
            assertEquals(1,new SessionStore(f.root.resolve("sessions")).load(f.s.id).messages.stream().filter(m->id.equals(m.steeringId)).count());
        }
        try(Fixture f=new Fixture()) {
            f.s.pendingSupplements.add("旧文本一");f.s.pendingSupplements.add("旧文本二");assertNull(f.s.pendingSteeringIds);
            f.model.body=(n,m,h)->{assertTrue(m.stream().anyMatch(x->"旧文本一".equals(x.content)&&x.steeringId!=null));
                assertTrue(m.stream().anyMatch(x->"旧文本二".equals(x.content)&&x.steeringId!=null));done(h);};
            f.loop.runUserTurn("继续");assertEquals(2,f.s.steeringSnapshot().size());assertTrue(f.s.steeringSnapshot().stream().allMatch(SteeringReceipt::isRead));
        }
    }
    @Test public void independentReviewCompressionAndProbeResponsesCannotConfirm()throws Exception {
        try(Fixture f=new Fixture()) {
            inserted(f,"id");f.model.completeChat(Collections.singletonList(Message.user("压缩")),"上下文压缩器");
            assertEquals("inserted",f.receipt("id").state);
            // The only confirmation hook is AgentLoop.streamChat, not any LlmClient general-purpose callback.
            f.model.body=(n,m,h)->done(h);f.model.streamChat(Collections.singletonList(Message.user("子agent")),Collections.<JsonObject>emptyList(),
                    new StreamHandler(){public void onFinish(String r,Usage u,List<ToolCall> t){}});
            assertEquals("inserted",f.receipt("id").state);
        }
    }
}
