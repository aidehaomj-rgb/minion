package com.minion.gui.artifact;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/** Fit-width image reader with bounded zoom and drag-to-pan. */
public final class ZoomableImageView extends BorderPane {
    private final ImageView imageView = new ImageView();
    private final ScrollPane scroll = new ScrollPane();
    private final Label loading = new Label();
    private double zoom = 1.0;

    public ZoomableImageView(Image image) {
        imageView.setImage(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        loading.getStyleClass().add("artifact-meta");
        StackPane holder = new StackPane(imageView, loading);
        holder.setAlignment(Pos.CENTER);
        holder.getStyleClass().add("zoomable-image-stage");
        image.progressProperty().addListener((o, a, b) -> updateLoading(image));
        image.errorProperty().addListener((o, a, b) -> updateLoading(image));
        updateLoading(image);
        scroll.setContent(holder);
        scroll.setPannable(true);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("artifact-reader");
        Button out = new Button("−");
        Button fit = new Button("适应窗口");
        Button actual = new Button("100%");
        Button in = new Button("+");
        Label value = new Label();
        value.getStyleClass().add("artifact-meta");
        out.setOnAction(e -> setZoom(zoom - 0.1));
        in.setOnAction(e -> setZoom(zoom + 0.1));
        fit.setOnAction(e -> {
            zoom = 1.0;
            imageView.setFitWidth(Math.max(120, scroll.getViewportBounds().getWidth() - 24));
            value.setText("适应窗口");
        });
        actual.setOnAction(e -> {
            zoom = Math.max(0.5, Math.min(3.0,
                    image.getWidth() / Math.max(1, scroll.getViewportBounds().getWidth())));
            applyWidth();
            value.setText("100%");
        });
        scroll.viewportBoundsProperty().addListener((o, a, b) -> applyWidth());
        HBox tools = new HBox(6, out, fit, actual, in, value);
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.getStyleClass().add("zoom-image-toolbar");
        setTop(tools);
        setCenter(scroll);
        applyWidth();
    }

    private void updateLoading(Image image) {
        if (image.isError()) {
            loading.setText("图片预览加载失败");
            loading.setVisible(true);
        } else if (image.getProgress() < 1.0) {
            int percent = (int) Math.round(Math.max(0, image.getProgress()) * 100);
            loading.setText("正在载入图片 " + percent + "%");
            loading.setVisible(true);
        } else {
            loading.setVisible(false);
        }
    }

    private void setZoom(double next) {
        zoom = Math.max(0.5, Math.min(3.0, next));
        applyWidth();
    }

    private void applyWidth() {
        double base = Math.max(120, scroll.getViewportBounds().getWidth() - 24);
        imageView.setFitWidth(Math.max(80, base * zoom));
    }
}
