import com.minion.gui.SidebarSplit;
import com.minion.gui.WindowsTaskbar;
import com.minion.gui.icon.AppIcons;
import com.sun.jna.*;
import javafx.application.*;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.scene.*;
import javafx.scene.layout.*;
import javafx.scene.control.*;
import javafx.stage.*;

/** Owned QA window only; no taskbar clicks/pins or user configuration changes. */
public class SidebarTaskbarSmoke extends Application {
    static Throwable failure;
    public interface User extends WindowsTaskbar.User {
        Pointer SendMessageW(Pointer hwnd, int message, Pointer wparam, Pointer lparam);
        boolean IsIconic(Pointer hwnd);
        boolean ShowWindow(Pointer hwnd, int command);
    }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public void start(Stage stage) { try {
        stage.initStyle(StageStyle.UNDECORATED);
        Region sidebar = new Region(), content = new Region();
        SplitPane split = SidebarSplit.create(sidebar, content);
        Scene scene = new Scene(split, 1100, 650);
        scene.getStylesheets().add(getClass().getResource("/theme/theme.css").toExternalForm());
        stage.setScene(scene); stage.show();
        split.applyCss(); split.layout();
        double before = sidebar.getWidth();
        split.setDividerPositions(0.40); split.layout();
        check(sidebar.getWidth() > before + 80, "divider must change actual sidebar width");
        check(content.getWidth() >= 360, "chat minimum width");
        check(WindowsTaskbar.install(stage), "native main-window minimize style");
        User user = Native.load("user32", User.class);
        Pointer hwnd = new Pointer(AppIcons.nativeHandle(stage));
        check((user.GetWindowLongW(hwnd, -16) & 0xA0000) == 0xA0000, "Shell minimize and system-menu styles");
        // The system minimize command used by the Shell; not a simulated taskbar click.
        user.SendMessageW(hwnd, 0x112, new Pointer(0xF020), null);
        PauseTransition pause = new PauseTransition(Duration.millis(500));
        pause.setOnFinished(e -> { try {
            check(user.IsIconic(hwnd), "SC_MINIMIZE must minimize actual HWND");
            user.ShowWindow(hwnd, 9);
            check(!user.IsIconic(hwnd), "SW_RESTORE must restore actual HWND");
            System.out.println("PASS actual sidebar width change; undecorated HWND SC_MINIMIZE/restore; no taskbar layout changed");
        } catch(Throwable bad) { failure=bad; bad.printStackTrace(); }
        finally { stage.close(); javafx.application.Platform.exit(); }});
        pause.play();
    } catch(Throwable bad) { failure=bad; bad.printStackTrace(); stage.close(); javafx.application.Platform.exit(); }}
    public static void main(String[] args) { launch(args); if(failure!=null)System.exit(1); }
}

