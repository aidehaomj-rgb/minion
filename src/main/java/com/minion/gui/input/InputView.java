package com.minion.gui.input;

import com.minion.core.config.Config;
import com.minion.core.llm.ImagePart;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.ViewImageTool;
import com.minion.gui.icon.IconFactory;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.embed.swing.SwingFXUtils;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 底部输入区：4/9 宽居中大框（上=块行+输入框，下=底部操作行：上传按钮左 + 发送按钮右）+ /命令与 @文件补全弹层。
 *  @文件确认后内联进输入框（@路径 文本，所见即所得；扫描异步后台线程，不卡输入）；/命令、/技能、粘贴、图片为块。
 *  按钮语义：上箭头=发送/补充/回答、变淡箭头=空输入或等待回答、方块=终止（提问挂起时改 Esc 终止）；
 *  背景按状态取色（btn-send-empty #f48771 / btn-send-full #ff947c）。上传按钮（回形针）支持图片和常用文档。
 *  运行中 + 有内容 → 放入待引导区，点击“立即引导”后才补充；等待回答 + 有内容 → 回答；运行中 + 空 → 终止。 */
public class InputView extends VBox {
    /** Early Minion centered the composer at 61.8%; keep it usable in a split and capped on ultrawide. */
    private static final double COMPOSER_WIDTH_SHARE = .618;
    private static final double COMPOSER_COMFORT_WIDTH = 500;
    private static final double COMPOSER_MAX_WIDTH = 960;

    /** 按钮模式：图标/透明度/背景类/动作的判定依据（ANSWER_DIM=提问挂起且空输入，变淡箭头等待输入回答） */
    enum BtnMode { SEND, SEND_DIM, SUPPLEMENT, ANSWER, ANSWER_DIM, STOP }

