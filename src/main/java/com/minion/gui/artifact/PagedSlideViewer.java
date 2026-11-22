package com.minion.gui.artifact;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Lazy single-page/continuous PDF reader shared by PDF, PPT and DOCX layouts. */
public final class PagedSlideViewer extends BorderPane implements AutoCloseable {
    private static final int FX_CACHE = 8;
    private final PagedPdfPreview pdf;
    private final int pages;
    private final ThreadPoolExecutor worker;
    private final LinkedHashMap<Integer, Image> imageCache =
            new LinkedHashMap<Integer, Image>(12, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, Image> eldest) {
                    return size() > FX_CACHE;
                }
            };
    private final Label pageCount = new Label();
    private final TextField pageField = new TextField();
    private final Label status = new Label();
    private final ImageView pageImage = new ImageView();
    private final ScrollPane pageScroll = new ScrollPane();
    private final ListView<Integer> continuous = new ListView<Integer>();
    private final ListView<Integer> thumbnails = new ListView<Integer>();
    private final StackPane imageStage = new StackPane();
    private final ToggleButton continuousMode = new ToggleButton("连续");
    private final Button zoomOut = new Button("−");
    private final Button fit = new Button("适应宽度");
    private final Button zoomIn = new Button("+");
    private int current = 1;
    private double zoom = 1.0;
    private long requestGeneration;
    private volatile boolean closed;

    public PagedSlideViewer(PagedPdfPreview pdf) {
        this.pdf = pdf;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(2),
                r -> {
                    Thread thread = new Thread(r, "minion-paged-preview");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
        getStyleClass().add("paged-slide-viewer");
        pages = pdf.pageCount();
        if (pages <= 0) throw new IllegalArgumentException("PDF 没有可显示的页面。");

        Button prev = iconButton("‹", "上一页 (←)");
        Button next = iconButton("›", "下一页 (→)");
        prev.setOnAction(e -> goTo(current - 1));
        next.setOnAction(e -> goTo(current + 1));
        pageField.setPrefColumnCount(3);
        pageField.setMaxWidth(56);
        pageField.setPromptText("1");
        pageField.setOnAction(e -> parsePage());
        pageCount.setText("/ " + pages);
        pageCount.getStyleClass().add("artifact-meta");
        zoomOut.setOnAction(e -> setZoom(zoom - 0.15));
        zoomIn.setOnAction(e -> setZoom(zoom + 0.15));
        fit.setOnAction(e -> {
            zoom = 1.0;
            renderCurrent();
        });
        continuousMode.setOnAction(e -> {
            setContinuous(continuousMode.isSelected());
            renderCurrent();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(4, prev, pageField, pageCount, next, spacer,
                continuousMode, zoomOut, fit, zoomIn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("paged-slide-toolbar");

        // 页面舞台：居中+柔和间距+圆角白页效果
        pageImage.setPreserveRatio(true);
        pageImage.setSmooth(true);
        imageStage.getChildren().add(pageImage);
        imageStage.setAlignment(Pos.CENTER);
        imageStage.setPadding(new Insets(20, 16, 20, 16));
        imageStage.getStyleClass().add("image-stage");
        pageScroll.setContent(imageStage);
        pageScroll.setFitToWidth(true);
        pageScroll.setPannable(true);
        pageScroll.getStyleClass().add("artifact-reader");

        thumbnails.setCellFactory(list -> new ListCell<Integer>() {
            private final ImageView view = new ImageView();
            @Override protected void updateItem(Integer page, boolean empty) {
                super.updateItem(page, empty);
                if (empty || page == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                setText(String.valueOf(page));
                view.setPreserveRatio(true);
                view.setFitWidth(112);
                loadPage(page, 150, image -> {
                    if (getItem() != null && getItem().equals(page)) {
                        view.setImage(image);
                        setGraphic(view);
                    }
                });
            }
        });
        thumbnails.setItems(FXCollections.observableArrayList(numbers(pages)));
        thumbnails.setPrefWidth(142);
        thumbnails.setMinWidth(112);
        thumbnails.setMaxWidth(170);
        thumbnails.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) goTo(b);
        });

        continuous.setCellFactory(list -> new ListCell<Integer>() {
            private final ImageView view = new ImageView();
            private final Label placeholder = new Label();
            @Override protected void updateItem(Integer page, boolean empty) {
                super.updateItem(page, empty);
                if (empty || page == null) {
                    setGraphic(null);
                    return;
                }
                placeholder.setText("第 " + page + " 页");
                setGraphic(placeholder);
                loadPage(page, 1400, image -> {
                    if (getItem() != null && getItem().equals(page)) {
                        view.setImage(image);
                        view.setPreserveRatio(true);
                        view.fitWidthProperty().bind(continuous.widthProperty().subtract(52));
                        setGraphic(view);
                    }
                });
            }
        });
        continuous.setItems(FXCollections.observableArrayList(numbers(pages)));
        continuous.setVisible(false);
        continuous.setManaged(false);
        continuous.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) current = b;
        });

        StackPane center = new StackPane(pageScroll, continuous);
        VBox.setVgrow(center, Priority.ALWAYS);
        setCenter(center);
        setLeft(thumbnails);
        setTop(toolbar);
        // 状态行融入底边（不占独立空间——放在 toolbar 右端）
        toolbar.getChildren().add(spacer2());
        toolbar.getChildren().add(status);
        status.getStyleClass().add("artifact-meta");
        status.setWrapText(false);
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.PAGE_UP) {
                goTo(current - 1); event.consume();
            } else if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.PAGE_DOWN) {
                goTo(current + 1); event.consume();
            }
        });
        addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.isControlDown()) {
                setZoom(zoom + (event.getDeltaY() > 0 ? 0.1 : -0.1));
                event.consume();
            }
        });
        setFocusTraversable(true);
        goTo(1);
    }

    public void goTo(int page) {
        current = Math.max(1, Math.min(pages, page));
        pageField.setText(String.valueOf(current));
        thumbnails.getSelectionModel().select(current - 1);
        continuous.getSelectionModel().select(current - 1);
        continuous.scrollTo(current - 1);
        renderCurrent();
    }

    private void renderCurrent() {
        if (continuousMode.isSelected()) return;
        final long request = ++requestGeneration;
        int width = (int) Math.max(420, Math.min(2200,
                pageScroll.getViewportBounds().getWidth() * zoom));
        loadPage(current, width, image -> {
            if (closed || request != requestGeneration) return;
            pageImage.setImage(image);
            pageImage.setFitWidth(width);
            status.setText("第 " + current + " / " + pages + " 页 · "
                    + Math.round(zoom * 100) + "%");
        });
        loadPage(current - 1, 260, image -> prefetch(current - 1));
        loadPage(current + 1, 260, image -> prefetch(current + 1));
    }

    private void prefetch(int page) {
        if (page >= 1 && page <= pages) {
            loadPage(page, 900, image -> { });
        }
    }

    private void setZoom(double value) {
        zoom = Math.max(0.5, Math.min(2.0, value));
        renderCurrent();
    }

    private void setContinuous(boolean enabled) {
        pageScroll.setVisible(!enabled);
        pageScroll.setManaged(!enabled);
        continuous.setVisible(enabled);
        continuous.setManaged(enabled);
        fit.setDisable(enabled);
    }

    private void parsePage() {
        try {
            goTo(Integer.parseInt(pageField.getText().trim()));
        } catch (NumberFormatException invalid) {
            pageField.setText(String.valueOf(current));
        }
    }

    private interface ImageConsumer { void accept(Image image); }

    private void loadPage(int page, int width, ImageConsumer consumer) {
        if (closed || page < 1 || page > pages) return;
        Image cached;
        synchronized (imageCache) { cached = imageCache.get(page); }
        if (cached != null && cached.getWidth() >= width - 120) {
            consumer.accept(cached);
            return;
        }
        try {
            worker.submit(() -> {
                try {
                    BufferedImage buffered = pdf.renderPage(page - 1, width);
                    Image image = SwingFXUtils.toFXImage(buffered, null);
                    synchronized (imageCache) { imageCache.put(page, image); }
                    Platform.runLater(() -> {
                        if (!closed) consumer.accept(image);
                    });
                } catch (Exception unavailable) {
                    Platform.runLater(() -> {
                        if (!closed) status.setText("第 " + page + " 页渲染失败："
                                + unavailable.getMessage());
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static Button iconButton(String text, String tip) {
        Button button = new Button(text);
        button.getStyleClass().add("artifact-icon-button");
        button.setTooltip(new javafx.scene.control.Tooltip(tip));
        return button;
    }

    private static Region spacer2() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Integer[] numbers(int count) {
        Integer[] values = new Integer[count];
        for (int i = 0; i < count; i++) values[i] = i + 1;
        return values;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        requestGeneration++;
        worker.shutdownNow();
        synchronized (imageCache) { imageCache.clear(); }
        Thread release = new Thread(pdf::close, "minion-paged-preview-close");
        release.setDaemon(true);
        release.start();
    }
}
