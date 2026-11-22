package com.minion.gui.artifact;

import javafx.collections.FXCollections;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 离线 PPTX 幻灯片预览（无 LibreOffice 时的 16:9 幻灯片卡片）。
 * 每张幻灯片渲染为 16:9 比例卡片：标题+内容+可选缩略图，
 * 深色画布居中展示（视觉与 PDF 预览一致）。
 */
public final class PptxCardViewer extends BorderPane {

    public PptxCardViewer(List<DocumentPreviewLoader.SlidePreview> slides) {
        getStyleClass().add("paged-slide-viewer");   // 复用 PDF 预览的深色画布样式

        ListView<DocumentPreviewLoader.SlidePreview> list =
                new ListView<DocumentPreviewLoader.SlidePreview>(
                        FXCollections.observableArrayList(slides));
        list.getStyleClass().add("pptx-card-list");
        list.setCellFactory(view -> new ListCell<DocumentPreviewLoader.SlidePreview>() {
            @Override protected void updateItem(DocumentPreviewLoader.SlidePreview slide,
                                                boolean empty) {
                super.updateItem(slide, empty);
                if (empty || slide == null) {
                    setGraphic(null);
                    return;
                }
                setGraphic(card(slide));
            }
        });

        // 顶部：幻灯片标题栏
        Label header = new Label("共 " + slides.size() + " 张幻灯片");
        header.getStyleClass().add("artifact-meta");
        header.setPadding(new Insets(6, 14, 6, 14));
        header.setStyle("-fx-text-fill: #9da3ae; -fx-font-size: 12px; -fx-background-color: #23262d;");
        BorderPane.setAlignment(header, Pos.CENTER_LEFT);

        setTop(header);
        setCenter(list);
    }

    private static VBox card(DocumentPreviewLoader.SlidePreview slide) {
        VBox card = new VBox(10);
        card.getStyleClass().add("pptx-card");
        card.setPadding(new Insets(24, 32, 20, 32));

        // 顶部行：幻灯片编号
        Label number = new Label(String.format("%02d", slide.number));
        number.getStyleClass().add("pptx-card-number");

        // 标题
        Label title = new Label(slide.title == null || slide.title.trim().isEmpty()
                ? "（无标题）" : slide.title);
        title.getStyleClass().add("pptx-card-title");
        title.setWrapText(true);
        title.setStyle("-fx-text-fill: #e8eaee; -fx-font-size: 20px; -fx-font-weight: bold;");

        card.getChildren().addAll(number, title);

        // 内容行：文本 + 图片
        HBox content = new HBox(16);
        content.setAlignment(Pos.TOP_LEFT);

        VBox textBox = new VBox(5);
        VBox.setVgrow(textBox, Priority.ALWAYS);
        for (String line : slide.lines) {
            if (line == null || line.trim().isEmpty()) continue;
            Label body = new Label("• " + line.trim());
            body.setWrapText(true);
            body.getStyleClass().add("pptx-card-body");
            body.setStyle("-fx-text-fill: #9da3ae; -fx-font-size: 13px;");
            textBox.getChildren().add(body);
            if (textBox.getChildren().size() >= 8) {
                Label more = new Label("…");
                more.setStyle("-fx-text-fill: #6b7078; -fx-font-size: 13px;");
                textBox.getChildren().add(more);
                break;
            }
        }
        if (!textBox.getChildren().isEmpty()) {
            HBox.setHgrow(textBox, Priority.ALWAYS);
            content.getChildren().add(textBox);
        }

        // 缩略图（如有）
        if (!slide.images.isEmpty()) {
            try {
                Image image = new Image(new ByteArrayInputStream(slide.images.get(0).bytes),
                        400, 250, true, true);
                if (!image.isError()) {
                    ImageView view = new ImageView(image);
                    view.setPreserveRatio(true);
                    view.setFitWidth(320);
                    view.getStyleClass().add("pptx-card-image");
                    content.getChildren().add(view);
                }
            } catch (Exception ignored) { }
        }

        if (!content.getChildren().isEmpty()) {
            card.getChildren().add(content);
        }

        // 16:9 比例（与主流幻灯片一致）
        card.setMinHeight(280);
        card.prefHeightProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(280, Math.min(680, card.getWidth() * 9.0 / 16.0)),
                card.widthProperty()));
        card.setMaxWidth(Double.MAX_VALUE);

        return card;
    }
}