    private final SessionManager manager;
    private final Config config;
    // Route the native context menu through the same routing as keyboard paste.
    private final TextArea input = new TextArea() {
        @Override public void paste() { pasteFromSystemClipboard(); }
    };
    private final Button sendButton = new Button();
    private final Button uploadButton = new Button();
    private final HBox bottomRow = new HBox(6);
    private final HBox pendingGuideRow = new HBox(8);
    private final Label pendingGuideLabel = new Label();
    private final Button guideNowButton = new Button("立即引导");
    private final Button editGuideButton = new Button("编辑");
    private final Button cancelGuideButton = new Button("取消");
    private final AtomicLong bindingGeneration = new AtomicLong();
    private final ContextRing contextRing = new ContextRing();
    private final SVGPath arrowIcon = IconFactory.send();
    private final SVGPath stopIcon = IconFactory.stop();
    private final SVGPath uploadIcon = IconFactory.attachFile();
    private final SuggestionPopup popup = new SuggestionPopup();
    private final FileSuggester fileSuggester = new FileSuggester();
    /** 块行与块列表：模型 List<InputChip> 与视图 FlowPane 同步维护（增删块后须 refreshChipRow + updateButton） */
    private final List<InputChip> chips = new ArrayList<InputChip>();
    private final FlowPane chipRow = new FlowPane();
    /** 粘贴块占位符序号：保证同框内占位符唯一（[粘贴块N]） */
    private int pasteSeq;
    /** 占位符 → 最近被移除的粘贴块：文本撤销（Ctrl+Z）使占位符重现时恢复块，避免占位文本原样发出 */
    private final Map<String, InputChip> droppedPastes = new LinkedHashMap<String, InputChip>();
    private final Map<SessionHandle,ComposerDraft> drafts=new WeakHashMap<SessionHandle,ComposerDraft>();
    private boolean restoringComposer;
    private long appliedBinding;
    /** reconcile 重入锁：清理占位残片改文本会再触发文本监听 */
    private boolean reconciling;
    private VBox frame; // 大框（弹层锚点）
    /** 输入区三列布局；第 0 列用于运行状态提示，第 1 列保持输入框 61.8% 宽。 */
    private final VBox composerShell = new VBox(6);
    private CompletionParser.Token lastToken; // 弹层可见时待替换的词
    /** 最近一次 @文件确认内联插入的完整文本（@路径）：该词保持原样时抑制弹层重开 */
    private String inlineJustInserted;
    /** @文件列表后台加载中（防抖：同时只跑一次 walk，完成后自动续载排队目录） */
    private volatile boolean fileLoading;
    /** 加载期间的排队目录（切工作空间连打 @ 时登记，完成后自动续载） */
    private String pendingLoadDir;
    /** @文件扫描线程（daemon 单线程）：同步遍历移出 FX 线程，大项目首次 @ 不卡输入框 */
    private final ExecutorService filePool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-suggester");
        t.setDaemon(true);
        return t;
    });
    /** 附件解析线程（daemon 单线程）：图片解码/文档提取/PNG 编码移出 FX 线程，选多个大附件不再冻结界面 */
    private final ExecutorService attachmentPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minion-attachments");
        t.setDaemon(true);
        return t;
    });
    private volatile SessionHandle current;
    // FX 线程缓存的状态（bindSession/onRunningChanged/onAskChanged 维护）
    private boolean running;
    private boolean askPending;
    private String askQuestion;
    private String askQuestionId;
    /** 运行中用户先暂存、尚未点击“立即引导”的内容；不得提前进入模型或聊天事件流。 */

    public InputView(final SessionManager manager, final Config config) {
        this.manager = manager;
        this.config = config;
        getStyleClass().addAll("panel-dark", "workbench-composer");
        setAlignment(Pos.TOP_CENTER);

        input.getStyleClass().add("input-textarea");
        input.setTooltip(new Tooltip());
        input.setWrapText(true);
        input.setPrefRowCount(2); // 加一行
        input.setMaxHeight(6 * 24);
        input.setFocusTraversable(true);
        input.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if(!input.isFocused())input.requestFocus();
        });
        // reconcile 先于补全解析：占位符增删同步粘贴块后再解析，弹层与发送按钮基于最终文本
        input.textProperty().addListener((obs, ov, nv) -> { if(!restoringComposer){updateButton();reconcilePasteChips();onTextChanged();} });
        input.caretPositionProperty().addListener((obs, ov, nv) -> {if(!restoringComposer)onTextChanged();});
        // 弹层显示改为输入内容驱动：输入框失焦（点击聊天区/侧栏）时关闭，点击输入框本身不关；
        // 焦点进入时刷新文案——设置窗勾选发送键后点回输入框，提示与 tooltip 立即正确
        // 聚焦边框：聚焦时大框边框 #f48771，失焦还原 #232733（frame 构造晚于本监听注册，判空）
        input.focusedProperty().addListener((obs, ov, nv) -> {
            if (frame != null) {
                if (nv) frame.getStyleClass().add("input-frame-focused");
                else frame.getStyleClass().remove("input-frame-focused");
            }
            if (!nv) { popup.hide(); lastToken = null; }
            else { updateButton(); updatePrompt(); }
        });

        // 上箭头（Claude Code 同款语义：可发送）；方块 = 终止（图形与样式类由 IconFactory 提供）
        uploadButton.setGraphic(uploadIcon);
        uploadButton.setMinSize(30, 30);
        uploadButton.setPrefSize(30, 30);
        uploadButton.getStyleClass().add("btn-upload");
        uploadButton.setTooltip(new Tooltip("添加附件（图片、PDF、Word、Excel、PPT、文本/代码）"));
        uploadButton.setOnAction(e -> chooseAttachments());

        pendingGuideRow.getStyleClass().add("pending-guide-row");
        pendingGuideRow.setAlignment(Pos.CENTER_LEFT);
        pendingGuideLabel.getStyleClass().add("pending-guide-label");
        pendingGuideLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        pendingGuideLabel.setMinWidth(0);
        HBox.setHgrow(pendingGuideLabel, Priority.ALWAYS);
        guideNowButton.getStyleClass().add("btn-guide-now");
        guideNowButton.setOnAction(e -> sendPendingGuide());
        cancelGuideButton.getStyleClass().add("btn-guide-cancel");
        cancelGuideButton.setOnAction(e -> clearPendingGuide());
        editGuideButton.getStyleClass().add("btn-guide-cancel");
        editGuideButton.setOnAction(e -> {
            if(current!=null)com.minion.gui.dialog.QueueEditor.show(getScene()==null?null:getScene().getWindow(),manager,current,current.pendingGuide());
            refreshPendingGuideRow();
        });
        pendingGuideRow.getChildren().addAll(pendingGuideLabel, editGuideButton, guideNowButton, cancelGuideButton);
        pendingGuideRow.setVisible(false);
        pendingGuideRow.setManaged(false);

        sendButton.setMinSize(36, 36);
        sendButton.setPrefSize(36, 36);
        sendButton.setOnAction(e -> onAction());
        updateButton();

        // 鼠标点击弹层条目：直接插入（弹层侧回调文本，本类执行替换；根因修复：旧接线点击后无插入）
        popup.setOnConfirm(insert -> confirmInsert(insert));

        // 键盘用 capture 阶段过滤器而非 setOnKeyPressed：TextArea 默认按键行为（换行/光标移动）
        // 同为 target 内 handler，bubble 顺序不保证——行为先执行会移动光标触发 onTextChanged 重置弹层
        // 选中、Enter 插入换行关弹层，导致上下键/确认失效；过滤器先于一切 target 内 handler 执行
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean ctrl = e.isControlDown(), shift = e.isShiftDown(), alt = e.isAltDown(), meta = e.isMetaDown();
            boolean enterSends = config.enterSends();
            // 发送键：默认模式优先于弹层（同现状：弹层开着 Ctrl+Enter 也发送）；
            // Enter 发送模式弹层打开时不发送，Enter/Ctrl+Enter 均走弹层确认
            if (isSendKey(e.getCode(), ctrl, shift, alt, meta, enterSends) && !(enterSends && popup.isShowing())) {
                e.consume();
                onAction(false); // 发送键绝不能在清空输入后变成停止键。
                return;
            }
            // Enter 发送模式：弹层关闭时 Ctrl+Enter 显式插入换行（JavaFX TextArea 对 Ctrl+Enter 无默认换行绑定，须 replaceSelection）
            if (enterSends && !popup.isShowing() && e.getCode() == KeyCode.ENTER && ctrl && !shift && !alt && !meta) {
                e.consume();
                input.replaceSelection("\n");
                return;
            }
            if (!alt && !meta && ((ctrl && e.getCode() == KeyCode.V)
                    || (!ctrl && shift && e.getCode() == KeyCode.INSERT))) {
                e.consume();
                input.paste();
                return;
            }
            // 补全弹层优先：↑↓ 移动、Enter/Tab 确认、Esc 仅关弹层
            if (popup.isShowing()) {
                switch (e.getCode()) {
                    case UP:    popup.move(-1); e.consume(); break;
                    case DOWN:  popup.move(1);  e.consume(); break;
                    case TAB:
                    case ENTER: confirmPopup(); e.consume(); break;
                    case ESCAPE: popup.hide(); lastToken = null; e.consume(); break;
                }
                return;
            }
            // 空文本时 Backspace/Delete 删除最后一个块
            if ((e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.DELETE)
                    && !chips.isEmpty() && input.getText().isEmpty()) {
                removeLastChip();
                e.consume();
                return;
            }
            // Esc：终止当前运行（提问挂起时亦可终止）
            if (e.getCode() == KeyCode.ESCAPE && current != null && running) {
                e.consume();
                manager.stop(current);
            }
        });

        // 大框：上=块行+输入框，下=底部操作行（上传按钮左 + 弹性空白 + 发送按钮右）
        frame = new VBox(6);
        frame.getStyleClass().add("input-frame");
        frame.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if(e.getTarget() instanceof Node && containsNode(input,(Node)e.getTarget()))return;
            if(!input.isFocused())input.requestFocus();
            if(input.getCaretPosition()<0)input.positionCaret(input.getLength());
        });
        frame.setOnDragOver(e -> {
            if (e.getGestureSource() != frame && e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        frame.setOnDragDropped(e -> {
            Dragboard board = e.getDragboard();
            boolean ok = board.hasFiles();
            if (ok) addAttachments(board.getFiles());
            e.setDropCompleted(ok);
            e.consume();
        });
        // 块行（上方）+ 文本区（下方）：chipRow 无块时 unmanaged 不占位
        chipRow.getStyleClass().add("chip-row");
        VBox composer = new VBox(6);
        composer.getChildren().addAll(chipRow, input);
        VBox.setVgrow(composer, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        bottomRow.getStyleClass().add("composer-actions");
        bottomRow.getChildren().addAll(uploadButton, contextRing, spacer, sendButton);
        frame.getChildren().addAll(pendingGuideRow, composer, bottomRow);

        // 启动/未选会话：环形圈初始隐藏（bindSession(null) 与 onContextStats 无数据时均不显示，避免空白 Tooltip 颜色块）
        contextRing.setVisible(false);
        contextRing.setManaged(false);

        // 点击环形圈 → 会话工作线程立即压缩（非运行中且超 30% 才可点；压缩事件回调驱动旋转动画与刷新）
        contextRing.setOnCompress(new Runnable() {
            @Override public void run() {
                SessionHandle h = current;
                if (h == null || h.running) return;
                contextRing.setCompressing(true); // 提交后立即防重；onCompressingChanged(false) 自然恢复
                h.pool.execute(new Runnable() {
                    @Override public void run() { h.loop.compactNow(); }
                });
            }
        });

        // Center within the conversation column, not across the sidebar or report.
        // Use all available space instead of pinching the editor in a narrow split.
        composerShell.setMinWidth(0);
        composerShell.maxWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(() -> {
            double available = Math.max(0, getWidth() - getInsets().getLeft() - getInsets().getRight());
            return Math.min(available, Math.min(COMPOSER_MAX_WIDTH,
                    Math.max(COMPOSER_COMFORT_WIDTH, available * COMPOSER_WIDTH_SHARE)));
        }, widthProperty(), insetsProperty()));
        composerShell.getChildren().add(frame);
        getChildren().add(composerShell);
        updatePrompt(); // askPending 默认 false，显示正常发送键提示
    }

    /** 状态独占一行，不挤占窄栏的输入框宽度。 */
    public void setStatusNode(Node node) {
        if (node == null) return;
        node.managedProperty().bind(node.visibleProperty());
        composerShell.getChildren().add(0, node);
    }

    /** 输入框底部固定入口：放在发送箭头左侧，不受会话页签宽度影响。 */
    public void setModelSelector(Node selector) {
        if (selector == null) return;
        bottomRow.getChildren().add(bottomRow.getChildren().size() - 1, selector);
    }

    /** 确认插入的最终文本：@ 文件补全补回 @ 前缀（FileSuggester 的 insertText 为纯路径）。
     *  纯静态可脱离 JavaFX 单测 */
    static String insertionText(CompletionParser.Mode mode, String insert) {
        return mode == CompletionParser.Mode.FILE && !insert.startsWith("@") ? "@" + insert : insert;
    }

    /** 内联确认后的安静态：光标处解析出的 FILE 词恰好等于刚插入的 @路径 全文时不重开弹层。
     *  词被改动（增删字符）、光标移到别的词、插入文本为空时自然恢复。纯静态可脱离 JavaFX 单测 */
    static boolean isInlineQuietState(String text, int caret, String inlineJustInserted) {
        if (inlineJustInserted == null || inlineJustInserted.isEmpty() || text == null) return false;
        CompletionParser.Token t = CompletionParser.parse(text, caret);
        if (t.mode != CompletionParser.Mode.FILE) return false;
        if (t.start < 0 || t.end > text.length() || t.end <= t.start) return false;
        return text.substring(t.start, t.end).equals(inlineJustInserted);
    }

    /** 块行占位开关：无块时 unmanaged（VBox 布局忽略，不产生多余间距） */
    private void refreshChipRow() {
        boolean has = !chips.isEmpty();
        chipRow.setManaged(has);
        chipRow.setVisible(has);
    }

    /** 追加块到尾部（模型 + 视图同步） */
    private void addChip(InputChip chip) {
        if (chip == null) return;
        chips.add(chip);
        chipRow.getChildren().add(chipView(chip));
        refreshChipRow();
        updateButton();
    }

    /** 删除第 i 个块（越界静默忽略） */
    private void removeChipAt(int i) {
        if (i < 0 || i >= chips.size()) return;
        chips.remove(i);
        chipRow.getChildren().remove(i);
        refreshChipRow();
        updateButton();
    }

    /** 删除最后一个块（Backspace 用）；无块返回 false */
    private boolean removeLastChip() {
        if (chips.isEmpty()) return false;
        removeChipAt(chips.size() - 1);
        return true;
    }

    /** 文本变化同步粘贴块（占位符是块在输入文本中的锚点）：
     *  占位符消失 → 删块并清理残留碎片（Backspace 逐字删占位符时整块移除）；
     *  占位符重现（Ctrl+Z 撤销删除）→ 从 droppedPastes 恢复块，避免占位文本原样发出。
     *  reconciling 防重入：清理碎片改文本会再次触发文本监听 */
    private void reconcilePasteChips() {
        if (reconciling) return;
        reconciling = true;
        try {
            String text = input.getText() == null ? "" : input.getText();
            for (int i = chips.size() - 1; i >= 0; i--) {
                InputChip c = chips.get(i);
                if (c.type != InputChip.Type.PASTE || c.placeholder == null || text.contains(c.placeholder)) continue;
                removeChipAt(i);
                droppedPastes.put(c.placeholder, c);
                removePlaceholderRemnants(c.placeholder);
            }
            String now = input.getText() == null ? "" : input.getText();
            Iterator<Map.Entry<String, InputChip>> it = droppedPastes.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, InputChip> en = it.next();
                if (now.contains(en.getKey())) {
                    addChip(en.getValue());
                    it.remove();
                }
            }
        } finally {
            reconciling = false;
        }
    }

    /** 占位符整体消失后清理残留碎片（用户逐字删除占位符的场景）：
     *  逐次查找文本中仍存在的最长前缀/后缀碎片（≥3 字符）并 deleteText 移除（光标留在删除点）。
     *  碎片若同时是其他在用占位符的子串（如「[粘贴块1」之于「[粘贴块12]」）则跳过，防止误伤整块 */
    private void removePlaceholderRemnants(String placeholder) {
        while (true) {
            String hit = null;
            for (int k = placeholder.length() - 1; k >= 3 && hit == null; k--) {
                String frag = placeholder.substring(0, k);
                if (input.getText().contains(frag) && !fragmentInOtherPlaceholder(frag)) hit = frag;
            }
            for (int k = 1; k <= placeholder.length() - 3 && hit == null; k++) {
                String frag = placeholder.substring(k);
                if (input.getText().contains(frag) && !fragmentInOtherPlaceholder(frag)) hit = frag;
            }
            if (hit == null) return;
            int idx = input.getText().indexOf(hit);
            input.deleteText(idx, idx + hit.length());
        }
    }

    /** 碎片是否仍属于其他在用块的占位符（防清理碎片误删其他块的占位符） */
    private boolean fragmentInOtherPlaceholder(String frag) {
        for (InputChip c : chips) {
            if (c != null && c.placeholder != null && c.placeholder.contains(frag)) return true;
        }
        return false;
    }

    /** 长粘贴块可以查看原文，其余块保持原有行为。 */
    private Node chipView(InputChip chip) {
        Label label = new Label(chip.display);
        label.getStyleClass().add("chip-label");
        SVGPath closeIcon = IconFactory.close();
        IconFactory.size(closeIcon, 11);
        Button close = new Button();
        close.setGraphic(closeIcon);
        close.getStyleClass().add("chip-close");
        close.setOnAction(e -> {
            removeChipAt(chips.indexOf(chip));
            // 粘贴块联动删除光标处占位符；记入 droppedPastes 使 Ctrl+Z 恢复占位文本时块一同恢复
            if (chip.type == InputChip.Type.PASTE && chip.placeholder != null) {
                droppedPastes.put(chip.placeholder, chip);
                int idx = input.getText().indexOf(chip.placeholder);
                if (idx >= 0) input.deleteText(idx, idx + chip.placeholder.length());
            }
            input.requestFocus();
        });
        HBox box = new HBox(6);
        box.getStyleClass().addAll("input-chip", chipTypeClass(chip.type));
        if(chip.type==InputChip.Type.PASTE){
            Button preview=new Button(chip.display+" · 查看全文");
            preview.getStyleClass().addAll("chip-label","chip-preview");
            preview.setOnAction(e->showPastedText(chip));
            box.getChildren().addAll(preview,close);
        }else box.getChildren().addAll(label, close);
        if (chip.type != InputChip.Type.PASTE && chip.content.length() > 40) {
            Tooltip.install(box, new Tooltip(chip.content));
        }
        return box;
    }

    private void showPastedText(InputChip chip){
        if(!chips.contains(chip))return;
        Dialog<ButtonType> dialog=new Dialog<ButtonType>();
        dialog.setTitle(chip.display);
        if(getScene()!=null&&getScene().getWindow()!=null)dialog.initOwner(getScene().getWindow());
        com.minion.gui.theme.Theme.style(dialog);
        TextArea body=new TextArea(chip.content);
        body.setEditable(false);body.setWrapText(false);body.setPrefSize(620,330);
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    /** 块类型 → 语义样式类 */
    private static String chipTypeClass(InputChip.Type type) {
        switch (type) {
            case COMMAND: return "input-chip-command";
            case SKILL:   return "input-chip-skill";
            case FILE:    return "input-chip-file";
            case IMAGE:   return "input-chip-image";
            default:      return "input-chip-paste";
        }
    }

    /** 纯静态判定（可脱离 JavaFX 单测）：运行/提问挂起/有内容 → 按钮模式。
     *  提问挂起时模型在等待回答而非忙碌，空输入显示变淡箭头而非终止方块 */
    static BtnMode buttonMode(boolean running, boolean askPending, boolean hasContent) {
        if (!running) return hasContent ? BtnMode.SEND : BtnMode.SEND_DIM;
        if (askPending) return hasContent ? BtnMode.ANSWER : BtnMode.ANSWER_DIM;
        return hasContent ? BtnMode.SUPPLEMENT : BtnMode.STOP;
    }

    /** 键盘发送没有“停止”语义，空输入/按键自动重复只消费事件。 */
    static BtnMode keyboardMode(boolean running, boolean askPending, boolean hasContent) {
        BtnMode mode = buttonMode(running, askPending, hasContent);
        return mode == BtnMode.STOP ? BtnMode.SEND_DIM : mode;
    }

    /** 文本/光标变化 → 重新解析补全模式并刷新弹层（弹层异常不得打断输入，兜底隐藏） */
    private void onTextChanged() {
        if(restoringComposer||appliedBinding!=bindingGeneration.get())return;
        try {
            String text = input.getText();
            int caret = input.getCaretPosition();
            // 内联确认安静态：刚插入的 @路径 词未改动时不再弹层（词被改动/光标移走自然恢复）
            if (isInlineQuietState(text, caret, inlineJustInserted)) return;
            CompletionParser.Token t = CompletionParser.parse(text, caret);
            switch (t.mode) {
                case SLASH:
                    popup.show(frame, SlashSuggester.all(manager.currentSkills()), t.query);
                    lastToken = t;
                    break;
                case SLASH_SKILL:
                    popup.show(frame, SlashSuggester.skillEntries(manager.currentSkills()), t.query);
                    lastToken = t;
                    break;
                case FILE: {
                    lastToken = t;
                    String dir = manager.currentWorkspaceDir();
                    if (dir == null) { popup.hide(); lastToken = null; break; }
                    showFilePopup(dir, t); // 缓存命中同步显示；未命中后台扫描后刷新
                    break;
                }
                default:
                    popup.hide();
                    lastToken = null;
            }
        } catch (Exception ex) {
            popup.hide(); // 弹层为增强体验，任何异常不打断输入
            lastToken = null;
        }
    }

    /** @文件弹层数据源：任何缓存（含过期）先即时显示，输入零等待；过期/无缓存时
     *  派 requestFileLoad 后台刷新，完成后列表有变化才替换弹层（showsSame 免闪烁） */
    private void showFilePopup(final String dir, final CompletionParser.Token t) {
        List<Suggestion> any = fileSuggester.listStaleOk(dir);
        if (any != null) {
            popup.show(frame, any, t.query); // 即时显示：过期也先用旧缓存
            if (fileSuggester.isFresh(dir)) return; // 新鲜缓存，无需后台刷新
        }
        requestFileLoad(dir);
    }

    /** 请求后台扫描某工作空间文件列表（新鲜缓存视为完成，直接返回）。
     *  同一时刻至多一次 walk（fileLoading 防抖）；加载中再收到新目录则排队（pendingLoadDir），
     *  完成后自动续载——切项目连打 @ 不丢首词弹层。加载完成回调用最新词统一刷新 */
    private void requestFileLoad(final String dir) {
        if (dir == null || fileSuggester.isFresh(dir)) return;
        if (fileLoading) {
            pendingLoadDir = dir;
            return;
        }
        fileLoading = true;
        filePool.execute(new Runnable() {
            @Override public void run() {
                final List<Suggestion> files = fileSuggester.load(dir); // IO 在后台线程
                Platform.runLater(new Runnable() {
                    @Override public void run() {
                        fileLoading = false;
                        String next = pendingLoadDir;
                        pendingLoadDir = null;
                        maybeShowFilePopup(dir, files);
                        if (next != null && !next.equals(dir)) requestFileLoad(next); // 续载排队目录
                    }
                });
            }
        });
    }

    /** 扫描完成回调：上下文仍有效（词仍是 @文件、非内联安静态、工作空间未切换）才以最新词刷新弹层；
     *  刷新结果与当前展示一致（showsSame）则跳过重建；过期结果一律丢弃 */
    private void maybeShowFilePopup(String dir, List<Suggestion> files) {
        CompletionParser.Token cur = lastToken;
        if (cur == null || cur.mode != CompletionParser.Mode.FILE) return; // 词已改/弹层已关
        if (isInlineQuietState(input.getText(), input.getCaretPosition(), inlineJustInserted)) return;
        String dirNow = manager.currentWorkspaceDir();
        if (dirNow == null || !dirNow.equals(dir)) return; // 工作空间已切换
        if (!popup.showsSame(files, cur.query)) popup.show(frame, files, cur.query);
    }

    /** 鼠标点击弹层条目回调：统一 hide + runLater 确认（与键盘同序，插入由 confirmApply 执行） */
    private void confirmInsert(String insert) {
        if (insert == null || lastToken == null) return;
        final CompletionParser.Token t = lastToken;
        lastToken = null;
        popup.hide();
        confirmApply(t, insert);
    }

    /** 键盘确认弹层选中：取插入文本 → 关弹层 → runLater 统一确认。
     *  runLater 保持原时序（KEY_PRESSED 派发期间改输入区与 behavior 竞争的老规避惯例，统一路径） */
    private void confirmPopup() {
        if (lastToken == null) return;
        final CompletionParser.Token t = lastToken;
        final String insert = popup.confirmSelected();
        popup.hide();
        lastToken = null;
        if (insert == null) return;
        confirmApply(t, insert);
    }

    /** 统一确认执行：@文件内联进输入框（不建块，所见即所得）；其余模式（/命令、/技能）建块。
     *  runLater 执行避免事件派发期间改输入区 */
    private void confirmApply(final CompletionParser.Token t, final String insert) {
        if (t == null || insert == null || insert.isEmpty()) return;
        final SessionHandle owner=current;
        final long binding=bindingGeneration.get();
        final String original=input.getText();
        Platform.runLater(new Runnable() {
            @Override public void run() {
                if(current!=owner||binding!=bindingGeneration.get()||!original.equals(input.getText()))return;
                if (t.mode == CompletionParser.Mode.FILE) {
                    // 内联：token 区间整体替换为 @路径，光标置于插入文本末尾；不再建块
                    final String inline = insertionText(t.mode, insert);
                    inlineJustInserted = inline; // 先于 replaceText：其触发的解析命中安静态不重开弹层
                    try {
                        input.replaceText(t.start, t.end, inline);
                        input.positionCaret(t.start + inline.length());
                    } catch (RuntimeException ex) {
                        inlineJustInserted = null; // 坐标越界：用户已改文本，静默放弃
                    }
                    input.requestFocus();
                } else {
                    addChipFor(t, insert); // 命令/技能块：删 token 并建块
                }
            }
        });
    }

    /** 按 token 建块并追加（/命令、/技能 等非 @文件模式）：@ 模式补回 @ 前缀；
     *  先删除 token 区间已输入的部分词（坐标过期=用户已改文本，整体放弃不建块）；空插入忽略 */
    private void addChipFor(CompletionParser.Token t, String insert) {
        if (insert == null || insert.isEmpty()) return;
        String text = insertionText(t.mode, insert);
        try {
            input.deleteText(t.start, t.end);
        } catch (RuntimeException ex) {
            return; // 坐标越界：用户已改文本，静默放弃
        }
        addChip(InputChip.textChip(InputChip.modeToType(t.mode), text));
    }

    /** 仅 FX 线程调用（MainWindow 的 runLater 内）；不加 synchronized——对 Node 加锁反而扩大外部死锁面 */
    public void bindSession(SessionHandle h) {
        final long binding = bindingGeneration.incrementAndGet();
        Runnable apply=() -> {
            if(bindingGeneration.get()!=binding)return;
            final SessionHandle previous=current;
            if(previous!=h||h==null){
                saveDraft(previous);
                current=h;
                restoreDraft(h);
            }
            appliedBinding=binding;
            running = h != null && h.running;
            askPending = h != null && h.askPending;
            askQuestion = h == null ? null : h.askQuestion;
            askQuestionId = h == null || h.loop == null ? null : h.loop.pendingAskUserId();
            updateButton();
            updatePrompt();
            refreshPendingGuideRow();
            Platform.runLater(() -> {
                if(!isDisabled()&&getScene()!=null)focusInput();
            });
            // @文件列表预热：绑定会话即后台扫描所属工作空间（每项目独立缓存 5 分钟；
            // 缓存命中零开销——同项目内切会话/切回均不再扫描）
            final String dir = manager.currentWorkspaceDir();
            if (dir != null) requestFileLoad(dir);
            // 环形圈：切会话先隐藏，初始估算放会话线程（全量估算较重，防卡 UI），完成后 runLater 填充
            contextRing.setCompressing(false);
            contextRing.setVisible(false);
            contextRing.setManaged(false);
            if (h != null && h.loop != null && h.loop.contextManager() != null) {
                final SessionHandle target = h;
                h.pool.execute(new Runnable() {
                    @Override public void run() {
                        final int used = target.loop.contextManager().estimate(target.session.messages);
                        final int max = target.loop.contextManager().maxTokens();
                        Platform.runLater(new Runnable() {
                            @Override public void run() { onContextStats(target, used, max); }
                        });
                    }
                });
            }
        };
        // FX navigation switches text and owner together, not on a later pulse with a new owner and old text.
        if(Platform.isFxApplicationThread())apply.run();else Platform.runLater(apply);
    }

    /** Focus the native text editor without changing the existing caret position. */
    public void focusInput(){
        if(!Platform.isFxApplicationThread()){Platform.runLater(this::focusInput);return;}
        if(isDisabled()||!isVisible())return;
        input.requestFocus();
        if(input.getCaretPosition()<0)input.positionCaret(input.getLength());
    }

    private static boolean containsNode(Node parent,Node possibleChild){
        for(Node node=possibleChild;node!=null;node=node.getParent())if(node==parent)return true;
        return false;
    }

    private void saveDraft(SessionHandle owner){
        if(owner==null)return;
        if(owner.deleted||input.getText().isEmpty()&&chips.isEmpty()){drafts.remove(owner);return;}
        drafts.put(owner,new ComposerDraft(input.getText(),input.getAnchor(),input.getCaretPosition(),input.getScrollTop(),input.getScrollLeft(),pasteSeq,inlineJustInserted,chips,droppedPastes));
    }
    private void restoreDraft(SessionHandle owner){
        ComposerDraft draft=owner==null||owner.deleted?null:drafts.remove(owner);
        restoringComposer=true;
        try{
            chips.clear();chipRow.getChildren().clear();droppedPastes.clear();pasteSeq=0;inlineJustInserted=null;
            popup.hide();lastToken=null;pendingLoadDir=null;
            // setText resets TextInputControl's undo history; clear()/deleteText() would allow cross-chat Ctrl+Z.
            input.setText("");
            if(draft!=null){
                pasteSeq=draft.pasteSeq;inlineJustInserted=draft.inline;droppedPastes.putAll(draft.dropped);
                chips.addAll(draft.chips);for(InputChip chip:chips)chipRow.getChildren().add(chipView(chip));
                input.setText(draft.text);input.selectRange(draft.anchor,draft.caret);
                input.setScrollTop(draft.scrollTop);input.setScrollLeft(draft.scrollLeft);
            }
            refreshChipRow();
        }finally{restoringComposer=false;}
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        final long binding=bindingGeneration.get();
        Platform.runLater(() -> {
            if (current != h||binding!=bindingGeneration.get()) return;
            this.running = h.running; // 延迟的旧回调不能覆盖当前真实运行状态。
            updateButton();
            updatePrompt();
            refreshPendingGuideRow();
            contextRing.setRunning(this.running);
        });
    }

    /** AskUserQuestion 挂起状态变化（MainWindow 转发自 SessionManager 监听） */
    public void onAskChanged(SessionHandle h, boolean asking, String question) {
        final long binding=bindingGeneration.get();
        Platform.runLater(() -> {
            if (current != h||binding!=bindingGeneration.get()) return;
            this.askPending = h.askPending;
            this.askQuestion = h.askQuestion;
            this.askQuestionId = h.loop == null ? null : h.loop.pendingAskUserId();
            updateButton();
            updatePrompt();
        });
    }

    /** 上下文统计更新（MainWindow 转发 SessionManager.Listener）：仅当前会话生效；
     *  无数据（max<=0）时隐藏不占位 */
    public void onContextStats(SessionHandle h, int used, int max) {
        final long binding=bindingGeneration.get();
        Platform.runLater(new Runnable() {
            @Override public void run() {
                if(current!=h||binding!=bindingGeneration.get())return;
                double threshold = 0.8; // 兜底；正常从 ContextManager 实时读取
                if (h!=null&&h.loop!=null&&h.loop.contextManager() != null && max > 0)
                    threshold = (double) h.loop.contextManager().autoCompressAtTokens() / max;
                boolean active = used > 0 && max > 0;
                contextRing.update(used, max, threshold, running);
                contextRing.setVisible(active);
                contextRing.setManaged(active);
            }
        });
    }

    /** 压缩状态变化（MainWindow 转发）：仅当前会话生效，环形旋转动画 */
    public void setCompressing(SessionHandle h, boolean compressing) {
        final long binding=bindingGeneration.get();
        Platform.runLater(new Runnable() {
            @Override public void run() {if(current==h&&binding==bindingGeneration.get())contextRing.setCompressing(compressing);}
        });
    }

    /** 块 + 文本组装发送文本 */
    private String composedText() {
        return InputChip.compose(chips, input.getText());
    }

    /** 发送后清空：块 + 文本区 + 弹层（块行 unmanaged 自动归位）；占位符状态一并重置 */
    private void clearComposer() {
        drafts.remove(current);
        chips.clear();
        chipRow.getChildren().clear();
        droppedPastes.clear();
        pasteSeq = 0;
        refreshChipRow();
        popup.hide();
        lastToken = null;
        inlineJustInserted = null;
        input.clear();
        updateButton();
    }

    private boolean hasContent() {
        return !chips.isEmpty() || (input.getText() != null && !input.getText().trim().isEmpty());
    }

    private void updatePrompt() {
        boolean enterSends = config.enterSends();
        input.getTooltip().setText(sendKeyLabel(enterSends) + " 发送 · "
                + (enterSends ? "Shift+Enter" : "Enter") + " 换行\n@ 引用文件 · / 命令");
        if (askPending) {
            String q = askQuestion == null ? "" : askQuestion;
            input.setPromptText("回答: " + (q.length() > 40 ? q.substring(0, 40) + "…" : q));
        } else if (running) {
            input.setPromptText("补充指令…");
        } else {
            input.setPromptText("输入消息…");
        }
    }

    /** 按钮模式 → 背景样式类：内容空与运行中 #f48771（btn-send-empty），有内容非运行 #ff947c（btn-send-full）；纯静态可单测 */
    static String buttonStyleClass(BtnMode mode) {
        switch (mode) {
            case SEND:
            case ANSWER:
                return "btn-send-full";
            default:
                return "btn-send-empty";
        }
    }

    /** 当前发送键模式下该按键事件是否为「发送」：默认=Ctrl+Enter（精确修饰键语义，同 KeyCodeCombination）；
     *  Enter 发送模式=纯 Enter。弹层打开时由事件过滤器分流，不算发送 */
    static boolean isSendKey(KeyCode code, boolean ctrl, boolean shift, boolean alt, boolean meta, boolean enterSends) {
        if (code != KeyCode.ENTER) return false;
        if (enterSends) return !ctrl && !shift && !alt && !meta;
        return ctrl && !shift && !alt && !meta;
    }

    /** 发送键显示名（按钮 tooltip / 输入框占位提示用） */
    static String sendKeyLabel(boolean enterSends) {
        return enterSends ? "Enter" : "Ctrl+Enter";
    }

    private void updateButton() {
        BtnMode mode = buttonMode(running, askPending, hasContent());
        String sendKey = sendKeyLabel(config.enterSends());
        switch (mode) {
            case SEND:       applyStyle(arrowIcon, buttonStyleClass(mode), 1.0, "发送 (" + sendKey + ")"); break;
            case SEND_DIM:   applyStyle(arrowIcon, buttonStyleClass(mode), 0.35, "输入消息后发送 (" + sendKey + ")"); break;
            case SUPPLEMENT: applyStyle(arrowIcon, buttonStyleClass(mode), 1.0, "排队为下一项任务；点击立即引导可补充当前任务 (" + sendKey + ")"); break;
            case ANSWER:     applyStyle(arrowIcon, buttonStyleClass(mode), 1.0, "回答模型的提问 (" + sendKey + ")"); break;
            case ANSWER_DIM: applyStyle(arrowIcon, buttonStyleClass(mode), 0.35, "输入回答后发送 (" + sendKey + ")"); break;
            case STOP:       applyStyle(stopIcon, buttonStyleClass(mode), 1.0, "终止当前运行 (Esc)"); break;
        }
    }

    /** 多次暂存按输入顺序追加；纯逻辑供单测。 */
    static String mergePendingGuide(String existing, String added) {
        String next = added == null ? "" : added.trim();
        if (existing == null || existing.trim().isEmpty()) return next;
        if (next.isEmpty()) return existing.trim();
        return existing.trim() + "\n" + next;
    }

    static String pendingGuidePreview(String text) {
        if (text == null) return "";
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }

    private void stagePendingGuide(String text, List<ImagePart> images) {
        if (current == null || text == null || text.trim().isEmpty()) return;
        manager.stageGuidance(current, text, images);
        refreshPendingGuideRow();
    }

    private void refreshPendingGuideRow() {
        SessionHandle.QueuedMessage task=current==null?null:current.pendingGuide();
        boolean show = task != null;
        pendingGuideRow.setVisible(show);
        pendingGuideRow.setManaged(show);
        if (!show) return;
        String suffix = task.images.isEmpty() ? "" : "（附件 " + task.images.size() + " 个）";
        int count = current.queuedSnapshot().size();
        pendingGuideLabel.setText("完成后自动执行（队列 " + count + " 项）：" + pendingGuidePreview(task.text) + suffix);
        guideNowButton.setText(running ? "立即引导" : "作为新消息发送");
    }

    private void sendPendingGuide() {
        manager.promoteGuidance(current);
        refreshPendingGuideRow();
    }

    private void clearPendingGuide() {
        manager.discardGuidance(current);
        refreshPendingGuideRow();
    }

    public void onQueueChanged(SessionHandle h) {
        Platform.runLater(() -> { if(current==h) refreshPendingGuideRow(); });
    }

    private void applyStyle(SVGPath graphic, String styleClass, double opacity, String tip) {
        sendButton.setGraphic(graphic);
        sendButton.getStyleClass().removeAll("btn-primary", "btn-danger", "btn-send-full", "btn-send-empty");
        sendButton.getStyleClass().add(styleClass);
        sendButton.setOpacity(opacity);
        sendButton.setTooltip(new Tooltip(tip));
    }

    /** Ctrl+Enter / 按钮点击统一入口：按当前模式分发 */
    private void onAction() {
        onAction(true);
    }

    private void onAction(boolean allowStop) {
        if(appliedBinding!=bindingGeneration.get()||current!=null&&current.deleted)return;
        // 输入区缓存由异步回调更新；实际发消息时使用会话即时状态，防重复发送串轮。
        boolean displayedAnswerMode = askPending;
        if (current != null) {
            running = current.running;
            askPending = current.askPending;
        }
        // 界面仍显示“回答”但提问已经结束：这次点击不能偷偷变成发新任务/补充/停止。
        // 刷新按钮语义并保留草稿，用户看到新状态后可自行决定是否再次发送。
        if (displayedAnswerMode && !askPending) {
            updateButton(); updatePrompt();
            if (hasContent()) notifyChat("提问状态已改变，回答未发送。文字和附件已保留，请检查后重试。");
            return;
        }
        switch (allowStop ? buttonMode(running, askPending, hasContent())
                : keyboardMode(running, askPending, hasContent())) {
            case SEND:
                onSend();
                break;
            case SUPPLEMENT: {
                String text = composedText();
                if (text == null || text.trim().isEmpty()) return;
                List<ImagePart> images = composedImages();
                clearComposer();
                stagePendingGuide(text, images); // 默认下个任务；“立即引导”才注入当前任务
                break;
            }
            case ANSWER: {
                String text = composedText();
                List<ImagePart> images = composedImages();
                if ((text == null || text.trim().isEmpty()) && images.isEmpty()) return;
                if (manager.sendAnswer(current, text, images, askQuestionId)) clearComposer();
                else notifyChat("回答未发送：提问已结束、状态已改变或附件无效。文字和附件已保留，请检查后重试。");
                break;
            }
            case STOP:
                if (current != null) manager.stop(current);
                break;
            case SEND_DIM:
            case ANSWER_DIM:
                break;
        }
    }

    private void onSend() {
        String text = composedText();
        List<ImagePart> images = composedImages();
        if ((text == null || text.trim().isEmpty()) && images.isEmpty()) return;
        clearComposer();
        SessionHandle target = current;
        if (target == null) {
            target = manager.createSession(null);
            if (target == null) return;
            manager.activateSession(target);
        }
        // 带图消息不走斜杠命令分发（图片无法本地处理，照发普通消息）
        if (images.isEmpty()) {
            manager.dispatchCommand(target, text); // 斜杠命令本地分发；普通消息走 send
            refreshPendingGuideRow();
        } else {
            manager.send(target, text, images);
        }
    }

    /** 图片块 → ImagePart 列表：解析 data URI 头拆 mime/base64；name 取 display 的「图片：」前缀之后 */
    private List<ImagePart> composedImages() {
        List<ImagePart> out = new ArrayList<ImagePart>();
        for (InputChip c : chips) {
            if (c == null || c.type != InputChip.Type.IMAGE) continue;
            int comma = c.content.indexOf(',');
            if (comma < 0 || !c.content.startsWith("data:")) continue;
            String header = c.content.substring(0, comma); // data:<mime>;base64
            int semi = header.indexOf(';');
            String mime = semi > 5 ? header.substring(5, semi) : header.substring(5);
            ImagePart p = new ImagePart();
            p.mime = mime;
            p.base64 = c.content.substring(comma + 1);
            p.name = c.display.startsWith("图片：") ? c.display.substring(3) : c.display;
            out.add(p);
        }
        return out;
    }

    /** 添加附件：图片走视觉通道，其他受支持文件在本地提取文本后加入当前消息。 */
    private void chooseAttachments() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("添加附件");
        chooser.getExtensionFilters().add(new ExtensionFilter("支持的文件",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp",
                "*.pdf", "*.ofd", "*.gd", "*.docx", "*.xlsx", "*.pptx", "*.txt", "*.md", "*.csv", "*.tsv",
                "*.json", "*.xml", "*.yaml", "*.yml", "*.log", "*.java", "*.py", "*.js", "*.ts",
                "*.html", "*.css", "*.sql", "*.sh", "*.ps1", "*.bat", "*.properties", "*.ini", "*.conf"));
        chooser.getExtensionFilters().add(new ExtensionFilter("图片文件",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
        chooser.getExtensionFilters().add(new ExtensionFilter("文档和文本",
                "*.pdf", "*.ofd", "*.gd", "*.docx", "*.xlsx", "*.pptx", "*.txt", "*.md", "*.csv", "*.tsv",
                "*.json", "*.xml", "*.yaml", "*.yml", "*.log", "*.java", "*.py", "*.js", "*.ts",
                "*.html", "*.css", "*.sql", "*.sh", "*.ps1", "*.bat", "*.properties", "*.ini", "*.conf"));
        List<File> selected = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (selected == null || selected.isEmpty()) return;
        addAttachments(selected);
    }

    /** 文件选择、拖放和剪贴板文件共用同一校验/解析路径。
     *  重解析（图片解码/文档提取）在附件后台线程执行；上限校验与 addChip 回到 FX 线程按最新块计数判定，
     *  选多个大文件不再逐个冻结界面。 */
    private void addAttachments(List<File> selected) {
        if (selected == null || selected.isEmpty()) return;
        final List<File> files = new ArrayList<File>(selected);
        attachmentPool.execute(() -> {
            for (final File f : files) {
                try {
                    if (isImage(f.getName())) {
                        if (f.length() > ImagePart.MAX_FILE_BYTES) {
                            notifyChat("图片超过 " + (ImagePart.MAX_FILE_BYTES / 1024 / 1024) + "MB，已跳过: " + f.getName());
                            continue;
                        }
                        final ToolResult overview = ViewImageTool.loadOverview(f.toPath());
                        if (!overview.ok || overview.images == null || overview.images.isEmpty()) {
                            notifyChat("图片无法进入视觉通道（" + f.getName() + "）: " + overview.output);
                            continue;
                        }
                        final ImagePart image = overview.images.get(0);
                        runOnFx(() -> {
                            int have = 0;
                            for (InputChip c : chips) if (c.type == InputChip.Type.IMAGE) have++;
                            if (have >= ImagePart.MAX_IMAGES) {
                                notifyChat("最多上传 " + ImagePart.MAX_IMAGES + " 张图片，已跳过: " + f.getName());
                                return;
                            }
                            addChip(InputChip.imageChip(image.mime, image.base64, f.getName()));
                        });
                    } else {
                        AttachmentReader.Result result;
                        try { result=AttachmentReader.read(f.toPath()); }
                        catch(com.minion.core.tools.data.GdSupport.ReaderRequired nativeGd){
                            result=chooseGdExportOnFx(f);
                            if(result==null)continue;
                        }
                        final AttachmentReader.Result parsed = result;
                        runOnFx(() -> {
                            int documents = 0;
                            for (InputChip c : chips) if (c.type == InputChip.Type.FILE && c.display.startsWith("文件：")) documents++;
                            if (documents >= AttachmentReader.MAX_ATTACHMENTS) {
                                notifyChat("最多添加 " + AttachmentReader.MAX_ATTACHMENTS + " 个文档，已跳过: " + f.getName());
                                return;
                            }
                            addChip(InputChip.attachmentChip(parsed.promptText, parsed.displayName));
                        });
                        if (result.truncated) notifyChat("附件内容较长，已截取前 " + AttachmentReader.MAX_EXTRACTED_CHARS + " 个字符: " + f.getName());
                    }
                } catch (Exception ex) {
                    notifyChat("读取附件失败（" + f.getName() + "）: " + ex.getMessage());
                }
            }
        });
    }

    /** 后台线程跳回 FX 线程执行（已在 FX 线程则直接执行） */
    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    /** GD 原件需要弹窗交互（书生阅读器导出）：后台线程经 FX 线程执行对话框并等待结果 */
    private AttachmentReader.Result chooseGdExportOnFx(final File original) throws Exception {
        if (Platform.isFxApplicationThread()) return chooseGdExport(original);
        final AtomicReference<Object> outcome = new AtomicReference<Object>();
        final CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { outcome.set(chooseGdExport(original)); }
            catch (Exception e) { outcome.set(e); }
            finally { done.countDown(); }
        });
        done.await();
        Object r = outcome.get();
        if (r instanceof Exception) throw (Exception) r;
        return (AttachmentReader.Result) r;
    }

    private AttachmentReader.Result chooseGdExport(File original)throws Exception {
        javafx.scene.control.Alert prompt=new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        if(getScene()!=null&&getScene().getWindow()!=null)prompt.initOwner(getScene().getWindow());
        prompt.setTitle("读取书生 GD 公文");
        prompt.setHeaderText(original.getName());
        String extra=config!=null&&!config.get("gd.converter.path","").trim().isEmpty()
                ?"\n也可以在对话里引用原文件，让 Document gd_read 调用已配置的本地转换程序。":"";
        prompt.setContentText("原生 GD 由书生阅读器解码。请按阅读器支持的格式导出 TXT、PDF 或 OFD，再在这里选择副本。"+extra);
        ButtonType open=new ButtonType("打开原件"), select=new ButtonType("选择导出副本");
        prompt.getButtonTypes().setAll(open,select,ButtonType.CANCEL);
        ButtonType choice=prompt.showAndWait().orElse(ButtonType.CANCEL);
        if(choice==open){
            if(!java.awt.Desktop.isDesktopSupported()||!java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN))
                throw new java.io.IOException("系统未配置GD打开方式；请手动用书生阅读器打开");
            java.awt.Desktop.getDesktop().open(original);
            notifyChat("已用系统关联打开GD原件。导出后请重新选择该GD，点击“选择导出副本”。");
            return null;
        }
        if(choice!=select)return null;
        FileChooser picker=new FileChooser();picker.setTitle("选择书生阅读器导出的副本");
        if(original.getAbsoluteFile().getParentFile().isDirectory())picker.setInitialDirectory(original.getAbsoluteFile().getParentFile());
        picker.getExtensionFilters().add(new ExtensionFilter("GD导出副本","*.txt","*.pdf","*.ofd"));
        File exported=picker.showOpenDialog(getScene()==null?null:getScene().getWindow());
        return exported==null?null:AttachmentReader.readGdExport(original.toPath(),exported.toPath());
    }

    void pasteFromSystemClipboard(){
        if(!input.isEditable()||input.isDisabled()||appliedBinding!=bindingGeneration.get())return;
        try{
            Clipboard clipboard=Clipboard.getSystemClipboard();
            pasteFrom(clipboard.getString(),clipboard::getFiles,clipboard::getImage);
        }catch(RuntimeException e){notifyChat("读取剪贴板失败: "+e.getMessage());}
    }

    /** Ask for alternate representations lazily: Office may advertise text and a rendered image together. */
    void pasteFrom(String text,Supplier<List<File>> files,Supplier<Image> image){
        if(text!=null&&!text.isEmpty()){
            if(InputChip.shouldChipPaste(text)){
                String placeholder;
                do{placeholder="[粘贴块"+(++pasteSeq)+"]";}while(input.getText().contains(placeholder));
                InputChip chip=InputChip.pasteChip(text,placeholder);
                addChip(chip);
                input.replaceSelection(placeholder);
                // A TextFormatter or rejected edit must not leave an unattached block.
                if(!input.getText().contains(placeholder))removeChipAt(chips.indexOf(chip));
            }else input.replaceSelection(text);
            return;
        }
        List<File> attachments=files.get();
        if(attachments!=null&&!attachments.isEmpty()){addAttachments(attachments);return;}
        Image bitmap=image.get();
        if(bitmap!=null)addClipboardImage(bitmap);
    }

    /** 剪贴板位图转 PNG 后直接进入视觉通道，不在磁盘创建临时文件。
     *  像素快照在 FX 线程完成，PNG 编码与上限判定放附件后台线程，大图不再冻结界面。 */
    private void addClipboardImage(Image bitmap) {
        int have = 0;
        for (InputChip c : chips) if (c.type == InputChip.Type.IMAGE) have++;
        if (have >= ImagePart.MAX_IMAGES) { notifyChat("最多上传 " + ImagePart.MAX_IMAGES + " 张图片"); return; }
        final java.awt.image.BufferedImage snapshot;
        try {
            snapshot = SwingFXUtils.fromFXImage(bitmap, null);
        } catch (Exception e) { notifyChat("读取剪贴板图片失败: " + e.getMessage()); return; }
        attachmentPool.execute(() -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(snapshot, "png", out);
                byte[] bytes = out.toByteArray();
                if (bytes.length > ImagePart.MAX_FILE_BYTES) { notifyChat("剪贴板图片超过大小限制"); return; }
                final String base64 = Base64.getEncoder().encodeToString(bytes);
                runOnFx(() -> {
                    int n = 0;
                    for (InputChip c : chips) if (c.type == InputChip.Type.IMAGE) n++;
                    if (n >= ImagePart.MAX_IMAGES) { notifyChat("最多上传 " + ImagePart.MAX_IMAGES + " 张图片"); return; }
                    addChip(InputChip.imageChip("image/png", base64, "剪贴板图片.png"));
                });
            } catch (Exception e) { notifyChat("读取剪贴板图片失败: " + e.getMessage()); }
        });
    }

    private static boolean isImage(String name) {
        String n = name == null ? "" : name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp");
    }

    /** 文件名后缀 → mime；未知后缀兜底 png */
    private static String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    /** 聊天区提示（无会话时静默忽略；发送前必先有会话或上传仅装饰） */
    private void notifyChat(String msg) {
        if (current != null) current.controller.onWarning(msg);
    }
}
