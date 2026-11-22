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

    @Test public void codeBlockRendersModernCardWithLangHeaderAndCopyButton() throws Exception {
        onFx(() -> {
            Node node = BlockNodeFactory.create(MarkdownRenderer.parse("```java\nint x = 1;\n```").get(0));
            assertTrue("代码块应渲染为卡片容器", node.getStyleClass().contains("md-code-card"));
            assertTrue(node instanceof javafx.scene.layout.VBox);
            javafx.scene.layout.VBox card = (javafx.scene.layout.VBox) node;
            assertEquals(2, card.getChildren().size());
            assertTrue(card.getChildren().get(0).getStyleClass().contains("md-code-header"));
            assertTrue(card.getChildren().get(1).getStyleClass().contains("md-code-body"));
            Label body = (Label) card.getChildren().get(1);
            assertEquals("int x = 1;", body.getText());
        });
    }

    @Test public void markdownBlocksUseThemeClassesWithoutHardcodedDarkFills() throws Exception {
        onFx(() -> {
            Node heading = BlockNodeFactory.create(MarkdownRenderer.parse("## 标题").get(0));
            assertTrue(heading.getStyleClass().contains("md-heading"));
            assertFalse("标题不应硬编码深色文字色", heading.getStyle().contains("#e6e8ee"));

            Node quote = BlockNodeFactory.create(MarkdownRenderer.parse("> 引用内容").get(0));
            assertTrue(quote.getStyleClass().contains("md-quote"));
            assertFalse("引用块不应硬编码边框色", quote.getStyle().contains("#4f8cff"));

            Node table = BlockNodeFactory.create(MarkdownRenderer.parse("| A | B |\n|---|---|\n| 1 | 2 |").get(0));
            assertTrue(table.getStyleClass().contains("md-table"));
        });
    }
}
