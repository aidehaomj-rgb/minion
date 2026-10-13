package com.minion.core.context;

import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 上下文管理：token 估算、阈值判断、链式压缩（完整回合链为单位，摘要置前） */
public class ContextManager {

    private static final String COMPRESS_SYSTEM =
            "你是 minion 的上下文压缩器。把用户提供的对话历史压缩成一段中文摘要，保留："
          + "未完成的任务与目标、已做出的关键决策及原因、使用过的工具与结果要点、"
          + "相关文件路径、代码约定、用户偏好、当前进度、失败尝试和明确的下一步。"
          + "已有历史摘要必须与新增历史合并；旧指令只作背景，不能当作当前待执行任务。"
          + "明确区分已完成和待执行的工作，不要省略仍待执行的用户要求；"
          + "只输出结构清晰的摘要正文，不要客套，3000 字以内。";

    private static final String TASK_START = "【当前任务原文】\n";
    private static final String TASK_END = "\n【历史背景】\n";
    private static final String TASK_STATE_START = "\n【任务清单快照：已完成项不要重复】\n";
    private static final int MAX_TASK_CHARS = 12000;

    private static final int KEEP_MIN = 12;          // 常规压缩保留区下限（条数）
    private static final int EMERGENCY_KEEP = 4;     // 服务端已报超限时只保留最近消息
    private static final double KEEP_RATIO = 0.5;    // 保留区占比目标

    private static final int SEGMENT_MIN = 30; // 段内消息数下限：以下不再递归
    private static final int COMPRESS_FIELD_CHARS = 16000; // 单字段送摘要模型的上限，防巨型工具输出拖垮压缩请求
    private static final int OLD_TOOL_CHARS = 8000;
    private static final int CURRENT_TOOL_CHARS = 24000;
    private static final int OLD_REASONING_CHARS = 4000;
    private static final int CURRENT_REASONING_CHARS = 12000;
    private static final int OLD_ASSISTANT_CHARS = 12000;
    private static final int CURRENT_ASSISTANT_CHARS = 24000;
    private static final int OLD_ARGUMENT_CHARS = 8000;
    private static final int CURRENT_ARGUMENT_CHARS = 16000;

    // FX 线程 update/setLlm 写入、会话工作线程 shouldCompress/compress 读取——volatile 防 JMM 数据竞争
    /** 用户配置的理论窗口，以及按模型/实测错误收紧后的实际安全窗口。 */
    private volatile int configuredMaxContextTokens;
    private volatile int effectiveMaxContextTokens;
    private volatile double threshold;
    private volatile int outputReserveTokens = 8192;
    private volatile int keepRecent;
    private volatile LlmClient llm;
    private final int systemTokens;    // system 提示词 token 估算在构造时固定，保持不变
    /** 最近一次 compress 是否真正尝试过压缩（take>0）：false = 真无可压缩（条数/token 未达），
     *  true = 已尝试但 LLM 调用失败原样返回。供 AgentLoop 区分"暂无可压缩"与"压缩失败"提示 */
    private volatile boolean lastCompressAttempted;
    private volatile double inputTokenScale = 1.0;
    private volatile String requestProfile="balanced";
    public void setRequestProfile(String profile) { requestProfile="compact".equals(profile)?"compact":"balanced"; }

    public ContextManager(int maxContextTokens, double threshold, int keepRecent,
                          LlmClient llm, int systemTokens) {
        this.configuredMaxContextTokens = maxContextTokens;
        this.effectiveMaxContextTokens = maxContextTokens;
        this.threshold = normalizedThreshold(threshold);
        this.keepRecent = keepRecent;
        this.llm = llm;
        this.systemTokens = systemTokens;
    }

    /** 模型参数热更新（设置窗修改后调用；运行时生效于下一轮压缩判断） */
    public void update(int maxContextTokens, double threshold, int keepRecent) {
        this.configuredMaxContextTokens = maxContextTokens;
        this.effectiveMaxContextTokens = maxContextTokens;
        this.threshold = normalizedThreshold(threshold);
        this.keepRecent = keepRecent;
    }

