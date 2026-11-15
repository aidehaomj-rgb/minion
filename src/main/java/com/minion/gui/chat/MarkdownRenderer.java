package com.minion.gui.chat;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown → 块结构（纯函数，不依赖 JavaFX，可单测）。
 * UI 层把 Block 转 JavaFX 节点（BlockNodeFactory）。
 */
public class MarkdownRenderer {

    /** 提取结果按原始 href 去重，防止不可信消息创建无界的链接控件。 */
    public static final int MAX_LINKS = 128;

    // Parser 不跨线程共享；也不在流式每次绘制时重新构建扩展/解析器。
    private static final ThreadLocal<Parser> PARSER = new ThreadLocal<Parser>() {
        @Override protected Parser initialValue() {
            Parser.Builder builder = Parser.builder();
            builder.set(Parser.SPACE_IN_LINK_URLS, true);
            return builder.extensions(Arrays.asList(TablesExtension.create(), StrikethroughExtension.create())).build();
        }
    };

    public static class Span {
        public final String text;
        public final String style; // plain / bold / italic / code / strike 组合
        public final String href; // null 表示普通文本；目标不做 URI 解码、路径归一化或打开
        public Span(String text, String style) {
            this(text, style, null);
        }
        public Span(String text, String style, String href) {
            this.text = text;
            this.style = style;
            this.href = href;
        }
    }

    public static final class LinkRef {
        public final String label;
        public final String href;
        public LinkRef(String label, String href) {
            this.label = label;
            this.href = href;
        }
    }

    public static class TableRowData {
        public final List<String> cells = new ArrayList<String>();
        /** 与 cells 同下标；保留旧 cells API，富文本表格使用此字段。 */
        public final List<List<Span>> cellSpans = new ArrayList<List<Span>>();
        public final boolean header;
        public TableRowData(boolean header) { this.header = header; }
    }

    public static class Block {
        public enum Type { PARAGRAPH, HEADING, CODE, LIST, QUOTE, TABLE, RULE }

        public final Type type;
        public String text;
        public String lang;
        public int level;
        public List<Span> spans = new ArrayList<Span>();
        public List<Block> items = new ArrayList<Block>();
        public List<TableRowData> rows = new ArrayList<TableRowData>();

        public Block(Type type) { this.type = type; }
    }

    /** 解析 Markdown → 块列表（空/空白文本 → 空列表） */
    public static List<Block> parse(String md) {
        List<Block> out = new ArrayList<Block>();
        if (md == null || md.trim().isEmpty()) return out;
        Document doc = PARSER.get().parse(md);
        for (Node n : doc.getChildren()) {
            convert(n, out);
        }
        return out;
    }

    /** 提取真实 Markdown 链接（不含图片/代码/未完成语法），保留首个标签和原始目标。
     * 最多 MAX_LINKS 个不同 href；调用方应在完成时或节流后调用，而非每 token 调用。
     */
    public static List<LinkRef> links(String md) {
        return links(md, 0, md == null ? 0 : md.length(), MAX_LINKS);
    }

    /** 在有界解析窗口内，只收集源码范围与本页相交的链接。偏移相对于 md。 */
    static List<LinkRef> links(String md, int from, int to, int limit) {
        if (md == null || md.isEmpty() || limit <= 0 || from >= to) return Collections.emptyList();
        // 标准链接至少含 [] 或 <>；纯文本/系统状态无需创建 AST。
        if (md.indexOf('[') < 0 && md.indexOf('<') < 0) return Collections.emptyList();
        Map<String, LinkRef> out = new LinkedHashMap<String, LinkRef>();
        collectLinks(PARSER.get().parse(md), from, to, Math.min(limit, MAX_LINKS), out);
        return Collections.unmodifiableList(new ArrayList<LinkRef>(out.values()));
    }

    private static void collectLinks(Node node, int from, int to, int limit, Map<String, LinkRef> out) {
        if (out.size() >= limit) return;
        String href = linkHref(node);
        if (href != null && node.getStartOffset() < to && node.getEndOffset() > from) {
            if (!out.containsKey(href)) out.put(href, new LinkRef(collectText(node), href));
            return;
        }
        for (Node child : node.getChildren()) {
            collectLinks(child, from, to, limit, out);
            if (out.size() >= limit) break;
        }
    }

