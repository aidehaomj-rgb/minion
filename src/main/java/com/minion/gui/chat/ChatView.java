package com.minion.gui.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.SteeringReceipt;
import com.minion.gui.icon.IconFactory;
import com.minion.gui.session.EventList;
import com.minion.gui.session.EventList.Ev;
import com.minion.gui.session.SessionHandle;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * 会话消息区（纯控制台输出流）：订阅 EventList（事件来自后台线程，Listener 内 Platform.runLater 包装）。
 * 每条消息 = HBox（彩色加粗标签 Label + 白色正文 MessageTextArea），段间无缝紧贴（spacing 0），
 * 整区背景 #121314（.panel-dark）铺满正文窗口（配合 ScrollPane fitToHeight）；
 * 正文 TextArea 高度自适应（MessageTextArea）内容全部平铺、无内部滚动条；
 * 段内原生拖选/Ctrl+C/右键复制。
 * 流式身份三值 StreamKind：THINK/REPLY 末段就地更新正文，NONE 静态行（输入/工具/系统等）永不参与就地更新。
 */
public class ChatView extends VBox {

    /** 空会话占位文本（只读 TextArea 不显示 promptText，用文本代替） */
    private static final String EMPTY_HINT = "输入消息开始新的会话";

    /** 截断保活上限：段数超限即移除头部旧段（200 段 ≈ 2000+ 行，长会话滚动不卡） */
    private static final int MAX_SEGS = 200;
    /** 每页可见内容数量；流增量合并后计为一条。 */
    private static final int HISTORY_PAGE_SIZE = 150;


    private final EventList events;
    private final SessionHandle handle;
    /** 流式缓冲：THINKING/CONTENT 增量累积，轮次边界重置（纯逻辑，见 StreamBuffer） */
    private final StreamBuffer stream = new StreamBuffer();

    /** 用户消息到达时的"滚动到底"回调（MainWindow 注入：强制贴底 + 布局完成后置底） */
    private Runnable scrollBottomRequest;
    private java.util.function.Consumer<String> fileLinkHandler;
    private java.util.function.Function<String,javafx.scene.control.ContextMenu> fileLinkMenu;
    private final java.util.LinkedHashMap<String,Seg> fileSegments=new java.util.LinkedHashMap<String,Seg>();
    /** 只索引当前可见段；完整回执索引保存在本会话 EventList 中。 */
    private final java.util.Map<String,Seg> steeringSegments=new java.util.HashMap<String,Seg>();
    public void setFileLinkHandler(java.util.function.Consumer<String> handler,
                                   java.util.function.Function<String,javafx.scene.control.ContextMenu> menu) {
        fileLinkHandler=handler;fileLinkMenu=menu;
        for(Seg seg:segs)if(seg.textPane!=null)seg.textPane.setLinkHandler(handler,menu);
    }

    /** MainWindow 注入：USER_MESSAGE 事件时请求滚动到底 */
    public void setScrollBottomRequest(Runnable r) { this.scrollBottomRequest = r; }

    /** 截断回调（MainWindow 注入：vvalue 补偿防历史区视口跳动；null 表示不补偿） */
    private DoubleConsumer trimListener;

    /** MainWindow 注入：头部段被截断时回调，参数 = 被删段高度合计（像素，补偿 vvalue 用） */
    public void setTrimListener(DoubleConsumer listener) { this.trimListener = listener; }

    /** 增量重放游标：已渲染到缓冲第几个事件（仅 FX 线程读写；直通渲染完成才推进，下次 bind 不重复） */
    private int replayed = 0;

    /** 旧页签排队的 runLater 在解绑后不得再绘制或扰乱新会话。 */
    private int bindEpoch = 0;
    private boolean bound = false;
    private boolean replaying = false;
    private final Button older = new Button("更早");
    private final Button newer = new Button("较新");
    private final Button latest = new Button("回到最新");
    private final Button archiveButton = new Button();
    private int archivedFiles;
    private Runnable openArtifactArchive = () -> {};
    private final Label pageInfo = new Label();
    private final HBox historyBar = new HBox(6, older, newer, latest, pageInfo, archiveButton);
    private final List<Integer> forwardPages = new ArrayList<Integer>();
    private int pageStart, pageEnd;
    private boolean browsingHistory;

    /** 上次展示结束时的滚动位置（MainWindow 切走时记、切回恢复；缓存视图内容连续，vvalue 语义保留） */
    private double savedVvalue = 1.0;

    public void rememberVvalue(double v) { this.savedVvalue = v; }

    public double savedVvalue() { return savedVvalue; }

    /** 流身份：THINK=思考流、REPLY=回复流（流式就地更新末段正文），NONE=静态行（永不参与就地更新） */
    private enum StreamKind { THINK, REPLY, NONE }

