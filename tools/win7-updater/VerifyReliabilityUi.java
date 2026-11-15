import com.minion.gui.TaskDashboard;
import com.minion.gui.session.*;
import com.minion.core.config.*;
import com.minion.core.tools.confirm.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.stage.Stage;
import javafx.scene.control.*;

/** Build and lay out the actual reliability UI without opening a user window. */
public final class VerifyReliabilityUi {
    public static void main(String[] args) throws Exception {
        new JFXPanel(); CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            SessionManager manager=null;
            try {
                Path jar=Files.createTempDirectory("minion-reliability-ui-");
                ConfirmUi confirm=request -> ConfirmUi.Decision.APPROVE;
                manager=new SessionManager(confirm,Config.load(jar),jar,WorkspaceManager.load(jar),ModelManager.load(jar),Collections.emptyList(),null,null);
                SessionHandle handle=manager.createSession("可靠执行控件验证");handle.session.taskState.begin("检查面板",false);
                TaskDashboard dashboard=new TaskDashboard(new Stage(),manager,handle);
                java.lang.reflect.Field field=TaskDashboard.class.getDeclaredField("stage");field.setAccessible(true);
                Stage panel=(Stage)field.get(dashboard);
                panel.getScene().getRoot().applyCss();panel.getScene().getRoot().layout();
                TabPane outer=(TabPane)panel.getScene().lookup(".tab-pane");
                if(outer==null||outer.getTabs().size()!=6)throw new AssertionError("Expected 6 dashboard tabs");
                boolean queue=false,reliability=false;
                for(Tab tab:outer.getTabs()){
                    outer.getSelectionModel().select(tab);panel.getScene().getRoot().applyCss();panel.getScene().getRoot().layout();
                    if("待执行队列".equals(tab.getText()))queue=true;
                    if("可靠执行工作台".equals(tab.getText())){
                        reliability=true;
                        TabPane inner=(TabPane)tab.getContent().lookup(".tab-pane");
                        if(inner==null||inner.getTabs().size()!=5)throw new AssertionError("Expected 5 reliability tabs");
                        for(Tab sub:inner.getTabs()){
                            inner.getSelectionModel().select(sub);panel.getScene().getRoot().applyCss();panel.getScene().getRoot().layout();
                        }
                    }
                }
                if(!queue||!reliability)throw new AssertionError("Queue or reliability tab missing");
                System.out.println("Verified JavaFX dashboard: queue editor and 5 reliability tabs");panel.close();
            }catch(Throwable t){error.set(t);}finally{if(manager!=null)manager.shutdown();done.countDown();}
        });
        if(!done.await(30,TimeUnit.SECONDS))throw new AssertionError("UI smoke timed out");
        if(error.get()!=null){error.get().printStackTrace();System.exit(1);}
        Platform.exit();System.exit(0);
    }
}
