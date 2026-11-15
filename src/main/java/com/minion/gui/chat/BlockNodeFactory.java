package com.minion.gui.chat;

import com.minion.gui.chat.MarkdownRenderer.Block;
import com.minion.gui.chat.MarkdownRenderer.Span;
import com.minion.gui.chat.MarkdownRenderer.TableRowData;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Block 结构 → JavaFX 节点（样式类与 theme.css 对应） */
public class BlockNodeFactory {

    /** 正文/表格文字颜色：Text 节点响应 -fx-fill（-fx-text-fill 仅对 Label 生效，根因在此） */
    private static final String TEXT_FILL = "#f0f2f6";
    private static final String TABLE_FILL = "#c9d1d9";
    private static final String CODE_FILL = "#79c0ff";

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
                    return flow(b.spans, b.text, onLink, linkMenu, "#e6e8ee", headingSize, true);
                }
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.setMaxWidth(Double.MAX_VALUE);
                l.setStyle("-fx-font-size: " + headingSize
                        + "px; -fx-font-weight: bold; -fx-text-fill: #e6e8ee;");
                return l;
            }
            case CODE: {
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.getStyleClass().add("code-block");
                return l;
            }
            case PARAGRAPH: {
                TextFlow flow = flow(b.spans, b.text, onLink, linkMenu, TEXT_FILL, bodySize, false);
                flow.setPadding(new Insets(2, 0, 2, 0));
                return flow;
            }
            case LIST: {
                VBox box = new VBox(2);
                for (Block item : b.items) {
                    HBox row = new HBox(6);
                    Label bullet = new Label("•");
                    bullet.getStyleClass().add("msg-thinking");
                    TextFlow flow = flow(item.spans, item.text, onLink, linkMenu, TEXT_FILL, bodySize, false);
                    flow.setMinWidth(0);
                    HBox.setHgrow(flow,javafx.scene.layout.Priority.ALWAYS);
                    row.getChildren().addAll(bullet, flow);
                    box.getChildren().add(row);
                }
                return box;
            }
            case QUOTE: {
                if (hasLinks(b.spans)) {
                    TextFlow flow = flow(b.spans, b.text, onLink, linkMenu, TEXT_FILL, bodySize, false);
                    flow.setStyle("-fx-border-color: #4f8cff; -fx-border-width: 0 0 0 3; -fx-padding: 4 8 4 8;");
                    return flow;
                }
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.getStyleClass().add("msg-thinking");
                l.setStyle("-fx-border-color: #4f8cff; -fx-border-width: 0 0 0 3; -fx-padding: 4 8 4 8;");
                return l;
            }
            case TABLE: {
                GridPane grid = new GridPane();
                grid.setHgap(16);
                grid.setVgap(4);
                grid.getStyleClass().add("code-block");
                int rowIdx = 0;
                for (TableRowData r : b.rows) {
                    for (int c = 0; c < r.cells.size(); c++) {
                        if (c < r.cellSpans.size() && hasLinks(r.cellSpans.get(c))) {
                            grid.add(flow(r.cellSpans.get(c), r.cells.get(c), onLink, linkMenu,
                                    TABLE_FILL, bodySize, r.header), c, rowIdx);
                            continue;
                        }
                        Text t = new Text(r.cells.get(c));
                        t.setFont(Font.font(t.getFont().getFamily(),bodySize));
                        if (r.header) {
                            t.setFont(Font.font(t.getFont().getFamily(), FontWeight.BOLD, t.getFont().getSize()));
                        }
                        t.setFill(javafx.scene.paint.Color.web(TABLE_FILL));
                        grid.add(t, c, rowIdx);
                    }
                    rowIdx++;
                }
                return grid;
            }
            default:
                return new Label(b.text == null ? "" : b.text);
        }
    }

    private static boolean hasLinks(List<Span> spans) {
        for (Span span : spans) if (span.href != null) return true;
        return false;
    }

    private static TextFlow flow(List<Span> spans, String fallback, Consumer<String> onLink,
                                 Function<String, ContextMenu> linkMenu, String fill, double size, boolean bold) {
        TextFlow flow = new TextFlow();
        if (spans.isEmpty()) {
            Text text = new Text(fallback == null ? "" : fallback);
            text.setFont(Font.font(Font.getDefault().getFamily(),size>0?size:14));
            text.setFill(javafx.scene.paint.Color.web(fill));
            flow.getChildren().add(text);
        }
        for (Span s : spans) {
            String style = s.style == null ? "" : s.style;
            Font font = Font.getDefault();
            font = Font.font(style.contains("code") ? "Consolas" : font.getFamily(),
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
                text.setFill(javafx.scene.paint.Color.web(style.contains("code") ? CODE_FILL : fill));
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
        link.setStyle("-fx-text-fill: " + CODE_FILL + ";");
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