    /** 消息段：彩色加粗标签 + 正文（普通 MessageTextArea 或 CollapsibleText）+ 流身份 kind */
    private static class Seg {
        final Label tag;
        final MessageTextArea body;      // 焦点治理目标（collapsible 段 = 内部内容体）
        CollapsibleText collapsible; // null = 普通段
        final MessageTextPane textPane;
        final StreamKind kind;
        boolean thinkFinalized; // THINK 段已按最终长度定稿（防 CONTENT 增量重复折叠覆盖手动展开）
        boolean streamFinalized;
        String steeringId;
        Seg(String tagText, String tagColorClass, String text, StreamKind kind) {
            this(tagText, tagColorClass, (Node) null, text, false, kind);
        }
        Seg(String tagText, String tagColorClass, Node summary, String text,
            boolean defaultExpanded, StreamKind kind) {
            tag = new Label(tagText);
            tag.getStyleClass().addAll("log-tag", tagColorClass);
            tag.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            if (summary == null) {
                collapsible = null;
                boolean artifact = "【文件】".equals(tagText);
                textPane = new MessageTextPane(text, kind == StreamKind.REPLY || artifact, artifact);
                body = textPane.contentArea();
            } else {
                textPane = null;
                collapsible = new CollapsibleText(summary, text, defaultExpanded);
                body = collapsible.contentArea();
            }
            // 正文 = 默认白（.log-body）+ 浅色调类别色（log-body-* 定义于 CSS；四类消息着色，系统行无定义自动回退白）
            body.getStyleClass().addAll("log-body", "log-body-" + tagColorClass.substring(4));
            // 段节点吃满剩余宽度：普通段 = 正文 body；折叠段 = CollapsibleText（VBox）整体——
            // 只对 body 设 Hgrow 无效（body 是 VBox 内部子节点，不直接参与 HBox 布局），
            // 折叠段会停在 prefWidth 不铺满、窗口窄时被压扁（P1 审查缺陷）
            HBox.setHgrow(node(), Priority.ALWAYS);
            this.kind = kind;
        }
        /** 加入段行的节点：折叠段为 CollapsibleText 整体，普通段为正文 */
        Node node() { return collapsible != null ? collapsible : textPane; }
    }

    private final List<Seg> segs = new ArrayList<Seg>();
    private Seg mainStreamSegment;
    private Seg pendingReply;
    private final java.util.Map<String, ChildStream> childStreams = new java.util.HashMap<String, ChildStream>();
    private static class ChildStream {
        final StreamBuffer buffer = new StreamBuffer();
        Seg thinking, reply;
    }
    private boolean empty = true; // 无任何消息段（仍显示占位提示）

    public ChatView(EventList events, SessionHandle handle) {
        this.events = events;
        this.handle = handle;
        getStyleClass().addAll("panel-dark", "workbench-transcript");
        historyBar.getStyleClass().add("history-navigation");
        older.setId("history-older");
        older.setAccessibleText("加载更早历史");
        older.setTooltip(new Tooltip("加载更早历史"));
        newer.setTooltip(new Tooltip("查看较新一页"));
        latest.setTooltip(new Tooltip("返回最新消息"));
        archiveButton.setId("artifact-archive-button");
        archiveButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        archiveButton.setOnAction(e -> openArtifactArchive.run());
        archiveButton.managedProperty().bind(archiveButton.visibleProperty());
        archiveButton.setTooltip(new Tooltip("打开右侧工作区的成果列表"));
        pageInfo.getStyleClass().add("history-info");
        pageInfo.setMinWidth(0);
        pageInfo.setTooltip(new Tooltip("每页最多 150 条。浏览历史时，新消息不会打断当前阅读。"));
        HBox.setHgrow(pageInfo, Priority.ALWAYS);
        newer.visibleProperty().bind(newer.disableProperty().not());
        newer.managedProperty().bind(newer.visibleProperty());
        latest.visibleProperty().bind(latest.disableProperty().not());
        latest.managedProperty().bind(latest.visibleProperty());
        clear();
        older.setOnAction(e -> {
            forwardPages.add(pageEnd);
            browsingHistory = true;
            showPage(pageStart);
        });
        newer.setOnAction(e -> {
            if (!forwardPages.isEmpty()) showPage(forwardPages.remove(forwardPages.size() - 1));
        });
        latest.setOnAction(e -> { browsingHistory = false; forwardPages.clear(); bind(true); });
    }

    /**
     * minHeight = prefHeight（防 ScrollPane 压缩，探针 19-20 实证根因）：
     * ScrollPane fitToHeight 布局用 boundedSize(视口高, content.minH, content.maxH) 定 content 高度。
     * VBox 默认 minH = 各段最小值之和（TextArea minH≈1-2 行），内容多时 content 被压到视口高，
     * VBox 空间不足再压缩各段 → 长消息被压矮、段内出现滚动条。
     * minH=prefH 后：内容多时 boundedSize 取 prefH 自然展开，内容少时取视口高保持铺满（fitToHeight 语义不变）。
     */
    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    public static ChatView forSession(SessionHandle h) {
        return new ChatView(h.controller.eventList(), h);
    }

    /** 本视图绑定的会话句柄（MainWindow 判断「删除的是当前展示会话」用） */
    public SessionHandle handle() { return handle; }
    public void setOpenArtifactArchive(Runnable open) { openArtifactArchive = open; }

