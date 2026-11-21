package com.minion.core.llm;

import com.google.gson.JsonObject;
import com.minion.core.context.ContextBudget;

public class UsageTracker {
    private int sessionInput;
    private int sessionOutput;
    private int sessionThinking;
    /** §1.1 缓存分账：命中/未命中累计（仅 cacheReported 的响应计入命中口径） */
    private long sessionCacheHit, sessionCacheMiss;
    private Usage last;
    // 旧累计字段保留供历史存档兼容；新增分项不把旧存档总量倒推为真实 usage。
    private long reportedInput, reportedOutput, estimatedInput, estimatedOutput;
    private long reportedRequests, estimatedRequests;
    private String calibrationFingerprint;
    private long calibrationLocal, calibrationActual, calibrationSamples;

    public synchronized void record(Usage usage) {
        if (usage == null) return;
        last = usage;
        sessionInput += usage.inputTokens;
        sessionOutput += usage.outputTokens;
        sessionThinking += usage.reasoningTokens;
        if (usage.cacheReported) { sessionCacheHit += usage.cacheHitTokens; sessionCacheMiss += usage.cacheMissTokens; }
        if (usage.estimated) {
            estimatedInput += usage.inputTokens; estimatedOutput += usage.outputTokens; estimatedRequests++;
        } else {
            reportedInput += usage.inputTokens; reportedOutput += usage.outputTokens; reportedRequests++;
        }
    }

    /** 同请求、完整真实 usage 的总量校准；仅观测，不写配置或改变 150K 压缩目标。 */
    public synchronized boolean observeCalibration(String fingerprint, ContextBudget.Estimate local, Usage actual) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || local == null || local.total() <= 0
                || actual == null || actual.estimated || actual.inputTokens <= 0) return false;
        if (!fingerprint.equals(calibrationFingerprint)) {
            calibrationFingerprint=fingerprint;calibrationLocal=0;calibrationActual=0;calibrationSamples=0;
        }
        calibrationLocal+=local.total();calibrationActual+=actual.inputTokens;calibrationSamples++;return true;
    }

    public synchronized JsonObject breakdown() {
        JsonObject o=new JsonObject();o.addProperty("reportedInputTokens",reportedInput);
        o.addProperty("reportedOutputTokens",reportedOutput);o.addProperty("estimatedOrMixedInputTokens",estimatedInput);
        o.addProperty("estimatedOrMixedOutputTokens",estimatedOutput);o.addProperty("reportedRequests",reportedRequests);
        o.addProperty("estimatedOrMixedRequests",estimatedRequests);
        o.addProperty("cacheHitTokens",sessionCacheHit);o.addProperty("cacheMissTokens",sessionCacheMiss);
        o.addProperty("unattributedHistoricalInputTokens",(long)sessionInput-reportedInput-estimatedInput);
        o.addProperty("unattributedHistoricalOutputTokens",(long)sessionOutput-reportedOutput-estimatedOutput);
        o.addProperty("calibrationFingerprint",calibrationFingerprint);o.addProperty("calibrationSamples",calibrationSamples);
        o.addProperty("totalInputRatio",calibrationLocal>0?(double)calibrationActual/calibrationLocal:-1);
        o.addProperty("actualComponentTokens","unknown; total usage cannot prove text/tool/schema/image components");
        return o;
    }

    public synchronized int sessionInput() { return sessionInput; }
    public synchronized int sessionOutput() { return sessionOutput; }
    public synchronized int sessionThinking() { return sessionThinking; }
    public synchronized long sessionCacheHit() { return sessionCacheHit; }
    public synchronized long sessionCacheMiss() { return sessionCacheMiss; }
    public synchronized int sessionTotal() { return sessionInput + sessionOutput; }
    public synchronized Usage last() { return last; }

    /** 清零本轮统计（/new 复用实例，不清引用） */
    public synchronized void reset() {
        sessionInput = 0;
        sessionOutput = 0;
        sessionThinking = 0;
        sessionCacheHit = 0;
        sessionCacheMiss = 0;
        last = null;
        reportedInput=reportedOutput=estimatedInput=estimatedOutput=reportedRequests=estimatedRequests=0;
        calibrationFingerprint=null;calibrationLocal=calibrationActual=calibrationSamples=0;
    }

    /** 从另一 tracker 复制统计（/resume 原地恢复：Main/CommandDispatcher 捕获的是会话
     *  usage 实例引用，换实例会让后续统计与 /tokens 展示落到已废弃实例上） */
    public void restore(UsageTracker from) {
        if (from == null) return;
        // 分开加锁，避免两个 tracker 相互 restore 时锁顺序相反。
        UsageTracker snapshot=new UsageTracker();
        synchronized(from) { snapshot.copyFrom(from); }
        synchronized(this) { copyFrom(snapshot); }
    }
    private void copyFrom(UsageTracker from) {
        sessionInput=from.sessionInput;sessionOutput=from.sessionOutput;sessionThinking=from.sessionThinking;last=from.last;
        sessionCacheHit=from.sessionCacheHit;sessionCacheMiss=from.sessionCacheMiss;
        reportedInput=from.reportedInput;reportedOutput=from.reportedOutput;estimatedInput=from.estimatedInput;
        estimatedOutput=from.estimatedOutput;reportedRequests=from.reportedRequests;estimatedRequests=from.estimatedRequests;
        calibrationFingerprint=from.calibrationFingerprint;calibrationLocal=from.calibrationLocal;
        calibrationActual=from.calibrationActual;calibrationSamples=from.calibrationSamples;
    }
}
