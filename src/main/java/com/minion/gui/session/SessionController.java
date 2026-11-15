package com.minion.gui.session;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentUi;
import com.minion.core.agent.RetryProgress;
import com.minion.core.agent.SteeringReceipt;
import com.minion.core.llm.ImagePart;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.AskUserQuestionTool;
import com.minion.core.tools.ToolResult;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** AgentUi → EventList 路由：会话级事件缓冲 */
public class SessionController implements AgentUi {
    private volatile java.util.function.Consumer<String> externalWaitListener;
    public void setExternalWaitListener(java.util.function.Consumer<String> listener) { externalWaitListener=listener; }
    @Override public void onExternalWaitChanged(boolean waiting,String jobId) {
        java.util.function.Consumer<String> listener=externalWaitListener;
        if(listener!=null)listener.accept(waiting?jobId:null);
    }

    private final EventList events = new EventList();

    /** AskUserQuestion 挂起状态回调（非 null=开始挂起并携带问题；null=回答完成），SessionManager 注入 */
    private volatile java.util.function.Consumer<String> askStateListener;

    public void setAskStateListener(java.util.function.Consumer<String> l) { this.askStateListener = l; }

    /** 上下文压缩状态回调（true=开始，false=结束），SessionManager 注入 */
    private volatile java.util.function.Consumer<Boolean> compressingStateListener;

    public void setCompressingStateListener(java.util.function.Consumer<Boolean> l) { this.compressingStateListener = l; }

    /** 瞬时错误长重试进度回调（attempt ≥ 1 进入/更新；0 退出），SessionManager 注入 */
    private volatile java.util.function.Consumer<RetryProgress> retryStateListener;

    public void setRetryStateListener(java.util.function.Consumer<RetryProgress> l) { this.retryStateListener = l; }

    /** 上下文统计回调（AgentLoop 关键节点推送），SessionManager 注入 */
    private volatile java.util.function.Consumer<ContextStat> contextStatsListener;

    public void setContextStatsListener(java.util.function.Consumer<ContextStat> l) { this.contextStatsListener = l; }

    private volatile java.util.function.Consumer<String> artifactListener;

    public void setArtifactListener(java.util.function.Consumer<String> l) { this.artifactListener = l; }

    private volatile Consumer<SteeringReceipt> steeringReceiptListener;

    /** 会话所属 Manager 注入；回调携带独立副本，不与事件流共享可变回执。 */
    public void setSteeringReceiptListener(Consumer<SteeringReceipt> listener) {
        steeringReceiptListener = listener;
    }

    @Override public synchronized void onSteeringReceipt(SteeringReceipt receipt) {
        if (!validReceipt(receipt)) return;
        SteeringReceipt snapshot = receipt.copy();
        SteeringReceipt previous = events.steeringReceipt(snapshot.id);
        if (previous != null && EventList.olderReceipt(snapshot, previous)) return;
        recordSteering(snapshot, snapshot.text);
        Consumer<SteeringReceipt> listener = steeringReceiptListener;
        if (listener != null) listener.accept(snapshot.copy());
    }

    private static boolean validReceipt(SteeringReceipt receipt) {
        return receipt != null && receipt.id != null && !receipt.id.trim().isEmpty();
    }

    /** 只创建 GUI 事件，绝不向 Session.messages 或模型发送新用户消息。 */
    private void recordSteering(SteeringReceipt receipt, String originalText) {
        boolean first = events.steeringReceipt(receipt.id) == null;
        events.add(new EventList.Ev(first ? EventList.Kind.USER_SUPPLEMENT : EventList.Kind.STEERING_UPDATE,
                first ? (originalText == null ? "" : originalText) : null, receipt.copy()));
    }

    /** 上下文统计快照（used/max = 估算 token；估算线程推送，只读） */
    public static class ContextStat {
        public final int used;
        public final int max;
        public ContextStat(int used, int max) {
            this.used = used;
            this.max = max;
        }
    }

    public EventList eventList() { return events; }

    /** 恢复会话时把历史消息灌入事件流：USER→USER_MESSAGE、ASSISTANT→THINKING/CONTENT/TOOL_CALL、
     *  TOOL→TOOL_RESULT；SYSTEM 跳过。思考与工具过程随历史重演——重启恢复后正文/工具调用不缺失 */
    public void replayHistory(List<Message> messages) {
        replayHistory(messages, null);
    }

