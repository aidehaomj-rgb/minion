package com.minion.gui.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Real miniature OOXML ZIP fixtures are generated in TemporaryFolder, not mocked XML readers. */
public class DocumentPreviewLoaderTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void pptxExtractsSlidesInPresentationOrder() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("ppt/presentation.xml", utf8("<p:presentation xmlns:p=\"" + P + "\" xmlns:r=\"" + R + "\">"
                + "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId1\"/></p:sldIdLst>"
                + "</p:presentation>"));
        entries.put("ppt/_rels/presentation.xml.rels", utf8("<Relationships xmlns=\"" + REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + R + "/slide\" Target=\"slides/slide1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + R + "/slide\" Target=\"slides/slide2.xml\"/>"
                + "</Relationships>"));
        entries.put("ppt/slides/slide1.xml", utf8(slide("第一页标题", "第一页正文")));
        entries.put("ppt/slides/slide2.xml", utf8(slide("第二页标题", "第二页正文")));
        System.setProperty("minion.preview.office.disabled","true");
        DocumentPreviewLoader.Result result;
        try{result=DocumentPreviewLoader.load(zip(entries, false, ".pptx"));}
        finally{System.clearProperty("minion.preview.office.disabled");}
        assertEquals("pptx", result.format);
        assertTrue(result.markdown.indexOf("第二页标题") < result.markdown.indexOf("第一页标题"));
        assertTrue(result.markdown.contains("## 幻灯片 1"));
        assertTrue(result.markdown.contains("## 幻灯片 2"));
        assertTrue(result.notice.contains("文字按幻灯片顺序提取"));
    }

    @Test public void miniatureDocxPreservesBasicStructureAndReturnsSeparateImagesWithoutChangingSource() throws Exception {
        Map<String, byte[]> entries = fixture(
                "<w:p><w:pPr><w:pStyle w:val=\"CustomHeading\"/></w:pPr><w:r><w:t>微型文档</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>普通段落 </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>加粗</w:t></w:r>"
                + "<w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t> 不加粗</w:t></w:r></w:p>"
                + listParagraph("42", 0, "有序项") + listParagraph("43", 1, "无序项")
                + "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"3000\"/></w:tblGrid>"
                + row("名称", "值") + row("甲|乙", "100") + "</w:tbl>"
                + "<w:p>" + drawing("picture") + drawing("picture").replace("wp:docPr id=\"1\"", "wp:docPr id=\"2\"") + "</w:p>");
        entries.put("word/styles.xml", utf8("<w:styles xmlns:w=\"" + W + "\">"
                + "<w:style w:type=\"paragraph\" w:styleId=\"BaseHeading\"><w:pPr><w:outlineLvl w:val=\"1\"/></w:pPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"CustomHeading\"><w:basedOn w:val=\"BaseHeading\"/></w:style>"
                + "</w:styles>"));
        entries.put("word/numbering.xml", utf8(numbering()));
        entries.put("word/_rels/document.xml.rels", utf8(relationships(imageRel("picture", "media/pixel.png", "")
                + "<Relationship Id=\"styles\" Type=\"" + R + "/styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"numbering\" Type=\"" + R + "/numbering\" Target=\"numbering.xml\"/>")));
        byte[] png = png(); entries.put("word/media/pixel.png", png);
        Path path = zip(entries, false);
        byte[] original = Files.readAllBytes(path);
        long modified = Files.getLastModifiedTime(path).toMillis();
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(path);
        assertEquals("docx", result.format);
        assertTrue(result.markdown, result.markdown.contains("## 微型文档"));
        assertTrue(result.markdown.contains("普通段落 **加粗** 不加粗"));
        assertTrue(result.markdown.contains("1. 有序项"));
        assertTrue(result.markdown.contains("  - 无序项"));
        assertTrue(result.markdown.contains("| 名称 | 值 |"));
        assertTrue(result.markdown.contains("| 甲\\|乙 | 100 |"));
        assertTrue(result.markdown.contains("见附图列表"));
        assertFalse(result.markdown.contains("!["));
        assertFalse(result.truncated);
        assertEquals(1, result.images.size());
        assertEquals("word/media/pixel.png", result.images.get(0).name);
        assertArrayEquals(png, result.images.get(0).bytes);
        assertTrue(result.notice.contains("并非 Word 分页完全保真"));
        assertArrayEquals(original, Files.readAllBytes(path));
        assertEquals(modified, Files.getLastModifiedTime(path).toMillis());
        try { result.images.clear(); fail("immutable list"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void supportsStrictOoxmlNamespacesAndHeadingFallback() throws Exception {
        Map<String, byte[]> entries = fixture("<w:p><w:pPr><w:pStyle w:val=\"Heading3\"/></w:pPr><w:r><w:t>标题</w:t></w:r></w:p>");
        entries.put("word/document.xml", utf8(new String(entries.get("word/document.xml"), StandardCharsets.UTF_8)
                .replace(W, "http://purl.oclc.org/ooxml/wordprocessingml/main")
                .replace(R, "http://purl.oclc.org/ooxml/officeDocument/relationships")));
        assertTrue(DocumentPreviewLoader.load(zip(entries, false)).markdown.startsWith("### 标题"));
    }

    @Test(timeout = 8000) public void doctypeInEveryConsumedXmlPartIsRejectedWithoutAnyHttpRequest() throws Exception {
        try (HttpProbe probe = new HttpProbe()) {
            String[] parts = {"word/document.xml", "word/styles.xml", "word/numbering.xml", "word/_rels/document.xml.rels"};
            for (String part : parts) {
                Map<String, byte[]> entries = fixture(paragraph("安全正文"));
                entries.put(part, utf8("<?xml version=\"1.0\"?><!DOCTYPE root SYSTEM \"" + probe.url()
                        + "\" [<!ENTITY steal SYSTEM \"" + probe.url() + "\">]><root>&steal;</root>"));
                expectFailure(zip(entries, false), "DOCTYPE");
            }
            assertEquals("must not connect even once", 0, probe.requests.get());
        }
    }

    @Test public void localFileEntitiesAndInternalEntityExpansionAreRejected() throws Exception {
        Path secret = file(".txt", utf8("PRIVATE-CONTENT-NOT-FOR-PREVIEW"));
        Map<String, byte[]> entries = fixture("");
        entries.put("word/document.xml", utf8(document(paragraph("&leak;")).replace("<w:document",
                "<!DOCTYPE w:document [<!ENTITY leak SYSTEM \"" + secret.toUri().toASCIIString() + "\">]><w:document")));
        expectFailure(zip(entries, false), "DOCTYPE");
        entries.put("word/document.xml", utf8(document(paragraph("&b;")).replace("<w:document",
                "<!DOCTYPE w:document [<!ENTITY a 'abc'><!ENTITY b '&a;&a;&a;'>]><w:document")));
        expectFailure(zip(entries, false), "DOCTYPE");
    }

    @Test(timeout = 8000) public void hyperlinksExternalImagesAndDisguisedLocalTargetsAreNeverResolved() throws Exception {
        try (HttpProbe probe = new HttpProbe()) {
            String body = "<w:p><w:hyperlink r:id=\"link\"><w:r><w:t>只保留链接文字</w:t></w:r></w:hyperlink>"
                    + drawing("remote") + drawing("disguised") + drawing("local") + drawing("traversal")
                    + "<w:r><w:drawing><a:blip r:link=\"remote\"/></w:drawing></w:r></w:p>"
                    + "<w:altChunk r:id=\"chunk\"/><w:p><w:r><w:object><w:t>不执行对象</w:t></w:object>"
                    + "<w:instrText>INCLUDETEXT " + probe.url() + "</w:instrText></w:r></w:p>";
            Map<String, byte[]> entries = fixture(body);
            entries.put("word/_rels/document.xml.rels", utf8(relationships(
                    imageRel("remote", probe.url(), "External") + imageRel("disguised", probe.url(), "")
                    + imageRel("local", "file:///C:/private/secret.png", "")
                    + imageRel("traversal", "%2e%2e/%2e%2e/private.png", "")
                    + "<Relationship Id=\"link\" Type=\"" + R + "/hyperlink\" Target=\"" + probe.url() + "\" TargetMode=\"External\"/>")));
            DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(zip(entries, false));
            assertTrue(result.markdown.contains("只保留链接文字"));
            assertFalse(result.markdown.contains("http:"));
            assertFalse(result.markdown.contains("private"));
            assertFalse(result.markdown.contains("不执行对象"));
            assertTrue(result.images.isEmpty());
            assertTrue(result.notice.contains("外部"));
            assertEquals(0, probe.requests.get());
        }
    }

    @Test public void missingFileAndDirectoryAndNullHaveExplicitIoFailure() throws Exception {
        expectFailure(temporary.getRoot().toPath().resolve("missing.docx"), "missing.docx");
        expectFailure(temporary.getRoot().toPath(), "不是普通文件");
        expectFailure(null, "未提供");
    }

    @Test public void legacyDocNeverDecodesBinaryAndUnknownFormatIsNotGuessedAsText() throws Exception {
        byte[] ole = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1, 0, 1};
        System.setProperty("minion.preview.office.disabled", "true");
        DocumentPreviewLoader.Result result;
        try {
            result = DocumentPreviewLoader.load(file(".DOC", ole));
        } finally {
            System.clearProperty("minion.preview.office.disabled");
        }
        assertEquals("doc", result.format);
        assertEquals("", result.markdown);
        assertTrue(result.notice.contains("无法直接预览"));
        assertTrue(result.notice.contains("Word/WPS"));
        assertTrue(result.images.isEmpty());
        result = DocumentPreviewLoader.load(file(".unknown", utf8("not a supported preview")));
        assertEquals("", result.markdown);
        assertTrue(result.notice.contains("不支持"));
    }

    @Test public void encryptedCompoundDocxAndEncryptedZipHaveActionableFailures() throws Exception {
        expectFailure(file(".docx", new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, 0, 0, 0, 0}), "加密");
        Path path = zip(fixture(paragraph("正文")), false);
        byte[] bytes = Files.readAllBytes(path);
        for (int i = 0; i + 46 < bytes.length; i++) {
            if (signature(bytes, i, 0x04034b50)) bytes[i + 6] |= 1;
            if (signature(bytes, i, 0x02014b50)) bytes[i + 8] |= 1;
        }
        Files.write(path, bytes);
        expectFailure(path, "加密");
    }

    @Test public void corruptZipMissingBodyMalformedXmlAndWrongNamespaceFailClearly() throws Exception {
        expectFailure(file(".docx", utf8("not a zip")), "损坏");
        Map<String, byte[]> entries = fixture(""); entries.remove("word/document.xml");
        expectFailure(zip(entries, false), "缺少正文");
        entries.put("word/document.xml", utf8("<broken>")); expectFailure(zip(entries, false), "XML");
        entries.put("word/document.xml", utf8("<document><body/></document>")); expectFailure(zip(entries, false), "命名空间");
    }

    @Test public void crcMismatchIsNotSilentlyDisplayed() throws Exception {
        Path path = zip(fixture(paragraph("checksum")), true);
        byte[] bytes = Files.readAllBytes(path);
        byte[] marker = utf8("checksum");
        int offset = find(bytes, marker); assertTrue(offset >= 0); bytes[offset] = 'X';
        Files.write(path, bytes);
        expectFailure(path, "CRC");
    }

    @Test public void zipBombCompressionRatioEntryCountAndTraversalAreRejected() throws Exception {
        Map<String, byte[]> entries = fixture(paragraph(repeat('x', 200_000)));
        expectFailure(zip(entries, false), "ZIP 炸弹");
        entries = fixture(paragraph("安全"));
        for (int i = 0; i < DocumentPreviewLoader.MAX_ZIP_ENTRIES; i++) entries.put("extra/" + i, new byte[0]);
        expectFailure(zip(entries, false), "2048");
        entries = fixture(""); entries.put("../escape", new byte[0]);
        expectFailure(zip(entries, false), "不安全路径");
    }

    @Test public void zipEntryAndAggregateExpandedSizeAreCheckedBeforeReading() throws Exception {
        Path path = zip(fixture(paragraph("safe")), true);
        byte[] bytes = Files.readAllBytes(path);
        patchUncompressedSize(bytes, "word/document.xml", DocumentPreviewLoader.MAX_ZIP_ENTRY_BYTES + 1);
        Files.write(path, bytes); expectFailure(path, "ZIP 炸弹");
        Map<String, byte[]> entries = fixture(paragraph("safe"));
        byte[] random = new byte[65536]; new Random(7).nextBytes(random);
        for (int i = 0; i < 5; i++) entries.put("extra/" + i, random);
        path = zip(entries, true); bytes = Files.readAllBytes(path);
        for (int i = 0; i < 5; i++) patchUncompressedSize(bytes, "extra/" + i, 7 * 1024 * 1024);
        Files.write(path, bytes); expectFailure(path, "解压总量");
    }

    @Test public void archiveFileSizeAndXmlByteDepthAndNodeBudgetsAreEnforced() throws Exception {
        Path path = file(".docx", new byte[0]);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) { file.setLength(DocumentPreviewLoader.MAX_DOCX_BYTES + 1); }
        expectFailure(path, "32 MiB");
        Map<String, byte[]> entries = fixture(paragraph(repeat('x', DocumentPreviewLoader.MAX_XML_BYTES)));
        expectFailure(zip(entries, true), "条目超过读取限制");
        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 70; i++) nested.append("<w:sdt>");
        for (int i = 0; i < 70; i++) nested.append("</w:sdt>");
        expectFailure(zip(fixture(nested.toString()), true), "过深/过多节点");
        StringBuilder nodes = new StringBuilder(); for (int i = 0; i < 50_001; i++) nodes.append("<w:p/>");
        expectFailure(zip(fixture(nodes.toString()), true), "过深/过多节点");
    }

    @Test public void markdownIsPreservedAndCommonTextFormatsAreBoundedLiteralBlocks() throws Exception {
        String original = "# 原文\r\n**强调**\n原样 `代码`\n";
        Path path = file(".MARKDOWN", utf8(original)); byte[] before = Files.readAllBytes(path);
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(path);
        assertEquals("markdown", result.format); assertEquals(original, result.markdown); assertFalse(result.truncated);
        assertArrayEquals(before, Files.readAllBytes(path));
        for (String format : Arrays.asList("txt", "csv", "json", "tsv", "yaml", "xml", "log")) {
            result = DocumentPreviewLoader.load(file("." + format, utf8("原样<内容>\r\n```\n~~~\n")));
            assertEquals(format, result.format); assertTrue(result.markdown.contains("原样<内容>\r\n```\n~~~\n"));
            assertTrue(result.markdown.length() <= DocumentPreviewLoader.MAX_MARKDOWN_CHARS);
            assertFalse(result.truncated); assertTrue(result.images.isEmpty());
        }
        assertEquals("", DocumentPreviewLoader.load(file(".txt", new byte[0])).markdown);
    }
    @Test public void htmlIsReturnedAsOfflineSourceInsteadOfACodeBlock()throws Exception{
        String source="<html><body><h1>报告</h1><script>alert(1)</script></body></html>";
        DocumentPreviewLoader.Result result=DocumentPreviewLoader.load(file(".html",utf8(source)));
        assertEquals("html",result.format);
        assertEquals(source,result.sourceText);
        assertFalse(result.markdown.startsWith("```html"));
        assertTrue(result.notice.contains("离线安全视图"));
    }

    @Test public void acceptsBomUtf8AndUtf16ButRejectsBinaryAndMalformedText() throws Exception {
        byte[] text = utf8("中文"); byte[] bom = new byte[text.length + 3];
        bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf; System.arraycopy(text, 0, bom, 3, text.length);
        assertEquals("中文", DocumentPreviewLoader.load(file(".md", bom)).markdown);
        assertEquals("中文", DocumentPreviewLoader.load(file(".md", "中文".getBytes(StandardCharsets.UTF_16))).markdown);
        byte[] little = "\ufeff中文".getBytes(StandardCharsets.UTF_16LE);
        assertEquals("中文", DocumentPreviewLoader.load(file(".md", little)).markdown);
        expectFailure(file(".txt", new byte[]{0, 1, 2, 3}), "二进制");
        expectFailure(file(".md", new byte[]{(byte) 0xc3, 0x28}), "UTF-8");
        expectFailure(file(".md", new byte[]{(byte) 0xe4, (byte) 0xb8}), "UTF-8");
    }

    @Test(timeout = 8000) public void sourceCodeStaysLiteralAndHtmlSourceNeverTriggersNetworkDuringExtraction() throws Exception {
        try (HttpProbe probe = new HttpProbe()) {
            String source = "<script src=\"" + probe.url() + "\">throw 'MUST NOT EXECUTE';</script>\n"
                    + "<img src=\"" + probe.url() + "\"><svg><image href=\"" + probe.url() + "\"/></svg>\n"
                    + "fetch('" + probe.url() + "');";
            for (String format : Arrays.asList("py", "java", "svg", "js", "ts", "css", "sql", "sh", "ps1", "go", "rs")) {
                DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(file("." + format, utf8(source)));
                assertEquals(format, result.format);
                assertTrue(result.markdown.startsWith("```" + format + "\n"));
                assertTrue(result.markdown.contains(source));
                assertTrue(result.markdown.endsWith("\n```"));
                assertTrue(result.images.isEmpty()); assertFalse(result.truncated);
            }
            DocumentPreviewLoader.Result html=DocumentPreviewLoader.load(file(".html",utf8(source)));
            assertEquals("html",html.format);assertEquals(source,html.sourceText);
            assertTrue(html.images.isEmpty());assertTrue(html.notice.contains("离线安全视图"));
            assertEquals(0, probe.requests.get());
        }
    }

    @Test public void exactCharacterBoundaryAndOneOverAndSurrogateCutoffAreCorrect() throws Exception {
        int limit = DocumentPreviewLoader.MAX_MARKDOWN_CHARS;
        String exact = repeat('a', limit);
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(file(".md", utf8(exact)));
        assertEquals(exact, result.markdown); assertFalse(result.truncated);
        result = DocumentPreviewLoader.load(file(".md", utf8(exact + "z")));
        assertEquals(limit, result.markdown.length()); assertTrue(result.truncated); assertTrue(result.notice.contains("截断"));
        result = DocumentPreviewLoader.load(file(".md", utf8(repeat('a', limit - 1) + "\ud83d\ude00tail")));
        assertEquals(limit - 1, result.markdown.length()); assertTrue(result.truncated);
        assertWellFormedSurrogates(result.markdown);
        result = DocumentPreviewLoader.load(file(".txt", utf8(exact + "tail")));
        assertTrue(result.truncated); assertTrue(result.markdown.length() <= limit);
        assertTrue(result.markdown.endsWith("```"));
    }

    @Test public void hugeTextReadsOnlyPrefixAndIncompleteUtf8AtByteBoundaryIsNotCorruption() throws Exception {
        int limit = DocumentPreviewLoader.MAX_TEXT_BYTES;
        Path path = file(".md", utf8(repeat('a', limit - 1) + "中文尾部"));
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(path);
        assertTrue(result.truncated); assertTrue(result.notice.contains("1 MiB"));
        assertFalse(result.markdown.contains("\ufffd"));
        assertEquals(DocumentPreviewLoader.MAX_MARKDOWN_CHARS, result.markdown.length());
        path = file(".md", utf8(repeat('a', limit)));
        result = DocumentPreviewLoader.load(path);
        assertTrue(result.truncated); assertFalse(result.notice.contains("1 MiB"));
        path = file(".txt", utf8(repeat('b', 3 * limit)));
        result = DocumentPreviewLoader.load(path);
        assertTrue(result.truncated); assertTrue(result.notice.contains("1 MiB"));
        assertTrue(result.markdown.length() <= DocumentPreviewLoader.MAX_MARKDOWN_CHARS);
    }

    @Test public void extremelyLongCodeFencesStillRespectOutputLimit() throws Exception {
        String text = repeat('`', 100_000) + "\n" + repeat('~', 100_000) + "\nend";
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(file(".txt", utf8(text)));
        assertTrue(result.truncated); assertTrue(result.markdown.startsWith("    "));
        assertTrue(result.markdown.length() <= DocumentPreviewLoader.MAX_MARKDOWN_CHARS);
    }

    @Test public void docxOutputAndLargeTableRowsColumnsAndCellsAreCapped() throws Exception {
        StringBuilder body = new StringBuilder("<w:tbl>");
        for (int row = 0; row < 105; row++) {
            body.append("<w:tr>");
            for (int column = 0; column < 23; column++) body.append("<w:tc>").append(paragraph("R" + row + "C" + column)).append("</w:tc>");
            body.append("</w:tr>");
        }
        body.append("</w:tbl>").append(paragraph("表格后正文"));
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(zip(fixture(body.toString()), true));
        assertTrue(result.truncated); assertTrue(result.notice.contains("100 行、20 列"));
        assertTrue(result.markdown.contains("R99C19")); assertFalse(result.markdown.contains("R100C0"));
        assertFalse(result.markdown.contains("R0C20")); assertTrue(result.markdown.contains("表格后正文"));
        result = DocumentPreviewLoader.load(zip(fixture("<w:tbl>" + row(repeat('c', 5000), "last") + "</w:tbl>"), true));
        assertTrue(result.truncated); assertFalse(result.markdown.contains(repeat('c', 4001)));
        result = DocumentPreviewLoader.load(zip(fixture(paragraph(repeat('p', 250_000))), true));
        assertTrue(result.truncated); assertTrue(result.markdown.length() <= DocumentPreviewLoader.MAX_MARKDOWN_CHARS);
    }

    @Test public void nestedTablesAreExplicitlyOmittedAndSourceRemainsAvailable() throws Exception {
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(zip(fixture("<w:tbl><w:tr><w:tc>"
                + paragraph("外表格") + "<w:tbl>" + row("内表格") + "</w:tbl></w:tc></w:tr></w:tbl>"), true));
        assertTrue(result.truncated); assertTrue(result.notice.contains("嵌套表格")); assertTrue(result.markdown.contains("外表格"));
    }

    @Test public void imageCountLimitIsEightAndDuplicateReferencesDoNotConsumeIt() throws Exception {
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(images(8, png(), false));
        assertEquals(8, result.images.size()); assertFalse(result.truncated);
        result = DocumentPreviewLoader.load(images(9, png(), false));
        assertEquals(8, result.images.size()); assertTrue(result.truncated); assertTrue(result.notice.contains("最多 8 张"));
    }

    @Test public void perImageAndTotalBytesAreBoundedIncludingExactBoundary() throws Exception {
        byte[] padded = Arrays.copyOf(png(), DocumentPreviewLoader.MAX_IMAGE_BYTES);
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(images(4, padded, true));
        assertEquals(4, result.images.size()); assertFalse(result.truncated);
        long total = 0; for (DocumentPreviewLoader.EmbeddedImage image : result.images) total += image.bytes.length;
        assertEquals(DocumentPreviewLoader.MAX_TOTAL_IMAGE_BYTES, total);
        result = DocumentPreviewLoader.load(images(5, padded, true));
        assertEquals(4, result.images.size()); assertTrue(result.truncated);
        result = DocumentPreviewLoader.load(images(1, Arrays.copyOf(png(), DocumentPreviewLoader.MAX_IMAGE_BYTES + 1), true));
        assertTrue(result.images.isEmpty()); assertTrue(result.truncated); assertTrue(result.notice.contains("单图 2 MiB"));
    }

    @Test(timeout = 8000) public void tinyPngWithGiantDimensionsIsRejectedFromMetadataWithoutPixelDecode() throws Exception {
        byte[] giant = pngDimensions(png(), 100_000, 100_000);
        assertTrue(giant.length < 1024);
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(images(1, giant, true));
        assertTrue(result.images.isEmpty()); assertTrue(result.truncated); assertTrue(result.notice.contains("16MP"));
        result = DocumentPreviewLoader.load(images(1, pngDimensions(png(), 8193, 1), true));
        assertTrue(result.images.isEmpty());
        result = DocumentPreviewLoader.load(images(1, pngDimensions(png(), 4001, 4000), true));
        assertTrue(result.images.isEmpty());
        result = DocumentPreviewLoader.load(images(1, pngDimensions(png(), 4000, 4000), true));
        assertEquals("metadata-only inspection must not allocate/decode a 16MP raster", 1, result.images.size());
    }

    @Test public void malformedImagesAndActiveSvgAreNotReturnedAsSafeImages() throws Exception {
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(images(1, utf8("broken image"), true));
        assertTrue(result.images.isEmpty()); assertTrue(result.truncated);
        result = DocumentPreviewLoader.load(images(1, utf8("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>danger()</script></svg>"), true));
        assertTrue(result.images.isEmpty()); assertTrue(result.truncated);
    }

    @Test public void staticGifIsAcceptedButMultiFrameGifIsOmittedWithoutDecodingPixels() throws Exception {
        BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(pixel, "gif", out));
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(images(1, out.toByteArray(), true));
        assertEquals(1, result.images.size()); assertFalse(result.truncated);
        out.reset();
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (javax.imageio.stream.MemoryCacheImageOutputStream stream = new javax.imageio.stream.MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream); writer.prepareWriteSequence(null);
            writer.writeToSequence(new javax.imageio.IIOImage(pixel, null, null), null);
            writer.writeToSequence(new javax.imageio.IIOImage(pixel, null, null), null);
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        result = DocumentPreviewLoader.load(images(1, out.toByteArray(), true));
        assertTrue(result.images.isEmpty()); assertTrue(result.truncated);
    }

    @Test public void embeddedImageConstructorDefensivelyCopiesAndResultCopiesList() {
        byte[] bytes = {1, 2};
        DocumentPreviewLoader.EmbeddedImage image = new DocumentPreviewLoader.EmbeddedImage("pixel.png", bytes);
        bytes[0] = 99; assertEquals(1, image.bytes[0]);
        List<DocumentPreviewLoader.EmbeddedImage> images = new ArrayList<DocumentPreviewLoader.EmbeddedImage>(); images.add(image);
        DocumentPreviewLoader.Result result = new DocumentPreviewLoader.Result("", "", false, "docx", images);
        images.clear(); assertEquals(1, result.images.size());
    }

    private Path images(int count, byte[] bytes, boolean stored) throws Exception {
        StringBuilder body = new StringBuilder("<w:p>"), rels = new StringBuilder();
        for (int i = 0; i < count; i++) { body.append(drawing("p" + i)); rels.append(imageRel("p" + i, "media/" + i + ".png", "")); }
        body.append("</w:p>");
        Map<String, byte[]> entries = fixture(body.toString());
        entries.put("word/_rels/document.xml.rels", utf8(relationships(rels.toString())));
        for (int i = 0; i < count; i++) entries.put("word/media/" + i + ".png", bytes);
        return zip(entries, stored);
    }

    private Path file(String extension, byte[] bytes) throws IOException {
        Path path = temporary.newFile("fixture-" + System.nanoTime() + extension).toPath();
        Files.write(path, bytes); return path;
    }

    private Path zip(Map<String, byte[]> entries, boolean stored) throws IOException {
        return zip(entries, stored, ".docx");
    }

    private Path zip(Map<String, byte[]> entries, boolean stored, String extension) throws IOException {
        Path path = file(extension, new byte[0]);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey());
                if (stored) {
                    CRC32 crc = new CRC32(); crc.update(item.getValue());
                    entry.setMethod(ZipEntry.STORED); entry.setSize(item.getValue().length); entry.setCompressedSize(item.getValue().length); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(item.getValue()); zip.closeEntry();
            }
        }
        return path;
    }

    private static Map<String, byte[]> fixture(String body) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("[Content_Types].xml", utf8("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>"));
        entries.put("_rels/.rels", utf8(relationships("<Relationship Id=\"document\" Type=\"" + R + "/officeDocument\" Target=\"word/document.xml\"/>")));
        entries.put("word/document.xml", utf8(document(body)));
        return entries;
    }

    private static String document(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R
                + "\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>" + body + "<w:sectPr/></w:body></w:document>";
    }

    private static String paragraph(String text) { return "<w:p><w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>"; }
    private static String slide(String title, String body) {
        return "<p:sld xmlns:p=\"" + P + "\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + "<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>" + title
                + "</a:t></a:r></a:p><a:p><a:r><a:t>" + body
                + "</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>";
    }
    private static String listParagraph(String id, int depth, String text) {
        return "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"" + depth + "\"/><w:numId w:val=\"" + id
                + "\"/></w:numPr></w:pPr><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }
    private static String row(String... cells) {
        StringBuilder row = new StringBuilder("<w:tr>");
        for (String cell : cells) row.append("<w:tc>").append(paragraph(cell)).append("</w:tc>");
        return row.append("</w:tr>").toString();
    }
    private static String drawing(String id) {
        return "<w:r><w:drawing><wp:inline><wp:extent cx=\"9525\" cy=\"9525\"/><wp:docPr id=\"1\" name=\"pixel\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
                + "<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"pixel.png\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"" + id + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
    }
    private static String relationships(String body) { return "<Relationships xmlns=\"" + REL + "\">" + body + "</Relationships>"; }
    private static String imageRel(String id, String target, String mode) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + R + "/image\" Target=\"" + target + "\""
                + (mode.isEmpty() ? "" : " TargetMode=\"" + mode + "\"") + "/>";
    }
    private static String numbering() {
        return "<w:numbering xmlns:w=\"" + W + "\"><w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/>"
                + "</w:lvl></w:abstractNum><w:abstractNum w:abstractNumId=\"2\"><w:lvl w:ilvl=\"1\"><w:numFmt w:val=\"bullet\"/>"
                + "</w:lvl></w:abstractNum><w:num w:numId=\"42\"><w:abstractNumId w:val=\"1\"/></w:num>"
                + "<w:num w:numId=\"43\"><w:abstractNumId w:val=\"2\"/></w:num></w:numbering>";
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out));
        return out.toByteArray();
    }
    private static byte[] pngDimensions(byte[] png, int width, int height) {
        byte[] bytes = png.clone(); bigEndian(bytes, 16, width); bigEndian(bytes, 20, height);
        CRC32 crc = new CRC32(); crc.update(bytes, 12, 17); bigEndian(bytes, 29, (int) crc.getValue());
        return bytes;
    }
    private static void bigEndian(byte[] bytes, int offset, int value) { for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> ((3 - i) * 8)); }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String repeat(char c, int count) { char[] chars = new char[count]; Arrays.fill(chars, c); return new String(chars); }
    private static void assertWellFormedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) assertTrue(++i < value.length() && Character.isLowSurrogate(value.charAt(i)));
            else assertFalse(Character.isLowSurrogate(c));
        }
    }
    private static void expectFailure(Path path, String message) throws IOException {
        try { DocumentPreviewLoader.load(path); fail("Expected IOException: " + message); }
        catch (IOException expected) { assertTrue(expected.toString(), expected.getMessage().contains(message)); }
    }
    private static boolean signature(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) if ((bytes[offset + i] & 255) != ((value >>> (i * 8)) & 255)) return false;
        return true;
    }
    private static int find(byte[] bytes, byte[] target) {
        outer: for (int i = 0; i <= bytes.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (bytes[i + j] != target[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static void patchUncompressedSize(byte[] bytes, String name, int size) {
        byte[] target = utf8(name);
        for (int i = 0; i + 46 + target.length <= bytes.length; i++) if (signature(bytes, i, 0x02014b50)) {
            if ((bytes[i + 28] & 255) + ((bytes[i + 29] & 255) << 8) != target.length) continue;
            boolean match = true;
            for (int j = 0; j < target.length; j++) if (bytes[i + 46 + j] != target[j]) { match = false; break; }
            if (match) { for (int j = 0; j < 4; j++) bytes[i + 24 + j] = (byte) (size >>> (j * 8)); return; }
        }
        fail("Missing central directory entry: " + name);
    }

    /** A real loopback listener: a broken parser would connect, get a reply, and fail the zero-request assertion. */
    private static final class HttpProbe implements java.io.Closeable {
        final AtomicInteger requests = new AtomicInteger();
        final ServerSocket server; final Thread worker;
        HttpProbe() throws IOException {
            server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(new Runnable() {
                @Override public void run() {
                    while (!server.isClosed()) {
                        try (Socket socket = server.accept()) {
                            requests.incrementAndGet();
                            OutputStream out = socket.getOutputStream();
                            out.write(utf8("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")); out.flush();
                        } catch (IOException ignored) { if (!server.isClosed()) requests.incrementAndGet(); }
                    }
                }
            }, "docx-no-network-probe");
            worker.setDaemon(true); worker.start();
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/must-not-be-requested"; }
        @Override public void close() throws IOException {
            server.close();
            try { worker.join(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("probe interrupted"); }
        }
    }
}
