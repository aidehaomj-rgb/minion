package com.minion.gui.artifact;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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
 * 深色画布 + 每张幻灯片一张居中 16:9 卡片（标题+内容+可选缩略图）。
 * 颜色全部内联（不依赖 CSS 类继承——ListView/ListCell 不透传父级样式）。
 */
public final class PptxCardViewer extends BorderPane {

    public PptxCardViewer(List<DocumentPreviewLoader.SlidePreview> slides) {
        // 深色画布
        setStyle("-fx-background-color: #1a1d23;");

        // 顶部信息栏
        Label header = new Label("  PPTX · 共 " + slides.size() + " 张幻灯片（离线预览，非渲染排版）");
        header.setStyle("-fx-text-fill: #9da3ae; -fx-font-size: 11px; -fx-padding: 6 14 6 14;"
                + "-fx-background-color: #23262d;");
        setTop(header);

        // 幻灯片列表（VBox in ScrollPane——不用 ListView，避免样式不继承）
        VBox cards = new VBox(16);
        cards.setPadding(new Insets(16, 24, 24, 24));
        cards.setAlignment(Pos.TOP_CENTER);
        for (DocumentPreviewLoader.SlidePreview slide : slides) {
            cards.getChildren().add(card(slide));
        }

        ScrollPane scroll = new ScrollPane(cards);
        scroll.setStyle("-fx-background: #1a1d23; -fx-control-inner-background: #1a1d23;");
        scroll.setFitToWidth(true);
        scroll.setPannable(true);
        setCenter(scroll);
    }

    private static VBox card(DocumentPreviewLoader.SlidePreview slide) {
        VBox card = new VBox(10);
        card.setStyle(
                "-fx-background-color: #262932; -fx-background-radius: 8;"
                + "-fx-border-color: #33363e; -fx-border-radius: 8; -fx-border-width: 1;"
                + "-fx-padding: 24 32 20 32;");
        card.setMaxWidth(760);

        // 编号行
        Label number = new Label(String.format("%02d", slide.number));
        number.setStyle("-fx-text-fill: #4a6fa5; -fx-font-size: 11px; -fx-font-weight: bold;");

        // 标题
        Label title = new Label(slide.title == null || slide.title.trim().isEmpty()
                ? "（无标题）" : slide.title);
        title.setWrapText(true);
        title.setStyle("-fx-text-fill: #e8eaee; -fx-font-size: 18px; -fx-font-weight: bold;");
        card.getChildren().addAll(number, title);

        // 内容行：文本 + 缩略图
        HBox content = new HBox(16);
        content.setAlignment(Pos.TOP_LEFT);

        VBox textBox = new VBox(4);
        boolean hasText = false;
        for (String line : slide.lines) {
            if (line == null || line.trim().isEmpty()) continue;
            Label body = new Label("• " + line.trim());
            body.setWrapText(true);
            body.setStyle("-fx-text-fill: #9da3ae; -fx-font-size: 12.5px;");
            textBox.getChildren().add(body);
            hasText = true;
            if (textBox.getChildren().size() >= 8) break;
        }
        if (hasText) {
            HBox.setHgrow(textBox, Priority.ALWAYS);
            content.getChildren().add(textBox);
        }

        // 缩略图（如有）
        if (!slide.images.isEmpty()) {
            try {
                Image image = new Image(new ByteArrayInputStream(slide.images.get(0).bytes),
                        360, 220, true, true);
                if (!image.isError()) {
                    ImageView view = new ImageView(image);
                    view.setPreserveRatio(true);
                    view.setFitWidth(300);
                    content.getChildren().add(view);
                }
            } catch (Exception ignored) { }
        }

        if (!content.getChildren().isEmpty()) {
            card.getChildren().add(content);
        }

        return card;
    }
}
