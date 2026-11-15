package com.minion.core.agent;

import com.minion.core.llm.ModelCapabilityProfile;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolResult;
import com.google.gson.JsonParser;
import java.util.*;

/** Immutable, per-model dispatch/output policy. Single-call TOOLS evidence is not batch evidence. */
public final class ToolSchedulingPolicy {
    public final int maxParallelReads;
    public final int writeChunkCharacters;
    public final int maxGeneratedCalls;

    private ToolSchedulingPolicy(int parallel, int chunk) {
        this(parallel,chunk,parallel);
    }
    private ToolSchedulingPolicy(int parallel,int chunk,int generated) {
        maxParallelReads = parallel;
        writeChunkCharacters = chunk;
        maxGeneratedCalls=generated;
    }

    /** Small real-work read batch first executes serially; no separate model probe or fake tool. */
    public static ToolSchedulingPolicy trialReadBatch(ModelCapabilityProfile profile,String fingerprint,long now) {
        if(profile==null||!Objects.equals(profile.fingerprint,fingerprint)
                ||profile.state(ModelCapabilityProfile.Capability.TOOLS,now)!=ModelCapabilityProfile.State.SUPPORTED)return conservative();
        return new ToolSchedulingPolicy(1,1000,2);
    }
    /** Grow a proven Write payload gradually, never beyond a reserved output budget. */
    public ToolSchedulingPolicy afterSuccessfulWrite(int observedCharacters,int maxOutputTokens) {
        if(observedCharacters<writeChunkCharacters||maxOutputTokens<4096)return this;
        int next=(int)Math.min(16000L,Math.min((maxOutputTokens-512L)/2L,Math.max(1000L,2L*observedCharacters)));
        return new ToolSchedulingPolicy(maxParallelReads,Math.max(writeChunkCharacters,next),maxGeneratedCalls);
    }

    public static ToolSchedulingPolicy conservative() { return new ToolSchedulingPolicy(1, 1000); }

    /**
     * Bootstrap from an actual completed multi-call turn/probe using the same request mode.
     * Unknown tools, mutation batches, malformed/duplicate IDs, partial finish and failed
     * tool results are not evidence. No request is sent here. Observation must already
     * have gone through the existing authorization/guard, even while dispatch was serial.
     */
    public static ToolSchedulingPolicy observeSuccessfulBatch(ModelCapabilityProfile profile,
            String fingerprint, String finishReason, List<ToolCall> calls, List<ToolResult> results,
            int requestedConcurrency, long now) {
        if (!"tool_calls".equals(finishReason) || calls == null || results == null
                || calls.size() < 2 || calls.size() > 4 || calls.size() != results.size()) return conservative();
        Set<String> ids = new HashSet<String>();
        try {
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                if (call == null || call.id == null || call.id.trim().isEmpty() || !ids.add(call.id)
                        || !"function".equals(call.type) || call.arguments == null
                        || !ToolResourceScope.parallelReadOnly(call.name,
                            JsonParser.parseString(call.arguments).getAsJsonObject())
                        || results.get(i) == null || !results.get(i).ok) return conservative();
            }
        } catch (RuntimeException malformed) { return conservative(); }
        // This observation proves bounded multi-call JSON, not long Write payloads.
        return fromEvidence(profile, fingerprint, true, false, 0,
                Math.min(requestedConcurrency, calls.size()), now);
    }

    /**
     * The caller must supply evidence for the CURRENT inherited model/request mode, never a
     * model-name heuristic. Existing ModelProbe only probes one call, so pass false for
     * batchProtocolVerified until an actual multi-call response has been validated.
     * Recreate on expiry/config changes; protocol truncation/failure must use conservative().
     * Chunk size is a budgeting hint, not a guarantee about a provider's tokenizer.
     */
    public static ToolSchedulingPolicy fromEvidence(ModelCapabilityProfile profile, String fingerprint,
            boolean batchProtocolVerified, boolean boundedWriteVerified, int maxOutputTokens,
            int requestedConcurrency, long now) {
        if (profile == null || fingerprint == null || !fingerprint.equals(profile.fingerprint)
                || profile.state(ModelCapabilityProfile.Capability.TOOLS, now)
                    != ModelCapabilityProfile.State.SUPPORTED) return conservative();
        int parallel = batchProtocolVerified ? Math.max(1, Math.min(4, requestedConcurrency)) : 1;
        int chunk = boundedWriteVerified && maxOutputTokens >= 4096
                ? (int) Math.min(16000L, Math.max(1000L, (maxOutputTokens - 512L) / 2L)) : 1000;
        return new ToolSchedulingPolicy(parallel, chunk);
    }

    public String promptRules() {
        String dispatch = maxGeneratedCalls>1&&maxParallelReads==1
                ? "当前模型单调用已验证，可将实际任务需要的最多2个独立只读小调用同轮发出；本地先串行验证完整批量协议，禁止为探测而额外读取无关文件。"
                : maxParallelReads == 1
                ? "当前模型批量协议尚未确认或已回退，每轮最多生成一个工具调用。"
                : "当前模型已验证小批量协议，每轮最多生成" + maxParallelReads
                    + "个真正只读、互不依赖的工具调用；本地调度仍会核对动作副作用。";
        return "11." + dispatch
                + "先读后改、依赖前一结果的动作分轮执行；写入、Shell、用户确认、共享浏览器动作不同时派发。"
                + "Edit每次只修改一处；Write根据本轮输出预算分块，首块mode=overwrite，后续块mode=append，"
                + "每块content不超过" + writeChunkCharacters + "字符（JSON转义与其他参数也占预算，必要时进一步减小）。"
                + "工具JSON截断或协议失败后回退单调用和1000字符保守分块，不更换模型。\n";
    }
}
