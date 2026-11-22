package com.minion.gui.chat;

import com.minion.gui.chat.MarkdownRenderer.Block;
import com.minion.gui.chat.MarkdownRenderer.Span;
import com.minion.gui.chat.MarkdownRenderer.TableRowData;
import com.minion.gui.icon.IconFactory;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Block 结构 → JavaFX 节点（样式类与 theme.css / workbench.css 四套主题变量联动）。
 * 去除硬编码深色文字，确保深色与明亮浅色主题均具备清晰对比度；代码块带语言角标与一键复制。
 */
public class BlockNodeFactory {

    public static javafx.scene.Node create(Block b) {
        return create(b, null, null);
    }

    /** 只透传 Markdown 原始目标；路径安全校验及打开策略由调用方负责。 */
    public static javafx.scene.Node create(Block b, Consumer<String> onLink,
                                           Function<String, ContextMenu> linkMenu) {
        return create(b, onLink, linkMenu, 14);
    }

    public static javafx.scene.Node create(Block b, Consumer<String> onLink,
                                           Function<String, ContextMenu> linkMenu, int bodySize) {
        switch (b.type) {
            case HEADING: {
                int headingSize = bodySize + Math.max(1, 5 - b.level);
                if (hasLinks(b.spans)) {
                    TextFlow tf = flow(b.spans, b.text, onLink, linkMenu, "md-heading-text", headingSize, true);
                    tf.getStyleClass().add("md-heading-flow");
                    return tf;
                }
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.setMaxWidth(Double.MAX_VALUE);
                l.getStyleClass().addAll("md-heading", "md-h" + Math.max(1, Math.min(6, b.level)));
                l.setStyle("-fx-font-size: " + headingSize + "px;");
                return l;
            }
            case CODE: {
                return codeCard(b.lang, b.text);
            }
            case PARAGRAPH: {
                TextFlow flow = flow(b.spans, b.text, onLink, linkMenu, "md-text", bodySize, false);
                flow.getStyleClass().add("md-paragraph");
                flow.setPadding(new Insets(2, 0, 2, 0));
                return flow;
            }
            case LIST: {
                VBox box = new VBox(4);
                box.getStyleClass().add("md-list");
                for (Block item : b.items) {
                    HBox row = new HBox(8);
                    row.setAlignment(Pos.TOP_LEFT);
                    Label bullet = new Label("•");
                    bullet.getStyleClass().add("md-bullet");
                    bullet.setMinWidth(Region.USE_PREF_SIZE);
                    TextFlow flow = flow(item.spans, item.text, onLink, linkMenu, "md-text", bodySize, false);
                    flow.setMinWidth(0);
                    HBox.setHgrow(flow, Priority.ALWAYS);
                    row.getChildren().addAll(bullet, flow);
                    box.getChildren().add(row);
                }
                return box;
            }
            case QUOTE: {
                if (hasLinks(b.spans)) {
                    TextFlow flow = flow(b.spans, b.text, onLink, linkMenu, "md-quote-text", bodySize, false);
                    flow.getStyleClass().add("md-quote");
                    return flow;
                }
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.setMaxWidth(Double.MAX_VALUE);
                l.getStyleClass().addAll("md-quote", "md-quote-label");
                return l;
            }
            case TABLE: {
                GridPane grid = new GridPane();
                grid.getStyleClass().add("md-table");
                int rowIdx = 0;
                for (TableRowData r : b.rows) {
                    String rowClass = r.header ? "md-table-head"
                            : (rowIdx % 2 == 0 ? "md-table-even" : "md-table-odd");
                    for (int c = 0; c < r.cells.size(); c++) {
                        javafx.scene.Node cellNode;
                        if (c < r.cellSpans.size() && hasLinks(r.cellSpans.get(c))) {
                            cellNode = flow(r.cellSpans.get(c), r.cells.get(c), onLink, linkMenu,
                                    "md-table-text", bodySize, r.header);
                        } else {
                            Text t = new Text(r.cells.get(c));
                            t.setFont(Font.font(t.getFont().getFamily(),
                                    r.header ? FontWeight.BOLD : FontWeight.NORMAL, bodySize));
                            t.getStyleClass().add(r.header ? "md-table-head-text" : "md-table-text");
                            cellNode = new TextFlow(t);
                        }
                        cellNode.getStyleClass().addAll("md-table-cell", rowClass);
                        GridPane.setHgrow(cellNode, Priority.ALWAYS);
                        grid.add(cellNode, c, rowIdx);
                    }
                    rowIdx++;
                }
                return grid;
            }
            default: {
                Label l = new Label(b.text == null ? "" : b.text);
                l.getStyleClass().add("md-fallback");
                return l;
            }
        }
    }

