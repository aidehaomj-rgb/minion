package com.minion.gui.chat;

import com.minion.gui.chat.MarkdownRenderer.Block;
import com.minion.gui.chat.MarkdownRenderer.LinkRef;
import com.minion.gui.chat.MarkdownRenderer.Span;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MarkdownRendererLinksTest {
    @Test public void oldSpanConstructorRemainsNonLink() {
        Span span = new Span("正文", "bold");
        assertNull(span.href);
        assertEquals("正文", span.text);
        assertEquals("bold", span.style);
    }

    @Test public void rawChineseSpaceAndFileUriTargetsSurvive() {
        String path = "D:/科室工作/报告 终稿(2).pdf";
        String uri = "file:///D:/报告%20目录/中文.pdf#page=2";
        String md = "[报告](<" + path + ">) 和 [URI](" + uri + ")";
        List<LinkRef> links = MarkdownRenderer.links(md);
        assertEquals(2, links.size());
        assertEquals("报告", links.get(0).label);
        assertEquals(path, links.get(0).href);
        assertEquals(uri, links.get(1).href);
        assertEquals(path, MarkdownRenderer.parse(md).get(0).spans.get(0).href);
        assertEquals("报告（" + path + "） 和 URI（" + uri + "）", MarkdownRenderer.toPlainText(md));
        assertEquals(path, MarkdownRenderer.links("[报告](" + path + ")").get(0).href);
    }

    @Test public void styledLabelKeepsEverySpanHrefAndOnePlainTarget() {
        String href = "D:/报告 终稿.pdf";
        String md = "[**加粗** 和 *斜体* `文件`](<" + href + ">)";
        Block block = MarkdownRenderer.parse(md).get(0);
        assertEquals("加粗 和 斜体 文件", block.text);
        assertEquals("bold", block.spans.get(0).style);
        assertTrue(block.spans.get(block.spans.size() - 1).style.contains("code"));
        for (Span span : block.spans) assertEquals(href, span.href);
        assertEquals("加粗 和 斜体 文件（" + href + "）", MarkdownRenderer.toPlainText(md));
        assertEquals("加粗 和 斜体 文件", MarkdownRenderer.links(md).get(0).label);
    }

    @Test public void headingsListsQuotesAndTableCellsRetainTargets() {
        String md = "# [标题](D:/标题.pdf)\n\n- [清单](D:/清单.pdf)\n\n> [引用](D:/引用.pdf)"
                + "\n\n| [表头](D:/表头.pdf) | 列 |\n|---|---|\n| [单元格](D:/单元格.pdf) | 内容 |";
        List<Block> blocks = MarkdownRenderer.parse(md);
        assertEquals("D:/标题.pdf", blocks.get(0).spans.get(0).href);
        assertEquals("D:/清单.pdf", blocks.get(1).items.get(0).spans.get(0).href);
        assertEquals("D:/引用.pdf", blocks.get(2).spans.get(0).href);
        assertEquals("表头", blocks.get(3).rows.get(0).cells.get(0));
        assertEquals("D:/表头.pdf", blocks.get(3).rows.get(0).cellSpans.get(0).get(0).href);
        assertEquals("标题（D:/标题.pdf）\n\n• 清单（D:/清单.pdf）\n\n引用（D:/引用.pdf）\n\n"
                + "表头（D:/表头.pdf） | 列\n单元格（D:/单元格.pdf） | 内容", MarkdownRenderer.toPlainText(md));
        assertEquals(5, MarkdownRenderer.links(md).size());
    }

    @Test public void referencesAutoLinksAndOptionalTitlesAreSupported() {
        String md = "[参考][report] 和 <file:///D:/中文.pdf> 和 [标题](<D:/有 空格.pdf> \"提示\")\n\n"
                + "[report]: <D:/参考 报告.pdf> \"说明\"";
        List<LinkRef> links = MarkdownRenderer.links(md);
        assertEquals(3, links.size());
        assertEquals("D:/参考 报告.pdf", links.get(0).href);
        assertEquals("file:///D:/中文.pdf", links.get(1).href);
        assertEquals("D:/有 空格.pdf", links.get(2).href);
        assertTrue(MarkdownRenderer.toPlainText(md).contains("参考（D:/参考 报告.pdf）"));
    }

    @Test public void codeImagesEscapedAndIncompleteLinksAreNotActionable() {
        String md = "`[代码](D:/代码.pdf)`\n\n```md\n[围栏](D:/围栏.pdf)\n```\n\n"
                + "    [缩进代码](D:/缩进.pdf)\n\n![图片](D:/图片.png)\n\n"
                + "\\[转义](D:/转义.pdf)\n\n[未完成](D:/未完成.pdf";
        assertTrue(MarkdownRenderer.links(md).isEmpty());
        assertTrue(MarkdownRenderer.toPlainText(md).contains("[代码](D:/代码.pdf)"));
        assertTrue(MarkdownRenderer.toPlainText(md).contains("[围栏](D:/围栏.pdf)"));
    }

    @Test public void deduplicatesByExactHrefAndBoundsResults() {
        StringBuilder md = new StringBuilder("[首个](D:/same.pdf) [别名](D:/same.pdf)\n\n");
        for (int i = 0; i < MarkdownRenderer.MAX_LINKS + 20; i++) md.append("[文件](D:/").append(i).append(".pdf) ");
        List<LinkRef> links = MarkdownRenderer.links(md.toString());
        assertEquals(MarkdownRenderer.MAX_LINKS, links.size());
        assertEquals("首个", links.get(0).label);
        assertEquals("D:/same.pdf", links.get(0).href);
        assertEquals("D:/126.pdf", links.get(links.size() - 1).href);
        assertTrue(MarkdownRenderer.links(null).isEmpty());
        assertTrue(MarkdownRenderer.links("").isEmpty());
    }

    @Test public void hrefsAreNotDecodedNormalizedOrSchemeFiltered() {
        String[] targets = {"file:///D:/A%20B/%E4%B8%AD.pdf", "D:/目录/../报告.pdf", "javascript:alert(1)",
                "../相对 文件.pdf", "D:\\工作 目录\\报告.xlsx"};
        for (String href : targets) {
            String md = "[文件](<" + href + ">)";
            assertEquals(href, MarkdownRenderer.links(md).get(0).href);
            assertEquals(href, MarkdownRenderer.parse(md).get(0).spans.get(0).href);
        }
    }

    @Test public void emptyLabelsStillPreserveTheLinkTarget() {
        String md = "[](D:/报告.pdf)";
        assertEquals("D:/报告.pdf", MarkdownRenderer.parse(md).get(0).spans.get(0).href);
        assertEquals("D:/报告.pdf", MarkdownRenderer.links(md).get(0).href);
        assertTrue(MarkdownRenderer.toPlainText(md).contains("D:/报告.pdf"));
    }

    @Test public void rangeFilteringPrecedesDeduplicationAndLimit() {
        String prefix = "[上一页](D:/same.pdf) [其他](D:/other.pdf)\n\n";
        String current = "[本页](D:/same.pdf) [下个](D:/next.pdf)";
        List<LinkRef> links = MarkdownRenderer.links(prefix + current, prefix.length(), prefix.length() + current.length(), 1);
        assertEquals(1, links.size());
        assertEquals("本页", links.get(0).label);
        assertEquals("D:/same.pdf", links.get(0).href);
    }
}
