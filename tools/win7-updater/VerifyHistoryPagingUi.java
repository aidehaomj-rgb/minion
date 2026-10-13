import com.minion.gui.chat.ChatView;
import com.minion.gui.session.EventList;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 真实控件验证，不连接模型、不修改会话文件。 */
public final class VerifyHistoryPagingUi {
    static Object field(Object o, String name) throws Exception {
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    static Button button(ChatView view, String text) {
        for(Node n:((HBox)view.getChildren().get(0)).getChildren())
            if(n instanceof Button && text.equals(((Button)n).getText()))return (Button)n;
        throw new AssertionError("按钮不存在: "+text);
    }
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        new JFXPanel();CountDownLatch done=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        Platform.runLater(()->{
            try {
                EventList events=new EventList();
                events.add(new EventList.Ev(EventList.Kind.USER_MESSAGE,"旧消息",null));
                for(int i=0;i<1000;i++)events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_DELTA,"子任务增量",null));
                for(int i=0;i<160;i++)events.add(new EventList.Ev(EventList.Kind.SYSTEM,"最新消息"+i,null));
                ChatView view=new ChatView(events,null);view.bind(true);
                check(view.getChildren().size()==151,"最新页应有150条内容");
                button(view,"加载更早历史").fire();
                check(view.getChildren().size()>2,"历史页不能只剩导航栏");
                int historyEnd=(Integer)field(view,"pageEnd");int nodes=view.getChildren().size();
                events.add(new EventList.Ev(EventList.Kind.CONTENT,"后台新消息",null));
                Platform.runLater(()->{
                    try {
                        check(historyEnd==(Integer)field(view,"pageEnd"),"新消息不得改历史游标");
                        check(nodes==view.getChildren().size(),"新消息不得覆盖历史页");
                        button(view,"较新一页").fire();
                        check(view.getChildren().size()==151,"较新一页恢复失败");
                        button(view,"返回最新消息").fire();
                        check((Integer)field(view,"pageEnd")==events.size(),"返回最新丢失后台消息");
                        check(!(Boolean)field(view,"browsingHistory"),"未退出历史浏览");
                        view.bind(false);
                        EventList onlyChild=new EventList();
                        for(int i=0;i<1000;i++)onlyChild.add(new EventList.Ev(EventList.Kind.SUB_AGENT_DELTA,"x",null));
                        ChatView child=new ChatView(onlyChild,null);child.bind(true);
                        check(child.getChildren().size()==2,"纯子任务输出应有可见折叠行");child.bind(false);
                        System.out.println("通过：150条分页、子任务历史显示、执行中历史不被覆盖、较新页和返回最新接续");
                    }catch(Throwable t){error.set(t);}finally{done.countDown();}
                });
            }catch(Throwable t){error.set(t);done.countDown();}
        });
        if(!done.await(40,TimeUnit.SECONDS))throw new AssertionError("UI验证超时");
        if(error.get()!=null){error.get().printStackTrace();System.exit(1);}
        Platform.exit();System.exit(0);
    }
}
