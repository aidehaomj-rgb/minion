package com.minion.gui.dialog;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.Motion;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * 高危/越界操作确认底部卡片（替代旧 Alert 版 ConfirmDialog）：
 * 挂右侧面板 StackPane 顶层，遮罩仅压暗右侧、卡片从底部滑入；弹窗期间
 * 全窗点击拦截（Scene 事件过滤器）、焦点圈定卡片内；并发确认串行排队。
 * 必须在 FX 线程调用（GuiConfirmUi 经 Platform.runLater 保证）。
 */
public class ConfirmSheet {

    private static StackPane host;
    private static boolean showing;
    private static final Queue<Runnable> pending = new ArrayDeque<Runnable>();
    private static VBox currentCard;
    private static Region currentScrim;
    private static Consumer<ConfirmUi.Decision> currentCallback;

    private ConfirmSheet() { }

    /** MainWindow 注册右侧面板栈（遮罩与卡片的挂载点） */
    public static void setHost(StackPane h) { host = h; }

    private static Consumer<Boolean> visibilityListener;

    /** 注册弹窗可见性回调：true=显示，false=完全关闭（淡出完成）；MainWindow 用于挂起/恢复运行状态指示器 */
    public static void setVisibilityListener(Consumer<Boolean> listener) { visibilityListener = listener; }

    public static void show(String message, Consumer<ConfirmUi.Decision> callback) {
        if (host == null) {
            System.err.println("[minion] ConfirmSheet host 未注册，确认请求自动拒绝");
            callback.accept(ConfirmUi.Decision.REJECT);
            return;
        }
        Runnable job = new Runnable() {
            @Override public void run() { display(message, callback); }
        };
        if (showing) { pending.add(job); return; }
        job.run();
    }

    /** §10.3 结构化富卡片：来源/风险/diff/可编辑规则建议 + 「允许并写规则」取编辑文本 */
    public static void show(final com.minion.core.tools.confirm.PermissionExplainer.Explanation ex,
            String message, final Consumer<ConfirmUi.Decision> callback,
            final java.util.function.Consumer<String> ruleCollector) {
        if (host == null) {
            callback.accept(ConfirmUi.Decision.REJECT);
            return;
        }
        Runnable job = new Runnable() {
            @Override public void run() { display(ex, message, callback, ruleCollector); }
        };
        if (showing) { pending.add(job); return; }
        job.run();
    }

    private static void display(final com.minion.core.tools.confirm.PermissionExplainer.Explanation ex,
            String message, final Consumer<ConfirmUi.Decision> callback,
            final java.util.function.Consumer<String> ruleCollector) {
        showing = true;
        if (visibilityListener != null) visibilityListener.accept(true);
        final Region scrim = new Region();
        scrim.getStyleClass().add("sheet-scrim");
        final VBox card = buildRichCard(ex, message, ruleCollector);
        StackPane.setAlignment(card, Pos.BOTTOM_CENTER);
        StackPane.setMargin(card, new Insets(0, 16, 24, 16));
        card.maxWidthProperty().bind(Bindings.min(640, host.widthProperty().subtract(32)));
        currentCard = card;
        currentScrim = scrim;
        currentCallback = callback;
        host.getScene().addEventFilter(MouseEvent.ANY, mouseFilter);
        host.getScene().addEventFilter(KeyEvent.ANY, keyFilter);
        host.getChildren().addAll(scrim, card);
        card.requestFocus();
    }

