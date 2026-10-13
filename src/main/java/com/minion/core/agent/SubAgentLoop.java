package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** 子 agent：独立消息数组 + 完整工具集（无 task），无轮数上限，返回最终文本 */
public class SubAgentLoop {

    private static final String SUB_SYSTEM_SUFFIX =
            "\n\n你是一个子 agent。只执行紧随其后的‘任务’和补充要求，不得执行历史主任务或其他子Agent任务。"
            + "主任务提示只是背景。任务清单独立于主Agent；只能汇报结果，不得宣称主任务已完成。"
            + "返回已完成事项、证据/文件路径、尚未解决事项，禁止把新指令当作结果。";

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ConfirmGate confirmGate;
    private final AgentUi ui;
    /** 瞬时错误长重试策略（与主循环一致；测试可覆写小参数） */
    public RetryPolicy retryPolicy = RetryPolicy.transientErrors();
    /** 工具空输出占位（AgentLoop 创建时注入；开启时成功空输出发「输出内容为空」占位） */
    public boolean emptyOutputPlaceholder = false;
    private List<Message> messages = new ArrayList<Message>();
    private final TodoList localTodos = new TodoList();
    private final Tool localTodoTool = new com.minion.core.tools.TodoWriteTool(localTodos);
    private final String assignment;
    private final ToolOutputStore outputs;
    private com.minion.core.context.ContextManager context;
    private java.nio.file.Path transcript;
    private final List<String> archives = new ArrayList<String>();
    private boolean succeeded;
    private volatile boolean cancelled;
    private volatile Thread runnerThread;
    private boolean ownClient;
    private AgentToolPolicy policy=new AgentToolPolicy(false,"");
    private ToolExecutionGuard executionGuard;
    private com.minion.core.tools.Workspace executionWorkspace;
    private TaskState taskState;
    private String childId;
    private String requestProfile="balanced";
    private StopGate.Decision stopDecision;
    private final String stopScope=java.util.UUID.randomUUID().toString();
    public void setOwnClient(boolean owns) { ownClient=owns; }
    public void setRequestProfile(String profile) { requestProfile=profile; }
    public void setExecutionScope(com.minion.core.tools.Workspace workspace,TaskState state,String id,AgentToolPolicy policy) {
        executionWorkspace=workspace; executionGuard=new ToolExecutionGuard(workspace,true); taskState=state; childId=id; this.policy=policy;
        messages.get(0).content+="\n本次子任务工具权限: "+(policy.readOnly()?"只读探索，禁止写入/Shell/Python/未知插件":"worker；仍需通过工具授权")+"。白名单以工具列表为准。";
    }
    public void interrupt() {
        cancelled=true;
        llm.cancelStopReview(runnerThread);
        if(ownClient) llm.cancel();
        Thread t=runnerThread; if(t!=null) t.interrupt();
    }
    public void archiveExistingRecord() throws Exception {
        if(transcript!=null&&java.nio.file.Files.exists(transcript)) {
            java.nio.file.Path backup=transcript.resolveSibling(transcript.getFileName()+".retry-"+java.util.UUID.randomUUID()+".json");
            java.nio.file.Files.copy(transcript,backup); archives.add(backup.toString());
        }
    }
    public void restoreRecord(java.nio.file.Path record) throws Exception {
        if(!java.nio.file.Files.isRegularFile(record)) throw new IllegalArgumentException("子Agent记录不存在，使用重试从分工重新开始");
        if(java.nio.file.Files.size(record)>32*1024*1024) throw new IllegalArgumentException("子Agent记录过大，请先人工核对");
        com.google.gson.Gson g=new com.google.gson.Gson();
        JsonObject data=JsonParser.parseString(new String(java.nio.file.Files.readAllBytes(record),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if(!assignment.equals(data.get("assignment").getAsString())) throw new IllegalArgumentException("子Agent记录分工不匹配");
        Message[] old=g.fromJson(data.get("messages"),Message[].class);
        if(old==null||old.length==0) throw new IllegalArgumentException("子Agent记录无可恢复消息");
        // Retain only paired tool calls. Do not replay an unfinished action on restart.
        List<Message> repaired=new ArrayList<Message>();
        for(int i=0;i<old.length;i++) {
            Message m=old[i]; if(m==null||m.role==null) continue;
            if(m.role==Message.Role.SYSTEM) continue;
            if(m.toolCalls!=null&&!m.toolCalls.isEmpty()) {
                List<Message> results=new ArrayList<Message>(); java.util.Set<String> ids=new java.util.HashSet<String>();
                int j=i+1; while(j<old.length&&old[j]!=null&&old[j].role==Message.Role.TOOL) { results.add(old[j]); ids.add(old[j].toolCallId); j++; }
                boolean valid=ids.size()==m.toolCalls.size();
                for(ToolCall call:m.toolCalls) {
                    try { JsonParser.parseString(call.arguments).getAsJsonObject(); } catch(Exception e) { valid=false; }
                    if(!ids.contains(call.id)) valid=false;
                }
                if(valid) { repaired.add(m); repaired.addAll(results); }
                else if(m.content!=null) repaired.add(Message.assistant(m.content+"\n[未配对工具调用已移除；先检查文件状态，勿盲目重跑]"));
                i=j-1;
            } else if(m.role!=Message.Role.TOOL) repaired.add(m);
        }
        Message system=messages.get(0); messages=new ArrayList<Message>(); messages.add(system); messages.addAll(repaired);
        if(data.has("cwd")&&!data.get("cwd").isJsonNull()) executionWorkspace.restore(data.get("cwd").getAsString());
        messages.add(Message.user("恢复当前子任务: "+assignment+"。当前cwd: "+executionWorkspace.cwd()+"。先核对文件和已完成步骤，只完成尚未解决事项，不重新执行整项任务。"));
        TodoList saved=g.fromJson(data.get("todos"),TodoList.class); if(saved!=null) localTodos.replace(saved.items);
        String[] prior=g.fromJson(data.get("archives"),String[].class); if(prior!=null) java.util.Collections.addAll(archives,prior);
        archiveExistingRecord();
    }
    public boolean succeeded() { return succeeded; }
    public String recordPath() { return transcript == null ? "(未配置)" : transcript.toString(); }
    public void setRecordPath(java.nio.file.Path path) { transcript = path.toAbsolutePath().normalize(); }
    public void setContextWindow(int max, double threshold) {
        context = new com.minion.core.context.ContextManager(max, threshold, 12, llm, 0);
    }
    public void setContextWindow(int max, double threshold, int outputReserveTokens) {
        setContextWindow(max, threshold);
        context.setOutputReserveTokens(outputReserveTokens);
    }

    public SubAgentLoop(String systemPrompt, String taskDescription, String workDir,
                        LlmClient llm, ToolRegistry registry, ConfirmGate confirmGate, AgentUi ui) {
        this.llm = llm;
        this.registry = registry;
        this.confirmGate = confirmGate;
        this.ui = ui;
        this.assignment = taskDescription;
        this.executionWorkspace=new com.minion.core.tools.Workspace(workDir);
        this.executionGuard=new ToolExecutionGuard(executionWorkspace,true);
        this.outputs = new ToolOutputStore(workDir, "subagent-" + java.util.UUID.randomUUID());
        setContextWindow(60000, 0.8);
        messages.add(Message.system(systemPrompt + SUB_SYSTEM_SUFFIX));
        messages.add(Message.user("任务: " + taskDescription));
    }

    public String run() {
        runnerThread=Thread.currentThread();
        ui.onSubAgentStart(messages.get(1).content);
        succeeded = false;
        saveTranscript("running");
        int retries = 0;
        int contextRecoveries = 0;
        int malformedRecoveries = 0;
        int lengthContinuationAttempts = 0;
        String lengthContinuationHint = null;
        boolean nextRequestWithoutThinking = false;
        boolean nextRequestForAction = false;
        int repetitionRecoveries = 0;
        List<StopGate.Receipt> stopReceipts=new ArrayList<StopGate.Receipt>();
        StopGate.Progress stopProgress=new StopGate.Progress();
        StringBuilder completedContent = new StringBuilder();
        final boolean[] inRetry = new boolean[1]; // 瞬时错误重试循环进行中（首个流式增量到达即复位指示器）；方法级：外层 InterruptedException 需访问
        try {
            while (true) {
                // 中断路径：主循环 interrupt() 取消 in-flight 工具 future → 本线程中断 → 立即中止
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    ui.onWarning("子 agent 已中断");
                    return "子 agent 已中断";
                }
                context.setRequestProfile(requestProfile);
                if (context.shouldCompress(messages)) compactChild(false);
                final List<ToolCall>[] toolCalls = new List[1];
                final String[] finish = new String[1];
                final Usage[] usage = new Usage[1];
                final LlmException[] streamError = new LlmException[1];
                final StringBuilder content = new StringBuilder();
                final StringBuilder thinking = new StringBuilder();
                final com.minion.core.llm.StreamHandler handler = new com.minion.core.llm.RepetitionGuard(new com.minion.core.llm.StreamHandler() {
                    @Override
                    public void onThinking(String delta) {
                        resetRetryOnFirstDelta();
                        thinking.append(delta);
                        ui.onThinking(delta);
                    }
                    @Override
                    public void onContent(String delta) {
                        resetRetryOnFirstDelta();
                        content.append(delta);
                        ui.onSubAgentDelta(delta);
                    }
                    @Override
                    public void onFinish(String finishReason, Usage u, List<ToolCall> tcs) {
                        resetRetryOnFirstDelta(); // 零增量成功（如纯 tool_calls 回复）兜底复位
                        finish[0] = finishReason;
                        usage[0] = u;
                        toolCalls[0] = tcs;
                        ui.onResponseEnd();
                    }
                    /** 重试成功后的首个流式回调：立即复位指示器（"重试中"文案消失） */
                    void resetRetryOnFirstDelta() {
                        if (inRetry[0]) {
                            inRetry[0] = false;
                            ui.onRetryProgress(RetryProgress.none());
                        }
                    }
                    @Override
                    public void onError(LlmException e) { finish[0] = "error"; streamError[0] = e; }
                });
                List<Message> requestMessages = messages;
                if (lengthContinuationHint != null) {
                    requestMessages = new ArrayList<Message>(messages);
                    requestMessages.add(Message.user(lengthContinuationHint));
                    lengthContinuationHint = null;
                }
                final boolean requestWithoutThinking = nextRequestWithoutThinking;
                nextRequestWithoutThinking = false;
                final boolean requestForAction = nextRequestForAction;
                nextRequestForAction = false;
                try {
                    streamChat(requestMessages, handler, requestWithoutThinking, requestForAction);
                } catch (LlmException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        // 已被主循环中断（cancel 引发的 Canceled 错误）：不重试
                        ui.onWarning("子 agent 已中断");
                        return "子 agent 已中断";
                    }
                    String errorText=(e.getMessage()+" "+e.body).toLowerCase(java.util.Locale.ROOT);
                    if ((e.httpCode==400 || e.httpCode==413) && contextRecoveries<2
                            && (errorText.contains("context") || errorText.contains("too many tokens") || errorText.contains("上下文"))) {
                        contextRecoveries++;
                        context.learnFromContextFailure(context.estimate(messages));
                        if(compactChild(true)) continue;
                    }
                    if (isTransientError(e) && noOutputYet(content, thinking)) {
                        // 瞬时错误长重试与主循环一致：固定 5s/次，墙钟总时长 20 分钟；
                        // 覆盖 429/500/502 + 网络超时 + 可恢复网络错误；零增量闸门防重复输出
                        int attempts = 0;
                        long retryStart = System.currentTimeMillis(); // 墙钟基准：含每次请求自身耗时
                        long elapsed = 0;                             // 耗尽时的真实耗时（返回值文案用）
                        boolean exhausted = false; // 超时总结标志：break 后统一复位指示器再返回
                        String failure = null;     // 重试中遇非瞬时错误的文案：break 后统一复位指示器再返回
                        LlmException last = e;
                        inRetry[0] = true;
                        while (true) {
                            attempts++;
                            ui.onRetryProgress(RetryProgress.from(attempts, last)); // 尝试前立即更新指示器
                            long delay = retryPolicy.delayMs(attempts);
                            if (!sleepWithInterruptCheck(delay)) break; // 中断
                            elapsed = System.currentTimeMillis() - retryStart;
                            if (retryPolicy.isExhausted(elapsed)) {
                                ui.onError("子 agent " + RetryProgress.tag(last) + " 重试了 " + attempts
                                        + " 次，持续 " + (elapsed / 60000) + " 分钟仍失败，已停止重试");
                                exhausted = true;
                                break;
                            }
                            try {
                                streamChat(requestMessages, handler, requestWithoutThinking, requestForAction);
                                // 成功后静默恢复（不打扰正文）：首个流式增量/onFinish 已复位指示器，
                                // 若流中断（onError 回调已提示）则落下方正常路径处理
                                break;
                            } catch (LlmException re) {
                                if (Thread.currentThread().isInterrupted()) break;
                                if (!isTransientError(re) || !noOutputYet(content, thinking)) {
                                    // 永久性/非瞬时错误（DNS 配错、其他 5xx、已吐字断流）：退出重试，
                                    // 统一复位指示器，不再继续退避
                                    ui.onError("子 agent 请求失败: " + re.getMessage());
                                    failure = re.getMessage();
                                    break;
                                }
                                last = re; // 仍可重试：指示器标签/错误体随最近一次失败更新
                            }
                        }
                        if (inRetry[0]) { inRetry[0] = false; ui.onRetryProgress(RetryProgress.none()); } // 退出重试态统一复位（幂等）
                        if (Thread.currentThread().isInterrupted()) {
                            ui.onWarning("子 agent 已中断");
                            return "子 agent 已中断";
                        }
                        if (exhausted) {
                            return "子 agent 失败: " + RetryProgress.tag(last) + " 持续 " + (elapsed / 60000) + " 分钟"; // 已 onError
                        }
                        if (failure != null) {
                            return "子 agent 失败: " + failure; // 已 onError
                        }
                        if (finish[0] == null && usage[0] == null) {
                            // 防御兜底：正常退出必有 finish/usage 回调（成功 break 后）或
                            // exhausted/failure 标志，理论不可达；保留旧文案以防回归误判
                            return "子 agent 失败: " + RetryProgress.tag(last) + " 重试超时"; // 已 onError
                        }
                        // 重试成功：落入下方正常处理
                    } else if (e.retryable && retries < 1 && noOutputYet(content, thinking)) {
                        retries++;
                        ui.onWarning("子 agent 请求失败（" + e.getMessage() + "），自动重试 1 次");
                        // 退避与主循环一致：429 限流 2s，其余（网络/超时）0.5s
                        Thread.sleep(e.type == LlmException.Type.RATE_LIMIT ? 2000 : 500);
                        continue; // 消息未变，直接重发本轮
                    } else {
                        ui.onError("子 agent 请求失败: " + e.getMessage());
                        return "子 agent 失败: " + e.getMessage();
                    }
                }
                if (streamError[0] != null || "error".equals(finish[0])) {
                    ui.onError("子 agent 流响应失败: " + (streamError[0] == null ? "未知错误" : streamError[0].getMessage()));
                    return "子 agent 失败，未完成: " + (streamError[0] == null ? "流响应错误" : streamError[0].getMessage());
                }
                if(taskState!=null) {
                    Usage measured=usage[0]!=null?usage[0]:Usage.estimate(messages,content.toString());
                    taskState.childStats(childId,context.estimate(messages),context.maxTokens(),measured);
                    if(!measured.estimated) context.observeInputTokens(messages,measured.inputTokens);
                }
                if (com.minion.core.llm.RepetitionGuard.FINISH.equals(finish[0])) {
                    if(cancelled || Thread.currentThread().isInterrupted()) return "子 agent 已中断";
                    com.minion.core.diagnostics.DiagnosticLog.info("stream-repetition", "scope=child attempt=" + repetitionRecoveries);
                    if(repetitionRecoveries >= com.minion.core.llm.RepetitionGuard.MAX_RECOVERIES) {
                        ui.onError("子Agent流式循环恢复3次仍失败，任务未完成");
                        return "子Agent重复循环，未完成；请检查记录或切换模型";
                    }
                    repetitionRecoveries++;
                    lengthContinuationHint=com.minion.core.llm.RepetitionGuard.HINT;
                    nextRequestForAction=true;
                    ui.onWarning("子Agent单次响应重复，正在工具动作恢复（第 " + repetitionRecoveries + " 次）");
                    saveTranscript("running");
                    continue;
                }
                if ("length".equalsIgnoreCase(finish[0])) {
                    boolean partialTool = toolCalls[0] != null && !toolCalls[0].isEmpty();
                    toolCalls[0] = null;
                    if (hasVisibleText(content)) {
                        Message partial = Message.assistant(content.toString());
                        partial.reasoningContent = thinking.length() == 0 ? null : thinking.toString();
                        messages.add(partial);
                        completedContent.append(content);
                    }
                    if (lengthContinuationAttempts >= 12) {
                        ui.onError("子 agent 连续 12 次达到单次输出上限，已停止自动续接");
                        String result = completedContent.toString();
                        ui.onSubAgentDone(result);
                        return result;
                    }
                    lengthContinuationAttempts++;
                    if (lengthContinuationAttempts == 2 && context.estimate(messages) >= 4096)
                        compactChild(true);
                    lengthContinuationHint = partialTool
                            ? "[系统续接] 上一次输出在工具调用中被截断，工具没有执行。请从头生成完整合法的工具调用并继续任务。"
                            : "[系统续接] 上一次输出达到长度上限。请紧接已有内容继续任务，不要重复或提前总结。";
                    nextRequestWithoutThinking = true;
                    ui.onWarning("子 agent 输出达到单次上限，正在自动续接（第 "
                            + lengthContinuationAttempts + " 次）");
                    continue;
                }
                if ("stop".equalsIgnoreCase(finish[0])
                        && (toolCalls[0] == null || toolCalls[0].isEmpty()) && !hasVisibleText(content)) {
                    if (lengthContinuationAttempts >= 12) {
                        ui.onError("子 agent 连续 12 次只生成思考或空响应，已停止自动恢复");
                        String result = completedContent.toString();
                        ui.onSubAgentDone(result);
                        return result;
                    }
                    lengthContinuationAttempts++;
                    boolean hadThinking = hasVisibleText(thinking);
                    if (lengthContinuationAttempts == 2 && context.estimate(messages) >= 4096)
                        compactChild(true);
                    lengthContinuationHint = buildNoActionRecoveryHint(
                            lengthContinuationAttempts, hadThinking);
                    nextRequestWithoutThinking = hadThinking;
                    ui.onWarning("子 agent 未产生可执行动作，正在自动继续（第 "
                            + lengthContinuationAttempts + " 次）");
                    continue;
                }
                if (toolCalls[0] == null || toolCalls[0].isEmpty()
                        || !"tool_calls".equals(finish[0])) {
                    if(toolCalls[0]!=null&&!toolCalls[0].isEmpty()) finish[0]="tool_calls";
                    else {
                    String result=completedContent.toString()+content;
                    if(!hasVisibleText(result)) return "子Agent无最终正文，未完成";
                    StopGate.Snapshot snapshot=childStopSnapshot(result,stopReceipts);
                    ui.onWarning("正在核验当前子任务是否完成");
                    stopDecision=StopGate.evaluate(llm,snapshot,measured -> {
                        if(taskState!=null)taskState.childStats(childId,context.estimate(messages),context.maxTokens(),measured);
                    },()->cancelled||Thread.currentThread().isInterrupted());
                    if(cancelled||Thread.currentThread().isInterrupted())return "子Agent已中断，未完成";
                    if(!snapshot.sameFacts(childStopSnapshot(result,stopReceipts))) {
                        lengthContinuationHint="[系统结束检查] 子任务状态已改变，旧审批失效。重新核对分配目标。";continue;
                    }
                    if(taskState!=null)taskState.childStop(childId,stopDecision);
                    if("unknown".equals(stopDecision.status)||"waiting_user".equals(stopDecision.status)) {
                        saveTranscript("paused");return "子Agent交付未确认，需主Agent处理: "+stopDecision.reason;
                    }
                    if("continue".equals(stopDecision.status)) {
                        stopDecision.noProgress=stopProgress.deny();
                        if(taskState!=null)taskState.childStop(childId,stopDecision);
                        if(stopDecision.noProgress>StopGate.NO_PROGRESS_LIMIT) {
                            saveTranscript("paused");return "子Agent无进展预算已用尽，未完成: "+stopDecision.reason;
                        }
                        Message interim=Message.assistant(content.toString());interim.reasoningContent=thinking.length()==0?null:thinking.toString();messages.add(interim);
                        lengthContinuationHint="[系统子任务结束检查] 尚未完成分配目标："+stopDecision.reason+"。下一步："+stopDecision.nextStep
                                +("task".equals(stopDecision.kind)?"。立即调用已有授权工具落实；缺资料/授权返回主Agent说明，不扩大权限，不重跑已完成步骤。"
                                :"。补足分配目标所需回答，不重写已交付段落，不增加未授权的文件操作或新任务。");
                        nextRequestForAction=stopDecision.noProgress>=2&&"task".equals(stopDecision.kind);
                        nextRequestWithoutThinking=stopDecision.noProgress>=2&&"answer".equals(stopDecision.kind);saveTranscript("running");continue;
                    }
                    completedContent.append(content);succeeded=true;
                    Message finalMessage=Message.assistant(content.toString());
                    finalMessage.reasoningContent=thinking.length()==0?null:thinking.toString();messages.add(finalMessage);
                    ui.onSubAgentDone(result);
                    return result;
                    }
                }
                // 成功产生工具动作后重新计算连续恢复次数，长任务不会累计到旧上限。
                boolean invalid=false;
                for(ToolCall call:toolCalls[0]) {
                    try { call.arguments=com.minion.core.tools.ToolArguments.parseObject(call.arguments).toString(); }
                    catch(Exception bad) { invalid=true; }
                }
                if(invalid) {
                    if(++malformedRecoveries>4) { ui.onError("子Agent工具参数连续损坏，记录保留；请缩小分工后重试"); return "子Agent工具参数损坏，未完成"; }
                    lengthContinuationHint="[系统修复] 上次工具参数不是完整JSON，工具未执行，坏参数没有写入历史。请生成单个完整工具调用；长文件分块，每块不超过1000字符。";
                    nextRequestWithoutThinking=true;
                    ui.onWarning("子Agent工具JSON损坏，正在重新生成（第 "+malformedRecoveries+" 次）"); continue;
                }
                malformedRecoveries=0;
                lengthContinuationAttempts = 0;
                // assistant 工具调用消息先入历史——tool 消息必须紧跟含对应 tool_call_id 的
                // assistant tool_calls 消息（DeepSeek/OpenAI 兼容 API 契约，否则 400）；
                // reasoningContent 原样回传同样是硬性要求（思考模式 + 工具调用，缺失下轮 400）
                Message assistantMsg = Message.assistant(
                        hasVisibleText(content) ? content.toString() : null);
                assistantMsg.reasoningContent = thinking.length() == 0 ? null : thinking.toString();
                assistantMsg.toolCalls = toolCalls[0];
                messages.add(assistantMsg);
                for (ToolCall call : toolCalls[0]) {
                    ToolResult result = runOneTool(call);
                    StopGate.Receipt receipt=new StopGate.Receipt("tool:"+call.id+"@"+stopReceipts.size(),call.name,call.arguments,result);
                    stopReceipts.add(receipt);stopProgress.observe(receipt);
                    messages.add(Message.toolResult(call.id, call.name,
                            outputs.forContext(ToolResult.outputForApi(result.output, emptyOutputPlaceholder),call)));
                    ui.onToolResult(call.name, result);
                }
                saveTranscript("running");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志，不吞掉中断
            if (inRetry[0]) { inRetry[0] = false; ui.onRetryProgress(RetryProgress.none()); } // 瞬时错误重试等待中被真实中断（future.cancel(true)）：复位指示器
            ui.onWarning("子 agent 已中断");
            return "子 agent 已中断";
        } catch (Exception e) {
            ui.onError("子 agent 异常: " + e.getMessage());
            return "子 agent 异常: " + e.getMessage();
        } finally {
            saveTranscript(succeeded ? "returned_needs_review" : "failed_or_interrupted");
            runnerThread=null;
        }
    }

    /** 瞬时错误（429 / 500 / 502 / 网络超时 / 可恢复网络错误）：可进长重试（与主循环字面一致）。
     *  网络类靠 retryable 区分永久性故障（DNS 解析失败不放行） */
    private boolean isTransientError(LlmException e) {
        return e.type == LlmException.Type.RATE_LIMIT
                || e.type == LlmException.Type.TIMEOUT
                || (e.type == LlmException.Type.NETWORK && e.retryable)
                || e.httpCode == 500 || e.httpCode == 502;
    }

    private void streamChat(List<Message> request, com.minion.core.llm.StreamHandler handler,
                            boolean withoutThinking, boolean forAction) throws LlmException {
        List<Message> bounded = context.prepareForRequest(request);
        if (forAction) llm.streamChatForAction(bounded, subAgentTools(), handler);
        else if (withoutThinking) llm.streamChatWithoutThinking(bounded, subAgentTools(), handler);
        else llm.streamChat(bounded, subAgentTools(), handler);
    }

    private boolean compactChild(boolean force) {
        List<Message> compacted = force
                ? context.compressForRecovery(messages, "子任务清单:\n" + localTodos.render(), assignment)
                : context.compress(messages, "子任务清单:\n" + localTodos.render(), assignment);
        if(!force && compacted==messages && context.shouldCompress(messages))
            compacted=context.compressForRecovery(messages,"子任务清单:\n" + localTodos.render(),assignment);
        if (compacted != messages) {
            if(transcript!=null) {
                saveTranscript("before_compact");
                try {
                    java.nio.file.Path archived=transcript.resolveSibling(transcript.getFileName()+".compact-"+java.util.UUID.randomUUID()+".json");
                    java.nio.file.Files.copy(transcript,archived);
                    archives.add(archived.toString());
                } catch(Exception e) { ui.onWarning("子Agent压缩前记录归档失败: " + e.getMessage()); }
            }
            messages = compacted;
            ui.onWarning("子Agent独立上下文已压缩，分工与主任务不变");
            saveTranscript("running");
            return true;
        }
        return false;
    }

    private void saveTranscript(String status) {
        if (transcript == null) return;
        try {
            JsonObject record = new JsonObject();
            com.google.gson.Gson gson = new com.google.gson.Gson();
            record.addProperty("assignment", assignment); record.addProperty("status", status);
            record.addProperty("cwd",executionWorkspace.cwd().toString());
            record.add("todos", gson.toJsonTree(localTodos)); record.add("messages", gson.toJsonTree(messages));
            record.add("archives", gson.toJsonTree(archives));
            record.add("stopDecision",gson.toJsonTree(stopDecision));
            java.nio.file.Files.createDirectories(transcript.getParent());
            java.nio.file.Path temp = transcript.resolveSibling(transcript.getFileName() + ".tmp");
            java.nio.file.Files.write(temp, gson.toJson(record).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.move(temp, transcript, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { ui.onWarning("子Agent记录保存失败: " + e.getMessage()); }
    }

    private StopGate.Snapshot childStopSnapshot(String candidate,List<StopGate.Receipt> receipts) {
        JsonObject facts=new JsonObject();facts.addProperty("child",true);facts.addProperty("todos",localTodos.render());
        facts.addProperty("toolsUsed",!receipts.isEmpty());facts.addProperty("structured",!localTodos.items.isEmpty());
        com.google.gson.JsonArray priorReplies=new com.google.gson.JsonArray();
        for(int i=messages.size()-1;i>=0&&priorReplies.size()<4;i--) {
            Message m=messages.get(i);if(m.role!=Message.Role.ASSISTANT||m.content==null||(m.toolCalls!=null&&!m.toolCalls.isEmpty()))continue;
            JsonObject part=new JsonObject();part.addProperty("text",m.content.substring(0,Math.min(2000,m.content.length())));part.addProperty("truncated",m.content.length()>2000);priorReplies.add(part);
        }
        facts.add("earlierDeliveredReplies",priorReplies);
        String blocking="";
        synchronized(localTodos){for(TodoList.TodoItem todo:localTodos.items)if(!todo.done){blocking="子任务待办尚未完成";break;}}
        if(taskState!=null) {
            TaskState.Child child=taskState.child(childId);facts.addProperty("parentTaskId",taskState.id);
            if(child==null||!java.util.Objects.equals(child.taskId,taskState.id))blocking="子任务不属于当前主任务，不能确认旧任务完成";
            else {facts.addProperty("mode",child.mode);facts.addProperty("allowedTools",child.allowedTools);}
            List<FileEvidence> files=new ArrayList<FileEvidence>();synchronized(taskState){for(TaskState.Evidence e:taskState.evidence)if(childId.equals(e.scope)&&e.file!=null)files.add(e.file);}
            facts.add("knownFiles",StopGate.fileFacts(files,p->com.minion.core.tools.PathsGuard.inside(executionWorkspace.workDir(),p)
                    ||com.minion.core.tools.PathsGuard.insideExtra(executionWorkspace,p)));
        }
        return new StopGate.Snapshot(childId==null?stopScope:childId,assignment,java.util.Collections.emptyList(),facts,receipts,candidate,blocking);
    }

    /** 零增量闸门：已吐过正文/思考即不可长重试（与主循环一致，防重复输出） */
    private boolean noOutputYet(StringBuilder content, StringBuilder thinking) {
        return !hasVisibleText(content) && !hasVisibleText(thinking);
    }

    private static boolean hasVisibleText(CharSequence text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u200B' && c != '\uFEFF') return true;
        }
        return false;
    }

    private static String buildNoActionRecoveryHint(int attempt, boolean hadThinking) {
        String prefix = hadThinking ? "/no_think\n[系统完成性检查] "
                : "[系统完成性检查] 上一轮关闭思考后返回了空响应，本轮允许必要的简短思考，但必须产生正文或工具调用。";
        if (attempt >= 3) {
            return prefix + "这是第 " + attempt + " 次纠偏。禁止解释计划、复述任务或只输出思考。"
                    + "下一条响应只允许是完整工具调用，或者直接交付完整最终结果。";
        }
        return prefix + (hadThinking ? "你刚才只有思考，没有正文或工具调用。"
                : "上一次响应没有可见正文或工具调用。")
                + "请立即输出完整动作或实际最终结果，不要再次说明计划。";
    }

    /** 可中断等待：100ms 小片轮询中断标志（与主循环 sleepWithInterruptCheck 一致；
     *  返回 false 表示已中断） */
    private boolean sleepWithInterruptCheck(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (Thread.currentThread().isInterrupted()) return false;
            Thread.sleep(Math.min(100, end - System.currentTimeMillis()));
        }
        return !Thread.currentThread().isInterrupted();
    }

    /** 子 agent 工具集 = registry 全部工具 schema，剔除 task（防无限递归） */
    private List<JsonObject> subAgentTools() {
        List<JsonObject> list = new ArrayList<JsonObject>();
        for (JsonObject s : registry.schemas()) {
            if(!policy.visible(s.getAsJsonObject("function").get("name").getAsString())) continue;
            if ("task".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            if ("AskUserQuestion".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            if ("Skill".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            if ("Context".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            list.add(s);
        }
        return list;
    }

    /** 单工具执行：任何异常均转为错误 ToolResult，单个工具失败不终止整个子 agent */
    private ToolResult runOneTool(ToolCall call) {
        try {
            if ("task".equals(call.name)) {
                // 防御：即使模型违规调用，也不得再派发子 agent（防无限递归）
                return ToolResult.error("子 agent 不可再派发子 agent（task 工具已禁用）");
            }
            if ("AskUserQuestion".equals(call.name)) {
                // 防御：子 agent 不得挂起询问用户（AskUserQuestion 已从 schema 剔除；防模型幻觉调用）
                return ToolResult.error("子 agent 不可询问用户（AskUserQuestion 工具已禁用）");
            }
            if ("Skill".equals(call.name)) {
                // 防御：子 agent 不得加载技能（正文会注入主会话队列——污染编排者上下文）
                return ToolResult.error("子 agent 不可加载技能（Skill 工具已禁用）");
            }
            if ("Context".equals(call.name)) return ToolResult.error("子Agent不可访问或修改主会话上下文策略");
            Tool tool = "TodoWrite".equals(call.name) ? localTodoTool : registry.get(call.name);
            if (tool == null) return ToolResult.error("工具不存在或已停用: " + call.name);
            JsonObject args;
            try {
                args = JsonParser.parseString(call.arguments == null ? "{}" : call.arguments).getAsJsonObject();
            } catch (Exception e) {
                return ToolResult.error("工具参数 JSON 解析失败: " + e.getMessage());
            }
            if(!policy.permits(call.name,args)) return ToolResult.error("子Agent工具权限拒绝: "+call.name+"；只读模式禁止写入、Shell、Python及未列入白名单的工具");
            if (!confirmGate.check(tool, args)) {
                return ToolResult.error("用户拒绝了该操作（" + call.name + "）");
            }
            ui.onToolCall(call.name, args);
            try {
                ToolResult result=executionGuard.execute(tool,args);
                FileEvidence file=null;
                if(result.ok&&args.has("path")&&java.util.Arrays.asList("Read","Write","Edit").contains(call.name)) {
                    try { file=FileEvidence.capture(com.minion.core.tools.PathsGuard.resolve(executionWorkspace.cwd().toString(),args.get("path").getAsString())); }
                    catch(Exception ignored) { }
                }
                if(taskState!=null) taskState.recordEvidence(childId,call.name,result.ok,result.output,file);
                return result;
            } catch (Exception e) {
                return ToolResult.error("工具执行异常: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            // 防御：参数类型非法等 unchecked 异常不得穿透，转错误结果继续循环
            return ToolResult.error("工具执行异常: " + e.getMessage());
        }
    }
}
