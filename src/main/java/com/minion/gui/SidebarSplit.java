package com.minion.gui;

import javafx.geometry.Orientation;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Region;

/** Independent outer divider: dragging it must not move the report divider. */
public final class SidebarSplit {
    private SidebarSplit() {}

    /** The rail starts wider for project names but doesn't keep growing with the screen. */
    static double defaultSidebarWidth(double windowWidth) {
        return Math.max(244, Math.min(360, windowWidth * .19));
    }

    /** Default only: the preview split still restores a user-adjusted divider. */
    static double defaultConversationShare(double workWidth) {
        if (workWidth < 600) return .60; // pane minimums arbitrate very small windows
        double preview = Math.max(300, Math.min(720, workWidth * .40));
        preview = Math.min(preview, workWidth - 300);
        return 1 - preview / workWidth;
    }

    public static SplitPane create(Region sidebar, Region content) {
        sidebar.setMinWidth(180);
        sidebar.setMaxWidth(Double.MAX_VALUE);
        content.setMinWidth(360);
        SplitPane split = new SplitPane(sidebar, content);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setId("sidebar-split");
        split.getStyleClass().add("sidebar-split");
        split.setFocusTraversable(false);
        split.setDividerPositions(0.25);
        SplitPane.setResizableWithParent(sidebar, false);
        return split;
    }
}
