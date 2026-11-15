import com.minion.core.config.Config;
import com.minion.gui.dialog.SettingsDialog;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
/** 不打开窗口，不连接模型；真实JavaFX控件和配置落盘检查。 */
public class VerifyDocumentSettingsUi {
 public static void main(String[] args)throws Exception {
  Path dir=Files.createTempDirectory(Paths.get(args[0]),"document-settings-");
  new JFXPanel();CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
  Platform.runLater(()->{try {
   Config c=Config.load(dir);Class<?> type=Class.forName(SettingsDialog.class.getName()+"$BasicPane");
   Constructor<?> ctor=type.getDeclaredConstructor(Config.class,javafx.stage.Window.class);ctor.setAccessible(true);Object pane=ctor.newInstance(c,null);
   Field root=type.getDeclaredField("documentRoot");root.setAccessible(true);Node n=(Node)root.get(pane);
   new Scene((javafx.scene.Parent)n,750,540);n.applyCss();((javafx.scene.Parent)n).layout();
   for(String key:new String[]{"ocrPath","ocrLanguage","officePath"}) {Field f=type.getDeclaredField(key);f.setAccessible(true);((TextField)f.get(pane)).setText(key+"-test");}
   Method apply=type.getDeclaredMethod("apply");apply.setAccessible(true);apply.invoke(pane);
   Config reload=Config.load(dir);if(!"ocrPath-test".equals(reload.ocrPath())||!"ocrLanguage-test".equals(reload.ocrLanguage())||!"officePath-test".equals(reload.officePath()))throw new AssertionError("settings not persisted");
   System.out.println("Verified real document/OCR controls and settings persistence; no window opened.");
  }catch(Throwable t){failure.set(t);}finally{done.countDown();}});
  if(!done.await(30,TimeUnit.SECONDS))throw new AssertionError("UI timeout");
  if(failure.get()!=null){failure.get().printStackTrace();System.exit(1);}Platform.exit();System.exit(0);
 }
}
