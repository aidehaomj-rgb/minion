import com.minion.gui.dialog.SettingsDialog;
import com.minion.gui.session.SessionManager;
import com.minion.core.config.ModelManager;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 不打开窗口，用真实设置页控件验证默认选择；不连接模型。 */
public final class VerifyDefaultModelUi {
    public static void main(String[] args) throws Exception {
        final Path dir=Files.createTempDirectory(Paths.get(args[0]),"default-model-ui-");
        new JFXPanel();CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        Platform.runLater(()->{
            try {
                ModelManager models=ModelManager.load(dir);String original=models.currentName();
                java.lang.reflect.Method build=SettingsDialog.class.getDeclaredMethod("modelPane",ModelManager.class,SessionManager.class);build.setAccessible(true);
                VBox root=(VBox)build.invoke(null,models,null);new Scene(root);root.applyCss();root.layout();
                ComboBox<String> choice=(ComboBox<String>)root.lookup("#new-session-default-model");
                if(choice==null || choice.getItems().size()!=2 || !original.equals(choice.getValue()))throw new AssertionError("Default selector missing");
                choice.setValue("qwen3-max");
                if(!"qwen3-max".equals(ModelManager.load(dir).defaultName()) || !original.equals(models.currentName()))throw new AssertionError("Selection not independently persisted");
                ListView<String> list=null;Button set=null;
                for(javafx.scene.Node n:root.getChildren()) {
                    if(n instanceof ListView)list=(ListView<String>)n;
                    if(n instanceof javafx.scene.layout.HBox)for(javafx.scene.Node b:((javafx.scene.layout.HBox)n).getChildren())
                        if(b instanceof Button && "设为新会话默认".equals(((Button)b).getText()))set=(Button)b;
                }
                list.getSelectionModel().select(original);if(set.isDisabled())throw new AssertionError("Default button disabled incorrectly");set.fire();
                if(!original.equals(choice.getValue()) || !original.equals(ModelManager.load(dir).defaultName()))throw new AssertionError("Button/dropdown out of sync");
                System.out.println("Verified real default-model dropdown, persistence, button synchronization and no global runtime refresh");
            }catch(Throwable t){error.set(t);}finally{done.countDown();}
        });
        if(!done.await(25,TimeUnit.SECONDS))throw new AssertionError("UI timed out");
        if(error.get()!=null){error.get().printStackTrace();System.exit(1);}Platform.exit();System.exit(0);
    }
}
