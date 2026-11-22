package com.minion.gui.artifact;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Assume;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class PagedPreviewUpgradeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void pdfUsesPagedBackendAndExtractsText() throws Exception {
        Path pdf = pdf(3);
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(pdf);
        assertEquals("pdf", result.format);
        assertEquals(pdf.toAbsolutePath(), result.previewPdf);
        assertTrue(result.images.isEmpty());
        try (PagedPdfPreview preview = new PagedPdfPreview(result.previewPdf)) {
            assertEquals(3, preview.pageCount());
            assertNotNull(preview.renderPage(1, 640));
            assertTrue(preview.pageText(1).contains("Page 2"));
            assertTrue(preview.fullText(100_000).contains("Page 3"));
        }
    }

    @Test public void xlsxReadsMultipleBoundedSheets() throws Exception {
        Path file = xlsx();
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(file);
        assertEquals("xlsx", result.format);
        assertEquals(2, result.sheets.size());
        assertEquals("Summary", result.sheets.get(0).name);
        assertEquals("Name", result.sheets.get(0).rows.get(0).get(0));
        assertEquals("Alice", result.sheets.get(0).rows.get(0).get(1));
        assertEquals("42", result.sheets.get(0).rows.get(1).get(0));
        assertEquals("7", result.sheets.get(0).rows.get(1).get(1));
        assertEquals("Details", result.sheets.get(1).name);
        assertEquals("Ready", result.sheets.get(1).rows.get(0).get(0));
    }

    @Test public void pptxFallbackCarriesPerSlideCardsAndImages() throws Exception {
        Path file = pptx();
        System.setProperty("minion.preview.office.disabled", "true");
        DocumentPreviewLoader.Result result;
        try {
            result = DocumentPreviewLoader.load(file);
        } finally {
            System.clearProperty("minion.preview.office.disabled");
        }
        assertEquals("pptx", result.format);
        assertEquals(2, result.slides.size());
        assertEquals("第一页", result.slides.get(0).title);
        assertTrue(result.slides.get(0).lines.contains("第一页正文"));
        assertEquals(1, result.slides.get(0).images.size());
        assertEquals("第二页", result.slides.get(1).title);
    }

    @Test public void officeConversionCanBeDisabledForOfflineTests() throws Exception {
        Path file = temp.newFile("offline.docx").toPath();
        Files.write(file, new byte[]{1});
        System.setProperty("minion.preview.office.disabled", "true");
        try {
            assertNull(OfficePreviewConverter.convert(file));
        } finally {
            System.clearProperty("minion.preview.office.disabled");
        }
    }

    @Test public void actualGeneratedPresentationConvertsWithLocalLibreOffice() throws Exception {
        Path deck = java.nio.file.Paths.get("target", "中新海关风险管理合作交流会议.pptx")
                .toAbsolutePath();
        Assume.assumeTrue("generated deck is not present", Files.isRegularFile(deck));
        Assume.assumeTrue("LibreOffice is not installed",
                Files.isRegularFile(java.nio.file.Paths.get(
                        "C:/Program Files/LibreOffice/program/soffice.exe")));
        System.setProperty("minion.office.com.disabled", "true");
        OfficePreviewConverter.Conversion conversion;
        try {
            conversion = OfficePreviewConverter.convert(deck);
        } finally {
            System.clearProperty("minion.office.com.disabled");
        }
        assertNotNull(conversion);
        assertTrue(Files.isRegularFile(conversion.pdf));
        try (PagedPdfPreview preview = new PagedPdfPreview(conversion.pdf)) {
            assertEquals(16, preview.pageCount());
            assertNotNull(preview.renderPage(15, 700));
        }
    }

    private Path pdf(int pages) throws Exception {
        Path file = temp.newFile("paged.pdf").toPath();
        try (PDDocument document = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 18);
                    stream.newLineAtOffset(72, 700);
                    stream.showText("Page " + i);
                    stream.endText();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    private Path xlsx() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("xl/workbook.xml", utf8(
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                        + "<sheets><sheet name=\"Summary\" sheetId=\"1\" r:id=\"rId1\"/>"
                        + "<sheet name=\"Details\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>"));
        entries.put("xl/_rels/workbook.xml.rels", utf8(
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\" "
                        + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"/>"
                        + "<Relationship Id=\"rId2\" Target=\"worksheets/sheet2.xml\" "
                        + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"/>"
                        + "</Relationships>"));
        entries.put("xl/sharedStrings.xml", utf8(
                "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + "<si><t>Name</t></si><si><t>Alice</t></si><si><t>Ready</t></si></sst>"));
        entries.put("xl/worksheets/sheet1.xml", utf8(
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\"><v>42</v></c><c r=\"B2\"><v>7</v></c></row>"
                        + "</sheetData></worksheet>"));
        entries.put("xl/worksheets/sheet2.xml", utf8(
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>2</v></c></row>"
                        + "</sheetData></worksheet>"));
        return zip(entries, ".xlsx");
    }

    private Path pptx() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
        entries.put("ppt/presentation.xml", utf8(
                "<p:presentation xmlns:p=\"" + P + "\" xmlns:r=\"" + R + "\">"
                        + "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId1\"/>"
                        + "<p:sldId id=\"257\" r:id=\"rId2\"/></p:sldIdLst></p:presentation>"));
        entries.put("ppt/_rels/presentation.xml.rels", utf8(
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"" + R + "/slide\" Target=\"slides/slide1.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"" + R + "/slide\" Target=\"slides/slide2.xml\"/>"
                        + "</Relationships>"));
        entries.put("ppt/slides/slide1.xml", utf8(slide("第一页", "第一页正文")));
        entries.put("ppt/slides/slide2.xml", utf8(slide("第二页", "第二页正文")));
        entries.put("ppt/slides/_rels/slide1.xml.rels", utf8(
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"img\" Type=\"" + R + "/image\" Target=\"../media/image1.png\"/>"
                        + "</Relationships>"));
        entries.put("ppt/media/image1.png", png());
        return zip(entries, ".pptx");
    }

    private Path zip(Map<String, byte[]> entries, String extension) throws Exception {
        Path file = temp.newFile("fixture" + extension).toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String slide(String title, String body) {
        return "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + "<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>" + title
                + "</a:t></a:r></a:p><a:p><a:r><a:t>" + body
                + "</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>";
    }
}
