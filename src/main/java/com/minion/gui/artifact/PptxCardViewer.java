package com.minion.gui.artifact;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 离线 PPTX 幻灯片卡片阅读器（无 Office/LibreOffice 转换器时的响应式降级视图）。
 * <ul>
 *   <li>支持单页聚焦翻页（←/→、上一页/下一页）与连续滚动双模式切换，对标主流 Agent 演示视图；</li>
 *   <li>采用纵向响应式卡片排版，窄分栏下正文永不被图片挤压成竖列；</li>
 *   <li>全面走 workbench.css 主题变量（.pptx-card-*），兼容全部 7 套明暗主题。</li>
 * </ul>
 */
public final class PptxCardViewer extends BorderPane {

    private final List<DocumentPreviewLoader.SlidePreview> slides;
    private final Label pageLabel = new Label();
    private final ToggleButton continuousToggle = new ToggleButton("连续");
    private final ScrollPane scroll = new ScrollPane();
    private final VBox stage = new VBox(16);
    private final DoubleBinding cardWidth;
    private int currentIndex = 0;

    public PptxCardViewer(List<DocumentPreviewLoader.SlidePreview> slides) {
        this.slides = slides;
        getStyleClass().add("pptx-card-viewer");

        Button prev = navButton("‹", "上一张 (←)");
        Button next = navButton("›", "下一张 (→)");
        prev.setOnAction(e -> showSlide(currentIndex - 1));
        next.setOnAction(e -> showSlide(currentIndex + 1));
        pageLabel.getStyleClass().add("artifact-meta");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label modeBadge = new Label("离线卡片");
        modeBadge.getStyleClass().add("pptx-mode-badge");
        continuousToggle.setOnAction(e -> render());

        HBox toolbar = new HBox(6, prev, pageLabel, next, spacer, modeBadge, continuousToggle);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("paged-slide-toolbar");
        setTop(toolbar);

        stage.setPadding(new Insets(16, 16, 24, 16));
        stage.setAlignment(Pos.TOP_CENTER);
        stage.setFillWidth(true);
        stage.getStyleClass().add("pptx-card-stage");

        scroll.setContent(stage);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPannable(true);
        scroll.getStyleClass().add("artifact-reader");
        setCenter(scroll);

        cardWidth = Bindings.createDoubleBinding(
                () -> Math.max(240, Math.min(760, scroll.getViewportBounds().getWidth() - 36)),
                scroll.viewportBoundsProperty());

        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (continuousToggle.isSelected()) return;
            if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.PAGE_UP) {
                showSlide(currentIndex - 1);
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.PAGE_DOWN) {
                showSlide(currentIndex + 1);
                event.consume();
            }
        });
        setFocusTraversable(true);
        render();
    }

    public void showSlide(int index) {
        if (slides == null || slides.isEmpty()) return;
        currentIndex = Math.max(0, Math.min(slides.size() - 1, index));
        render();
    }

    private void render() {
        stage.getChildren().clear();
        if (slides == null || slides.isEmpty()) {
            pageLabel.setText("0 / 0");
            return;
        }
        if (continuousToggle.isSelected()) {
            pageLabel.setText("共 " + slides.size() + " 页");
            for (DocumentPreviewLoader.SlidePreview slide : slides) {
                stage.getChildren().add(slideCard(slide));
            }
        } else {
            pageLabel.setText((currentIndex + 1) + " / " + slides.size());
            stage.getChildren().add(slideCard(slides.get(currentIndex)));
        }
        scroll.setVvalue(0);
    }

    private VBox slideCard(DocumentPreviewLoader.SlidePreview slide) {
        VBox card = new VBox(12);
        card.getStyleClass().add("pptx-slide-card");
        card.setMinWidth(0);
        card.prefWidthProperty().bind(cardWidth);
        card.maxWidthProperty().bind(cardWidth);

        // 页眉：页码微胶囊 + 幻灯片标题
        Label numBadge = new Label(String.format("SLIDE %02d", slide.number));
        numBadge.getStyleClass().add("pptx-slide-num");

        Label title = new Label(slide.title == null || slide.title.trim().isEmpty()
                ? "幻灯片 " + slide.number : slide.title.trim());
        title.setWrapText(true);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        title.getStyleClass().add("pptx-slide-title");

        card.getChildren().addAll(numBadge, title);

        // 正文要点（始终占满卡片宽度，不再被图片挤压）
        VBox bullets = new VBox(6);
        bullets.setMinWidth(0);
        bullets.setMaxWidth(Double.MAX_VALUE);
        for (String line : slide.lines) {
            if (line == null || line.trim().isEmpty()) continue;
            HBox row = new HBox(8);
            row.setAlignment(Pos.TOP_LEFT);
            row.setMinWidth(0);
            Label dot = new Label("•");
            dot.getStyleClass().add("pptx-slide-dot");
            dot.setMinWidth(Region.USE_PREF_SIZE);
            Label body = new Label(line.trim());
            body.setWrapText(true);
            body.setMinWidth(0);
            body.setMaxWidth(Double.MAX_VALUE);
            body.getStyleClass().add("pptx-slide-bullet");
            HBox.setHgrow(body, Priority.ALWAYS);
            row.getChildren().addAll(dot, body);
            bullets.getChildren().add(row);
            if (bullets.getChildren().size() >= 10) break;
        }
        if (!bullets.getChildren().isEmpty()) {
            card.getChildren().add(bullets);
        }

        // 内嵌附图：纵向排在要点下方，宽度响应式跟随卡片，绝不溢出或挤压文字
        if (!slide.images.isEmpty()) {
            try {
                Image image = new Image(new ByteArrayInputStream(slide.images.get(0).bytes),
                        960, 540, true, true);
                if (!image.isError()) {
                    ImageView view = new ImageView(image);
                    view.setPreserveRatio(true);
                    view.setSmooth(true);
                    view.fitWidthProperty().bind(Bindings.max(160, cardWidth.subtract(44)));
                    view.setFitHeight(220);
                    StackPane frame = new StackPane(view);
                    frame.getStyleClass().add("pptx-slide-image-frame");
                    frame.setAlignment(Pos.CENTER);
                    card.getChildren().add(frame);
                }
            } catch (Exception ignored) { }
        }

        return card;
    }

    private static Button navButton(String text, String tip) {
        Button button = new Button(text);
        button.getStyleClass().add("artifact-icon-button");
        button.setTooltip(new Tooltip(tip));
        return button;
    }
}