    /** §10.3 富卡片：判定来源行 | 风险行 | Write/Edit 对照 | 可编辑规则建议 | 四键 */
    private static VBox buildRichCard(
            final com.minion.core.tools.confirm.PermissionExplainer.Explanation ex,
            String message, final java.util.function.Consumer<String> ruleCollector) {
        VBox card = new VBox();
        card.getStyleClass().add("sheet-card");
        card.setPrefWidth(600);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        Region accent = new Region();
        accent.getStyleClass().add("sheet-accent");

        Label body = new Label(message);
        body.setWrapText(true);
        body.getStyleClass().add("sheet-message");

        Label source = new Label("[判定来源] " + ex.decisionSource);
        source.setWrapText(true);
        source.getStyleClass().add("sheet-meta");
        Label risk = new Label("[风险] " + ex.riskLevel + " — " + ex.riskDetail);
        risk.setWrapText(true);
        risk.getStyleClass().add("sheet-meta");

        VBox content = new VBox(6);
        content.setPadding(new Insets(8, 14, 8, 14));
        content.getChildren().addAll(body, source, risk);

        // Write/Edit 自绘对照（数据展示；路径 + 旧/新文本块，各限 6 行可见）
        if (ex.diffNew != null || ex.diffOld != null) {
            Label path = new Label("[文件] " + (ex.diffPath == null ? "?" : ex.diffPath));
            path.getStyleClass().add("sheet-meta");
            content.getChildren().add(path);
            if (ex.diffOld != null && !ex.diffOld.isEmpty()) {
                TextArea old = new TextArea(ex.diffOld);
                styleDiffArea(old, "旧内容（-）");
                content.getChildren().add(old);
            }
            if (ex.diffNew != null) {
                TextArea fresh = new TextArea(ex.diffNew);
                styleDiffArea(fresh, "新内容（+）");
                content.getChildren().add(fresh);
            }
        }

        // 可编辑规则建议（「允许并写规则」取此文本）
        final TextField rule = new TextField(ex.suggestedRule);
        Label ruleLabel = new Label("[规则建议·可编辑] 作用域 " + ex.ruleScope);
        ruleLabel.getStyleClass().add("sheet-meta");
        HBox ruleBox = new HBox(8, ruleLabel);
        VBox ruleArea = new VBox(4, ruleBox, rule);

        Button reject = new Button("拒绝");
        reject.getStyleClass().add("btn-ghost");
        reject.setOnAction(e -> finish(ConfirmUi.Decision.REJECT));
        Button session = new Button("本次会话批准");
        session.getStyleClass().add("btn-ghost");
        session.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE_SESSION));
        Button saveRule = new Button("允许并写规则");
        saveRule.getStyleClass().add("btn-ghost");
        saveRule.setOnAction(e -> {
            if (ruleCollector != null) ruleCollector.accept(rule.getText());
            finish(ConfirmUi.Decision.APPROVE_WHITELIST);
        });
        Button approve = new Button("同意");
        approve.getStyleClass().add("btn-approve");
        approve.setDefaultButton(true);
        approve.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE));
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(reject, session, saveRule, approve);

        content.getChildren().addAll(ruleArea, buttons);
        card.getChildren().addAll(accent, content);
        return card;
    }

    private static void styleDiffArea(TextArea area, String prompt) {
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(4);
        area.setMaxHeight(96);
        area.setPromptText(prompt);
    }

    /** 坐标落卡片外一律拦截：实现「点遮罩无反应 + 弹窗期间全窗点击拦截（含侧栏）」 */
    private static final EventHandler<MouseEvent> mouseFilter = new EventHandler<MouseEvent>() {
        @Override public void handle(MouseEvent e) {
            VBox card = currentCard;
            if (card == null) return;
            if (!card.contains(card.sceneToLocal(e.getSceneX(), e.getSceneY()))) e.consume();
        }
    };

    /** Esc=拒绝；Enter 放行触发焦点按钮（默认焦点=同意）；其余按键一律拦截（Tab 圈定焦点防逃逸到侧栏） */
    private static final EventHandler<KeyEvent> keyFilter = new EventHandler<KeyEvent>() {
        @Override public void handle(KeyEvent e) {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                finish(ConfirmUi.Decision.REJECT);
            } else if (e.getCode() != KeyCode.ENTER) {
                e.consume();
            }
        }
    };

    private static void display(String message, final Consumer<ConfirmUi.Decision> callback) {
        showing = true;
        if (visibilityListener != null) visibilityListener.accept(true);

        final Region scrim = new Region();
        scrim.getStyleClass().add("sheet-scrim");
        // 软件渲染下直接落终态：遮罩淡入是整块右侧面板的逐帧重绘（约 9 帧），
        // 卡片滑入同样是 60fps 位移，低端机/远程桌面上省掉这段没有信息量的动画（见 Motion）
        final boolean instant = Motion.reduced();
        if (instant) {
            scrim.setOpacity(1);
        } else {
            FadeTransition scrimIn = new FadeTransition(Duration.millis(150), scrim);
            scrimIn.setFromValue(0);
            scrimIn.setToValue(1);
            scrimIn.play();
        }

        final VBox card = buildCard(message);
        StackPane.setAlignment(card, Pos.BOTTOM_CENTER);
        StackPane.setMargin(card, new Insets(0, 16, 24, 16)); // 距底 24px ≈ 1 行（行高单位同输入框 6*24）
        // 宽度随右侧面板收缩（分栏拖窄时不越过分隔线），上限 640
        card.maxWidthProperty().bind(Bindings.min(640, host.widthProperty().subtract(32)));

        currentCard = card;
        currentScrim = scrim;
        currentCallback = callback;
        Scene scene = host.getScene();
        scene.addEventFilter(MouseEvent.ANY, mouseFilter);
        scene.addEventFilter(KeyEvent.ANY, keyFilter);
        host.getChildren().addAll(scrim, card);

        // 滑入动画需布局完成后的卡片高度，runLater 等一个布局周期；降级时只保留聚焦
        Platform.runLater(new Runnable() {
            @Override public void run() {
                if (!instant) {
                    double h = card.getBoundsInParent().getHeight();
                    TranslateTransition slide = new TranslateTransition(Duration.millis(180), card);
                    slide.setFromY(h);
                    slide.setToY(0);
                    slide.setInterpolator(Interpolator.EASE_OUT);
                    slide.play();
                }
                card.requestFocus();
            }
        });
    }

    /** 卡片：顶部琥珀危险饰条 | 消息 | 按钮行（压缩为两行，去掉标题行与快捷键提示行） */
    private static VBox buildCard(String message) {
        VBox card = new VBox();
        card.getStyleClass().add("sheet-card");
        card.setPrefWidth(560);
        // 宿主 StackPane 默认把可缩放子节点拉伸铺满自身（maxHeight 无界 → 卡片铺满整页），
        // 置 USE_PREF_SIZE 使卡片紧贴内容高度，仅占两行（消息行 + 按钮行）
        card.setMaxHeight(Region.USE_PREF_SIZE);

        Region accent = new Region();
        accent.getStyleClass().add("sheet-accent");

        Label body = new Label(message);
        body.setWrapText(true);
        body.getStyleClass().add("sheet-message");

        Button reject = new Button("拒绝");
        reject.getStyleClass().add("btn-ghost");
        reject.setOnAction(e -> finish(ConfirmUi.Decision.REJECT));
        Button session = new Button("本次会话批准");
        session.getStyleClass().add("btn-ghost");
        session.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE_SESSION));
        Button whitelist = new Button("批准并记住");
        whitelist.getStyleClass().add("btn-ghost");
        whitelist.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE_WHITELIST));
        Button approve = new Button("同意");
        approve.getStyleClass().add("btn-approve");
        approve.setDefaultButton(true); // Enter 即同意（默认焦点落同意）
        approve.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE));
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(reject, session, whitelist, approve);

        VBox content = new VBox(6);
        content.setPadding(new Insets(8, 14, 8, 14));
        content.getChildren().addAll(body, buttons);
        card.getChildren().addAll(accent, content);
        return card;
    }

    /** 收尾：移除拦截 → 120ms 淡出 → 卸载节点 → 回调结果 → 出队展示下一个 */
    private static void finish(final ConfirmUi.Decision d) {
        final VBox card = currentCard;
        final Region scrim = currentScrim;
        final Consumer<ConfirmUi.Decision> callback = currentCallback;
        if (card == null) return; // 双击/重复触发防御
        Scene scene = host.getScene();
        scene.removeEventFilter(MouseEvent.ANY, mouseFilter);
        scene.removeEventFilter(KeyEvent.ANY, keyFilter);
        currentCard = null;
        currentScrim = null;
        currentCallback = null;
        final Runnable complete = new Runnable() {
            @Override public void run() {
                host.getChildren().removeAll(scrim, card);
                showing = false;
                if (visibilityListener != null) visibilityListener.accept(false);
                callback.accept(d);
                Runnable next = pending.poll();
                if (next != null) next.run();
            }
        };
        if (Motion.reduced()) { complete.run(); return; }
        ParallelTransition out = new ParallelTransition(
                new FadeTransition(Duration.millis(120), scrim),
                new FadeTransition(Duration.millis(120), card));
        out.setOnFinished(e -> complete.run());
        out.play();
    }
}