    /** 现代代码块卡片：顶部语言角标 + 一键复制按钮，下方等宽代码正文 */
    private static VBox codeCard(String lang, String rawCode) {
        final String code = rawCode == null ? "" : rawCode;
        VBox card = new VBox();
        card.getStyleClass().add("md-code-card");

        String langText = (lang == null || lang.trim().isEmpty()) ? "TEXT" : lang.trim().toUpperCase();
        Label langLabel = new Label(langText);
        langLabel.getStyleClass().add("md-code-lang");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        SVGPath copyIcon = IconFactory.copy();
        IconFactory.size(copyIcon, 13);
        Button copyBtn = new Button("复制", copyIcon);
        copyBtn.getStyleClass().add("md-code-copy");
        copyBtn.setFocusTraversable(false);
        copyBtn.setTooltip(new Tooltip("复制代码"));
        copyBtn.setOnAction(e -> {
            try {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(code);
                Clipboard.getSystemClipboard().setContent(cc);
                copyBtn.setText("已复制");
                if (!copyBtn.getStyleClass().contains("copied")) copyBtn.getStyleClass().add("copied");
                PauseTransition reset = new PauseTransition(Duration.seconds(1.5));
                reset.setOnFinished(ev -> {
                    copyBtn.setText("复制");
                    copyBtn.getStyleClass().remove("copied");
                });
                reset.play();
            } catch (Exception ignored) { }
        });

        HBox header = new HBox(8, langLabel, spacer, copyBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("md-code-header");

        Label body = new Label(code);
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        body.getStyleClass().addAll("code-block", "md-code-body");

        card.getChildren().addAll(header, body);
        return card;
    }

    private static boolean hasLinks(List<Span> spans) {
        for (Span span : spans) if (span.href != null) return true;
        return false;
    }

    private static TextFlow flow(List<Span> spans, String fallback, Consumer<String> onLink,
                                 Function<String, ContextMenu> linkMenu, String textStyleClass,
                                 double size, boolean bold) {
        TextFlow flow = new TextFlow();
        if (spans.isEmpty()) {
            Text text = new Text(fallback == null ? "" : fallback);
            text.setFont(Font.font(Font.getDefault().getFamily(),
                    bold ? FontWeight.BOLD : FontWeight.NORMAL, size > 0 ? size : 14));
            text.getStyleClass().add(textStyleClass);
            flow.getChildren().add(text);
        }
        for (Span s : spans) {
            String style = s.style == null ? "" : s.style;
            boolean isCode = style.contains("code");
            Font font = Font.getDefault();
            font = Font.font(isCode ? "Consolas" : font.getFamily(),
                    bold || style.contains("bold") ? FontWeight.BOLD : FontWeight.NORMAL,
                    style.contains("italic") ? FontPosture.ITALIC : FontPosture.REGULAR,
                    size > 0 ? size : 14);
            if (s.href != null) {
                Hyperlink link = link(s.text, s.href, onLink, linkMenu);
                link.setFont(font);
                flow.getChildren().add(link);
            } else {
                Text text = new Text(s.text);
                text.setFont(font);
                text.setStrikethrough(style.contains("strike"));
                text.getStyleClass().add(isCode ? "md-inline-code" : textStyleClass);
                flow.getChildren().add(text);
            }
        }
        return flow;
    }

    /** 与分页链接条共享交互规则；右键时才向调用方索取菜单，不自行创建打开动作。 */
    static Hyperlink link(String label, String href, Consumer<String> onLink,
                          Function<String, ContextMenu> linkMenu) {
        Hyperlink link = new Hyperlink(label);
        link.setWrapText(true);
        link.setUserData(href);
        link.setTooltip(new Tooltip(href));
        link.setPadding(Insets.EMPTY);
        link.getStyleClass().add("md-link");
        link.setOnAction(event -> {
            if (onLink != null) onLink.accept(href);
        });
        link.setOnContextMenuRequested(event -> {
            ContextMenu menu = linkMenu == null ? null : linkMenu.apply(href);
            link.setContextMenu(menu);
            if (menu != null && link.getScene() != null && link.getScene().getWindow() != null
                    && link.getScene().getWindow().isShowing()) {
                menu.show(link, event.getScreenX(), event.getScreenY());
            }
            event.consume();
        });
        return link;
    }
}
