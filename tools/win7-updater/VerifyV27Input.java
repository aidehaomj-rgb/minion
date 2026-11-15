import com.minion.core.config.*;
import com.minion.core.llm.*;
import com.minion.core.skills.Skill;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.input.InputView;
import com.minion.gui.session.*;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.event.Event;
import javafx.scene.control.*;
import javafx.scene.input.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 无可见窗口，直接触发真实输入框事件；在最终补丁JAR上执行。 */
public final class VerifyV27Input {
    private static void fx(Runnable work) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        Platform.runLater(()->{try{work.run();}catch(Throwable t){error.set(t);}finally{done.countDown();}});
        if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("FX timed out");
        if(error.get()!=null)throw new AssertionError(error.get());
    }
    private static void enter(TextArea input) {
        Event.fireEvent(input,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,false,false,false));
    }
    private static Object field(Object target,String name) {
        try{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
        catch(Exception e){throw new AssertionError(e);}
    }
    public static void main(String[] args) throws Exception {
        new JFXPanel();Path jar=Files.createTempDirectory("v27-input-");Config cfg=Config.load(jar);
        SlowClient client=new SlowClient();
        SessionManager manager=new SessionManager(message->ConfirmUi.Decision.APPROVE,cfg,jar,
                WorkspaceManager.load(jar),ModelManager.load(jar),new ArrayList<Skill>(),null,null) {
            @Override public LlmClient newLlm(ModelConfig mc){return client;}
        };
        try {
            SessionHandle h=manager.createSession("测试输入");manager.activateSession(h);
            AtomicReference<InputView> view=new AtomicReference<InputView>();
            fx(()->{view.set(new InputView(manager,cfg));view.get().bindSession(h);});
            fx(()->{});
            TextArea input=(TextArea)field(view.get(),"input");
            fx(()->{input.setText("补充第四批原文");enter(input);});
            if(!client.started.await(5,TimeUnit.SECONDS))throw new AssertionError("Task did not start");
            view.get().onRunningChanged(h,true);fx(()->{});
            fx(()->{enter(input);enter(input);});
            if(client.cancels.get()!=0)throw new AssertionError("Repeated Enter cancelled task");
            // 故意送入迟到的空闲回调，缓存必须仍反映真实运行态。
            view.get().onRunningChanged(h,false);fx(()->{});
            if(!Boolean.TRUE.equals(field(view.get(),"running")))throw new AssertionError("Stale callback overwrote live state");
            fx(()->{input.setText("下一项完整核验");enter(input);enter(input);});
            if(h.queuedSnapshot().size()!=1||client.cancels.get()!=0)throw new AssertionError("Guidance repeated or cancelled");
            // 保留用户真正点击停止的权限；取消后不自动重启，也不执行排队任务。
            fx(()->((Button)field(view.get(),"sendButton")).fire());
            if(client.cancels.get()!=1)throw new AssertionError("Stop button did not cancel");
            client.release.countDown();h.pool.submit(()->{}).get(5,TimeUnit.SECONDS);
            if(h.loop.lastTurnSucceeded()||h.queuedSnapshot().size()!=1)throw new AssertionError("Explicit stop resumed queue");
            System.out.println("Verified actual InputView: repeated Enter cannot stop; stale callback ignored; guidance queued once; explicit stop preserved");
        } finally {client.release.countDown();manager.shutdown();Platform.exit();}
        System.exit(0);
    }
    private static final class SlowClient implements LlmClient {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger cancels=new AtomicInteger();
        public void streamChat(List<Message> messages,List<JsonObject> tools,StreamHandler handler)throws LlmException {
            started.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            handler.onContent("已完成");handler.onFinish("stop",new Usage(),new ArrayList<ToolCall>());
        }
        public String completeChat(List<Message> messages,String system){return "摘要";}
        public void cancel(){cancels.incrementAndGet();}
    }
}