    /**
     * 绑定/解绑事件流：active=true 增量重放自上次游标起的新事件 + 后续直通（视图缓存场景不 clear，
     * 流式合并状态与 segs 连续，切走再切回输出无缝）；active=false 解绑直通，事件只入缓冲。
     * 首次绑定游标=0 全量重放（语义与旧 clear+重放一致）。
     */
    public void bind(boolean active) {
        if (active) {
            final int epoch = ++bindEpoch;
            bound = true;
            EventList.Listener listener = e -> Platform.runLater(() -> {
                if (!bound || bindEpoch != epoch) return;
                replayed++;
                if (!browsingHistory) {
                    pageEnd = replayed;
                    onEventFx(displayEvent(e));
                } else if (e.kind == EventList.Kind.STEERING_UPDATE) onEventFx(e);
                updateHistoryBar();
            });
            // 重放预算必须在 EventList 锁内与注册直通一起确定；旧版全量重放长会话会在
            // FX 线程创建成千上万个 TextArea，即使随后 trim 到 200 段也已把界面拖垮。
            EventList.Replay tail = events.rebindRecent(listener, events.size(), 1, 1);
            replayed = tail.nextIndex;
            showPage(browsingHistory ? pageEnd : replayed);
        } else {
            bound = false;
            bindEpoch++;
            events.setActive(false, null);
        }
    }

    private void showPage(int before) {
        EventList.Page page = events.pageBefore(before, HISTORY_PAGE_SIZE);
        pageStart = page.start;
        pageEnd = page.end;
        archivedFiles = 0;
        resetNodes();
        segs.clear();
        fileSegments.clear();
        steeringSegments.clear();
        empty = true;
        mainStreamSegment = null;
        pendingReply = null;
        stream.onRoundBoundary();
        replaying = true;
        childStreams.clear();
        try {
            for (Ev e : page.events) onEventFx(displayEvent(e));
            finalizeThinking();
            for (ChildStream child : childStreams.values()) {
                if (child.thinking != null) child.thinking.collapsible.finalizeThinking();
            }
            if (empty) getChildren().add(emptyHint(page.end == 0
                    ? EMPTY_HINT : "此页暂无可显示内容；可加载更早历史或返回最新消息"));
        } finally { replaying = false; }
        updateHistoryBar();
    }

    private void resetNodes() {
        getChildren().clear();
        getChildren().add(historyBar);
    }

    private void updateHistoryBar() {
        older.setDisable(pageStart == 0);
        older.setVisible(pageStart > 0 || browsingHistory);
        older.setManaged(older.isVisible());
        newer.setDisable(forwardPages.isEmpty());
        latest.setDisable(!browsingHistory);
        historyBar.setVisible(pageStart > 0 || browsingHistory || archivedFiles > 0);
        historyBar.setManaged(historyBar.isVisible());
        pageInfo.setText(browsingHistory ? "历史浏览" : pageStart > 0 ? "对话历史" : "");
        archiveButton.setVisible(archivedFiles > 0);
        if (archivedFiles > 0) archiveButton.setText("历史成果 · " + archivedFiles);
    }

    /** 完整事件交给分页控件，不修改 EventList、Session.messages 或落盘 JSON。 */
    private static Ev displayEvent(Ev e) {
        return e; // 完整事件保留；正文控件以分页限制布局量。
    }

    /** 展示层剥离完成标记：[[MINION_TASK_COMPLETE]] 是完成门禁的内部记号（模型按系统提示
     *  在最终答复末尾追加），不进入聊天区。仅影响显示——事件流、会话落盘与门禁判定保留原文。
     *  流式时标记可能跨 delta 分片（如末尾出现 "[[MINION_"），渐进入前缀暂不显示，下一增量自然补齐。
     *  每个流式增量都会调用：前缀探测用 regionMatches（零分配），replace 在无匹配时同样返回原引用。 */
    static String stripTaskMarker(String text) {
        if (text == null) return text;
        String marker = com.minion.core.agent.AgentLoop.TASK_COMPLETE_MARKER;
        String out = text.replace(marker, "");
        // 尾部若为标记的未完成前缀（最短可识别 "[[" 起步，避免误伤普通 "[" 结尾的正文），暂截显示
        for (int len = 2; len < marker.length(); len++) {
            int from = out.length() - len;
            if (from < 0) break;
            if (marker.regionMatches(0, out, from, len)) return out.substring(0, from);
        }
        return out;
    }

    static String clipForDisplay(String text) {
        return text;
    }

    public void clear() {
        resetNodes();
        forwardPages.clear();
        browsingHistory = false;
        pageStart = pageEnd = 0;
        archivedFiles = 0;
        updateHistoryBar();
        segs.clear();
        steeringSegments.clear();
        childStreams.clear();
        stream.onRoundBoundary();
        empty = true;
        replayed = 0; // 清空后游标归零：下次 bind 从 0 全量重放（clear 仅删除会话回收路径调用）
        mainStreamSegment = null;
        pendingReply = null;
        getChildren().add(emptyHint(EMPTY_HINT));
    }

