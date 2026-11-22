package com.minion.gui.artifact;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded OOXML worksheet reader for the read-only workspace preview. */
final class XlsxPreview {
    static final int MAX_ROWS = 200;
    static final int MAX_COLUMNS = 30;
    private static final int MAX_SHEETS = 40;
    private static final int MAX_XML = 8 * 1024 * 1024;
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static final class Sheet {
        final String name;
        final List<List<String>> rows;
        final boolean truncated;
        Sheet(String name, List<List<String>> rows, boolean truncated) {
            this.name = name;
            this.rows = Collections.unmodifiableList(rows);
            this.truncated = truncated;
        }
    }

    private XlsxPreview() { }

    static List<Sheet> load(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<String> shared = sharedStrings(zip);
            Map<String, String> relations = relationships(zip);
            Document workbook = xml(zip, "xl/workbook.xml");
            Element sheets = first(workbook.getDocumentElement(), "sheets");
            if (sheets == null) throw new IOException("XLSX 缺少工作表目录。");
            List<Sheet> result = new ArrayList<Sheet>();
            NodeList list = sheets.getElementsByTagNameNS("*", "sheet");
            for (int i = 0; i < list.getLength() && result.size() < MAX_SHEETS; i++) {
                Element sheet = (Element) list.item(i);
                String name = sheet.getAttribute("name");
                String id = sheet.getAttributeNS(REL, "id");
                if (id.isEmpty()) id = sheet.getAttribute("r:id");
                String target = relations.get(id);
                if (target == null) continue;
                Document xml = xml(zip, normalizeSheet(target));
                result.add(readSheet(name, xml, shared));
            }
            if (result.isEmpty()) throw new IOException("XLSX 中没有可读取的工作表。");
            return result;
        }
    }

    private static Sheet readSheet(String name, Document document, List<String> shared) {
        List<List<String>> rows = new ArrayList<List<String>>();
        Element data = first(document.getDocumentElement(), "sheetData");
        if (data == null) return new Sheet(name, rows, false);
        NodeList rowNodes = data.getElementsByTagNameNS("*", "row");
        boolean truncated = rowNodes.getLength() > MAX_ROWS;
        for (int r = 0; r < rowNodes.getLength() && rows.size() < MAX_ROWS; r++) {
            Element row = (Element) rowNodes.item(r);
            List<String> cells = new ArrayList<String>();
            NodeList children = row.getElementsByTagNameNS("*", "c");
            for (int c = 0; c < children.getLength() && cells.size() < MAX_COLUMNS; c++) {
                Element cell = (Element) children.item(c);
                int column = column(cell.getAttribute("r"));
                if (column > MAX_COLUMNS - 1) {
                    truncated = true;
                    continue;
                }
                while (cells.size() < column) cells.add("");
                cells.add(value(cell, shared));
            }
            while (cells.size() < MAX_COLUMNS) cells.add("");
            rows.add(Collections.unmodifiableList(cells));
        }
        return new Sheet(name, rows, truncated);
    }

    private static String value(Element cell, List<String> shared) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            Element inline = first(cell, "is");
            return inline == null ? "" : text(inline);
        }
        Element value = first(cell, "v");
        String raw = value == null ? "" : value.getTextContent();
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw);
                return index >= 0 && index < shared.size() ? shared.get(index) : raw;
            } catch (NumberFormatException invalid) {
                return raw;
            }
        }
        if ("b".equals(type)) return "1".equals(raw) ? "TRUE" : "FALSE";
        return raw;
    }

    private static List<String> sharedStrings(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return Collections.emptyList();
        Document document = xml(zip, "xl/sharedStrings.xml");
        List<String> result = new ArrayList<String>();
        NodeList items = document.getElementsByTagNameNS("*", "si");
        for (int i = 0; i < items.getLength(); i++)
            result.add(text((Element) items.item(i)));
        return result;
    }

    private static Map<String, String> relationships(ZipFile zip) throws IOException {
        if (zip.getEntry("xl/_rels/workbook.xml.rels") == null)
            return Collections.emptyMap();
        Document document = xml(zip, "xl/_rels/workbook.xml.rels");
        NodeList list = document.getElementsByTagNameNS("*", "Relationship");
        Map<String, String> result = new HashMap<String, String>();
        for (int i = 0; i < list.getLength(); i++) {
            Element relationship = (Element) list.item(i);
            result.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
        }
        return result;
    }

    private static String normalizeSheet(String target) throws IOException {
        if (target == null || target.trim().isEmpty())
            throw new IOException("XLSX 工作表关系为空。");
        String value = target.replace('\\', '/');
        if (value.startsWith("/")) value = value.substring(1);
        else if (!value.startsWith("xl/")) value = "xl/" + value;
        List<String> parts = new ArrayList<String>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IOException("XLSX 工作表路径越界。");
            parts.add(part);
        }
        return String.join("/", parts);
    }

    private static Document xml(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("XLSX 缺少 " + name);
        if (entry.getSize() < 0 || entry.getSize() > MAX_XML)
            throw new IOException("XLSX XML 条目超过安全上限: " + name);
        byte[] data;
        try (InputStream input = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAX_XML)
                    throw new IOException("XLSX XML 实际大小超过安全上限。");
                if (count > 0) output.write(buffer, 0, count);
            }
            data = output.toByteArray();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        } catch (Exception invalid) {
            throw new IOException("XLSX XML 无效: " + name, invalid);
        }
    }

    private static Element first(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String text(Element element) {
        NodeList list = element.getElementsByTagNameNS("*", "t");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.getLength(); i++) {
            if (i > 0) out.append(' ');
            out.append(list.item(i).getTextContent());
        }
        return out.toString();
    }

    private static int column(String reference) {
        if (reference == null || reference.isEmpty()) return 0;
        int value = 0;
        for (int i = 0; i < reference.length(); i++) {
            char c = Character.toUpperCase(reference.charAt(i));
            if (c < 'A' || c > 'Z') break;
            value = value * 26 + (c - 'A' + 1);
        }
        return Math.max(0, value - 1);
    }
}
