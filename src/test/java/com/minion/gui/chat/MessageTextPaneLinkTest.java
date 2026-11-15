package com.minion.gui.chat;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.MenuItem;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.minion.gui.chat.BlockNodeFactoryTest.*;
import static org.junit.Assert.*;

public class MessageTextPaneLinkTest {
    @BeforeClass public static void startFx() { BlockNodeFactoryTest.startFx(); }

    private static void press(MessageTextPane pane, String text) {
        for (Node node : ((Parent) pane.getChildren().get(0)).getChildrenUnmodifiable()) {
            if (node instanceof Button && text.equals(((Button) node).getText())) {
                ((Button) node).fire();
                return;
            }
        }
        fail("找不到分页按钮：" + text);
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, ch);
        return new String(chars);
    }

    @Test public void selectableBodyAndLinkCallbacksKeepRawPathAndMenu() throws Exception {
        onFx(() -> {
            String href = "D:/科室报告/最终 报告.pdf";
            String md = "正文 [报告](<" + href + ">)";
            MessageTextPane pane = new MessageTextPane(md, true);
            List<String> clicked = new ArrayList<String>();
            List<String> requested = new ArrayList<String>();
            ContextMenu menu = new ContextMenu(new MenuItem("主流程菜单"));
            pane.setLinkHandler(clicked::add, target -> { requested.add(target); return menu; });
            assertFalse(pane.contentArea().isEditable());
            assertEquals("正文 报告", pane.contentArea().getText());
            assertEquals(md, pane.fullText());
            pane.contentArea().selectAll();
            assertEquals(pane.contentArea().getText(), pane.contentArea().getSelectedText());
            Hyperlink link = links(pane).get(0);
            link.fire();
            assertEquals(java.util.Arrays.asList(href), clicked);
            assertTrue(requested.isEmpty());
            requestMenu(link);
            assertSame(menu, link.getContextMenu());
            assertEquals(java.util.Arrays.asList(href), requested);
            // 重绑 handler 不重写正文，不破坏用户选区。
            pane.setLinkHandler(target -> clicked.add("new:" + target), null);
            assertEquals(pane.contentArea().getText(), pane.contentArea().getSelectedText());
            links(pane).get(0).fire();
            assertEquals("new:" + href, clicked.get(1));
            requestMenu(links(pane).get(0));
            assertNull(links(pane).get(0).getContextMenu());
        });
    }

    @Test public void ordinaryMessagePutsLinksBehindOneCollapsedControlAndKeepsRawMessage() throws Exception {
        onFx(() -> {
            String source = "见 [甲](D:/a.pdf) 和 [乙](D:/b.pdf)";
            MessageTextPane pane = new MessageTextPane(source, true);
            pane.setLinkHandler(target -> {}, null);
            Parent bar = (Parent) pane.getChildren().get(2);
            Button toggle = (Button) bar.getChildrenUnmodifiable().get(0);
            Node items = bar.getChildrenUnmodifiable().get(1);
            assertFalse(items.isVisible());
            assertFalse(items.isManaged());
            assertTrue(toggle.getText().contains("相关链接 · 2"));
            assertEquals("见 甲 和 乙", pane.contentArea().getText());
            assertEquals(source, pane.fullText());
            toggle.fire();
            assertTrue(items.isVisible());
            assertEquals(2, links(items).size());
            toggle.fire();
            assertFalse(items.isVisible());
        });
    }

    @Test public void artifactMessageShowsJustOneClickableFilenameOrReadableFallback() throws Exception {
        onFx(() -> {
            String href = "file:///D:/report.md";
            String source = "[报告.md](" + href + ")";
            MessageTextPane pane = new MessageTextPane(source, true, true);
            assertTrue(pane.contentArea().isVisible());
            List<String> opened = new ArrayList<String>();
            pane.setLinkHandler(opened::add, null);
            assertFalse(pane.contentArea().isManaged());
            Parent bar = (Parent) pane.getChildren().get(2);
            assertEquals(1, bar.getChildrenUnmodifiable().size());
            Hyperlink file = (Hyperlink) bar.getChildrenUnmodifiable().get(0);
            assertEquals("报告.md", file.getText());
            file.fire();
            assertEquals(java.util.Arrays.asList(href), opened);
            assertEquals(source, pane.fullText());
            pane.setText("未找到文件", false);
            assertTrue(pane.contentArea().isManaged());
            assertEquals("未找到文件", pane.contentArea().getText());
            assertTrue(links(pane).isEmpty());
        });
    }

    @Test public void eachPageHasOnlyItsLinksAndKeepsEverySourceCharacter() throws Exception {
        onFx(() -> {
            String first = "[第一页](D:/first.pdf)\n\n";
            String source = first + repeat('甲', MessageTextPane.PAGE_CHARS - first.length())
                    + "\n\n[第二页](file:///D:/第二页%20报告.pdf)";
            MessageTextPane pane = new MessageTextPane(source, true);
            assertEquals(1, links(pane).size());
            assertEquals("D:/first.pdf", links(pane).get(0).getUserData());
            press(pane, "下一段");
            assertEquals(1, links(pane).size());
            assertEquals("file:///D:/第二页%20报告.pdf", links(pane).get(0).getUserData());
            press(pane, "上一段");
            assertEquals("D:/first.pdf", links(pane).get(0).getUserData());
            // 复制全文仍使用完整原文，而非当前页/卡片标签；不访问操作系统剪贴板。
            assertEquals(source, pane.fullText());
        });
    }

    @Test public void completeLinkAcrossPageBoundaryIsActionableOnBothPages() throws Exception {
        onFx(() -> {
            String prefix = repeat('甲', MessageTextPane.PAGE_CHARS - 12) + "\n\n";
            String href = "D:/中文路径/带 空格 报告.pdf";
            MessageTextPane pane = new MessageTextPane(prefix + "[跨页报告](<" + href + ">)", true);
            assertEquals(href, links(pane).get(0).getUserData());
            press(pane, "下一段");
            assertEquals(1, links(pane).size());
            assertEquals(href, links(pane).get(0).getUserData());
        });
    }

    @Test public void streamingDefersLinksUntilCompletionAndClearsStaleTargets() throws Exception {
        onFx(() -> {
            MessageTextPane pane = new MessageTextPane("[旧文件](D:/old.pdf)", true);
            assertEquals(1, links(pane).size());
            String complete = "[新文件](<D:/新 报告.pdf>)";
            for (int i = 1; i <= complete.length(); i++) {
                pane.setText(complete.substring(0, i), true);
                assertTrue(links(pane).isEmpty());
            }
            pane.setText(complete, false);
            assertEquals(1, links(pane).size());
            assertEquals("D:/新 报告.pdf", links(pane).get(0).getUserData());
            pane.setText(null, false);
            assertTrue(links(pane).isEmpty());
            assertEquals("", pane.fullText());
        });
    }

    @Test public void linkBarIsBoundedDeduplicatedAndDoesNotTruncateSource() throws Exception {
        onFx(() -> {
            StringBuilder md = new StringBuilder("[首个](D:/same.pdf) [重复](D:/same.pdf)\n\n");
            for (int i = 0; i < MessageTextPane.MAX_PAGE_LINKS + 20; i++) md.append("[文件](D:/").append(i).append(".pdf) ");
            MessageTextPane pane = new MessageTextPane(md.toString(), true);
            assertEquals(MessageTextPane.MAX_PAGE_LINKS, links(pane).size());
            assertEquals("首个", links(pane).get(0).getText());
            assertEquals(md.toString(), pane.fullText());
            assertTrue(pane.contentArea().getText().contains("D:/31.pdf"));
        });
    }

    @Test public void plainModeKeepsRawBodyAndLongLabelDoesNotShortenHref() throws Exception {
        onFx(() -> {
            String href = "file:///D:/" + repeat('路', 200) + "%20报告.pdf";
            String md = "[" + repeat('标', 300) + "](" + href + ")";
            MessageTextPane pane = new MessageTextPane(md);
            List<String> clicked = new ArrayList<String>();
            pane.setLinkHandler(clicked::add, null);
            Hyperlink link = links(pane).get(0);
            assertTrue(link.getText().length() <= 161);
            assertEquals(href, link.getTooltip().getText());
            assertEquals(md, pane.contentArea().getText());
            link.fire();
            assertEquals(java.util.Arrays.asList(href), clicked);
        });
    }

    @Test public void laterPageIsNotStarvedByEarlierExtractionLimit() throws Exception {
        onFx(() -> {
            StringBuilder first = new StringBuilder();
            for (int i = 0; i < MarkdownRenderer.MAX_LINKS + 10; i++) first.append("[早先](D:/").append(i).append(".pdf) ");
            first.append("\n\n");
            first.append(repeat('甲', MessageTextPane.PAGE_CHARS - first.length()));
            String source = first + "\n\n[后页报告](../相对 报告.pdf)";
            MessageTextPane pane = new MessageTextPane(source, true);
            press(pane, "末段");
            assertEquals(1, links(pane).size());
            assertEquals("../相对 报告.pdf", links(pane).get(0).getUserData());
            List<String> opened = new ArrayList<String>();
            pane.setLinkHandler(opened::add, null);
            links(pane).get(0).fire();
            assertEquals(java.util.Arrays.asList("../相对 报告.pdf"), opened);
        });
    }

    @Test public void systemFileCardInPlainModeSupportsLateHandlerInjection() throws Exception {
        onFx(() -> {
            String href = "file:///D:/科室报告/中文%20文件.pdf";
            String card = "【文件】\n[中文 文件.pdf](" + href + ")";
            MessageTextPane pane = new MessageTextPane(card, false);
            List<String> opened = new ArrayList<String>();
            ContextMenu menu = new ContextMenu(new MenuItem("文件菜单"));
            pane.setLinkHandler(opened::add, target -> {
                assertEquals(href, target);
                return menu;
            });
            assertEquals(card, pane.contentArea().getText());
            assertEquals(1, links(pane).size());
            Hyperlink link = links(pane).get(0);
            assertEquals("中文 文件.pdf", link.getText());
            link.fire();
            assertEquals(java.util.Arrays.asList(href), opened);
            requestMenu(link);
            assertSame(menu, link.getContextMenu());
        });
    }

    @Test public void streamFollowTailAndManualPagingRemainUnchanged() throws Exception {
        onFx(() -> {
            MessageTextPane pane = new MessageTextPane("");
            String text = repeat('甲', MessageTextPane.PAGE_CHARS) + "末段";
            pane.setText(text, true);
            assertEquals("末段", pane.contentArea().getText());
            press(pane, "上一段");
            pane.setText(text + "追加", true);
            assertEquals(MessageTextPane.pageText(text, 0), pane.contentArea().getText());
            press(pane, "末段");
            assertEquals("末段追加", pane.contentArea().getText());
            assertTrue(links(pane).isEmpty());
        });
    }
}