    /** 按稳定 ID 在原 USER 消息位置恢复回执；正文优先使用实际消息，而非 receipt.text 节选。
     * 未匹配的回执只补一条 GUI 行（包括尚未插入的引导），不重发模型，也不触发运行时 listener。
     */
    public synchronized void replayHistory(List<Message> messages, List<SteeringReceipt> receipts) {
        Map<String, SteeringReceipt> byId = new LinkedHashMap<String, SteeringReceipt>();
        if (receipts != null) for (SteeringReceipt receipt : receipts) {
            if (!validReceipt(receipt)) continue;
            SteeringReceipt previous = byId.get(receipt.id);
            if (previous == null || !EventList.olderReceipt(receipt, previous)) byId.put(receipt.id, receipt.copy());
        }
        Set<String> restored = new HashSet<String>();
        if (messages == null) messages = Collections.emptyList();
        for (Message m : messages) {
            if (m == null || m.role == null) continue;
            if (m.role == Message.Role.USER) {
                // 回答已由对应 TOOL 重演一次；这条消息只承载像素，不重复显示为新用户任务。
                if (m.toolImage && m.replyToToolCallId != null) continue;
                SteeringReceipt receipt = byId.get(m.steeringId);
                if (receipt != null) {
                    if (restored.add(receipt.id)) {
                        String text = ImagePart.displayText(m.images, m.content);
                        recordSteering(receipt, text == null || text.isEmpty() ? receipt.text : text);
                    }
                    continue;
                }
                // 带图消息事件文本拼图片占位（聊天区不渲染图片本体）
                events.add(new EventList.Ev(m.supplement
                        ? EventList.Kind.USER_SUPPLEMENT : EventList.Kind.USER_MESSAGE,
                        ImagePart.displayText(m.images, m.content), null));
            } else if (m.role == Message.Role.ASSISTANT) {
                if (m.reasoningContent != null && !m.reasoningContent.isEmpty()) {
                    events.add(new EventList.Ev(EventList.Kind.THINKING, m.reasoningContent, null));
                }
                if (m.content != null && !m.content.trim().isEmpty()) {
                    // 思考内容先于正文重演：ChatView 的【思考】段只由 THINKING 事件驱动，
                    // 不重演则重启恢复后思考丢失（上下文完好，纯显示缺失）
                    events.add(new EventList.Ev(EventList.Kind.CONTENT, m.content, null));
                }
                if ((m.content != null && !m.content.trim().isEmpty())
                        || (m.reasoningContent != null && !m.reasoningContent.isEmpty())) onResponseEnd();
                // 工具调用逐个重演（含纯工具调用无正文消息），参数同运行时 onToolCall 格式
                if (m.toolCalls != null) {
                    for (ToolCall tc : m.toolCalls) {
                        if (tc == null || hiddenControlTool(tc.name)) continue;
                        events.add(new EventList.Ev(EventList.Kind.TOOL_CALL, tc.name,
                                tc.arguments == null ? "{}" : tc.arguments));
                    }
                }
            } else if (m.role == Message.Role.TOOL && m.name != null) {
                if (hiddenControlTool(m.name)) continue;
                // AskUserQuestion 的回答存于 TOOL 消息 output：先重演回答行（USER_SUPPLEMENT【输入】段）
                // 再 ✅ 行，恢复会话后提问与回答成对显示、顺序与运行时一致。
                // 例外：带 INVALID_PREFIX 的是「提不出提问内容」的失败输出（历史无成败标记），
                // 不能当成用户回答重演，只在 ✅ 行显示原因
                if ("AskUserQuestion".equals(m.name) && m.content != null && !m.content.trim().isEmpty()
                        && !m.content.startsWith(AskUserQuestionTool.INVALID_PREFIX)) {
                    events.add(new EventList.Ev(EventList.Kind.USER_SUPPLEMENT,
                            AskUserQuestionTool.answerForDisplay(m.content), null));
                }
                // 历史 TOOL 消息无成败标记（只存 output），统一按成功态重演，携带完整输出
                String out = (m.content == null || m.content.trim().isEmpty()) ? "ok" : "ok\n" + m.content;
                events.add(new EventList.Ev(EventList.Kind.TOOL_RESULT, m.name, out));
            }
        }
        for (SteeringReceipt receipt : byId.values()) {
            if (!restored.contains(receipt.id)) recordSteering(receipt, receipt.text);
        }
    }

