import com.minion.core.config.*;
import com.minion.core.llm.*;
import com.minion.core.skills.Skill;
import com.minion.core.tools.AskUserImageAnswerTest;
import com.minion.core.tools.confirm.*;
import com.minion.gui.input.InputChip;
import com.minion.gui.input.InputView;
import com.minion.gui.session.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** 显式运行的真实 JavaFX 8 输入路径检查；不要求日常单测具备图形桌面。 */
public class VerifyQuestionImageComposer extends Application {
    private static Throwable failure;
    private static final class CaptureManager extends SessionManager {
        boolean accept = true;
        int calls;
        String answer;
        List<ImagePart> images;
        CaptureManager(Path root) throws Exception {
            super(new FakeConfirmUi(ConfirmUi.Decision.APPROVE), Config.load(root), root,
                    WorkspaceManager.load(root), ModelManager.load(root), new ArrayList<Skill>(), null, null);
        }
        @Override public LlmClient newLlm(ModelConfig config) { return new FakeLlmClient(); }
        @Override public boolean sendAnswer(SessionHandle h, String text, List<ImagePart> attachments, String expectedQuestionId) {
            check("displayed-question".equals(expectedQuestionId), "未使用界面显示的提问身份");
            calls++; answer = text; images = attachments; return accept;
        }
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }

    @Override public void start(Stage stage) {
        CaptureManager manager = null;
        InputView view = null;
        try {
            Path root = Files.createTempDirectory("minion-question-image-fx-");
            manager = new CaptureManager(root);
            SessionHandle handle = manager.createSession(null); handle.running = true; handle.askPending = true;
            view = new InputView(manager, Config.load(root));
            stage.setScene(new Scene(view, 900, 450)); // 不弹出窗口；仍使用真正的 JavaFX 控件和发送事件。
            Field current = InputView.class.getDeclaredField("current"); current.setAccessible(true); current.set(view, handle);
            Field questionId = InputView.class.getDeclaredField("askQuestionId"); questionId.setAccessible(true); questionId.set(view, "displayed-question");
            TextArea input = (TextArea) field(view, "input");
            @SuppressWarnings("unchecked") List<InputChip> chips = (List<InputChip>) field(view, "chips");
            Method add = InputView.class.getDeclaredMethod("addChip", InputChip.class); add.setAccessible(true);
            Method action = InputView.class.getDeclaredMethod("onAction"); action.setAccessible(true);
            ImagePart image = AskUserImageAnswerTest.image("截图.png");

            input.setText("截图说明"); add.invoke(view, InputChip.imageChip(image.mime, image.base64, image.name)); action.invoke(view);
            check(manager.calls == 1 && "截图说明".equals(manager.answer) && manager.images.size() == 1, "图文回答未走带图接口");
            check(chips.isEmpty() && input.getText().isEmpty(), "成功后未清理草稿");

            add.invoke(view, InputChip.imageChip(image.mime, image.base64, image.name)); action.invoke(view);
            check(manager.calls == 2 && manager.answer.trim().isEmpty() && manager.images.size() == 1, "仅图片回答被空文本拦截");
            check(chips.isEmpty(), "仅图成功后附件未清理");

            manager.accept = false; input.setText("保留此回答");
            add.invoke(view, InputChip.imageChip(image.mime, image.base64, image.name)); action.invoke(view);
            check(manager.calls == 3 && "保留此回答".equals(input.getText()) && chips.size() == 1, "接收失败丢失草稿/附件");
            check(handle.pendingGuide() == null, "回答被误放入新任务队列");
            handle.askPending = false; handle.running = false; manager.accept = true;
            action.invoke(view);
            check(manager.calls == 3 && "保留此回答".equals(input.getText()) && chips.size() == 1,
                    "已结束提问的回答被误发为新任务");
            System.out.println("PASS: JavaFX text+image, image-only, rejected-answer draft retention, stale-question state guard, no new queued task");
        } catch (Throwable error) { failure = error; error.printStackTrace(); }
        finally {
            if (view != null) try { ((ExecutorService) field(view, "filePool")).shutdownNow(); } catch (Exception ignored) { }
            if (manager != null) manager.shutdown();
            stage.close(); Platform.exit();
        }
    }
    public static void main(String[] args) {
        launch(args);
        if (failure != null) System.exit(1);
    }
}
