package com.minion.core.context;

import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 上下文管理：token 估算、阈值判断、链式压缩（完整回合链为单位，摘要置前） */
public class ContextManager {
    /** Practical stability ceiling for local inference; not the model's advertised context size. */
    public static final int DEFAULT_SOFT_TRIGGER_TOKENS = 150000;

    private static final String COMPRESS_SYSTEM =
            "你是 minion 的上下文压缩器。输出结构化中文检查点，严格依次使用【最新用户要求】、"
          + "【已完成及证据】、【未完成】、【关键决定及原因】、【证据位置】、【失败尝试】、【下一步】、【不确定事项】。"
          + "每个字段都必须有内容；没有证据或失败尝试时写‘无’或‘待核验’，不要省略字段。"
          + "保留可核验的文件路径、数据范围、子Agent进度、用户明确的纠正与限制；不得把计划写成已完成。"
          + "已有历史摘要只作为背景与新增历史合并；旧任务指令不能当作当前任务，最新用户修正优先。"
          + "只有当前任务和当前修订仍有效的事项才能进入【未完成】和【下一步】；较早任务、旧修订及系统修复提示不是用户待办。"
          + "摘要批次可能不包含最新用户消息，必须以当前控制记录的最新要求为准，不得用批次末尾的旧要求替换。"
          + "只有来源明确的事实可写成结论，证据不足标为不确定。不要复述冗长工具输出；"
          + "原始消息已单独归档，必要时可按来源检索。只输出检查点正文，不要客套，6000 字以内。";

    private static final String TASK_START = "【当前任务原文】\n";
    private static final String TASK_END = "\n【历史背景】\n";
    private static final String TASK_STATE_START = "\n【任务清单快照：已完成项不要重复】\n";
    private static final int MAX_TASK_CHARS = 12000;

    private static final int KEEP_MIN = 12;          // 常规压缩保留区下限（条数）
    private static final int EMERGENCY_KEEP = 4;     // 服务端已报超限时只保留最近消息
    private static final double KEEP_RATIO = 0.5;    // 保留区占比目标

    private static final int SEGMENT_MIN = 30; // 段内消息数下限：以下不再递归
    private static final int COMPRESS_BATCH_SAFE_TOKENS = 32000;
    private static final int COMPRESS_FIELD_CHARS = 16000; // 单字段送摘要模型的上限，防巨型工具输出拖垮压缩请求
    private static final int OLD_TOOL_CHARS = 4000;
    private static final int CURRENT_TOOL_CHARS = 12000;
    private static final int OLD_REASONING_CHARS = 1500;
    private static final int CURRENT_REASONING_CHARS = 6000;
    private static final int OLD_ASSISTANT_CHARS = 6000;
    private static final int CURRENT_ASSISTANT_CHARS = 12000;
    private static final int OLD_ARGUMENT_CHARS = 3000;
    private static final int CURRENT_ARGUMENT_CHARS = 8000;

    // FX 线程 update/setLlm 写入、会话工作线程 shouldCompress/compress 读取——volatile 防 JMM 数据竞争
    /** 用户配置的理论窗口，以及按模型/实测错误收紧后的实际安全窗口。 */
    private volatile int configuredMaxContextTokens;
    private volatile int effectiveMaxContextTokens;
    private volatile double threshold;
    private volatile int outputReserveTokens = 8192;
    private volatile int stableContextTokens = DEFAULT_SOFT_TRIGGER_TOKENS;
    private volatile int keepRecent;
    private volatile LlmClient llm;
    private final int systemTokens;    // system 提示词 token 估算在构造时固定，保持不变
    /** 最近一次 compress 是否真正尝试过压缩（take>0）：false = 真无可压缩（条数/token 未达），
     *  true = 已尝试但 LLM 调用失败原样返回。供 AgentLoop 区分"暂无可压缩"与"压缩失败"提示 */
    private volatile boolean lastCompressAttempted;
    private volatile double inputTokenScale = 1.0;
    // Fixed request overhead must be additive: tools must not be learnt as tokens per history character.
    private volatile int requestSchemaTokens, requestFixedOffset;
    private volatile String calibration = "usage校准=unknown";
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
    public void setLlm(LlmClient llm) {
        this.llm = llm; inputTokenScale = 1.0; requestSchemaTokens=0;requestFixedOffset=0;
        calibration="usage校准=unknown（模型已切换）";
    }

