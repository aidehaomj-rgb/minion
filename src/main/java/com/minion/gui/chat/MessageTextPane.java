package com.minion.gui.chat;

import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** 保存完整显示文本，只把当前分页交给 JavaFX 测量，避免超长节点布局失效。 */
public final class MessageTextPane extends VBox {
    static final int PAGE_CHARS = 12000;
    static final int MAX_PAGE_LINKS = 12;
    private final MessageTextArea area = new MessageTextArea("");
    private final Button previous = new Button("上一段");
    private final Button next = new Button("下一段");
    private final Button last = new Button("末段");
    private final Button copy = new Button("复制全文");
    private final Label info = new Label();
    private final FlowPane navigation = new FlowPane(6, 4, previous, next, last, copy, info);
    private final VBox linkBar = new VBox(4);
    private final VBox linkItems = new VBox(4);
    private final Button showLinks = new Button();
    private String fullText = "";
    private int page;
    private boolean followTail = true;
    private final boolean markdown;
    private final boolean standaloneArtifact;
    private boolean streaming;
    private Consumer<String> onLink;
    private Function<String, ContextMenu> linkMenu;
    private String linkWindow;
    private int linkFrom = -1;
    private int linkTo = -1;
    private List<MarkdownRenderer.LinkRef> pageLinks = Collections.emptyList();
    private Set<String> actionableLinks = Collections.emptySet();
    private Set<String> renderedLinks = Collections.emptySet();
    private String renderedSource;
    private String renderedText;

    public MessageTextPane(String text) {
        this(text, false);
    }
    public MessageTextPane(String text, boolean markdown) {
        this(text, markdown, false);
    }
    MessageTextPane(String text, boolean markdown, boolean standaloneArtifact) {
        this.markdown = markdown;
        this.standaloneArtifact = standaloneArtifact;
        navigation.getStyleClass().add("history-navigation");
        info.getStyleClass().add("history-info");
        linkBar.getStyleClass().add("message-links");
        linkItems.getStyleClass().add("message-link-items");
        showLinks.getStyleClass().add("related-links-toggle");
        showLinks.setOnAction(e -> setLinksExpanded(!linkItems.isVisible()));
        getChildren().addAll(navigation, area, linkBar);
        previous.setOnAction(e -> showPage(page - 1));
        next.setOnAction(e -> showPage(page + 1));
        last.setOnAction(e -> showPage(pageCount(fullText) - 1));
        copy.setOnAction(e -> {
            ClipboardContent value = new ClipboardContent();
            value.putString(fullText);
            Clipboard.getSystemClipboard().setContent(value);
        });
        setText(text, false);
    }
    public MessageTextArea contentArea() { return area; }
    public String fullText() { return fullText; }

    /** FX 线程调用，可补设/重绑。链接提取与 markdown 标记无关（兼容系统文件卡片）；
     * 点击/右键仅透传原始 href，不在消息控件内解析或打开文件。
     */
    public void setLinkHandler(Consumer<String> onLink, Function<String, ContextMenu> linkMenu) {
        this.onLink = onLink;
        this.linkMenu = linkMenu;
        if (!streaming) {
            rebuildLinkBar();
            renderBody(pageText(fullText, page));
        }
    }

    public void setText(String text, boolean streaming) {
        boolean wasEmpty = fullText.isEmpty();
        this.streaming = streaming;
        fullText = text == null ? "" : text;
        if (streaming && (wasEmpty || followTail)) {
            page = pageCount(fullText) - 1;
            followTail = true;
        }
        render();
    }
    private void showPage(int selected) {
        page = Math.max(0, Math.min(selected, pageCount(fullText) - 1));
        followTail = page == pageCount(fullText) - 1;
        render();
    }
    private void render() {
        int count = pageCount(fullText);
        page = Math.max(0, Math.min(page, count - 1));
        navigation.setVisible(count > 1);
        navigation.setManaged(count > 1);
        previous.setDisable(page == 0);
        next.setDisable(page == count - 1);
        last.setDisable(page == count - 1);
        info.setText("第 " + (page + 1) + "/" + count + " 段 · 全文 " + fullText.length() + " 字符（未截断）");
        if (streaming) {
            // 采用完成后更新策略：流式不增加链接 AST 扫描，也不保留旧目标供误点。
            if (!linkBar.getChildren().isEmpty()) linkBar.getChildren().clear();
            linkBar.setVisible(false);
            linkBar.setManaged(false);
            area.setVisible(true);
            area.setManaged(true);
            actionableLinks = Collections.emptySet();
            linkWindow = null;
        } else {
            updatePageLinks();
        }
        renderBody(pageText(fullText, page));
    }

