package com.minion.core.tools.data;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only subset of GB/T 33190: package, page directory, native text and referenced scan images. */
final class OfdReader implements AutoCloseable {
    private static final String NS = "http://www.ofdspec.org/2016";
    private static final long MAX_FILE = 50L * 1024 * 1024;
    private static final int MAX_XML = 8 * 1024 * 1024, MAX_IMAGE = 20 * 1024 * 1024;
    private static final int MAX_TOTAL_EXPANDED = 64 * 1024 * 1024;
    private static final int MAX_PAGES = 10000, MAX_NODES = 60000, MAX_PAGE_TEXT = 120000;
    private static final Pattern ESCAPE = Pattern.compile("\\\\([0-9a-fA-F]{4})");

    private final ZipFile zip;
    private final XMLInputFactory xml = XMLInputFactory.newFactory();
    private final Map<String, ZipEntry> entries = new HashMap<>();
    private final List<Part> parts = new ArrayList<>();
    private final List<Page> pages = new ArrayList<>();
    private int expanded;

    OfdReader(Path path) throws IOException {
        if (Files.size(path) > MAX_FILE) throw new IOException("OFD超过50MB，请先拆分");
        xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xml.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("禁止外部XML引用"); });
        zip = new ZipFile(path.toFile());
        try {
            if (zip.size() > 20000) throw new IOException("OFD包内条目过多");
            for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                ZipEntry entry = it.nextElement();
                if (entry.isDirectory()) continue;
                String name = locate("", entry.getName());
                if (entries.put(name, entry) != null) throw new IOException("OFD包含重复包内路径: " + name);
            }
            for (Node body : tree("OFD.xml", "OFD").children("DocBody")) {
                String pathInPackage = locate("", body.value("DocRoot"));
                Node document = tree(pathInPackage, "Document"), info = body.first("DocInfo");
                Part part = new Part(info == null ? "" : info.value("Title"), body.first("Signatures") != null);
                if (parts.size() >= 100) throw new IOException("OFD包含过多子文档");
                parts.add(part);
                Node common = document.first("CommonData"), index = document.first("Pages");
                if (common != null) {
                    for (Node n : common.children) {
                        if ("PublicRes".equals(n.name) || "DocumentRes".equals(n.name))
                            part.resources.add(locate(parent(pathInPackage), n.value()));
                        if ("TemplatePage".equals(n.name))
                            part.templates.put(n.attr("ID"), new Template(locate(parent(pathInPackage), n.attr("BaseLoc")), n.attr("ZOrder")));
                    }
                }
                if (index == null || index.children("Page").isEmpty()) throw new IOException("OFD缺少有效页面目录：" + pathInPackage);
                for (Node ref : index.children("Page")) {
                    if (pages.size() >= MAX_PAGES) throw new IOException("OFD页数超出上限");
                    pages.add(new Page(part, locate(parent(pathInPackage), ref.attr("BaseLoc"))));
                    part.count++;
                }
            }
            if (pages.isEmpty()) throw new IOException("OFD.xml中没有可读文档");
        } catch (IOException | RuntimeException e) {
            try { zip.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }

    int pageCount() { return pages.size(); }
    String description() {
        StringBuilder out = new StringBuilder("总页数: ").append(pages.size()).append("；子文档数: ").append(parts.size());
        int start = 1;
        for (int i = 0; i < Math.min(parts.size(), 10); i++) {
            Part p = parts.get(i);
            out.append("\n子文档").append(i + 1).append("：").append(start).append('-').append(start + p.count - 1).append("页");
            if (!p.title.isEmpty()) out.append("；标题: ").append(p.title.substring(0, Math.min(160, p.title.length())).replaceAll("[\\r\\n]+", " "));
            if (p.signed) out.append("；含签章引用（未验签）");
            start += p.count;
        }
        if (parts.size() > 10) out.append("\n[其余子文档信息未列出]");
        return out.toString();
    }

    PageText text(int number) throws IOException {
        Page page = pages.get(number - 1);
        Node node = tree(page.path, "Page");
        List<String> backgrounds = new ArrayList<>(), foregrounds = new ArrayList<>();
        for (Node use : node.children("Template")) {
            if (backgrounds.size() + foregrounds.size() >= 32) throw new IOException("页面模板引用过多");
            Template template = page.part.templates.get(use.attr("TemplateID"));
            if (template == null) throw new IOException("缺少OFD模板: " + use.attr("TemplateID"));
            String z = use.attr("ZOrder");
            ("Foreground".equals(z.isEmpty() ? template.order : z) ? foregrounds : backgrounds).add(template.path);
        }
        List<LineFragment> fragments = new ArrayList<>();
        int[] stored = {0};
        for (String source : backgrounds) collectText(tree(source, "Page").first("Content"), fragments, stored);
        collectText(node.first("Content"), fragments, stored);
        for (String source : foregrounds) collectText(tree(source, "Page").first("Content"), fragments, stored);
        return new PageText(arrange(fragments), stored[0] > MAX_PAGE_TEXT);
    }

    List<String> imageResources(int number) throws IOException {
        Page page = pages.get(number - 1);
        Node node = tree(page.path, "Page");
        Set<String> ids = new LinkedHashSet<>();
        collectImageIds(node.first("Content"), ids);
        if (ids.isEmpty()) return Collections.emptyList();
        List<String> resFiles = new ArrayList<>(page.part.resources);
        for (Node n : node.children("PageRes")) resFiles.add(locate(parent(page.path), n.value()));
        Map<String, String> resources = new HashMap<>();
        for (String resFile : new LinkedHashSet<>(resFiles)) {
            Node res = tree(resFile, "Res"), all = res.first("MultiMedias");
            if (all == null) continue;
            String folder = res.attr("BaseLoc").isEmpty() ? parent(resFile) : locate(parent(resFile), res.attr("BaseLoc"));
            for (Node image : all.children("MultiMedia")) {
                if (!ids.contains(image.attr("ID")) || !"Image".equalsIgnoreCase(image.attr("Type"))) continue;
                resources.put(image.attr("ID"), locate(folder, image.value("MediaFile")));
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        for (String id : ids) {
            String path = resources.get(id);
            if (path == null) throw new IOException("页面引用的内嵌图像资源不存在: " + id);
            paths.add(path);
        }
        return new ArrayList<>(paths);
    }

    byte[] imageBytes(String entryName) throws IOException { return bytes(entryName, MAX_IMAGE); }

    private static void collectImageIds(Node node, Set<String> ids) throws IOException {
        if (node == null) return;
        if ("ImageObject".equals(node.name) && !node.attr("ResourceID").isEmpty()) {
            ids.add(node.attr("ResourceID"));
            if (ids.size() > 256) throw new IOException("单页引用的图像资源过多");
        }
        if (container(node.name)) for (Node child : node.children) collectImageIds(child, ids);
    }

    private static void collectText(Node node, List<LineFragment> out, int[] stored) {
        if (node == null || stored[0] > MAX_PAGE_TEXT) return;
        if (container(node.name)) {
            for (Node child : node.children) collectText(child, out, stored);
        } else if ("TextObject".equals(node.name)) {
            String[] box = node.attr("Boundary").trim().split("\\s+");
            double bx = box.length == 4 ? number(box[0], Double.NaN) : Double.NaN;
            double by = box.length == 4 ? number(box[1], Double.NaN) : Double.NaN;
            boolean positioned = node.attr("CTM").isEmpty()
                    && (node.attr("ReadDirection").isEmpty() || "0".equals(node.attr("ReadDirection")))
                    && (node.attr("CharDirection").isEmpty() || "0".equals(node.attr("CharDirection")));
            double x = 0, y = 0;
            StringBuilder run = new StringBuilder();
            double runX = Double.NaN, runY = Double.NaN;
            for (Node code : node.children("TextCode")) {
                x = number(code.attr("X"), x); y = number(code.attr("Y"), y);
                if (run.length() > 0 && positioned && Double.isFinite(by) && Math.abs(by + y - runY) > 0.9) {
                    out.add(new LineFragment(run.toString(), runX, runY));
                    run.setLength(0);
                }
                if (run.length() == 0) { runX = positioned ? bx + x : Double.NaN; runY = positioned ? by + y : Double.NaN; }
                String text = unescape(code.text == null ? "" : code.text.toString());
                // Glyph-only placeholders carry no readable Unicode by themselves.
                if (!text.isEmpty() && text.codePoints().allMatch(c -> c == 0x00A4 || Character.isWhitespace(c)) && text.indexOf('¤') >= 0)
                    continue;
                int keep = Math.min(text.length(), MAX_PAGE_TEXT - stored[0]);
                if (keep < text.length() && keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) keep--;
                run.append(text, 0, keep);
                stored[0] += keep;
                if (keep < text.length()) { stored[0] = MAX_PAGE_TEXT + 1; break; }
            }
            if (run.length() > 0) out.add(new LineFragment(run.toString(), runX, runY));
        }
    }

    private static boolean container(String name) {
        return "Content".equals(name) || "Layer".equals(name) || "PageBlock".equals(name);
    }
    private static double number(String text, double fallback) {
        try { double n = Double.parseDouble(text); return Double.isFinite(n) && Math.abs(n) < 1e6 ? n : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String arrange(List<LineFragment> list) {
        StringBuilder out = new StringBuilder();
        if (list.stream().anyMatch(f -> !Double.isFinite(f.x) || !Double.isFinite(f.y))) {
            for (LineFragment f : list) out.append(f.text).append('\n');
        } else {
            list.sort(Comparator.comparingDouble(f -> f.y));
            for (int i = 0; i < list.size(); ) {
                int j = i + 1;
                while (j < list.size() && list.get(j).y - list.get(i).y <= 0.9) j++;
                List<LineFragment> row = list.subList(i, j);
                row.sort(Comparator.comparingDouble(f -> f.x));
                for (int k = 0; k < row.size(); k++) {
                    if (k > 0) out.append(' ');
                    out.append(row.get(k).text);
                }
                out.append('\n');
                i = j;
            }
        }
        return out.toString().trim();
    }

    private static String unescape(String input) {
        Matcher m = ESCAPE.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            char c = (char) Integer.parseInt(m.group(1), 16);
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(c < 32 && c != '\n' && c != '\t' && c != '\r' ? '\uFFFD' : c)));
        }
        m.appendTail(out);
        return out.toString().replace('\r', '\n');
    }

    private Node tree(String name, String expected) throws IOException {
        byte[] data = bytes(name, MAX_XML);
        try (InputStream input = new ByteArrayInputStream(data)) {
            XMLStreamReader reader = xml.createXMLStreamReader(input);
            try {
                Deque<Node> stack = new ArrayDeque<>();
                Node root = null;
                int count = 0, foreignDepth = 0;
                while (reader.hasNext()) {
                    int kind = reader.next();
                    if (kind == XMLStreamConstants.DTD || kind == XMLStreamConstants.ENTITY_REFERENCE)
                        throw new IOException("OFD XML不允许DTD或外部实体");
                    if (kind == XMLStreamConstants.START_ELEMENT) {
                        if (++count > MAX_NODES || stack.size() + foreignDepth >= 64 || reader.getAttributeCount() > 64)
                            throw new IOException("OFD XML结构超过解析上限");
                        String ns = reader.getNamespaceURI();
                        if (foreignDepth > 0 || (ns != null && !ns.isEmpty() && !NS.equals(ns))) { foreignDepth++; continue; }
                        Node node = new Node(reader.getLocalName());
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String attrNs = reader.getAttributeNamespace(i);
                            if (attrNs == null || attrNs.isEmpty()) node.attrs.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        }
                        if (stack.isEmpty()) {
                            if (root != null || !expected.equals(node.name)) throw new IOException("包内XML结构不符: " + name);
                            root = node;
                        } else stack.peek().children.add(node);
                        stack.push(node);
                    } else if (kind == XMLStreamConstants.END_ELEMENT) {
                        if (foreignDepth > 0) foreignDepth--; else stack.pop();
                    } else if (foreignDepth == 0 && !stack.isEmpty()
                            && (kind == XMLStreamConstants.CHARACTERS || kind == XMLStreamConstants.CDATA || kind == XMLStreamConstants.SPACE)
                            && (!reader.isWhiteSpace() || "TextCode".equals(stack.peek().name))) {
                        Node node = stack.peek();
                        if (node.text == null) node.text = new StringBuilder();
                        node.text.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
                    }
                }
                if (root == null) throw new IOException("包内XML没有有效根节点: " + name);
                return root;
            } finally { reader.close(); }
        } catch (XMLStreamException e) { throw new IOException("OFD XML无法解析: " + name + "（" + e.getMessage() + "）", e); }
    }

    private byte[] bytes(String name, int cap) throws IOException {
        ZipEntry entry = entries.get(name);
        if (entry == null) throw new IOException("OFD缺少包内文件: " + name);
        int remaining = Math.min(cap, MAX_TOTAL_EXPANDED - expanded);
        if (entry.getSize() > remaining) throw new IOException("OFD解压数据超过本次上限: " + name + "；请减少所读页数");
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("OFD读取已中断");
                if (n > remaining - out.size()) throw new IOException("OFD解压数据超过本次上限: " + name);
                out.write(buffer, 0, n);
            }
            expanded += out.size();
            return out.toByteArray();
        }
    }

    /** Resolve ST_Loc with ZIP-root semantics, never host filesystem semantics. */
    private static String locate(String directory, String location) throws IOException {
        String raw = location.trim().replace('\\', '/');
        if (raw.isEmpty() || raw.length() > 4096 || raw.startsWith("//")
                || raw.indexOf(':') >= 0 || raw.chars().anyMatch(c -> c < 32))
            throw new IOException("无效的OFD包内引用: " + location.substring(0, Math.min(150, location.length())));
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : (raw.startsWith("/") || directory.isEmpty() ? raw : directory + "/" + raw).split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) throw new IOException("OFD引用越出压缩包根目录");
                segments.removeLast();
            } else segments.addLast(segment);
        }
        return String.join("/", segments);
    }
    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    @Override public void close() throws IOException { zip.close(); }

    static final class PageText {
        final String text;
        final boolean truncated;
        PageText(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }
    private static final class Part {
        final String title;
        final boolean signed;
        final List<String> resources = new ArrayList<>();
        final Map<String, Template> templates = new HashMap<>();
        int count;
        Part(String title, boolean signed) { this.title = title; this.signed = signed; }
    }
    private static final class Template {
        final String path, order;
        Template(String path, String order) { this.path = path; this.order = order; }
    }
    private static final class Page {
        final Part part;
        final String path;
        Page(Part part, String path) { this.part = part; this.path = path; }
    }
    private static final class LineFragment {
        final String text;
        final double x, y;
        LineFragment(String text, double x, double y) { this.text = text; this.x = x; this.y = y; }
    }
    private static final class Node {
        final String name;
        final Map<String, String> attrs = new HashMap<>();
        final List<Node> children = new ArrayList<>();
        StringBuilder text;
        Node(String name) { this.name = name; }
        String attr(String key) { return attrs.getOrDefault(key, ""); }
        String value() { return text == null ? "" : text.toString().trim(); }
        String value(String name) { Node child = first(name); return child == null ? "" : child.value(); }
        Node first(String name) { for (Node n : children) if (name.equals(n.name)) return n; return null; }
        List<Node> children(String name) {
            List<Node> found = new ArrayList<>();
            for (Node n : children) if (name.equals(n.name)) found.add(n);
            return found;
        }
    }
}