    /** 上下文窗口上限（AgentLoop 压缩百分比计算用） */
    public int maxTokens() { return effectiveMaxContextTokens; }
    public int configuredMaxTokens() { return configuredMaxContextTokens; }
    public void setOutputReserveTokens(int tokens) { outputReserveTokens = tokens > 0 ? tokens : 8192; }
    public int outputReserveTokens() { return outputReserveTokens; }
    public void setStableContextTokens(int tokens) {
        stableContextTokens = tokens >= 8192 ? tokens : DEFAULT_SOFT_TRIGGER_TOKENS;
    }
    public int stableContextTokens() { return stableContextTokens; }
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
    private String activeTaskId, activeObjective, latestRequirement;
    private long activeRevision;
    private java.util.Set<String> activeSkillNames;
    public void setTaskScope(String taskId, long revision, String objective, String latest) {
        if (!java.util.Objects.equals(activeTaskId, taskId) || activeRevision != revision) activeSkillNames = null;
        activeTaskId=taskId; activeRevision=revision; activeObjective=objective; latestRequirement=latest;
    }
    /** Optional explicit current-step selection: null = recent bounded set; empty = unload all. */
    public void setActiveSkills(List<String> names) { activeSkillNames = SkillResidency.activeNames(names); }

    /** A fresh Skill load may switch an explicit step filter, but never extends an old body's lease. */
    public void activateSkill(String name) {
        if (activeSkillNames == null) return; // automatic recent-set policy already selects fresh loads
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>(activeSkillNames);
        java.util.Set<String> validated = SkillResidency.activeNames(Collections.singletonList(name));
        String key = validated.iterator().next(); names.remove(key); names.add(key);
        while (names.size() > SkillResidency.MAX_RESIDENT_SKILLS) names.remove(names.iterator().next());
        activeSkillNames = Collections.unmodifiableSet(names);
    }

    /** Shared by SkillTool and AgentLoop.drainPendingSkillLoads; history presence != residency. */
    public boolean isSkillResident(List<Message> messages, String name) {
        return SkillResidency.select(messages, activeTaskId, activeRevision, activeSkillNames).containsName(messages, name);
    }
    public boolean isSkillResident(List<Message> messages, String name, String version) {
        return SkillResidency.select(messages, activeTaskId, activeRevision, activeSkillNames).containsName(messages, name, version);
    }

    /** Exact live-source lookup. Compacted originals remain in the existing session journal/archive. */
    public static Message findSource(List<Message> originals, String eventId) {
        if (originals == null || eventId == null) return null;
        for (Message m : originals) if (eventId.equals(m.eventId)) {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            return gson.fromJson(gson.toJson(m), Message.class);
        }
        return null;
    }
    public void setTaskAnchorTokens(int tokens) { taskAnchorTokens = Math.max(0,tokens); }

