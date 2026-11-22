package com.minion.gui.input;

import com.minion.core.agent.StatsLine;
import com.minion.gui.Motion;
import com.minion.gui.theme.Theme;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * 上下文环形进度圈（参考 Claude VSCode context 指示器）：
 * Canvas 自绘背景环+进度弧+中心百分比数字；颜色随主题自适应（深色主题亮色系 /
 * 浅色主题深色系）；三段色彩：<50% 蓝绿、50-80% 橙、>80% 红。
 * 悬停显示"上下文大小xk,占比y%,剩余z%自动压缩"，
 * 超 30% 第二行提示"点击立即压缩"；压缩中环体旋转动画。
 */
public class ContextRing extends Canvas {

    /** 可点击压缩阈值：超过 30% 才可点击 */
    public static final double COMPRESS_HINT_PCT = 0.3;

    private static final double SIZE = 34;      // 画布宽（加大提高可见性）
    private static final double HEIGHT = 44;    // 画布高
    private static final double CY_RATIO = 0.5; // 圆心垂直居中
    private static final double STROKE = 4;     // 环线宽（加粗）
    private static final double R = SIZE / 2 - STROKE / 2 - 1;

    // 深色主题配色（亮色系在深底上醒目）
    private static final Color DARK_BG = Color.web("#3a3f4c", 0.6);
    private static final Color DARK_OK = Color.web("#4a9eff");    // <50% 蓝
    private static final Color DARK_WARN = Color.web("#ff9500");  // 50-80% 橙
    private static final Color DARK_HOT = Color.web("#ff4757");   // >80% 红
    private static final Color DARK_TEXT = Color.web("#c8cbd1");

    // 浅色主题配色（深色系在白底上醒目）
    private static final Color LIGHT_BG = Color.web("#c5c9d0", 0.5);
    private static final Color LIGHT_OK = Color.web("#2196f3");
    private static final Color LIGHT_WARN = Color.web("#e65100");
    private static final Color LIGHT_HOT = Color.web("#d32f2f");
    private static final Color LIGHT_TEXT = Color.web("#5f6570");

    private int used;
    private int max;
    private boolean running;
    private boolean compressing;
    private double threshold = 0.8;
    private Runnable onCompress;

    private final Label infoLine = new Label();
    private final Label hintLine = new Label();
    private final AnimationTimer spinner = new AnimationTimer() {
        @Override public void handle(long now) {
            if (!compressing) { stop(); return; }
            repaint(now / 1_000_000);
        }
    };

    public ContextRing() {
        super(SIZE, HEIGHT);
        VBox box = new VBox(3);
        hintLine.setStyle("-fx-text-fill: rgba(240,242,246,0.55);");
        box.getChildren().addAll(infoLine, hintLine);
        Tooltip tip = new Tooltip();
        tip.setGraphic(box);
        Tooltip.install(this, tip);
        hoverProperty().addListener((obs, ov, nv) -> repaint(-1));
        setOnMouseClicked(e -> {
            if (compressable(compressing, running, pct()) && onCompress != null) onCompress.run();
        });
        repaint(-1);
    }

    public void update(int used, int max, double threshold, boolean running) {
        this.used = used;
        this.max = max;
        this.threshold = threshold;
        this.running = running;
        refreshTooltip();
        repaint(-1);
    }

    public void setRunning(boolean running) {
        this.running = running;
        refreshTooltip();
        repaint(-1);
    }

    public void setCompressing(boolean compressing) {
        this.compressing = compressing;
        refreshTooltip();
        if (compressing && !Motion.reduced()) {
            spinner.start();
        } else {
            spinner.stop();
            repaint(-1);
        }
    }

    public void setOnCompress(Runnable onCompress) { this.onCompress = onCompress; }

    private double pct() { return max > 0 ? (double) used / max : 0; }

    /** 当前是否浅色主题 */
    private static boolean isLightTheme() {
        String id = Theme.activeId();
        return "light".equals(id) || "paper".equals(id) || "mist".equals(id);
    }

    /** 按占比取进度色（三段：蓝/橙/红；主题自适应亮度） */
    private static Color progressColor(double p, boolean light) {
        if (p >= 0.8) return light ? LIGHT_HOT : DARK_HOT;
        if (p >= 0.5) return light ? LIGHT_WARN : DARK_WARN;
        return light ? LIGHT_OK : DARK_OK;
    }

    private void refreshTooltip() {
        infoLine.setText(formatInfo(used, max, threshold));
        boolean over = pct() > COMPRESS_HINT_PCT;
        if (compressing) {
            hintLine.setText("正在压缩中");
        } else {
            hintLine.setText(over ? (running ? "运行中不可压缩" : "点击立即压缩") : "");
        }
        hintLine.setVisible(over || compressing);
        hintLine.setManaged(over || compressing);
    }

    private void repaint(long nowMs) {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();
        gc.clearRect(0, 0, w, h);
        double cx = w / 2;
        double cy = h * CY_RATIO;
        boolean light = isLightTheme();

        // 背景环（半透明，深浅主题都可见）
        gc.setLineWidth(STROKE);
        gc.setStroke(light ? LIGHT_BG : DARK_BG);
        gc.strokeOval(cx - R, cy - R, 2 * R, 2 * R);

        gc.setLineCap(StrokeLineCap.ROUND);
        if (compressing) {
            double start = nowMs < 0 ? 0 : (nowMs % 1000) / 1000.0 * 360;
            gc.setStroke(light ? LIGHT_WARN : DARK_WARN);
            gc.strokeArc(cx - R, cy - R, 2 * R, 2 * R, start, 300, ArcType.OPEN);
            return;
        }

        double p = pct();
        Color c = progressColor(p, light);
        if (isHover()) c = c.brighter();
        gc.setStroke(c);
        gc.strokeArc(cx - R, cy - R, 2 * R, 2 * R, -90, arcAngle(p), ArcType.OPEN);

        // 中心百分比数字（≥10% 才显示，避免低占比时挤在一起）
        int pctInt = (int) Math.round(p * 100);
        if (pctInt >= 10) {
            gc.setFill(light ? LIGHT_TEXT : DARK_TEXT);
            gc.setFont(Font.font("System", FontWeight.BOLD, pctInt >= 100 ? 7.5 : 8.5));
            String text = String.valueOf(pctInt);
            javafx.scene.text.Text t = new javafx.scene.text.Text(text);
            t.setFont(gc.getFont());
            double tw = t.getLayoutBounds().getWidth();
            gc.fillText(text, cx - tw / 2, cy + 3);
        }
    }

    static double arcAngle(double pct) {
        double p = pct < 0 ? 0 : pct > 1 ? 1 : pct;
        return p * 360;
    }

    static boolean compressable(boolean compressing, boolean running, double pct) {
        return !compressing && !running && pct > COMPRESS_HINT_PCT;
    }

    static String formatInfo(int used, int max, double threshold) {
        int pct = max > 0 ? (int) Math.round(used * 100.0 / max) : 0;
        int remain = max > 0 ? (int) Math.round(threshold * 100.0 - pct) : 0;
        if (remain < 0) remain = 0;
        return "上下文大小" + StatsLine.formatTokens(used) + ",占比" + pct + "%,剩余" + remain + "%自动压缩";
    }
}
