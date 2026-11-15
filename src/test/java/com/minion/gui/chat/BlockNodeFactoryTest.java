package com.minion.gui.chat;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class BlockNodeFactoryTest {
    @BeforeClass public static void startFx() {
        new JFXPanel();
        Platform.setImplicitExit(false);
    }

    static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<Void>(action, null);
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    static List<Hyperlink> links(Node node) {
        List<Hyperlink> out = new ArrayList<Hyperlink>();
        if (node instanceof Hyperlink) out.add((Hyperlink) node);
        else if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) out.addAll(links(child));
        }
        return out;
    }

    static void requestMenu(Hyperlink link) {
        link.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 10, 10, false, null));
    }

    @Test public void paragraphClickAndLazyMenuReceiveExactOriginalHref() throws Exception {
        onFx(() -> {
            String href = "file:///D:/中文%20目录/报告.pdf";
            List<String> clicked = new ArrayList<String>();
            List<String> menus = new ArrayList<String>();
            ContextMenu menu = new ContextMenu(new MenuItem("主流程菜单"));
            Node node = BlockNodeFactory.create(MarkdownRenderer.parse("[报告](" + href + ")").get(0),
                    clicked::add, target -> { menus.add(target); return menu; });
            Hyperlink link = links(node).get(0);
            assertEquals("报告", link.getText());
            assertEquals(href, link.getUserData());
            assertEquals(href, link.getTooltip().getText());
            assertTrue(menus.isEmpty());
            link.fire();
            assertEquals(java.util.Arrays.asList(href), clicked);
            requestMenu(link);
            assertEquals(java.util.Arrays.asList(href), menus);
            assertSame(menu, link.getContextMenu());
        });
    }

    @Test public void listsHeadingsQuotesAndTablesUseRealHyperlinks() throws Exception {
        onFx(() -> {
            String[] blocks = {"- [清单](<D:/科室/报告 终稿.pdf>)", "# [标题](<D:/科室/报告 终稿.pdf>)",
                    "> [引用](<D:/科室/报告 终稿.pdf>)",
                    "| [表头](<D:/科室/报告 终稿.pdf>) |\n|---|\n| [表格](<D:/科室/报告 终稿.pdf>) |"};
            for (String md : blocks) {
                List<String> clicked = new ArrayList<String>();
                List<Hyperlink> controls = links(BlockNodeFactory.create(MarkdownRenderer.parse(md).get(0), clicked::add, null));
                assertFalse(md, controls.isEmpty());
                for (Hyperlink link : controls) {
                    link.fire();
                    assertEquals("D:/科室/报告 终稿.pdf", clicked.get(clicked.size() - 1));
                }
            }
        });
    }

    @Test public void nestedStylesRemainOnLinkedSpansAndOldCreateIsSafe() throws Exception {
        onFx(() -> {
            Node node = BlockNodeFactory.create(MarkdownRenderer.parse("[**加粗** *斜体* `代码`](D:/报告.pdf)").get(0));
            List<Hyperlink> controls = links(node);
            assertTrue(controls.size() >= 3);
            assertTrue(controls.get(0).getFont().getStyle().contains("Bold"));
            for (Hyperlink link : controls) {
                assertEquals("D:/报告.pdf", link.getUserData());
                link.fire();
                requestMenu(link);
                assertNull(link.getContextMenu());
            }
            assertTrue(BlockNodeFactory.create(MarkdownRenderer.parse("# 原标题").get(0)) instanceof Label);
            assertTrue(links(BlockNodeFactory.create(MarkdownRenderer.parse("正文 `代码`").get(0))).isEmpty());
        });
    }

    @Test public void menuFactoryMayReturnNullWithoutUsingStaleMenu() throws Exception {
        onFx(() -> {
            final boolean[] enabled = {true};
            ContextMenu menu = new ContextMenu();
            Hyperlink link = links(BlockNodeFactory.create(MarkdownRenderer.parse("[文件](D:/报告.pdf)").get(0),
                    null, href -> enabled[0] ? menu : null)).get(0);
            requestMenu(link);
            assertSame(menu, link.getContextMenu());
            enabled[0] = false;
            requestMenu(link);
            assertNull(link.getContextMenu());
        });
    }
}
