package com.minion.gui;

import com.minion.gui.chat.BlockNodeFactory;
import com.minion.gui.chat.MarkdownRenderer;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Agent 生成文件的内置 Markdown 预览区，兼容 JavaFX 8 / Win7。 */
final class ArtifactPreviewPane extends VBox {
    private final Label title = new Label("任务报告");
    private final Label pathLabel = new Label();
    private final VBox body = new VBox(8);
    private Path current;

    ArtifactPreviewPane(Runnable closeAction) {
        setSpacing(8);
        setPadding(new Insets(10));
        setStyle("-fx-background-color:#121314;-fx-border-color:#34373d;-fx-border-width:0 0 0 1;");
        title.setStyle("-fx-text-fill:#f0f2f6;-fx-font-size:15px;-fx-font-weight:bold;");
        pathLabel.setStyle("-fx-text-fill:#8b949e;-fx-font-size:10px;");
        pathLabel.setWrapText(true);
        Button refresh = new Button("刷新");
        refresh.setOnAction(e -> reload());
        Button close = new Button("关闭");
        close.setOnAction(e -> closeAction.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, refresh, close);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background:#121314;-fx-background-color:#121314;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, pathLabel, scroll);
    }

    void open(String path) {
        current = path == null ? null : Paths.get(path).toAbsolutePath().normalize();
        reload();
    }

    private void reload() {
        body.getChildren().clear();
        pathLabel.setText(current == null ? "" : current.toString());
        try {
            if (current == null || !Files.isRegularFile(current)) throw new IllegalArgumentException("报告文件不存在");
            String markdown = new String(Files.readAllBytes(current), StandardCharsets.UTF_8);
            for (MarkdownRenderer.Block block : MarkdownRenderer.parse(markdown)) {
                body.getChildren().add(BlockNodeFactory.create(block));
            }
        } catch (Exception e) {
            Label error = new Label("无法打开报告：" + e.getMessage());
            error.setStyle("-fx-text-fill:#ff7b72;");
            error.setWrapText(true);
            body.getChildren().add(error);
        }
    }
}
