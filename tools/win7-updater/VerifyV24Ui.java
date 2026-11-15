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

/** Layout the real JavaFX controls without showing or operating a user's window. */
public final class VerifyV24Ui {
    public static void main(String[] args) throws Exception {
        new JFXPanel(); CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            SessionManager manager=null;
            try {
                Path jar=Files.createTempDirectory("minion-v24-ui-");
                ConfirmUi confirm=request -> ConfirmUi.Decision.APPROVE;
                manager=new SessionManager(confirm,Config.load(jar),jar,WorkspaceManager.load(jar),ModelManager.load(jar),Collections.emptyList(),null,null);
                SessionHandle handle=manager.createSession("V24 控件验证"); handle.session.taskState.begin("检查面板",false);
                TaskDashboard dashboard=new TaskDashboard(new Stage(),manager,handle);
                java.lang.reflect.Field field=TaskDashboard.class.getDeclaredField("stage"); field.setAccessible(true);
                Stage panel=(Stage)field.get(dashboard);
                panel.getScene().getRoot().applyCss(); panel.getScene().getRoot().layout();
                TabPane tabs=(TabPane)panel.getScene().lookup(".tab-pane");
                if(tabs==null||tabs.getTabs().size()!=5) throw new AssertionError("Expected 5 task dashboard tabs");
                for(Tab tab:tabs.getTabs()) { tabs.getSelectionModel().select(tab); panel.getScene().getRoot().applyCss(); panel.getScene().getRoot().layout(); }
                System.out.println("Verified JavaFX V24 dashboard construction/layout: 5 tabs"); panel.close();
            } catch(Throwable t) { error.set(t); }
            finally { if(manager!=null)manager.shutdown(); done.countDown(); }
        });
        if(!done.await(25,TimeUnit.SECONDS)) throw new AssertionError("UI smoke timed out");
        if(error.get()!=null) { error.get().printStackTrace(); System.exit(1); }
        Platform.exit(); System.exit(0);
    }
}