    @Override public void onUserMessage(String text) {
        events.add(new EventList.Ev(EventList.Kind.USER_MESSAGE, text, null));
    }
    @Override public void onThinking(String delta) {
        events.add(new EventList.Ev(EventList.Kind.THINKING, delta, null));
    }
    @Override public void onContent(String delta) {
        events.add(new EventList.Ev(EventList.Kind.CONTENT, delta, null));
    }
    @Override public void onTaskCompletionTime(String line) { onSystem(line); }
    @Override public void onResponseEnd() {
        events.add(new EventList.Ev(EventList.Kind.RESPONSE_END, null, null));
    }
    @Override public void onToolCall(String name, JsonObject args) {
        if (hiddenControlTool(name)) return;
        events.add(new EventList.Ev(EventList.Kind.TOOL_CALL, name,
                args == null ? "{}" : args.toString()));
    }
    @Override public void onToolResult(String name, ToolResult result) {
        if (hiddenControlTool(name)) return;
        if (result == null) {
            events.add(new EventList.Ev(EventList.Kind.TOOL_RESULT, name, "ok"));
        } else if (result.ok) {
            // "ok" 前缀保持 ChatView 现有 ok 判断兼容；换行后接完整输出（可能为空串）
            events.add(new EventList.Ev(EventList.Kind.TOOL_RESULT, name,
                    "ok\n" + (result.output == null ? "" : result.output)));
        } else {
            events.add(new EventList.Ev(EventList.Kind.TOOL_RESULT, name,
                    "error:" + (result.output == null ? "" : result.output)));
        }
    }

    /** Internal orchestration remains durable in task/event storage but does not pollute chat. */
    static boolean hiddenControlTool(String name) {
        if (name == null) return false;
        String normalized=name.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("agentcontrol") || normalized.equals("todowrite")
                || normalized.equals("discovertools") || normalized.equals("context")
                || normalized.equals("skill");
    }
    @Override public void onSubAgentStart(String description) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_START, description, null));
    }
    @Override public void onChildEvent(String id,String kind,String text,Object data) {
        EventList.Kind type;
        if ("START".equals(kind)) type=EventList.Kind.SUB_AGENT_START;
        else if ("DONE".equals(kind)) type=EventList.Kind.SUB_AGENT_DONE;
        else type=EventList.Kind.valueOf(kind);
        events.add(new EventList.Ev(type,text,data,id));
    }
    @Override public void onSubAgentDelta(String delta) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_DELTA, delta, null));
    }
    @Override public void onSubAgentDone(String summary) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_DONE, summary, null));
    }
    @Override public void onStatsLine(String line) {
        events.add(new EventList.Ev(EventList.Kind.STATS, line, null));
    }
    @Override public void onError(String message) {
        events.add(new EventList.Ev(EventList.Kind.ERROR, message, null));
    }
    @Override public void onWarning(String message) {
        events.add(new EventList.Ev(EventList.Kind.WARNING, message, null));
    }
    @Override public void onUserSupplement(String text) {
        events.add(new EventList.Ev(EventList.Kind.USER_SUPPLEMENT, text, null));
    }
    /** 系统行（斜杠命令结果等 GUI 本地事件；非 AgentUi 接口方法，仅命令分发路径使用） */
    public void onSystem(String text) {
        events.add(new EventList.Ev(EventList.Kind.SYSTEM, text, null));
    }
    @Override public void onAskUserStart(String question) {
        if (askStateListener != null) askStateListener.accept(question);
    }
    @Override public void onAskUserDone(String answer) {
        if (askStateListener != null) askStateListener.accept(null);
        // 回答入事件流（USER_SUPPLEMENT 渲染为【输入】段）：消息区提问「❓ 模型向你提问」与回答成对显示；
        // 空回答不投递，与「输入为空不发」一致
        if (answer != null && !answer.isEmpty()) onUserSupplement(answer);
    }
    @Override public void onCompressingChanged(boolean compressing) {
        if (compressingStateListener != null) compressingStateListener.accept(compressing);
    }

    @Override public void onRetryProgress(RetryProgress p) {
        if (retryStateListener != null) retryStateListener.accept(p);
    }

    @Override public void onContextStats(int used, int max) {
        if (contextStatsListener != null) contextStatsListener.accept(new ContextStat(used, max));
    }
    @Override public void onArtifactCreated(String path) {
        if(path==null||path.trim().isEmpty())return;
        events.add(new EventList.Ev(EventList.Kind.ARTIFACT,path,null));
        if (artifactListener != null) artifactListener.accept(path);
    }
    public void replayArtifacts(java.util.List<com.minion.core.agent.GeneratedArtifact> artifacts) {
        if(artifacts==null)return;
        int count = 0;
        for(com.minion.core.agent.GeneratedArtifact a:artifacts)
            if(com.minion.gui.artifact.ArtifactSelection.presentable(a))count++;
        // Saved artifacts are already indexed in the workspace. Don't append a
        // wall of old paths after the restored transcript.
        if(count>0)events.add(new EventList.Ev(EventList.Kind.ARTIFACT_ARCHIVE,Integer.toString(count),null));
    }
}