    private void onEventFx(Ev e) {
        if (e.subAgentId != null) { onChildEventFx(e); return; }
        // 回执只更新原行状态，不能打断/定稿正在输出的思考或回复。
        if (e.kind == EventList.Kind.STEERING_UPDATE) { updateSteering(e); return; }
        if (e.kind == EventList.Kind.USER_SUPPLEMENT && e.data instanceof SteeringReceipt) {
            Seg existing = steeringSegments.get(((SteeringReceipt) e.data).id);
            if (existing != null && segs.contains(existing)) { updateSteering(e); return; }
        }
        // Only fold when the event sequence proves that this reply is intermediate.
        if(e.kind==EventList.Kind.TOOL_CALL || (startsResponse(e)&&pendingReply!=null&&pendingReply.streamFinalized)){
            foldIntermediate(pendingReply);pendingReply=null;
        }else if(e.kind==EventList.Kind.USER_MESSAGE||e.kind==EventList.Kind.USER_SUPPLEMENT)pendingReply=null;
        // 轮次边界（用户消息/补充/工具调用到达）先行重置流式缓冲：AgentLoop 一轮 runUserTurn 内
        // 多轮 agent 回合间无 USER_MESSAGE，若不清零则多轮回复文本跨轮累积进同一段，
        // 每轮内容越滚越长，表现为"一直在回复同一段内容"（线上实证，用户误判上下文错乱）
        if (StreamBuffer.isRoundBoundary(e.kind)) { stream.onRoundBoundary(); mainStreamSegment=null; }
        // 思考段定稿：任何非思考事件到达即思考结束，按最终长度折叠（≥阈值折叠、短展开）
        if (e.kind != EventList.Kind.THINKING) finalizeThinking();
        switch (e.kind) {
            case RESPONSE_END:
                finalizeReply(pendingReply);
                break;
            case ARTIFACT:
                appendArtifact(e.text);
                break;
            case ARTIFACT_ARCHIVE:
                archivedFiles = Integer.parseInt(e.text); // internal event, not model text
                break;
            case USER_MESSAGE:
                append("【输入】", "log-input", e.text, StreamKind.NONE);
                if (!replaying && scrollBottomRequest != null) scrollBottomRequest.run();
                break;
            case USER_SUPPLEMENT:
                if (e.data instanceof SteeringReceipt) appendSteering(e);
                else append("【输入】", "log-input", e.text, StreamKind.NONE);
                break;
            case THINKING:
                // 空思考增量不渲染（与 CONTENT 分支空防御对称）：qwen 流式每 chunk 带
                // reasoning_content 空字符串字段（非 null），若空增量也走 stream()，
                // 正文阶段每 chunk 追加一段【思考】+【回复】，表现为"同一段回复不停重复"
                if (e.text == null || e.text.isEmpty()) break;
                stream.onThinking(e.text);
                stream("【思考】", "log-think", stream.thinking(), StreamKind.THINK);
                break;
            case CONTENT:
                stream.onContent(e.text);
                // 纯文本展示（Label 不可选问题之解）：markdown 展平去语法记号，段内原生拖选复制
                String plain = stripTaskMarker(stream.content()); // Markdown 只解析当前分页，避免每个增量重解析整篇超长正文。
                // 回复内容仍为空（思考后直接调工具等场景，LLM 空 content chunk 增量）：
                // 不打印【回复】标签——空标签+空白正文的"幽灵段"；缓冲只追加不会中途变空
                if (plain.trim().isEmpty()) break;
                stream("【回复】", "log-reply", plain, StreamKind.REPLY);
                break;
            case TOOL_CALL: {
                if ("AskUserQuestion".equals(e.text)) {
                    // 提问段无视长度阈值恒展开（长选项被折叠 = 「看不见提问内容」的第二类成因）
                    String askBody = askQuestionOf(e.data);
                    appendCollapsible("【工具】", "log-tool",
                            statusSummary(IconFactory.help(), askSummaryText(e.data)),
                            askBody, StreamKind.NONE, askExpanded(askBody));
                } else {
                    appendCollapsible("【工具】", "log-tool",
                            toolSummary(toolCallSummary(e.text, e.data)), toolCallBody(e.text, e.data));
                }
                break;
            }
            case TOOL_RESULT: {
                String data = e.data == null ? "" : e.data.toString();
                boolean ok = data.startsWith("ok");
                appendCollapsible(ok ? "【工具】" : "【系统】", ok ? "log-tool" : "log-error",
                        statusSummary(ok ? IconFactory.success() : IconFactory.error(),
                                e.text + (ok ? " 成功" : " 失败")),
                        toolResultBody(e.text, data));
                break;
            }
            case ERROR:
                append("【系统】", "log-error", e.text, StreamKind.NONE);
                break;
            case WARNING:
                append("【系统】", "log-warn", e.text, StreamKind.NONE);
                break;
            case STATS:
                // 统计行 "⏱ " 前缀由 GUI 剥离展示（core StatsLine 保持原样；不匹配则原样展示）
                String stats = e.text != null && e.text.startsWith("⏱ ") ? e.text.substring(2) : e.text;
                appendCollapsible("【系统】", "log-sys",
                        statusSummary(IconFactory.timer(), stats), null, StreamKind.NONE);
                break;
            case SYSTEM: // 斜杠命令结果等 GUI 本地事件（不入 LLM 历史）
                append("【系统】", "log-sys", e.text, StreamKind.NONE);
                break;
            case SUB_AGENT_START:
                appendCollapsible("【工具】", "log-tool",
                        statusSummary(IconFactory.play(), "子任务: " + e.text), null, StreamKind.NONE);
                break;
            case SUB_AGENT_DELTA:
                // 原始子任务增量没有身份字段，不能把交错输出误标成某个子 Agent。
                // 仅重放合并后的历史内容，实时输出仍通过子任务状态/结果查看。
                if (replaying && e.text != null && !e.text.trim().isEmpty()) {
                    appendCollapsible("【工具】", "log-tool",
                            statusSummary(IconFactory.play(), "子任务输出（按记录顺序）"), e.text);
                }
                break;
            case SUB_AGENT_DONE:
                appendCollapsible("【工具】", "log-tool",
                        statusSummary(IconFactory.check(), "子任务完成: " + e.text), null, StreamKind.NONE);
                break;
            default:
                break;
        }
    }

