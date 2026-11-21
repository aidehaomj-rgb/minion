package com.minion.gui.dialog;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * v2.4 §9.3 Rewind 选择器（双击 Esc 入口）：列出可回退的用户消息（eventId+预览），
 * 按 both/conversation/code/summarize 恢复；恢复前自动建「rewind 前」恢复点
 * （RewindService 语义），冲突不静默覆盖（结果文本回显）。FX 薄壳：数据与执行
 * 全部走 SessionManager.rewind*（唯一真源），本类只收集选择。
 */
public final class RewindSelector {

    private static final String[] MODES = {"both", "conversation", "code", "summarize", "nevermind"};

    private RewindSelector() { }

    public static void show(Stage owner, SessionManager manager, SessionHandle handle) {
        if (handle == null || handle.deleted) return;
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("回退选择器 — " + (handle.title == null ? handle.id : handle.title));

        TextArea list = new TextArea(manager.rewindListFor(handle.session));
        list.setEditable(false);
        list.setWrapText(true);
        list.setPrefRowCount(10);

        TextField eventId = new TextField();
        eventId.setPromptText("eventId（列表第一列）");
        final ComboBox<String> mode = new ComboBox<String>(
                javafx.collections.FXCollections.observableArrayList(MODES));
        mode.getSelectionModel().select(0);
        Label result = new Label();
        result.setWrapText(true);

        Button execute = new Button("回退");
        execute.getStyleClass().add("btn-approve");
        execute.setDefaultButton(true);
        execute.setOnAction(e -> {
            String id = eventId.getText().trim();
            if (id.isEmpty()) { result.setText("先填 eventId"); return; }
            result.setText(manager.rewindExecuteFor(handle.session, id, mode.getValue()));
        });
        Button close = new Button("关闭");
        close.getStyleClass().add("btn-ghost");
        close.setOnAction(e -> stage.close());
        HBox row = new HBox(8, new Label("模式"), mode, eventId, execute, close);
        row.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label("任何恢复前自动创建「rewind 前」恢复点；外部修改冲突将逐项列出，不会静默覆盖。");
        note.setWrapText(true);

        VBox root = new VBox(10, note, list, row, result);
        root.setPadding(new Insets(12));
        VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
        Theme.apply(root, Theme.activeId());
        Scene scene = new Scene(root, 560, 420);
        scene.getStylesheets().addAll(Theme.STYLESHEET, Theme.WORKBENCH);
        stage.setScene(scene);
        stage.show();
    }
}
