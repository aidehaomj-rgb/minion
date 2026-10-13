package com.minion.gui.chat;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** 保存完整显示文本，只把当前分页交给 JavaFX 测量，避免超长节点布局失效。 */
public final class MessageTextPane extends VBox {
    static final int PAGE_CHARS = 12000;
    private final MessageTextArea area = new MessageTextArea("");
    private final Button previous = new Button("上一段");
    private final Button next = new Button("下一段");
    private final Button last = new Button("末段");
    private final Button copy = new Button("复制全文");
    private final Label info = new Label();
    private final HBox navigation = new HBox(8, previous, next, last, info, copy);
    private String fullText = "";
    private int page;
    private boolean followTail = true;
    private final boolean markdown;

    public MessageTextPane(String text) {
        this(text, false);
    }
    public MessageTextPane(String text, boolean markdown) {
        this.markdown = markdown;
        getChildren().addAll(navigation, area);
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
    public void setText(String text, boolean streaming) {
        boolean wasEmpty = fullText.isEmpty();
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
        String shown = pageText(fullText, page);
        if (markdown) shown = MarkdownRenderer.toPlainText(shown);
        if (!shown.equals(area.getText())) area.setStreamText(shown);
        navigation.setVisible(count > 1);
        navigation.setManaged(count > 1);
        previous.setDisable(page == 0);
        next.setDisable(page == count - 1);
        last.setDisable(page == count - 1);
        info.setText("第 " + (page + 1) + "/" + count + " 段 · 全文 " + fullText.length() + " 字符（未截断）");
    }
    static int pageCount(String text) {
        return text == null || text.isEmpty() ? 1 : (text.length() - 1) / PAGE_CHARS + 1;
    }
    static String pageText(String text, int page) {
        if (text == null || text.isEmpty()) return "";
        int index = Math.max(0, Math.min(page, pageCount(text) - 1));
        int start = safeBoundary(text, index * PAGE_CHARS);
        int end = safeBoundary(text, Math.min(text.length(), (index + 1) * PAGE_CHARS));
        return text.substring(start, end);
    }
    private static int safeBoundary(String text, int offset) {
        // 前后段一致调整边界，不丢字符，也不拆开 emoji 代理项。
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset)) ? offset - 1 : offset;
    }
}