    /** 独立子任务缓冲，交错到达也只更新自己的段，不污染主代理流。 */
    private void onChildEventFx(Ev e) {
        String tag="【子Agent "+e.subAgentId.substring(0,Math.min(8,e.subAgentId.length()))+"】";
        ChildStream child=childStreams.get(e.subAgentId);
        if(child==null){child=new ChildStream();childStreams.put(e.subAgentId,child);}
        if(e.kind!=EventList.Kind.THINKING && child.thinking!=null) {
            child.thinking.collapsible.finalizeThinking();child.thinking=null;
        }
        if(e.kind==EventList.Kind.THINKING || e.kind==EventList.Kind.CONTENT) {
            if(e.text==null || e.text.isEmpty())return;
            if(child.reply!=null&&child.reply.streamFinalized){foldIntermediate(child.reply);child.reply=null;}
            boolean think=e.kind==EventList.Kind.THINKING;
            if(think)child.buffer.onThinking(e.text);else child.buffer.onContent(e.text);
            String text=think?child.buffer.thinking():stripTaskMarker(child.buffer.content());
            Seg seg=think?child.thinking:child.reply;
            if(seg!=null && segs.contains(seg)) {
                if(think)seg.collapsible.setStreamText(text);else seg.textPane.setText(text,!replaying);
            } else {
                if(think)appendCollapsible(tag,"log-think",statusSummary(IconFactory.play(),"思考"),text,StreamKind.NONE,true);
                else append(tag,"log-reply",text,StreamKind.NONE);
                seg=segs.get(segs.size()-1);
                if(think)child.thinking=seg;else child.reply=seg;
            }
            return;
        }
        if(e.kind==EventList.Kind.RESPONSE_END){finalizeReply(child.reply);child.buffer.onRoundBoundary();return;}
        if(e.kind==EventList.Kind.TOOL_CALL)foldIntermediate(child.reply);
        child.buffer.onRoundBoundary();child.reply=null;
        switch(e.kind) {
            case SUB_AGENT_START:
                appendCollapsible(tag,"log-tool",statusSummary(IconFactory.play(),"子任务: "+e.text),null);break;
            case SUB_AGENT_DONE:
                appendCollapsible(tag,"log-tool",statusSummary(IconFactory.check(),"子任务已返回"),e.text);
                childStreams.remove(e.subAgentId);break;
            case TOOL_CALL:
                appendCollapsible(tag,"log-tool",toolSummary(toolCallSummary(e.text,e.data)),toolCallBody(e.text,e.data));break;
            case TOOL_RESULT:
                String data=e.data==null?"":e.data.toString();boolean ok=data.startsWith("ok");
                appendCollapsible(tag,ok?"log-tool":"log-error",statusSummary(ok?IconFactory.success():IconFactory.error(),e.text+(ok?" 成功":" 失败")),toolResultBody(e.text,data));break;
            case ERROR: append(tag,"log-error",e.text,StreamKind.NONE);break;
            case WARNING: append(tag,"log-warn",e.text,StreamKind.NONE);break;
            default: break;
        }
    }

    /** 系统行（错误横幅等，MainWindow.showError 入口） */
    public void appendSystemLine(String text) {
        append("【系统】", "log-error", text, StreamKind.NONE);
    }

    private Node emptyHint(String content) {
        Label label = new Label(content);
        label.setWrapText(true);
        label.getStyleClass().add("transcript-hint");
        return label;
    }

    private static HBox messageRow(Seg seg) {
        HBox row = new HBox(seg.tag, seg.node());
        row.getStyleClass().add("transcript-row");
        if (seg.tag.getStyleClass().contains("log-input")) row.getStyleClass().add("transcript-user");
        else if (seg.kind == StreamKind.REPLY) row.getStyleClass().add("transcript-reply");
        else if (seg.collapsible != null) row.getStyleClass().add("transcript-process");
        else if (seg.tag.getStyleClass().contains("log-error")) row.getStyleClass().add("transcript-error");
        else if (seg.tag.getStyleClass().contains("log-warn")) row.getStyleClass().add("transcript-warn");
        return row;
    }

    /** 追加一段控制台输出（首段先清掉占位提示；kind 仅记录流身份，NONE 静态行恒新起一段） */
    private Seg append(String tagText, String tagColorClass, String text, StreamKind kind) {
        if (empty) {
            resetNodes();
            empty = false;
        }
        Seg seg = new Seg(tagText, tagColorClass, text, kind);
        attachFocusGovernance(seg);
        segs.add(seg);
        getChildren().add(messageRow(seg));
        trimHead();
        return seg;
    }

    private SteeringReceipt latestSteering(Ev e) {
        if (!(e.data instanceof SteeringReceipt)) return null;
        SteeringReceipt receipt = (SteeringReceipt) e.data;
        if (receipt.id == null || receipt.id.trim().isEmpty()) return null;
        SteeringReceipt latest = events.steeringReceipt(receipt.id);
        return latest == null ? receipt : latest;
    }

