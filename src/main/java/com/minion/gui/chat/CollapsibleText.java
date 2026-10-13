package com.minion.gui.chat;

import com.minion.gui.icon.IconFactory;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/** 可折叠文本段：摘要行（可点击切换）+ 内容体（MessageTextArea 高度自适应）。
 *  内容 ≥COLLAPSE_THRESHOLD 字符时默认折叠，短内容默认展开；均可手动点击切换。
 *  折叠态内容体不参与布局（managed=false），滚动只由外层 ScrollPane 统一负责。
 *  摘要行 = [chevron 图标][摘要节点][收起/展开（N 字符）]，chevron 随折叠态切换图形（SVG，不依赖字体）。 */
public class CollapsibleText extends VBox {

    /** 默认折叠阈值：内容长度 ≥ 此值默认折叠（常量可调，不设设置项） */
    public static final int COLLAPSE_THRESHOLD = 500;

    private final MessageTextArea content;
    private final MessageTextPane textPane;
    private final HBox toggle;          // 摘要行（可点击）
    private final SVGPath chevron;      // 折叠态指示（expand_more/chevron_right）
    private final Label state;          // 「收起」/「展开（N 字符）」
    private final Node summaryNode;     // 摘要（String 构造时 = Label）
    private String text; // 流式思考段需更新，不可 final
    private boolean expanded;

    public CollapsibleText(String summary, String text, boolean defaultExpanded) {
        this(new Label(summary == null ? "" : summary), text, defaultExpanded);
    }

    public CollapsibleText(Node summary, String text, boolean defaultExpanded) {
        this.summaryNode = summary;
        this.text = text == null ? "" : text;
        this.expanded = defaultExpanded;
        getStyleClass().add("log-collapsible");
        setSpacing(2);

        chevron = new SVGPath();
        chevron.getStyleClass().add("icon-chevron");
        IconFactory.size(chevron, 12);
        state = new Label();
        state.getStyleClass().add("log-collapse-toggle");
        toggle = new HBox(4);
        toggle.getStyleClass().add("log-collapse-toggle");
        toggle.getChildren().addAll(chevron, summaryNode, state);
        toggle.setOnMouseClicked(e -> {
            if (text != null && !text.trim().isEmpty()) setExpanded(!expanded);
        });

        // 折叠的长工具输出/思考虽然不可见，旧版仍把全文交给 TextArea 测量，
        // 恢复长会话时会在 FX 线程产生大量无用布局。仅展开时装入正文。
        textPane = new MessageTextPane(expanded ? this.text : "");
        content = textPane.contentArea();
        content.getStyleClass().add("log-body");

        getChildren().addAll(toggle, textPane);
        update();
    }

    /** 内容体（焦点治理等需要直接访问内部 TextArea 的场景） */
    public MessageTextArea contentArea() { return content; }

    public boolean isExpanded() { return expanded; }

    public void setExpanded(boolean v) {
        if (expanded != v) {
            expanded = v;
            textPane.setText(v ? text : "", false);
        }
        update();
    }

    /** 流式内容更新（思考段）：更新正文并强制展开——流式过程内容持续增长，
     *  折叠会遮挡实时输出；思考结束由 finalizeThinking 收起，工具仍按长度定稿 */
    public void setStreamText(String t) {
        this.text = t == null ? "" : t;
        expanded = true;
        textPane.setText(this.text, true);
        update();
    }

    /** 内容定稿：按最终长度决定折叠态（≥阈值折叠、短内容展开），流式结束后调用一次 */
    public void finalizeLength() {
        setExpanded(!shouldCollapse(text));
    }

    /** 思考结束即收起，短思考也不占正文空间；仍可手动展开完整分页。 */
    public void finalizeThinking() { setExpanded(false); }

    private void update() {
        boolean hasText = text != null && !text.trim().isEmpty();
        // 空正文（统计行/子任务行等纯摘要段）：无内容可展开，不渲染展开按钮，直接展示摘要行
        chevron.setVisible(hasText);
        chevron.setManaged(hasText);
        state.setVisible(hasText);
        state.setManaged(hasText);
        textPane.setVisible(hasText && expanded);
        textPane.setManaged(hasText && expanded);
        if (hasText) {
            chevron.setContent(expanded ? IconFactory.CHEVRON_DOWN_PATH : IconFactory.CHEVRON_RIGHT_PATH);
            state.setText(expanded ? "收起" : "展开（" + text.length() + " 字符）");
        }
    }

    /** 默认折叠判定（纯逻辑可单测）：null/空不折叠 */
    public static boolean shouldCollapse(String text) {
        return text != null && text.length() >= COLLAPSE_THRESHOLD;
    }
}