    /** Markdown → 可读纯文本（消息区 TextArea 展示用）：去语法记号，块间空行分隔，整体 trim */
    public static String toPlainText(String md) {
        return toPlainText(md, Collections.<String>emptySet());
    }

    /** Skip URLs that the message already exposes as actionable links; keep the selectable labels. */
    static String toPlainText(String md, java.util.Set<String> actionableLinks) {
        List<Block> blocks = parse(md);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            appendPlain(blocks.get(i), sb, actionableLinks);
            if (i < blocks.size() - 1) sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 单块 → 纯文本（列表/表格带前缀与分隔，其余直接取 text；RULE 忽略） */
    private static void appendPlain(Block b, StringBuilder sb, java.util.Set<String> actionableLinks) {
        switch (b.type) {
            case PARAGRAPH:
            case HEADING:
            case QUOTE:
                appendPlainSpans(b.spans, b.text, sb, actionableLinks);
                break;
            case CODE:
                sb.append(b.text);
                break;
            case LIST:
                for (Block item : b.items) {
                    sb.append("• ");
                    appendPlainSpans(item.spans, item.text, sb, actionableLinks);
                    sb.append('\n');
                }
                if (!b.items.isEmpty()) sb.setLength(sb.length() - 1);
                break;
            case TABLE:
                for (TableRowData r : b.rows) {
                    for (int i = 0; i < r.cells.size(); i++) {
                        if (i > 0) sb.append(" | ");
                        appendPlainSpans(i < r.cellSpans.size() ? r.cellSpans.get(i) : Collections.<Span>emptyList(),
                                r.cells.get(i), sb, actionableLinks);
                    }
                    sb.append('\n');
                }
                if (!b.rows.isEmpty()) sb.setLength(sb.length() - 1);
                break;
            default:
                break;
        }
    }

    private static void appendPlainSpans(List<Span> spans, String fallback, StringBuilder out,
                                         java.util.Set<String> actionableLinks) {
        if (spans.isEmpty()) {
            if (fallback != null) out.append(fallback);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < spans.size();) {
            Span s = spans.get(i++);
            text.append(s.text);
            if (s.href != null) {
                // 一个链接内可能有多个强调/代码 span，目标只追加一次。
                while (i < spans.size() && s.href.equals(spans.get(i).href)) text.append(spans.get(i++).text);
                if (!actionableLinks.contains(s.href)) text.append('（').append(s.href).append('）');
            }
        }
        out.append(text.toString().trim());
    }

    private static void convert(Node n, List<Block> out) {
        if (n instanceof Heading) {
            Heading h = (Heading) n;
            Block b = new Block(Block.Type.HEADING);
            b.level = h.getLevel();
            b.spans = collectSpans(h);
            b.text = spanLabel(b.spans);
            out.add(b);
        } else if (n instanceof Paragraph) {
            Block b = new Block(Block.Type.PARAGRAPH);
            b.spans = collectSpans((Paragraph) n);
            b.text = spanLabel(b.spans);
            out.add(b);
        } else if (n instanceof FencedCodeBlock) {
            FencedCodeBlock f = (FencedCodeBlock) n;
            Block b = new Block(Block.Type.CODE);
            b.lang = f.getInfo() == null ? "" : f.getInfo().toString().trim();
            // flexmark 0.62.2/0.64.8 无 getContentChars()：内容行是 FencedCodeBlock 的 Text 子节点
            StringBuilder sb = new StringBuilder();
            for (Node child : f.getChildren()) sb.append(child.getChars());
            b.text = sb.toString().replaceAll("\\n$", "");
            out.add(b);
        } else if (n instanceof BulletList || n instanceof OrderedList) {
            Block b = new Block(Block.Type.LIST);
            for (Node child : n.getChildren()) {
                if (child instanceof ListItem) {
                    Block item = new Block(Block.Type.PARAGRAPH);
                    item.spans = collectSpans(child);
                    item.text = spanLabel(item.spans);
                    b.items.add(item);
                }
            }
            out.add(b);
        } else if (n instanceof BlockQuote) {
            Block b = new Block(Block.Type.QUOTE);
            b.spans = collectSpans(n);
            b.text = spanLabel(b.spans);
            out.add(b);
        } else if (n instanceof ThematicBreak) {
            out.add(new Block(Block.Type.RULE));
        } else if (n instanceof TableBlock) {
            Block b = new Block(Block.Type.TABLE);
            for (Node section : n.getChildren()) {
                if (!(section instanceof TableHead) && !(section instanceof TableBody)) continue;
                boolean header = section instanceof TableHead;
                for (Node rowNode : section.getChildren()) {
                    if (!(rowNode instanceof TableRow)) continue;
                    TableRowData row = new TableRowData(header);
                    for (Node cellNode : rowNode.getChildren()) {
                        if (cellNode instanceof TableCell) {
                            List<Span> spans = collectSpans(cellNode);
                            row.cells.add(spanLabel(spans));
                            row.cellSpans.add(spans);
                        }
                    }
                    b.rows.add(row);
                }
            }
            out.add(b);
        } else if (!(n instanceof Reference)) {
            Block b = new Block(Block.Type.PARAGRAPH);
            b.spans = collectSpans(n);
            b.text = spanLabel(b.spans);
            out.add(b);
        }
    }

    private static List<Span> collectSpans(Node node) {
        List<Span> spans = new ArrayList<Span>();
        walkInline(node, "", null, spans);
        return spans;
    }

    /** 递归收集行内富文本 span；style 继承当前样式（可组合：boldcode 等） */
    private static void walkInline(Node node, String style, String href, List<Span> spans) {
        String target = linkHref(node);
        if (target != null) {
            int before = spans.size();
            if (node.hasChildren()) {
                for (Node child : node.getChildren()) walkInline(child, style, target, spans);
            } else {
                // 自动链接没有 Text 子节点。
                String label = node instanceof MailLink ? ((MailLink) node).getText().toString()
                        : node instanceof AutoLink ? ((AutoLink) node).getText().toString() : target;
                spans.add(new Span(label, style, target));
            }
            // 空标签或仅含非文本节点的 Link 也不能丢失目标。
            if (spans.size() == before) spans.add(new Span(target, style, target));
        } else if (node instanceof Text) {
            String t = ((Text) node).getChars().toString();
            if (!t.isEmpty()) spans.add(new Span(t, style, href));
        } else if (node instanceof Code) {
            String t = stripCodeMarks(((Code) node).getChars().toString());
            if (!t.isEmpty()) spans.add(new Span(t, style + "code", href));
        } else if (node instanceof StrongEmphasis) {
            for (Node child : node.getChildren()) walkInline(child, style + "bold", href, spans);
        } else if (node instanceof Emphasis) {
            for (Node child : node.getChildren()) walkInline(child, style + "italic", href, spans);
        } else if (node instanceof Strikethrough) {
            for (Node child : node.getChildren()) walkInline(child, style + "strike", href, spans);
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            spans.add(new Span("\n", style, href));
        } else {
            for (Node child : node.getChildren()) walkInline(child, style, href, spans);
        }
    }

    private static String linkHref(Node node) {
        if (node instanceof Link) return ((Link) node).getUrl().toString();
        if (node instanceof AutoLink) return ((AutoLink) node).getText().toString();
        if (node instanceof MailLink) return "mailto:" + ((MailLink) node).getText().toString();
        if (node instanceof com.vladsch.flexmark.ast.LinkRef) {
            Reference ref = ((com.vladsch.flexmark.ast.LinkRef) node).getReferenceNode(node.getDocument());
            if (ref != null) return ref.getUrl().toString();
        }
        return null;
    }

    private static String stripCodeMarks(String s) {
        if (s.startsWith("`")) s = s.substring(1);
        if (s.endsWith("`") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String collectText(Node node) {
        return spanLabel(collectSpans(node));
    }

    private static String spanLabel(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (Span span : spans) sb.append(span.text);
        return sb.toString().trim();
    }
}