    private void appendSteering(Ev e) {
        SteeringReceipt receipt = latestSteering(e);
        if (receipt == null) { append("【输入】", "log-input", e.text, StreamKind.NONE); return; }
        // 始终保留初次/实际 USER 消息正文；后续 receipt.text 可能只是节选。
        Seg seg = append(steeringBadge(receipt), "log-input", e.text, StreamKind.NONE);
        if (segs.contains(seg)) {
            seg.steeringId = receipt.id;
            steeringSegments.put(receipt.id, seg);
            applySteering(seg, receipt);
        }
    }

    private void updateSteering(Ev e) {
        SteeringReceipt receipt = latestSteering(e);
        if (receipt == null) return;
        Seg seg = steeringSegments.get(receipt.id);
        if (seg != null && segs.contains(seg)) applySteering(seg, receipt);
        // 原行不在本页时不补正文；翻回原页时 EventList 会附带最新状态。
    }

    static String steeringBadge(SteeringReceipt receipt) {
        return "【引导·" + (receipt.isRead()?"已读":"未读") + "】";
    }

    private static void applySteering(Seg seg, SteeringReceipt receipt) {
        seg.tag.setText(steeringBadge(receipt));
        seg.tag.setStyle("-fx-text-fill: " + (receipt.isRead() ? "#3fb950" : "#9da7b5") + ";");
        seg.tag.setTooltip(new Tooltip("当前状态：" + receipt.displayState()
                + "。已读仅表示：确实包含该引导的模型请求已收到响应，不代表模型已理解、已执行或任务已完成。"
                + "未读、已插入、发送中、未确认都不等于已读；与下一任务队列的未发送草稿无关。"));
    }

    private void appendArtifact(String path) {
        if(path==null||path.trim().isEmpty())return;
        try {
            java.nio.file.Path file=java.nio.file.Paths.get(path).toAbsolutePath().normalize();
            String key=file.toString(),label=file.getFileName().toString().replace("[","\\[").replace("]","\\]");
            String text="["+label+"]("+file.toUri().toASCIIString()+")";
            Seg old=fileSegments.get(key);
            if(old!=null&&segs.contains(old)){old.textPane.setText(text,false);return;}
            append("【文件】","log-tool",text,StreamKind.NONE);
            fileSegments.put(key,segs.get(segs.size()-1));
            while(fileSegments.size()>100)fileSegments.remove(fileSegments.keySet().iterator().next());
        }catch(RuntimeException invalid){append("【文件】","log-tool",path,StreamKind.NONE);}
    }

    /** 焦点治理：任一段获得焦点即清除其他段选区（选区是 TextInputControl 私有状态，多块同时显示选中
     *  但 Ctrl+C 只复制焦点块——用户反馈"只有最后的块选中才有复制效果"）；排除自身保留本段选区 */
    private void attachFocusGovernance(Seg seg) {
        if(seg.textPane!=null&&fileLinkHandler!=null)seg.textPane.setLinkHandler(fileLinkHandler,fileLinkMenu);
        seg.body.focusedProperty().addListener((obs, ov, nv) -> {
            if (!Boolean.TRUE.equals(nv)) return;
            for (Seg other : segs) {
                if (other != seg) other.body.deselect();
            }
        });
    }

    private static boolean startsResponse(Ev e){
        return (e.kind==EventList.Kind.THINKING||e.kind==EventList.Kind.CONTENT)
                &&e.text!=null&&!e.text.trim().isEmpty();
    }

    private static void finalizeReply(Seg seg){
        if(seg!=null&&!seg.streamFinalized&&seg.textPane!=null){
            seg.textPane.setText(seg.textPane.fullText(),false);
            seg.streamFinalized=true;
        }
    }

    private static void foldIntermediate(Seg seg){
        if(seg==null||seg.collapsible!=null||seg.textPane==null||!(seg.textPane.getParent() instanceof HBox))return;
        String text=seg.textPane.fullText();
        if(!CollapsibleText.shouldCollapse(text)){finalizeReply(seg);return;}
        HBox row=(HBox)seg.textPane.getParent();
        int index=row.getChildren().indexOf(seg.textPane);
        row.getChildren().remove(index);
        Label summary=new Label("执行过程回复");summary.getStyleClass().add("log-summary-text");
        seg.collapsible=new CollapsibleText(summary,text,false,seg.textPane);
        HBox.setHgrow(seg.collapsible,Priority.ALWAYS);
        row.getChildren().add(index,seg.collapsible);
        seg.streamFinalized=true;
    }

    /** 折叠段默认展开判定：长内容（≥阈值）默认折叠、短内容默认展开；
     *  与 shouldCollapse 语义相反（package-private 供单测） */
    static boolean defaultExpanded(String text) {
        return !CollapsibleText.shouldCollapse(text);
    }

    /** 追加可折叠段（Node 摘要：图标+文本组合行）；正文为空则只渲染摘要行 */
    private void appendCollapsible(String tagText, String tagColorClass, Node summary, String text) {
        appendCollapsible(tagText, tagColorClass, summary, text, StreamKind.NONE, false);
    }

    /** 追加可折叠段（含流式身份）：THINK 流式段未知最终长度，先默认展开，定稿时按长度折叠 */
    private void appendCollapsible(String tagText, String tagColorClass, Node summary, String text,
                                   StreamKind kind) {
        appendCollapsible(tagText, tagColorClass, summary, text, kind, false);
    }

