package com.minion.gui;
import org.junit.Test;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Region;
import javafx.geometry.Orientation;
import static org.junit.Assert.*;
public class SidebarSplitTest {
    @Test public void defaultsKeepLeftRailAndGiveConversationMoreRoom() {
        assertEquals(244, SidebarSplit.defaultSidebarWidth(960), .001);
        assertEquals(244, SidebarSplit.defaultSidebarWidth(1230), .001);
        assertEquals(360, SidebarSplit.defaultSidebarWidth(2200), .001);
        assertEquals(300, 714 * (1 - SidebarSplit.defaultConversationShare(714)), .001);
        assertEquals(394.4, 986 * (1 - SidebarSplit.defaultConversationShare(986)), .001);
        assertEquals(720, 2100 * (1 - SidebarSplit.defaultConversationShare(2100)), .001);
        assertTrue(600 * SidebarSplit.defaultConversationShare(600) >= 300 - .001);
    }
    @Test public void adjustableDividerAndContentLimits() {
        new javafx.embed.swing.JFXPanel();
        Region left = new Region(), right = new Region();
        SplitPane split = SidebarSplit.create(left, right);
        assertEquals(Orientation.HORIZONTAL, split.getOrientation());
        assertEquals(0.25, split.getDividerPositions()[0], 0.001);
        split.setDividerPositions(0.4);
        assertEquals(0.4, split.getDividerPositions()[0], 0.001);
        assertEquals(180, left.getMinWidth(), 0);
        assertEquals(360, right.getMinWidth(), 0);
        assertFalse(SplitPane.isResizableWithParent(left));
    }
    @Test public void nativeStylePreservesOtherBitsAndNoCaption() {
        int original = 0x90000000;
        int style = WindowsTaskbar.minimizeStyle(original);
        assertEquals(original, style & original);
        assertEquals(0xA0000, style & 0xA0000);
        assertEquals(0, style & 0xC00000);
        assertEquals(style, WindowsTaskbar.minimizeStyle(style));
    }
}
