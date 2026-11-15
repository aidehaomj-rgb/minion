package com.minion.gui.theme;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Dialog;

/** 主题工具：统一样式表、可切换工作台主题和弹窗挂载。 */
public final class Theme {

    public static final String STYLESHEET = "/theme/theme.css";
    public static final String WORKBENCH = "/theme/workbench.css";

    /** 可选主题；配置保存 id，界面显示 label。 */
    public enum Variant {
        OBSIDIAN("obsidian", "深夜黑"),
        GRAPHITE("graphite", "石墨灰"),
        MIST("mist", "雾蓝灰"),
        LIGHT("light", "明亮浅色");

        public final String id;
        public final String label;

        Variant(String id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override public String toString() { return label; }
    }

    private static Variant active = Variant.OBSIDIAN;

    private Theme() { }

    /** 未识别或缺失的主题值统一回落深夜黑。 */
    public static Variant variant(String id) {
        if (id != null) {
            for (Variant value : Variant.values()) {
                if (value.id.equalsIgnoreCase(id.trim())) return value;
            }
        }
        return Variant.OBSIDIAN;
    }

    public static String activeId() { return active.id; }

    /** 应用主题到节点及其内部所有工作台根节点。 */
    public static void applyTree(Node root, String id) {
        Variant variant = variant(id);
        active = variant;
        applyNode(root, variant);
    }

    /** 仅应用到一个工作台节点，用于已经明确节点范围的弹窗。 */
    public static void apply(Node node, String id) {
        Variant variant = variant(id);
        active = variant;
        applyNode(node, variant);
    }

    private static void applyNode(Node node, Variant variant) {
        if (node == null) return;
        if (node.getStyleClass().contains("minion-workbench")) {
            node.getStyleClass().removeAll("theme-obsidian", "theme-graphite", "theme-mist", "theme-light");
            node.getStyleClass().add("theme-" + variant.id);
            if (node instanceof Parent && node.getScene() != null) ((Parent) node).applyCss();
        }
        if (!(node instanceof Parent)) return;
        for (Node child : ((Parent) node).getChildrenUnmodifiable()) applyNode(child, variant);
    }

    /** 给弹窗挂统一样式；Alert/TextInputDialog/Dialog 因此与主窗口使用同一主题。 */
    public static void style(Dialog<?> dialog) {
        if (dialog == null || dialog.getDialogPane() == null) return;
        com.minion.gui.icon.AppIcons.watch(dialog.getDialogPane());
        if (!dialog.getDialogPane().getStyleClass().contains("minion-workbench")) {
            dialog.getDialogPane().getStyleClass().add("minion-workbench");
        }
        if (!dialog.getDialogPane().getStylesheets().contains(STYLESHEET)) {
            dialog.getDialogPane().getStylesheets().add(STYLESHEET);
        }
        if (!dialog.getDialogPane().getStylesheets().contains(WORKBENCH)) {
            dialog.getDialogPane().getStylesheets().add(WORKBENCH);
        }
        applyNode(dialog.getDialogPane(), active);
    }
}
