package com.minion.gui;

import com.minion.core.agent.TaskBoardModel;
import com.minion.core.agent.TaskBoardModel.BoardRow;
import com.minion.core.config.Config;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/** 对话框上方常驻任务条（Part E，纯展示层 additive）：与 TaskDashboard 同源
 *  TaskBoardModel.snapshot，1s 轮询刷新（照 TaskDashboard/状态栏先例，不新增事件通道）。
 *  折叠态单行摘要 + 进度徽标 n/m；点击展开 ≤8 行明细（再点进 TaskDashboard）；
 *  无 todo/task/background 时整条隐藏（managed=false，布局与现状逐像素一致）。
 *  只读展示——不做勾选交互，所有变更仍归 TaskV2/TodoWrite/TaskStop 工具。
 *  摘要/徽标/隐藏决策抽为纯静态方法，headless 可单测（TaskStripModelTest）。 */
public final class TaskStrip extends VBox {

    /** 展开态最多渲染的明细行数（超出滚动 + “共 N 项”提示） */
    static final int MAX_ROWS = 8;
    /** 当前行标题截断长度 */
    static final int TITLE_CLIP = 60;

    private final SessionManager manager;
    private final Config config;
    private final Runnable openDashboard;
    private final Label chevron = new Label("▸");
    private final Label summary = new Label();
    private final VBox rowsBox = new VBox(2);
    private final ScrollPane rowsScroll = new ScrollPane(rowsBox);
    private final HBox summaryRow;
    private final Timeline timer;
    private boolean expanded;
    private String lastSignature = "";

    public TaskStrip(SessionManager manager, Config config, Runnable openDashboard) {
        this.manager = manager;
        this.config = config;
        this.openDashboard = openDashboard;
        this.expanded = Boolean.parseBoolean(config.get("ui.taskStrip.expanded", "false"));
        getStyleClass().add("task-strip");
        setSpacing(3);

        chevron.getStyleClass().add("task-strip-chevron");
        summary.getStyleClass().add("task-strip-summary");
        summary.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summary, Priority.ALWAYS);
        summaryRow = new HBox(6, chevron, summary);
        summaryRow.setAlignment(Pos.CENTER_LEFT);
        summaryRow.getStyleClass().add("task-strip-header");
        summaryRow.setOnMouseClicked(e -> toggle());

        rowsBox.getStyleClass().add("task-strip-rows");
        rowsScroll.getStyleClass().add("task-strip-scroll");
        rowsScroll.setFitToWidth(true);
        rowsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rowsScroll.setMaxHeight(160);
        rowsScroll.setPrefHeight(Region.USE_COMPUTED_SIZE);
        rowsScroll.setVisible(expanded);
        rowsScroll.setManaged(expanded);

