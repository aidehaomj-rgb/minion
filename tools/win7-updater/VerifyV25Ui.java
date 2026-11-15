import com.minion.gui.dialog.SettingsDialog;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.*;

/** Exercise the real model settings form without displaying a native window. */
public final class VerifyV25Ui {
    public static void main(String[] args) throws Exception {
        new JFXPanel(); CountDownLatch done=new CountDownLatch(1); AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        Platform.runLater(() -> {
            try {
                ModelConfig input=ModelManager.createDeepseek(); input.maxContextTokens=1000000; input.compressThreshold=0.8;
                java.lang.reflect.Method method=SettingsDialog.class.getDeclaredMethod("modelForm",ModelConfig.class); method.setAccessible(true);
                Dialog<ModelConfig> dialog=(Dialog<ModelConfig>)method.invoke(null,input);
                DialogPane pane=dialog.getDialogPane(); pane.applyCss(); pane.resize(pane.prefWidth(-1),pane.prefHeight(-1)); pane.layout();
                TextField percent=(TextField)pane.lookup("#model-compression-percent");
                TextField window=(TextField)pane.lookup("#model-context-window");
                Label preview=(Label)pane.lookup("#model-compression-preview");
                if(!"80.0".equals(percent.getText())) throw new AssertionError("Legacy .8 must display 80 percent");
                percent.setText("90");
                if(!preview.getText().contains("900000")) throw new AssertionError(preview.getText());
                ModelConfig saved=dialog.getResultConverter().call(ButtonType.OK);
                if(saved.compressThreshold!=0.9 || saved.maxContextTokens!=1000000) throw new AssertionError("Wrong persisted ratio");
                percent.setText("NaN"); ((Button)pane.lookupButton(ButtonType.OK)).fire();
                if(dialog.getResult()!=null || !preview.getText().contains("阈值")) throw new AssertionError("Invalid value must not close/save");
                percent.setText("95"); window.setText("1048576");
                if(!preview.getText().contains("996147")) throw new AssertionError(preview.getText());
                System.out.println("Verified V25 model settings layout, legacy percent, live preview, save and invalid-input guard");
                dialog.close();
            } catch(Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        if(!done.await(25,TimeUnit.SECONDS)) throw new AssertionError("UI smoke timed out");
        if(error.get()!=null) {error.get().printStackTrace(); System.exit(1);}
        Platform.exit(); System.exit(0);
    }
}
