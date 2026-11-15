package com.minion.gui;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.Label;
import org.junit.Test;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RunningIndicatorExternalWaitTest {
    @Test public void externalWaitSurvivesCompressResetAndDoesNotLeakToNextTask() throws Exception {
        new JFXPanel();CompletableFuture<Void> done=new CompletableFuture<Void>();
        Platform.runLater(()->{
            RunningIndicator indicator=null;
            try {
                indicator=new RunningIndicator();indicator.setRunning(true);indicator.setExternalWait(true);
                Label label=(Label)indicator.getChildren().get(1);
                assertEquals(RunningIndicator.EXTERNAL_WAIT_TEXT,label.getText());
                indicator.setCompressing(false);assertEquals(RunningIndicator.EXTERNAL_WAIT_TEXT,label.getText());
                indicator.setRunning(false);assertFalse(indicator.isVisible());
                indicator.setRunning(true);assertNotEquals(RunningIndicator.EXTERNAL_WAIT_TEXT,label.getText());
                done.complete(null);
            }catch(Throwable bad){done.completeExceptionally(bad);}
            finally {if(indicator!=null)indicator.setRunning(false);}
        });
        done.get(10,TimeUnit.SECONDS);
    }
}
