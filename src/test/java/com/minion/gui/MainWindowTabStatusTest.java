package com.minion.gui;

import com.minion.gui.session.SessionHandle.ActivityState;
import javafx.scene.control.Tab;
import javafx.scene.shape.Circle;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 顶部页签与左侧列表使用相同的运行/完成/已查看状态点。 */
public class MainWindowTabStatusTest {

    @Test
    public void completedUnreadShowsGreenUntilViewed() {
        Tab tab = new Tab();
        MainWindow.updateTabStatus(tab, ActivityState.COMPLETED_UNREAD);
        Circle completed = (Circle) tab.getGraphic();
        assertTrue(completed.isVisible());
        assertTrue(completed.getStyleClass().contains("status-dot-completed"));

        MainWindow.updateTabStatus(tab, ActivityState.NONE);
        Circle viewed = (Circle) tab.getGraphic();
        assertFalse(viewed.isVisible());
        assertFalse(viewed.isManaged());
    }
    @Test public void pausedUnreadAlsoUsesGreenTerminalBadge() {
        Tab tab=new Tab();
        MainWindow.updateTabStatus(tab,ActivityState.PAUSED_UNREAD);
        Circle paused=(Circle)tab.getGraphic();
        assertTrue(paused.isVisible());
        assertTrue(paused.getStyleClass().contains("status-dot-paused"));
        assertTrue(paused.getStyleClass().contains("status-dot-completed"));
    }
}
