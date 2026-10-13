import com.minion.gui.chat.*;
import com.minion.gui.session.*;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 无可见窗口校验真实分页、流式结束折叠和手动展开；不改系统剪贴板。 */
public final class VerifyV27Chat {
    public static void main(String[] args) throws Exception {
        new JFXPanel();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            try {
                StringBuilder source = new StringBuilder();
                for (int i=0;i<50000;i++) source.append((char)('a'+i%26));
                String text=source.toString();
                MessageTextPane pane=new MessageTextPane(text);
                StringBuilder actual=new StringBuilder(pane.contentArea().getText());
                Button next=button(pane,"next");
                while(!next.isDisable()) {next.fire();actual.append(pane.contentArea().getText());}
                check(text.equals(actual.toString()),"Paged text lost middle content");
                button(pane,"previous").fire();
                String selected=pane.contentArea().getText();
                pane.setText(text+"new delta",true);
                check(selected.equals(pane.contentArea().getText()),"Streaming interrupted history reading");
                button(pane,"last").fire();
                check(pane.contentArea().getText().endsWith("new delta"),"Last page lost fresh delta");
                check(pane.fullText().equals(text+"new delta"),"Full copy source not preserved");

                ChatView chat=new ChatView(new EventList(),null);
                Method event=ChatView.class.getDeclaredMethod("onEventFx",EventList.Ev.class);event.setAccessible(true);
                emit(event,chat,EventList.Kind.THINKING,"短思考");
                CollapsibleText think=(CollapsibleText)lastNode(chat);
                check(think.isExpanded(),"Thinking must initially stream expanded");
                emit(event,chat,EventList.Kind.RESPONSE_END,null);
                check(!think.isExpanded(),"Thinking-only finish did not collapse");
                think.setExpanded(true);
                emit(event,chat,EventList.Kind.CONTENT,"正文");
                check(think.isExpanded(),"Later content overrode manual expansion");
                emit(event,chat,EventList.Kind.RESPONSE_END,null);
                emit(event,chat,EventList.Kind.CONTENT,"下一次回复");
                check(((MessageTextPane)lastNode(chat)).fullText().equals("下一次回复"),"Responses merged across stop");
                emit(event,chat,EventList.Kind.RESPONSE_END,null);
                emit(event,chat,EventList.Kind.THINKING,text);
                CollapsibleText longThink=(CollapsibleText)lastNode(chat);
                emit(event,chat,EventList.Kind.CONTENT,"最终结果");
                check(!longThink.isExpanded(),"Long thinking did not auto-collapse");
                longThink.setExpanded(true);
                MessageTextPane thoughtPane=(MessageTextPane)longThink.getChildren().get(1);
                check(thoughtPane.fullText().equals(text),"Collapsed thinking lost full text");
                check(thoughtPane.contentArea().getText().length()<=12001,"Unbounded text sent to JavaFX");
                ChatView mixed=new ChatView(new EventList(),null);
                event.invoke(mixed,new EventList.Ev(EventList.Kind.CONTENT,"main-",null));
                event.invoke(mixed,new EventList.Ev(EventList.Kind.CONTENT,"a-",null,"agent-a"));
                event.invoke(mixed,new EventList.Ev(EventList.Kind.CONTENT,"b-",null,"agent-b"));
                event.invoke(mixed,new EventList.Ev(EventList.Kind.CONTENT,"tail",null,"agent-a"));
                event.invoke(mixed,new EventList.Ev(EventList.Kind.CONTENT,"tail",null));
                check(mixed.getChildren().size()==4,"Interleaved streams created duplicate segments");
                String[] expected={"main-tail","a-tail","b-"};
                for(int i=1;i<=3;i++)check(((MessageTextPane)((HBox)mixed.getChildren().get(i)).getChildren().get(1)).fullText().equals(expected[i-1]),"Agent buffers mixed");
                event.invoke(mixed,new EventList.Ev(EventList.Kind.THINKING,"child thought",null,"agent-a"));
                CollapsibleText childThink=(CollapsibleText)lastNode(mixed);
                event.invoke(mixed,new EventList.Ev(EventList.Kind.RESPONSE_END,null,null,"agent-a"));
                check(!childThink.isExpanded(),"Child thinking did not collapse");
                event.invoke(mixed,new EventList.Ev(EventList.Kind.SUB_AGENT_DONE,"done",null,"agent-a"));
                Field children=ChatView.class.getDeclaredField("childStreams");children.setAccessible(true);
                check(!((Map)children.get(mixed)).containsKey("agent-a"),"Completed child buffer not released");
                System.out.println("Verified full-text pagination, stream reading position, response boundaries, short/long thinking collapse and manual expansion");
            } catch(Throwable t) {error.set(t);} finally {done.countDown();}
        });
        if(!done.await(40,TimeUnit.SECONDS))throw new AssertionError("Chat verification timed out");
        Platform.exit();
        if(error.get()!=null){error.get().printStackTrace();System.exit(1);}
        System.exit(0);
    }
    private static Button button(MessageTextPane pane,String name)throws Exception {
        Field f=MessageTextPane.class.getDeclaredField(name);f.setAccessible(true);return (Button)f.get(pane);
    }
    private static Node lastNode(ChatView chat) {
        HBox row=(HBox)chat.getChildren().get(chat.getChildren().size()-1);
        return row.getChildren().get(1);
    }
    private static void emit(Method method,ChatView chat,EventList.Kind kind,String text)throws Exception {
        method.invoke(chat,new EventList.Ev(kind,text,null));
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