        getChildren().addAll(summaryRow, rowsScroll);
        setVisible(false);
        setManaged(false);

        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
        refresh();
        timer.play();
    }

    /** 窗口隐藏/会话宿主销毁时停轮询（防 Timeline 泄漏，照 TaskDashboard 生命周期） */
    public void stop() { timer.stop(); }

    private void toggle() {
        expanded = !expanded;
        config.set("ui.taskStrip.expanded", Boolean.toString(expanded));
        chevron.setText(expanded ? "▾" : "▸");
        rowsScroll.setVisible(expanded);
        rowsScroll.setManaged(expanded);
    }

    private void refresh() {
        SessionHandle h = manager.currentSession();
        List<BoardRow> rows = (h == null || h.deleted || !h.isLoaded())
                ? new ArrayList<BoardRow>()
                : TaskBoardModel.snapshot(h.session, manager.backgroundRegistryOf(h.id));
        boolean visible = !rows.isEmpty();
        setVisible(visible);
        setManaged(visible);
        if (!visible) { lastSignature = ""; return; }
        String sig = signature(rows);
        if (sig.equals(lastSignature)) return;   // 快照未变跳过重绘（防抖动）
        lastSignature = sig;
        summary.setText(summaryText(rows));
        chevron.setText(expanded ? "▾" : "▸");
        rowsBox.getChildren().clear();
        int shown = 0;
        for (BoardRow r : rows) {
            if (shown >= MAX_ROWS) break;
            Label row = new Label(rowLabel(r));
            row.getStyleClass().add("task-strip-row");
            if (isDone(r)) row.getStyleClass().add("task-strip-row-done");
            else if (isActive(r)) row.getStyleClass().add("task-strip-row-active");
            row.setMaxWidth(Double.MAX_VALUE);
            row.setOnMouseClicked(e -> openDashboard.run());
            rowsBox.getChildren().add(row);
            shown++;
        }
        if (rows.size() > MAX_ROWS) {
            Label more = new Label("…共 " + rows.size() + " 项，点击查看全部");
            more.getStyleClass().add("task-strip-more");
            more.setOnMouseClicked(e -> openDashboard.run());
            rowsBox.getChildren().add(more);
        }
        rowsScroll.setVisible(expanded);
        rowsScroll.setManaged(expanded);
    }

    // ---- 纯静态展示逻辑（headless 可单测） ----

    /** 终态判定：todo=完成；task=已返回/取消/失败/中断等不再推进；background=settled */
    public static boolean isDone(BoardRow r) {
        if (TaskBoardModel.KIND_TODO.equals(r.kind)) return "完成".equals(r.status);
        if (TaskBoardModel.KIND_TASK.equals(r.kind))
            return "returned_needs_review".equals(r.status) || "completed".equals(r.status)
                    || "cancelled".equals(r.status) || "failed_or_interrupted".equals(r.status)
                    || "interrupted_needs_review".equals(r.status);
        return r.exitCode != null || "COMPLETED".equals(r.status) || "FAILED".equals(r.status)
                || "CANCELLED".equals(r.status) || "UNKNOWN".equals(r.status);
    }

    /** 进行中（▶）：task=running；background=非终态的运行/排队/等待/核验 */
    public static boolean isActive(BoardRow r) {
        if (TaskBoardModel.KIND_TASK.equals(r.kind)) return "running".equals(r.status);
        if (TaskBoardModel.KIND_BACKGROUND.equals(r.kind))
            return "RUNNING".equals(r.status) || "QUEUED".equals(r.status) || "VERIFYING".equals(r.status)
                    || "WAITING_EXTERNAL".equals(r.status) || "WAITING_USER".equals(r.status);
        return false;
    }

    public static String icon(BoardRow r) { return isDone(r) ? "✓" : isActive(r) ? "▶" : "○"; }

    /** [done, total] */
    public static int[] counts(List<BoardRow> rows) {
        int done = 0;
        for (BoardRow r : rows) if (isDone(r)) done++;
        return new int[]{done, rows.size()};
    }

    /** 第一条未完成行标题（截断 TITLE_CLIP）；全完成返回空串 */
    public static String currentTitle(List<BoardRow> rows) {
        for (BoardRow r : rows) if (!isDone(r)) return clip(r.title, TITLE_CLIP);
        return "";
    }

    /** 折叠态摘要文案（不含 chevron，GUI 按展开态前置 ▸/▾）；空态返回空串 */
    public static String summaryText(List<BoardRow> rows) {
        if (rows.isEmpty()) return "";
        int[] c = counts(rows);
        if (c[0] == c[1]) return "✓ 任务清单 " + c[0] + "/" + c[1] + " 已完成";
        return "任务清单  [" + c[0] + "/" + c[1] + "]  当前：" + currentTitle(rows);
    }

    /** 展开态单行：[✓/▶/○] 标题（task 附 owner·status；background 附 exit=N） */
    public static String rowLabel(BoardRow r) {
        StringBuilder sb = new StringBuilder(icon(r)).append(' ').append(clip(r.title, TITLE_CLIP));
        if (TaskBoardModel.KIND_TASK.equals(r.kind)) {
            String owner = r.owner == null ? "" : r.owner;
            String status = r.status == null ? "" : r.status;
            if (!owner.isEmpty() || !status.isEmpty())
                sb.append("（").append(owner).append(owner.isEmpty() || status.isEmpty() ? "" : "·").append(status).append("）");
        } else if (TaskBoardModel.KIND_BACKGROUND.equals(r.kind) && r.exitCode != null) {
            sb.append(" exit=").append(r.exitCode);
        }
        return sb.toString();
    }

    /** 重绘签名：仅覆盖展示用字段（不含 recentOutput，避免后台输出尾部每秒抖动重绘） */
    public static String signature(List<BoardRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (BoardRow r : rows)
            sb.append(r.kind).append('|').append(r.id).append('|').append(r.title).append('|')
                    .append(r.status).append('|').append(r.owner).append('|').append(r.exitCode).append('\n');
        return sb.toString();
    }

    public static String clip(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}