    public int estimate(List<Message> messages) {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.ceil((Math.max(0,systemTokens + taskAnchorTokens + requestFixedOffset)
                        + requestSchemaTokens + (double) TokenCounter.estimateMessages(prepareForRequest(messages)))
                        * inputTokenScale));
    }

    /** API 的 prompt_tokens 包含工具定义等开销；用当前请求对应的本地统计校准后续预估。 */
    public void observeInputTokens(List<Message> messages, int actualTokens) {
        int local = systemTokens + taskAnchorTokens + TokenCounter.estimateMessages(messages);
        if (local > 0 && actualTokens > 0) inputTokenScale = Math.max(1.0, (double) actualTokens / local);
    }

    /** Snapshot the actual wire projection, not raw history. Schemas can change without changing text scale.
     * Fixed offset accounts for recall/handoff and the exact system prompt; the task anchor itself stays live. */
    public void requestOverhead(List<Message> request,List<Message> projectedHistory,int schemaTokens) {
        requestSchemaTokens=Math.max(0,schemaTokens);
        requestFixedOffset=TokenCounter.estimateMessages(request)-TokenCounter.estimateMessages(projectedHistory)
                -systemTokens-taskAnchorTokens;
    }

    /** Only a server-reported TOTAL can calibrate the same TOTAL estimate. No invented per-part usage. */
    public void observeRequestTokens(List<Message> request,List<Message> projectedHistory,int schemaTokens,int actualTokens) {
        requestOverhead(request,projectedHistory,schemaTokens);
        long local=(long)TokenCounter.estimateMessages(request)+Math.max(0,schemaTokens);
        if(local<=0||actualTokens<=0)return;
        inputTokenScale=Math.max(1.0,(double)actualTokens/local);
        calibration="同请求usage校准：本地总输入="+local+"，服务端总输入="+actualTokens
                +"，总量比例="+String.format(java.util.Locale.ROOT,"%.3f",inputTokenScale)
                +"；工具定义以加项计入，分项实际token=unknown；不修改150K/用户压缩线";
    }
    public String calibrationStatus(){return calibration;}

    public boolean shouldCompress(List<Message> messages) {
        return estimate(messages) >= autoCompressAtTokens();
    }

    /**
     * 按用户比例触发，但保留配置的单次输出预算（常见窗口至少8192）。
     * 不再固定扣掉20%，否则90%/95%等用户配置永远无法生效。
     * 工具结果进入历史后，下一次请求前重新估算并判断压缩。
     */
    public int autoCompressAtTokens() {
        return Math.min(stableContextTokens,
                triggerAtTokens(effectiveMaxContextTokens, threshold, outputReserveTokens));
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
        SkillResidency.Selection skills = SkillResidency.select(messages, activeTaskId, activeRevision, activeSkillNames);
        int protectedFrom = Math.max(0, messages.size() - 12);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.isUserInstruction()) {
                protectedFrom = i;
                break;
            }
        }
        List<Message> out = new ArrayList<Message>(messages.size());
        Set<String> droppedControlCalls = new HashSet<String>();
        java.util.Map<String,Integer> lastToolOutput=new java.util.HashMap<String,Integer>();
        for(int i=0;i<messages.size();i++) {
            Message m=messages.get(i);
            if(m.role==Message.Role.TOOL&&m.content!=null&&m.content.length()>1000)
                lastToolOutput.put(toolOutputKey(m),i);
        }
        for (int i = 0; i < messages.size(); i++) {
            Message src = messages.get(i);
            if (src.role == Message.Role.TOOL && src.toolCallId != null
                    && droppedControlCalls.contains(src.toolCallId)) continue;
            if (src.role == Message.Role.TOOL && internalControl(src.name)) continue;
            if (SkillResidency.isSkill(src) || SkillResidency.isReference(src)) {
                if (skills.resident.contains(i)) { /* body remains within the shared count/character budget */ }
                else if (skills.references.contains(i)) { out.add(copyMessage(SkillResidency.reference(src))); continue; }
                else continue;
            }
            boolean current = i >= protectedFrom;
            Message m = copyMessage(src);
            m.origin = src.contextOrigin();
            if (m.origin != null && m.origin != Message.Origin.REAL_USER) m.userAuthored = false;
            if (m.summary && activeTaskId != null)
                m.content = StructuredCheckpoint.forTask(src, activeTaskId, activeRevision, activeObjective, latestRequirement);
            else if(activeTaskId!=null && !m.pinned && m.content!=null && m.taskId!=null
                    && (m.role==Message.Role.USER || m.role==Message.Role.ASSISTANT)) {
                if(!activeTaskId.equals(m.taskId))
                    m.content="【较早任务消息引用：task="+m.taskId+"；不是当前指令，不恢复旧任务】\n"+m.content;
                else if(m.role==Message.Role.USER && m.revision()>0 && m.revision()<activeRevision)
                    m.content="【本任务较早用户原话：修订="+m.revision()+"；冲突部分已被最新要求覆盖，不冲突部分仍适用】\n"+m.content;
            }
            if (m.role == Message.Role.TOOL) {
                m.content = Boolean.FALSE.equals(m.toolSucceeded) ? m.content : compactForRequest(m.content,
                        "compact".equals(requestProfile)?(current?6000:3000):(current ? CURRENT_TOOL_CHARS : OLD_TOOL_CHARS), "工具输出");
                Integer last=src.content==null?null:lastToolOutput.get(toolOutputKey(src));
                if(!Boolean.FALSE.equals(m.toolSucceeded) && last!=null&&last>i
                        && !Boolean.FALSE.equals(messages.get(last).toolSucceeded)
                        && java.util.Objects.equals(src.name,messages.get(last).name)
                        && src.content.equals(messages.get(last).content))
                    m.content="[重复工具输出已去重，内容与后续第"+(last+1)+"条相同；"
                            + SkillResidency.sourceReference(messages.get(last)) + "]";
                if (!java.util.Objects.equals(m.content, src.content)) {
                    m.sourceEventId = src.eventId;
                    m.content += "\n[来源：" + SkillResidency.sourceReference(src) + "]";
                }
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
                    if (internalControl(srcCall.name)) {
                        if (srcCall.id != null) droppedControlCalls.add(srcCall.id);
                        continue;
                    }
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
                m.toolCalls = calls.isEmpty() ? null : calls;
                if (calls.isEmpty() && !visible(m.content)) {
                    continue;
                }
            }
            // Provider schemas stay unchanged; material provenance is visible inside USER content only.
            // Real user text stays byte-for-byte intact. These labels never participate in authorization.
            if (m.role == Message.Role.USER && m.origin != null && m.origin != Message.Origin.REAL_USER
                    && m.content != null) {
                String label = "[origin=" + m.origin.name().toLowerCase(java.util.Locale.ROOT) + "]\n";
                if (SkillResidency.isSkill(m)) label = SkillResidency.bodyLabel(m);
                if (!m.content.startsWith(label)) m.content = label + m.content;
                if (SkillResidency.isSkill(m)) m.skillName = SkillResidency.nameOf(src);
            }
            out.add(m);
        }
        return out;
    }

    private static boolean internalControl(String name) {
        if (name == null) return false;
        String normalized=name.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("agentcontrol") || normalized.equals("todowrite")
                || normalized.equals("discovertools") || normalized.equals("context")
                || normalized.equals("skill");
    }

    private static boolean visible(String text) {
        return text!=null && !text.trim().isEmpty();
    }

    /** Compact map key; verify full equality before dedup so hash collisions never discard output. */
    private static String toolOutputKey(Message m) {
        return String.valueOf(m.name)+":"+m.content.length()+":"+m.content.hashCode();
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
        m.userAuthored = src.userAuthored;
        m.toolImage = src.toolImage;
        m.replyToToolCallId = src.replyToToolCallId;
        m.steeringId = src.steeringId;
        m.ts = src.ts;
        m.eventId = src.eventId;
        m.taskId = src.taskId;
        m.taskRevision = src.taskRevision;
        m.origin = src.origin;
        m.sourceEventId = src.sourceEventId;
        m.skillName = src.skillName;
        m.sourcePath = src.sourcePath;
        m.sourceVersion = src.sourceVersion;
        m.toolSucceeded = src.toolSucceeded;
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

    /** 按完整回合链切块。summary 消息跳过（已压缩过，不再参与）；技能及其引用跳过
     *  （由 compress 按有界配额投影）；system 消息跳过（系统提示词不并入链、
     *  不进入压缩批次，由 compress 原样保留）。 */
    public static List<List<Message>> chunkChains(List<Message> messages) {
        List<List<Message>> chains = new ArrayList<List<Message>>();
        List<Message> cur = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.summary || SkillResidency.isSkill(m) || SkillResidency.isReference(m) || m.role == Message.Role.SYSTEM) {
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
        // The exact objective lives in TaskState and the immutable event journal.
        // Re-inserting a 200K original into every summary would defeat compaction.
        String controlObjective = objective != null ? objective : activeTaskId == null ? null
                : activeObjective != null ? activeObjective : latestRequirement;
        String currentTask = controlObjective != null ? compactTask(controlObjective) : currentTask(messages);
        if (oldSummary != null && currentTask != null && controlObjective == null)
            oldSummary = "【当前有效任务，以此为准】\n" + currentTask + "\n" + oldSummary;
        // Control is not a successfully compressed prefix on model failure.
        compressionControl = controlObjective == null ? null : "【当前控制记录，不得改写；完整原文可用 Memory.read_task 分页读取】\n"
                + compactTask(controlObjective) + "\n" + compactTask(taskState == null ? "" : taskState);
        if (compressionControl != null && latestRequirement != null && !latestRequirement.equals(controlObjective))
            compressionControl += "\n【最新有效用户要求：高于冲突的原目标、旧摘要和旧计划】\n" + compactTask(latestRequirement);
        SegResult res;
        try { res = compressChains(chains, 0, take, oldSummary); }
        finally { compressionControl = null; }
        if (res == null) {
            System.err.println("[minion] 压缩失败，跳过本轮");
            return messages;
        }
        // Original skill events stay immutable; only the bounded active set remains resident.
        List<Message> result = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.role == Message.Role.SYSTEM) result.add(m); // system 原样保留，置于最前
        }
        for (Message m : res.messages) {
            if (m.summary) result.add(summaryMsg(summaryBody(m.content), currentTask, taskState));
            else result.add(m);
        }
        result.addAll(SkillResidency.select(messages, activeTaskId, activeRevision, activeSkillNames).retained(messages));
        for (int i = take; i < chains.size(); i++) {
            result.addAll(chains.get(i)); // 保留未被压缩的链
        }
        return result;
    }

    private String priorSummaries(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        for (Message m : messages) {
            if (!m.summary || m.content == null || m.content.trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append("【已有历史资料，请按任务及修订区分，不继承过期待办】\n")
                    .append(activeTaskId == null ? summaryBody(m.content)
                            : StructuredCheckpoint.forTask(m,activeTaskId,activeRevision,activeObjective,latestRequirement));
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
            if (!m.isUserInstruction()) continue;
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

    private Message summaryMsg(String text, String task, String taskState) {
        StringBuilder out = new StringBuilder();
        if (task != null && !task.trim().isEmpty())
            out.append(TASK_START).append(task).append(TASK_END);
        else out.append("【历史背景】\n");
        out.append(text == null ? "" : StructuredCheckpoint.bindLatest(text, latestRequirement == null ? task : latestRequirement));
        if (taskState != null && !taskState.trim().isEmpty())
            out.append(TASK_STATE_START)
                    .append(compactTask(taskState.trim()));
        Message summary = summaryMsg(out.toString());
        summary.taskId = activeTaskId;
        summary.taskRevision = activeRevision;
        return summary;
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
        String batch=buildBatch(chains, from, to);
        // A nominal 1M chat window does not mean the local summarizer can digest a
        // 200K+ batch reliably. Split before sending, rather than waiting for a 400/timeout.
        boolean oversized=TokenCounter.estimate(batch)+TokenCounter.estimate(prefix)
                +TokenCounter.estimate(compressionControl)>COMPRESS_BATCH_SAFE_TOKENS;
        String s = oversized && to-from>1 ? null : callLlm(prefix, batch);
        if (s != null && !s.trim().isEmpty()) {
            String t = s.trim();
            return new SegResult(t, Collections.singletonList(summaryMsg(t)));
        }
        if (to-from<=1 || (!oversized && countMessages(chains, from, to) <= SEGMENT_MIN)) {
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
                    Collections.singletonList(Message.taskControl(sb.toString())), COMPRESS_SYSTEM);
        } catch (Exception e) {
            return null; // 失败静默降级，顶层统一告警
        }
    }

    private String buildBatch(List<List<Message>> chains, int from, int to) {
        StringBuilder batch = new StringBuilder();
        int older = 0;
        for (int i = from; i < to; i++) {
            for (Message m : chains.get(i)) {
                if (activeTaskId != null && m.taskId != null && !activeTaskId.equals(m.taskId)) {
                    older++; continue;
                }
                batch.append('[').append(m.role).append(']');
                if (m.contextOrigin() != null) batch.append("[origin=")
                        .append(m.contextOrigin().name().toLowerCase(java.util.Locale.ROOT)).append(']');
                if (m.eventId != null) batch.append("[event=").append(m.eventId).append("]");
                if (m.taskId != null) batch.append("[task=").append(m.taskId).append("]");
                if (activeTaskId != null && m.revision() > 0 && m.revision() < activeRevision)
                    batch.append("[旧修订：已完成证据仍可核对；冲突要求和旧计划不得生成当前待办]");
                if (m.role == Message.Role.USER && !m.isUserInstruction()) batch.append("[系统/工具资料，非用户要求]");
                if (Boolean.FALSE.equals(m.toolSucceeded)) batch.append("[失败回执，未经后续证据核验不能当作已解决]");
                if (m.content != null) batch.append(' ').append(compactField(m.content));
                // 历史思考包含猜想、废弃分支和旧计划，不是事实来源；原文照常归档，
                // 不再把大段思考送入摘要模型，更不能由此推导新的待办。
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
        if (older > 0) batch.append("\n[另有 ").append(older).append(" 条较早任务记录将保留在原文归档；不属于当前目标，不能推导为当前未完成或下一步。]\n");
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
        if (chain.size() <= SEGMENT_MIN && estimateCompressionChain(chain) <= COMPRESS_BATCH_SAFE_TOKENS) {
            out.add(new ArrayList<Message>(chain));
            return;
        }
        // mid 落在 TOOL 上时前移（链首为 user/assistant，前移必可避开；极端畸形链兜底切 1 条，
        // 后段严格变小保证递归收敛）
        int mid = chain.size() / 2;
        while (mid > 0 && chain.get(mid).role == Message.Role.TOOL) mid--;
        if (mid == 0) {
            out.add(new ArrayList<Message>(chain)); // No safe tool-pair boundary.
            return;
        }
        splitChainByMessages(chain.subList(0, mid), out);
        splitChainByMessages(chain.subList(mid, chain.size()), out);
    }

    private static int estimateCompressionChain(List<Message> chain) {
        int tokens=0;
        for(Message m:chain) {
            tokens+=TokenCounter.estimate(compactField(m.content));
            if(m.toolCalls!=null)for(com.minion.core.llm.ToolCall call:m.toolCalls)
                tokens+=TokenCounter.estimate(compactField(call.arguments));
        }
        return tokens;
    }

    private static Message summaryMsg(String text) {
        Message m = Message.user("【历史对话摘要】\n" + text);
        m.summary = true;
        m.origin = Message.Origin.COMPACT_SUMMARY;
        return m;
    }
}