    private void renderBody(String source) {
        if (!source.equals(renderedSource) || !actionableLinks.equals(renderedLinks)) {
            renderedText = markdown ? MarkdownRenderer.toPlainText(source, actionableLinks) : source;
            renderedSource = source;
            renderedLinks = actionableLinks;
        }
        if (!renderedText.equals(area.getText())) area.setStreamText(renderedText);
    }

    private void updatePageLinks() {
        int start = safeBoundary(fullText, page * PAGE_CHARS);
        int end = safeBoundary(fullText, (int) Math.min((long) fullText.length(), ((long) page + 1) * PAGE_CHARS));
        // 前后各借一页，识别横跨分页边界的完整链接；不会扫描整条长消息。
        int windowStart = safeBoundary(fullText, Math.max(0, start - PAGE_CHARS));
        int windowEnd = safeBoundary(fullText, (int) Math.min((long) fullText.length(), (long) end + PAGE_CHARS));
        String window = fullText.substring(windowStart, windowEnd);
        int from = start - windowStart;
        int to = end - windowStart;
        if (!window.equals(linkWindow) || from != linkFrom || to != linkTo) {
            pageLinks = MarkdownRenderer.links(window, from, to, MAX_PAGE_LINKS);
            linkWindow = window;
            linkFrom = from;
            linkTo = to;
            rebuildLinkBar();
        }
    }

    private void rebuildLinkBar() {
        boolean expanded = linkBar.getChildren().contains(linkItems) && linkItems.isVisible();
        linkBar.getChildren().clear();
        linkItems.getChildren().clear();
        Set<String> targets = new HashSet<String>();
        // A file event is one actionable row, not URI text followed by a duplicate link.
        boolean linkOnly = standaloneArtifact && onLink != null && pageLinks.size() == 1;
        area.setVisible(!linkOnly);
        area.setManaged(!linkOnly);
        if (linkOnly && !linkBar.getStyleClass().contains("artifact-message-link"))
            linkBar.getStyleClass().add("artifact-message-link");
        else if (!linkOnly)
            linkBar.getStyleClass().remove("artifact-message-link");
        if (!pageLinks.isEmpty()) {
            for (MarkdownRenderer.LinkRef ref : pageLinks) {
                String label = ref.label.isEmpty() ? ref.href : ref.label;
                // 仅截短按钮标签；tooltip、回调及菜单收到的 href 始终是完整原值。
                if (label.length() > 160) label = label.substring(0, safeBoundary(label, 160)) + "…";
                Hyperlink link = BlockNodeFactory.link(label, ref.href, onLink, linkMenu);
                if (linkOnly) {
                    link.setWrapText(false);
                    link.setMinWidth(0);
                    linkBar.getChildren().add(link);
                } else {
                    linkItems.getChildren().add(link);
                }
                if (markdown && onLink != null) targets.add(ref.href);
            }
            if (!linkOnly) {
                linkBar.getChildren().addAll(showLinks, linkItems);
                setLinksExpanded(expanded);
            }
        }
        actionableLinks = targets.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(targets);
        linkBar.setVisible(!pageLinks.isEmpty());
        linkBar.setManaged(!pageLinks.isEmpty());
    }

    private void setLinksExpanded(boolean expanded) {
        linkItems.setVisible(expanded);
        linkItems.setManaged(expanded);
        showLinks.setText(expanded ? "▾ 收起链接" : "▸ 相关链接 · " + pageLinks.size());
    }
    static int pageCount(String text) {
        return text == null || text.isEmpty() ? 1 : (text.length() - 1) / PAGE_CHARS + 1;
    }
    static String pageText(String text, int page) {
        if (text == null || text.isEmpty()) return "";
        int index = Math.max(0, Math.min(page, pageCount(text) - 1));
        int start = safeBoundary(text, index * PAGE_CHARS);
        int end = safeBoundary(text, (int) Math.min((long) text.length(), ((long) index + 1) * PAGE_CHARS));
        return text.substring(start, end);
    }
    private static int safeBoundary(String text, int offset) {
        // 前后段一致调整边界，不丢字符，也不拆开 emoji 代理项。
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset)) ? offset - 1 : offset;
    }
}