    /** forcedExpanded=true：无视长度阈值默认展开（提问段——折叠成一行即「看不见提问内容」） */
    private void appendCollapsible(String tagText, String tagColorClass, Node summary, String text,
                                   StreamKind kind, boolean forcedExpanded) {
        if (text == null || text.trim().isEmpty()) {
            // 无正文（子任务行/统计行）：CollapsibleText 空内容 → 只渲染摘要行
            if (empty) {
                resetNodes(); // 保留历史导航
                empty = false;
            }
            Seg seg = new Seg(tagText, tagColorClass, summary, "", false, kind);
            attachFocusGovernance(seg);
            segs.add(seg);
            getChildren().add(messageRow(seg));
            trimHead();
            return;
        }
        if (empty) {
            resetNodes();
            empty = false;
        }
        boolean expanded = forcedExpanded || kind == StreamKind.THINK ? true : defaultExpanded(text);
        Seg seg = new Seg(tagText, tagColorClass, summary, text, expanded, kind);
        attachFocusGovernance(seg);
        segs.add(seg);
        getChildren().add(messageRow(seg));
        trimHead();
    }

    /** 思考段定稿：从尾部找最后一个 THINK 段，结束即折叠。
     *  只定稿一次——CONTENT 流式增量重复到达不会覆盖用户手动展开状态；
     *  新一轮思考流式更新（setStreamText）会重置标志，结束后重新定稿 */
    private void finalizeThinking() {
        for (int i = segs.size() - 1; i >= 0; i--) {
            Seg s = segs.get(i);
            if (s.kind == StreamKind.THINK && s.collapsible != null) {
                if (!s.thinkFinalized) {
                    s.collapsible.finalizeThinking();
                    s.thinkFinalized = true;
                }
                return;
            }
        }
    }

    /** 截断保活：段数超 MAX_SEGS 时移除头部多余段（segs 与节点树同步删），并回调注入的 trim 监听。
     *  只动头部——流式段（THINK/REPLY）恒在尾部就地更新，不会被误删。 */
    private void trimHead() {
        int excess = segs.size() - MAX_SEGS;
        if (excess <= 0) return;
        double removedH = 0;
        for (int i = 0; i < excess; i++) {
            // 头部段早已布局（新段只加在尾部），layoutBounds 高度准确
            removedH += getChildren().get(i + 1).getLayoutBounds().getHeight() + getSpacing();
            Seg removed = segs.get(i);
            if (removed.steeringId != null && steeringSegments.get(removed.steeringId) == removed)
                steeringSegments.remove(removed.steeringId);
        }
        segs.subList(0, excess).clear();
        getChildren().remove(1, excess + 1); // 第一个节点为历史导航
        // 刷新为有完整事件边界的一页，确保被移除的段仍可向前翻阅。
        if (!replaying) showPage(replayed);
        if (!replaying && trimListener != null) trimListener.accept(removedH);
    }

    /** 流式增量：末段是同一流（THINK/REPLY）→ 就地更新正文不重建节点；NONE 静态行永不参与就地更新，恒新起一段 */
    private void stream(String tagText, String tagColorClass, String text, StreamKind kind) {
        Seg last = mainStreamSegment != null && segs.contains(mainStreamSegment) ? mainStreamSegment : null;
        if (last != null && !last.streamFinalized && last.kind == kind && kind != StreamKind.NONE) {
            if (last.collapsible != null) {
                // 思考段：流式更新强制展开（内容增长需实时可见），并重置定稿标志——
                // 新一轮思考复用同一段时，结束后需按新长度重新定稿折叠
                last.collapsible.setStreamText(text);
                last.thinkFinalized = false;
            } else {
                last.textPane.setText(text, true);
            }
            return;
        }
        // 思考段流式先展开，单次响应结束即收起，仍可手动查看全文。
        if (kind == StreamKind.THINK) {
            appendCollapsible(tagText, tagColorClass, new Label(""), text, kind);
        } else {
            append(tagText, tagColorClass, text, kind);
        }
        mainStreamSegment = segs.isEmpty() ? null : segs.get(segs.size()-1);
        if(kind==StreamKind.REPLY)pendingReply=mainStreamSegment;
    }