    /** 换 LLM 客户端（模型切换后调用；压缩请求走新客户端） */
    public void setLlm(LlmClient llm) { this.llm = llm; inputTokenScale = 1.0; }

    /** 上下文窗口上限（AgentLoop 压缩百分比计算用） */
    public int maxTokens() { return effectiveMaxContextTokens; }
    public int configuredMaxTokens() { return configuredMaxContextTokens; }
    public void setOutputReserveTokens(int tokens) { outputReserveTokens = tokens > 0 ? tokens : 8192; }
    public int outputReserveTokens() { return outputReserveTokens; }
    public static double normalizedThreshold(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) || value <= 0 || value > 1 ? 0.8 : value;
    }

    /**
     * 某些 OpenAI 兼容网关声明的窗口大于实际部署窗口。初始化时可给一个保守上限；
     * 不改用户保存的模型配置，只影响当前会话的自动压缩和进度显示。
     */
    public void setInitialSafeMax(int safeTokens) {
        if (safeTokens <= 0) return;
        effectiveMaxContextTokens = Math.max(8192,
                Math.min(configuredMaxContextTokens, safeTokens));
    }

    /** 服务端超限/连续空响应后，根据本次实际输入继续收紧当前会话窗口。 */
    public int learnFromContextFailure(int currentTokens) {
        if (currentTokens >= 16384) {
            int learned = Math.max(8192, (int) Math.floor(currentTokens * 0.90));
            effectiveMaxContextTokens = Math.min(effectiveMaxContextTokens, learned);
        }
        return effectiveMaxContextTokens;
    }

    /** 最近一次 compress 是否真正尝试过压缩（供"压缩失败"与"暂无可压缩"文案区分） */
    public boolean lastCompressAttempted() { return lastCompressAttempted; }

    /** 自动压缩阈值（0~1，如 0.8=80% 触发；GUI 悬停"剩余x%自动压缩"计算用） */
    public double threshold() { return threshold; }
    private int taskAnchorTokens;
    private String compressionControl;
    public void setTaskAnchorTokens(int tokens) { taskAnchorTokens = Math.max(0,tokens); }

    public int estimate(List<Message> messages) {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.ceil((systemTokens + taskAnchorTokens + (double) TokenCounter.estimateMessages(prepareForRequest(messages)))
                        * inputTokenScale));
    }

    /** API 的 prompt_tokens 包含工具定义等开销；用当前请求对应的本地统计校准后续预估。 */
    public void observeInputTokens(List<Message> messages, int actualTokens) {
        int local = systemTokens + taskAnchorTokens + TokenCounter.estimateMessages(messages);
        if (local > 0 && actualTokens > 0) inputTokenScale = Math.max(1.0, (double) actualTokens / local);
    }

    public boolean shouldCompress(List<Message> messages) {
        return estimate(messages) >= autoCompressAtTokens();
    }

    /**
     * 按用户比例触发，但保留配置的单次输出预算（常见窗口至少8192）。
     * 不再固定扣掉20%，否则90%/95%等用户配置永远无法生效。
     * 工具结果进入历史后，下一次请求前重新估算并判断压缩。
     */
    public int autoCompressAtTokens() {
        return triggerAtTokens(effectiveMaxContextTokens, threshold, outputReserveTokens);
    }

    /** 设置页预览与运行时使用同一算法。小窗口保持旧版测试/本地模型的行为。 */
    public static int triggerAtTokens(int max, double ratio, int outputTokens) {
        int ratioLimit = (int) Math.floor(max * normalizedThreshold(ratio));
        int reserve = max < 16384 ? 0 : Math.max(8192, outputTokens > 0 ? outputTokens : 8192);
        return Math.max(1, Math.min(ratioLimit, Math.max(1, max - reserve)));
    }

    /**
     * 仅构造“发给模型”的轻量副本，不修改 session 原始历史，因此界面回放和落盘内容完整：
     * 旧图片不重复传 base64；历史长思考、长工具输出和长参数只保留首尾。当前任务链使用
     * 更宽松的上限，用户原始输入永不截断。
     */
    public List<Message> prepareForRequest(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        int protectedFrom = Math.max(0, messages.size() - 12);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role == Message.Role.USER && !m.summary && !m.pinned) {
                protectedFrom = i;
                break;
            }
        }
        List<Message> out = new ArrayList<Message>(messages.size());
        java.util.Map<String,Integer> lastToolOutput=new java.util.HashMap<String,Integer>();
        for(int i=0;i<messages.size();i++) {
            Message m=messages.get(i);
            if(m.role==Message.Role.TOOL&&m.content!=null&&m.content.length()>1000) lastToolOutput.put(m.name+"\n"+m.content,i);
        }
        for (int i = 0; i < messages.size(); i++) {
            Message src = messages.get(i);
            boolean current = i >= protectedFrom;
            Message m = copyMessage(src);
            if (m.role == Message.Role.TOOL) {
                m.content = compactForRequest(m.content,
                        "compact".equals(requestProfile)?(current?6000:3000):(current ? CURRENT_TOOL_CHARS : OLD_TOOL_CHARS), "工具输出");
                Integer last=src.content==null?null:lastToolOutput.get(src.name+"\n"+src.content);
                if(last!=null&&last>i) m.content="[重复工具输出已去重，内容与后续第"+(last+1)+"条相同；原记录仍保存在本地历史]";
            } else if (m.role == Message.Role.ASSISTANT) {
                m.content = compactForRequest(m.content,
                        current ? CURRENT_ASSISTANT_CHARS : OLD_ASSISTANT_CHARS, "助手正文");
                m.reasoningContent = compactForRequest(m.reasoningContent,
                        "compact".equals(requestProfile)?(current?4000:1000):(current ? CURRENT_REASONING_CHARS : OLD_REASONING_CHARS), "历史思考");
            }
            if (!current && m.role == Message.Role.USER && m.images != null && !m.images.isEmpty()) {
                m.images = null;
                m.content = (m.content == null ? "" : m.content)
                        + "\n[旧图片数据已从模型上下文移除；图片记录仍保存在会话历史中]";
            }
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                List<com.minion.core.llm.ToolCall> calls = new ArrayList<com.minion.core.llm.ToolCall>();
                for (com.minion.core.llm.ToolCall srcCall : m.toolCalls) {
                    com.minion.core.llm.ToolCall call = new com.minion.core.llm.ToolCall();
                    call.id = srcCall.id;
                    call.type = srcCall.type;
                    call.name = srcCall.name;
                    // assistant.tool_calls.function.arguments 必须始终是合法 JSON；首尾截断会让
                    // OpenAI 兼容服务直接报“tool call arguments must be valid JSON”。当前链保留
                    // 原参数，旧链用合法占位 JSON 替代（工具结果和本地历史仍完整）。
                    call.arguments = current ? srcCall.arguments
                            : compactArguments(srcCall.arguments, OLD_ARGUMENT_CHARS);
                    calls.add(call);
                }
                m.toolCalls = calls;
            }
            out.add(m);
        }
        return out;
    }

    private static Message copyMessage(Message src) {
        Message m = new Message();
        m.role = src.role;
        m.content = src.content;
        m.reasoningContent = src.reasoningContent;
        m.toolCalls = src.toolCalls;
        m.toolCallId = src.toolCallId;
        m.name = src.name;
        m.summary = src.summary;
        m.pinned = src.pinned;
        m.images = src.images;
        m.supplement = src.supplement;
        m.ts = src.ts;
        return m;
    }

    private static String compactForRequest(String text, int limit, String label) {
        if (text == null || text.length() <= limit) return text;
        int head = limit * 2 / 3;
        int tail = limit - head;
        return text.substring(0, head) + "\n...[" + label + "中间已省略 "
                + (text.length() - limit) + " 字符；完整内容仍保存在本地历史]...\n"
                + text.substring(text.length() - tail);
    }

    private static String compactArguments(String json, int limit) {
        if (json == null || json.length() <= limit) return json;
        return "{\"_minion_compacted\":true,\"_original_chars\":" + json.length() + "}";
    }

    /** 按完整回合链切块。summary 消息跳过（已压缩过，不再参与）；pinned 消息跳过（技能
     *  正文常驻，压缩豁免，由 compress 原样保留）；system 消息跳过（系统提示词不并入链、
     *  不进入压缩批次，由 compress 原样保留）。 */
    public static List<List<Message>> chunkChains(List<Message> messages) {
        List<List<Message>> chains = new ArrayList<List<Message>>();
        List<Message> cur = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.summary || m.pinned || m.role == Message.Role.SYSTEM) {
                flush(chains, cur);
                continue;
            }
            cur.add(m);
            if (m.role == Message.Role.ASSISTANT
                    && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                flush(chains, cur); // 无工具调用的 assistant 结束一条链
            }
        }
        flush(chains, cur);
        return chains;
    }

    private static void flush(List<List<Message>> chains, List<Message> cur) {
        if (!cur.isEmpty()) {
            chains.add(new ArrayList<Message>(cur));
            cur.clear();
        }
    }

    /** 保留区动态缩小：keep 条数起步，保留区 token 占比 > KEEP_RATIO 且条数 > KEEP_MIN
     *  时减半重算。take==0 但上下文已超阈值时同样强制缩小：消息条数少但单条超长
     *  （如 43 轮长思考/大工具输出，token 已 99% 而条数 ≤ keepRecent）时，
     *  修复前 take==0 直接原样返回，误报"暂无可压缩内容"。返回要压缩的链数（前 take 个链）。 */
    private int takeChains(List<List<Message>> chains, int limit, List<Message> messages) {
        int take = computeTake(chains, limit);
        int total = TokenCounter.estimateMessages(messages);
        boolean force = take == 0 && shouldCompress(messages); // 条数不足但 token 超阈值：强制缩小
        while ((force || take > 0) && limit > KEEP_MIN) {
            int keptTokens = 0;
            for (int i = take; i < chains.size(); i++) {
                keptTokens += TokenCounter.estimateMessages(chains.get(i));
            }
            if (take > 0 && (double) keptTokens / total <= KEEP_RATIO) break;
            limit = Math.max(KEEP_MIN, limit / 2); // 减半不跌破下限（如 13 → 12，而非 6）
            int next = computeTake(chains, limit);
            if (next == 0) break; // 缩到下限仍全保留（消息 ≤ KEEP_MIN 条）：真无可压缩
            take = next;
        }
        return take;
    }

    /** 从后往前按链累计：保留的完整链条数总和 ≤ limit。返回首个保留链的下标（= 要压缩的链数）。 */
    private int computeTake(List<List<Message>> chains, int limit) {
        int kept = 0;
        int take = chains.size();
        for (int i = chains.size() - 1; i >= 0; i--) {
            int size = chains.get(i).size();
            if (kept + size > limit) break;
            kept += size;
            take = i;
        }
        return take;
    }

    /** 压缩：链为单位，摘要置前；保留最近 keepRecent 条原文。失败分段递归降级，全部失败原样返回。 */
    public List<Message> compress(List<Message> messages) {
        return compress(messages, keepRecent, false, null);
    }

    /** 任务清单由 AgentLoop 注入；独立于模型生成的摘要保留。 */
    public List<Message> compress(List<Message> messages, String taskState) {
        return compress(messages, keepRecent, false, taskState);
    }

    public List<Message> compress(List<Message> messages, String taskState, String objective) {
        return compress(messages, keepRecent, false, taskState, objective);
    }

    public List<Message> compressForRecovery(List<Message> messages, String taskState, String objective) {
        return compress(messages, EMERGENCY_KEEP, true, taskState, objective);
    }

    /**
     * 服务端明确返回上下文超限后的强制压缩。它不依赖本地窗口估算（配置值可能大于
     * 内网模型真实窗口），并允许压缩当前尚未结束的工具链，只保留最近少量原文。
     */
    public List<Message> compressForRecovery(List<Message> messages) {
        return compress(messages, EMERGENCY_KEEP, true, null);
    }

    public List<Message> compressForRecovery(List<Message> messages, String taskState) {
        return compress(messages, EMERGENCY_KEEP, true, taskState);
    }

    private List<Message> compress(List<Message> messages, int requestedKeep, boolean force,
                                   String taskState) {
        return compress(messages, requestedKeep, force, taskState, null);
    }

    private List<Message> compress(List<Message> messages, int requestedKeep, boolean force,
                                   String taskState, String objective) {
        // 超长单链先按消息均分拆成 ≤ SEGMENT_MIN 的子链：否则单链段无法在链边界二分，
        // splitByTokens 会把 mid 钳回 from，产生同参数递归 → 无限递归栈溢出
        List<List<Message>> chains = splitOversizedChains(chunkChains(messages));
        int take = takeChains(chains, requestedKeep, messages);
        if (force && take == 0 && !chains.isEmpty()) {
            // 单条未结束链也必须可恢复：将其纳入摘要，而不是因“近期消息需保留”原样返回。
            take = chains.size();
        }
        if (take == 0) {
            lastCompressAttempted = false; // 真无可压缩（未达压缩条件）
            return messages; // 全部要保留，无需压缩
        }
        lastCompressAttempted = true; // 已尝试：后续无论成败都不是"无可压缩"

        String oldSummary = priorSummaries(messages);
        String currentTask = objective != null ? objective : currentTask(messages);
        if (oldSummary != null && currentTask != null)
            oldSummary = "【当前有效任务，以此为准】\n" + currentTask + "\n" + oldSummary;
        // Control is not a successfully compressed prefix on model failure.
        compressionControl = objective == null ? null : "【当前控制记录，不得改写】\n" + objective + "\n" + taskState;
        SegResult res;
        try { res = compressChains(chains, 0, take, oldSummary); }
        finally { compressionControl = null; }
        if (res == null) {
            System.err.println("[minion] 压缩失败，跳过本轮");
            return messages;
        }
        // 技能加载消息（pinned）常驻：不入链不参与摘要，原样保留在摘要后
        List<Message> result = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.role == Message.Role.SYSTEM) result.add(m); // system 原样保留，置于最前
        }
        for (Message m : res.messages) {
            if (m.summary) result.add(summaryMsg(summaryBody(m.content), currentTask, taskState));
            else result.add(m);
        }
        for (Message m : messages) {
            if (m.pinned) result.add(m);
        }
        for (int i = take; i < chains.size(); i++) {
            result.addAll(chains.get(i)); // 保留未被压缩的链
        }
        return result;
    }

    private static String priorSummaries(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message m : messages) {
            if (!m.summary || m.content == null || m.content.trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append("【已有历史摘要，请合并】\n").append(summaryBody(m.content));
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String summaryBody(String content) {
        if (content == null) return "";
        int background = content.indexOf(TASK_END);
        String body = content;
        if (content.startsWith("【历史对话摘要】\n") && background >= 0)
            body = content.substring(background + TASK_END.length());
        else if (content.startsWith("【历史对话摘要】\n"))
            body = content.substring("【历史对话摘要】\n".length());
        int state = body.indexOf(TASK_STATE_START);
        return state >= 0 ? body.substring(0, state) : body;
    }

    private static String currentTask(List<Message> messages) {
        String oldTask = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.summary && m.content != null && oldTask == null) {
                int start = m.content.indexOf(TASK_START);
                int end = m.content.indexOf(TASK_END, start + TASK_START.length());
                if (start >= 0 && end > start) oldTask = m.content.substring(start + TASK_START.length(), end);
            }
            if (m.role != Message.Role.USER || m.summary || m.pinned || m.content == null) continue;
            String text = m.content.trim();
            if (text.isEmpty() || continuation(text)) continue;
            return compactTask(text);
        }
        return oldTask;
    }

    private static boolean continuation(String text) {
        String t = text.replaceAll("[\\s，。！!？?、,.]", "");
        return t.matches("(?i)(继续.*|接着.*|恢复.*|完成.*(之前|未完成).*|为什么.*(停|不执行|不生成).*|"
                + "怎么.*(停|不执行|不生成).*|又.*(停|不执行|不生成).*|好的|好|确认|是|同意|ok|yes|1)");
    }

    private static String compactTask(String text) {
        if (text.length() <= MAX_TASK_CHARS) return text;
        int half = MAX_TASK_CHARS / 2;
        return text.substring(0, half) + "\n[原始指令中间过长，已省略]\n"
                + text.substring(text.length() - half);
    }

    private static Message summaryMsg(String text, String task, String taskState) {
        StringBuilder out = new StringBuilder();
        if (task != null && !task.trim().isEmpty())
            out.append(TASK_START).append(task).append(TASK_END);
        else out.append("【历史背景】\n");
        out.append(text == null ? "" : text);
        if (taskState != null && !taskState.trim().isEmpty())
            out.append(TASK_STATE_START)
                    .append(compactTask(taskState.trim()));
        return summaryMsg(out.toString());
    }

    private static class SegResult {
        final String summary;         // 本段完全成功时的摘要文本；null = 未完全成功
        final List<Message> messages; // 本段输出（摘要置前+失败原文）；null = 段整体失败
        SegResult(String summary, List<Message> messages) {
            this.summary = summary;
            this.messages = messages;
        }
    }

    /** 压缩 chains[from,to)，prefix 为上游摘要文本（可为 null）。递归降级：
     *  整体成功 → 单摘要；失败且段 > SEGMENT_MIN → token 均衡二分递归；
     *  失败且已最小段 → prefix(如有)+原文。返回 null = 本段整体失败。 */
    private SegResult compressChains(List<List<Message>> chains, int from, int to, String prefix) {
        String s = callLlm(prefix, buildBatch(chains, from, to));
        if (s != null && !s.trim().isEmpty()) {
            String t = s.trim();
            return new SegResult(t, Collections.singletonList(summaryMsg(t)));
        }
        if (countMessages(chains, from, to) <= SEGMENT_MIN) {
            if (prefix == null) return null; // 无上游摘要且本段失败 → 整体失败
            List<Message> out = new ArrayList<Message>();
            out.add(summaryMsg(prefix));
            for (int i = from; i < to; i++) out.addAll(chains.get(i));
            return new SegResult(null, out);
        }
        int mid = splitByTokens(chains, from, to);
        SegResult left = compressChains(chains, from, mid, prefix);
        if (left == null) {
            // 左段全败：右段带原 prefix 压，左段原文保留在结果尾部
            SegResult right = compressChains(chains, mid, to, prefix);
            if (right == null) return null;
            List<Message> out = new ArrayList<Message>(right.messages);
            for (int i = from; i < mid; i++) out.addAll(chains.get(i));
            return new SegResult(right.summary, out);
        }
        if (left.summary != null) {
            // 左段完全成功：摘要A 作 prefix 压右段（合并），左段摘要由右段结果取代
            SegResult right = compressChains(chains, mid, to, left.summary);
            if (right == null) {
                // 右段整体失败：摘要A + 右段原文（原文不得因降级丢失）
                List<Message> out = new ArrayList<Message>(left.messages);
                for (int i = mid; i < to; i++) out.addAll(chains.get(i));
                return new SegResult(left.summary, out);
            }
            return right;
        }
        // 左段部分成功（含失败原文）：右段独立压，两侧结果摘要统一置前拼接
        SegResult right = compressChains(chains, mid, to, null);
        if (right == null) {
            // 右段整体失败：左段部分结果 + 右段原文（原文不得因降级丢失）
            List<Message> out = new ArrayList<Message>(left.messages);
            for (int i = mid; i < to; i++) out.addAll(chains.get(i));
            return new SegResult(left.summary, out);
        }
        List<Message> out = new ArrayList<Message>();
        for (Message m : left.messages) if (m.summary) out.add(m);
        for (Message m : right.messages) if (m.summary) out.add(m);
        for (Message m : left.messages) if (!m.summary) out.add(m);
        for (Message m : right.messages) if (!m.summary) out.add(m);
        return new SegResult(null, out);
    }

    /** 拼压缩批次文本；prefix 非空时置于批次前（上游摘要参与下游合并） */
    private String callLlm(String prefix, String batch) {
        StringBuilder sb = new StringBuilder();
        if (compressionControl != null) sb.append(compressionControl).append('\n');
        if (prefix != null) sb.append(prefix).append('\n');
        sb.append(batch);
        try {
            return llm.completeChat(
                    Collections.singletonList(Message.user(sb.toString())), COMPRESS_SYSTEM);
        } catch (Exception e) {
            return null; // 失败静默降级，顶层统一告警
        }
    }

    private String buildBatch(List<List<Message>> chains, int from, int to) {
        StringBuilder batch = new StringBuilder();
        for (int i = from; i < to; i++) {
            for (Message m : chains.get(i)) {
                batch.append('[').append(m.role).append(']');
                if (m.content != null) batch.append(' ').append(compactField(m.content));
                if (m.reasoningContent != null) batch.append(" (思考: ")
                        .append(compactField(m.reasoningContent)).append(')');
                if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                    batch.append(" [工具: ");
                    for (com.minion.core.llm.ToolCall tc : m.toolCalls) {
                        batch.append(tc.name).append(' ');
                        if (tc.arguments != null) batch.append(compactField(tc.arguments)).append(' ');
                    }
                    batch.append(']');
                }
                batch.append('\n');
            }
        }
        return batch.toString();
    }

    /** 巨型文件/SQL/命令输出只取首尾，保留错误结尾和关键开头，并明确告知摘要模型发生过截断。 */
    private static String compactField(String text) {
        if (text == null || text.length() <= COMPRESS_FIELD_CHARS) return text;
        int head = COMPRESS_FIELD_CHARS * 2 / 3;
        int tail = COMPRESS_FIELD_CHARS - head;
        return text.substring(0, head) + "\n...[中间内容过长，压缩时已省略 "
                + (text.length() - COMPRESS_FIELD_CHARS) + " 字符]...\n"
                + text.substring(text.length() - tail);
    }

    private int countMessages(List<List<Message>> chains, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) n += chains.get(i).size();
        return n;
    }

    /** 按 token 均衡二分（链为边界），mid 严格落在 (from, to) 内 */
    private int splitByTokens(List<List<Message>> chains, int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) total += TokenCounter.estimateMessages(chains.get(i));
        int half = total / 2;
        int acc = 0;
        int mid = from + 1;
        for (int i = from; i < to - 1; i++) {
            acc += TokenCounter.estimateMessages(chains.get(i));
            if (acc >= half) {
                mid = i + 1;
                break;
            }
        }
        if (mid <= from) mid = from + 1;
        if (mid >= to) mid = to - 1;
        return mid;
    }

    /** 拆分超长单链（> SEGMENT_MIN 条消息）为 ≤ SEGMENT_MIN 的子链，保持消息顺序。
     *  保证递归中任何 countMessages > SEGMENT_MIN 的段至少含 2 条链，splitByTokens 的
     *  mid 必严格落在 (from, to) 内，杜绝单链段同参数无限递归。 */
    static List<List<Message>> splitOversizedChains(List<List<Message>> chains) {
        List<List<Message>> out = new ArrayList<List<Message>>();
        for (List<Message> chain : chains) {
            splitChainByMessages(chain, out);
        }
        return out;
    }

    /** 单链按消息数均分递归二分，直至子链 ≤ SEGMENT_MIN 条（拷贝为独立列表，不持有原链视图）。
     *  切分点避开 tool_result：子链首条不得为 TOOL，否则切断 tool_call/tool_result 配对，
     *  保留区首条可能成为孤立 TOOL 消息 → 下轮发送 DeepSeek 400。 */
    private static void splitChainByMessages(List<Message> chain, List<List<Message>> out) {
        if (chain.size() <= SEGMENT_MIN) {
            out.add(new ArrayList<Message>(chain));
            return;
        }
        // mid 落在 TOOL 上时前移（链首为 user/assistant，前移必可避开；极端畸形链兜底切 1 条，
        // 后段严格变小保证递归收敛）
        int mid = chain.size() / 2;
        while (mid > 0 && chain.get(mid).role == Message.Role.TOOL) mid--;
        if (mid == 0) mid = 1;
        splitChainByMessages(chain.subList(0, mid), out);
        splitChainByMessages(chain.subList(mid, chain.size()), out);
    }

    private static Message summaryMsg(String text) {
        Message m = Message.user("【历史对话摘要】\n" + text);
        m.summary = true;
        return m;
    }
}