    /** 工具调用参数 → 展示正文：Edit/Write 生成行级 diff（仅变更行），其余完整参数 JSON；
     *  解析失败回退原 JSON（package-private 供单测） */
    static String toolCallBody(String name, Object data) {
        String json = data == null ? "{}" : data.toString();
        if (!"Edit".equals(name) && !"Write".equals(name)) return json;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String oldS = o.has("oldString") && !o.get("oldString").isJsonNull()
                    ? o.get("oldString").getAsString() : "";
            String newS = o.has("newString") && !o.get("newString").isJsonNull()
                    ? o.get("newString").getAsString() : "";
            StringBuilder sb = new StringBuilder();
            for (SimpleDiff.Line ln : SimpleDiff.diff(oldS, newS)) {
                if (ln.mark == SimpleDiff.COMMON) continue; // 只显示变更行
                sb.append(ln.mark).append(' ').append(ln.text).append('\n');
            }
            String body = sb.toString().trim();
            return body.isEmpty() ? json : body; // 无变更（如 newString==oldString）回退原 JSON
        } catch (Exception e) {
            return json;
        }
    }

    /** 工具调用摘要行：Edit/Write 附带路径，其余仅名称（package-private 供单测）；
     *  返回纯文本（无前缀标记——前缀图标由 toolSummary 的 SVG 扳手承担） */
    static String toolCallSummary(String name, Object data) {
        if ("Edit".equals(name) || "Write".equals(name)) {
            try {
                JsonObject o = JsonParser.parseString(data == null ? "{}" : data.toString()).getAsJsonObject();
                if (o.has("path") && !o.get("path").isJsonNull()) {
                    return name + " → " + o.get("path").getAsString();
                }
            } catch (Exception ignored) { }
        }
        return name;
    }

    /** 状态摘要行（正文段摘要）：图标(14px) + 文本，左对齐（替代原 ✅/❌/▶/✓ 等 Unicode 前缀，不依赖字体） */
    private static Node statusSummary(SVGPath icon, String text) {
        IconFactory.size(icon, 14);
        Label label = new Label(text);
        label.getStyleClass().add("log-summary-text");
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(icon, label);
        return box;
    }

    /** 工具调用摘要行：扳手图标 + 纯文本摘要（原 "⛭ " 前缀） */
    private static Node toolSummary(String text) {
        return statusSummary(IconFactory.build(), text);
    }

    /** TOOL_RESULT 数据 → 展示正文："ok\n" 前缀后为成功输出，"error:" 前缀后为失败原因；
     *  旧格式裸 "ok"（无输出）返回空串（package-private 供单测） */
    static String toolResultBody(String data) {
        if (data == null) return "";
        if (data.startsWith("ok\n")) return data.substring(3);
        if (data.startsWith("error:")) return data.substring("error:".length());
        return "";
    }

    /** 带工具名的正文判定：AskUserQuestion 的回答已由 onAskUserDone 投递 USER_SUPPLEMENT
     *  （【输入】段）渲染，成功态再渲染一遍会让同一段回答出现两次 → 只留状态摘要行；
     *  失败态（空参数快速失败等）无其他渲染路径，正文必须原样显示
     *  （package-private 供单测） */
    static String toolResultBody(String name, String data) {
        if ("AskUserQuestion".equals(name) && data != null && data.startsWith("ok")) return "";
        return toolResultBody(data);
    }

    /**
     * 流式缓冲（纯逻辑，无 JavaFX 依赖，可单测）：THINKING/CONTENT 增量累积；
     * 轮次边界事件（用户消息/补充/工具调用到达）整清空——AgentLoop 一轮 runUserTurn 内
     * 多轮 agent 回合（assistant→工具→assistant…）之间无 USER_MESSAGE，
     * 边界不清零会导致多轮回复文本跨轮累积，显示为"一直在回复同一段内容"。
     */
    static class StreamBuffer {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();

        /** 事件种类是否标志新一轮开始（上轮流式缓冲作废） */
        static boolean isRoundBoundary(EventList.Kind kind) {
            return kind == EventList.Kind.USER_MESSAGE
                    || kind == EventList.Kind.USER_SUPPLEMENT
                    || kind == EventList.Kind.TOOL_CALL
                    || kind == EventList.Kind.RESPONSE_END;
        }

        void onThinking(String delta) { if (delta != null) thinking.append(delta); }

        void onContent(String delta) { if (delta != null) content.append(delta); }

        /** 轮次边界：清空累积，下一轮回复/思考另起新段 */
        void onRoundBoundary() {
            content.setLength(0);
            thinking.setLength(0);
        }

        String content() { return content.toString(); }

        String thinking() { return thinking.toString(); }
    }

    /** 提问段强制展开上限：超此长度仍折叠（异常超长兜底原文防爆屏） */
    static final int ASK_FORCE_EXPAND_MAX = 4000;

    /** AskUserQuestion 工具调用参数 → 展示正文。委托 core AskUserQuestionTool.normalize：
     *  容错提取（键名写错/数组退化成字符串/标签泄漏吞掉 question）+ 提不出内容时回退原始参数，
     *  永不产出空白（旧实现在此静默 return "" → 消息区只剩「模型向你提问」一行，用户无从作答）。
     *  package-private 供单测 */
    static String askQuestionOf(Object data) {
        return com.minion.core.tools.AskUserQuestionTool
                .normalize(data == null ? "{}" : data.toString()).renderText();
    }

    /** 提问摘要行文本：「模型向你提问」+ header（header 是模型给的问题主题，此前从未渲染；超 20 字截断） */
    static String askSummaryText(Object data) {
        com.minion.core.tools.AskUserQuestionTool.Ask ask =
                com.minion.core.tools.AskUserQuestionTool
                        .normalize(data == null ? "{}" : data.toString());
        String header = ask.header;
        if (header == null || header.trim().isEmpty()) return "模型向你提问";
        String h = header.trim();
        // 只有 header 的畸形提问：normalize 已把 header 当问题正文，摘要再带一遍就重复两行
        if (ask.question != null && h.equals(ask.question.trim())) return "模型向你提问";
        if (h.length() > 20) h = h.substring(0, 20) + "…";
        return "模型向你提问 · " + h;
    }

    /** 提问段默认展开判定：无视 COLLAPSE_THRESHOLD（3 个长 description 的选项轻易超 500 字被折叠，
     *  即「看不见提问内容」的第二类成因），仅超 ASK_FORCE_EXPAND_MAX 才折叠（package-private 供单测） */
    static boolean askExpanded(String body) {
        return body == null || body.length() < ASK_FORCE_EXPAND_MAX;
    }
}
