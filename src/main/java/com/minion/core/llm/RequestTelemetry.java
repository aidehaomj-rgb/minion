package com.minion.core.llm;

import com.google.gson.JsonObject;
import com.minion.core.context.ContextBudget;
import com.minion.core.context.TokenCounter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 客户端内存内的有界请求观测；不保存正文、工具参数、地址或凭据。 */
public final class RequestTelemetry {
    private static final int MAX_RECENT = 32;
    private final Deque<JsonObject> recent = new ArrayDeque<JsonObject>();
    private long attempts;

    static final class Attempt {
        final long started = System.nanoTime();
        final String kind;
        final ContextBudget.Estimate estimate;
        long networkStart = -1, networkEnd = -1, headers = -1, first = -1;
        int apiInput = -1, apiOutput = -1, apiReasoning = -1;
        boolean sse, cachedRejection;
        Usage usage;
        String finish;
        LlmRecovery.Category failure;
        LlmRecovery.Action recovery;
        Attempt(String kind,List<Message> messages,List<JsonObject> tools) {
            this.kind=kind;this.estimate=ContextBudget.estimate(messages,tools);
        }
        void headers() { headers=System.nanoTime(); }
        void networkStart() { networkStart=System.nanoTime(); }
        void networkEnd() { if(networkEnd<0)networkEnd=System.nanoTime(); }
        void delta() { if(first<0)first=System.nanoTime(); }
        void observedUsage(JsonObject o) {
            int input=count(o,"prompt_tokens"),output=count(o,"completion_tokens");
            if(input>=0)apiInput=input;if(output>=0)apiOutput=output;
            if(o.has("completion_tokens_details")&&o.get("completion_tokens_details").isJsonObject()) {
                int reasoning=count(o.getAsJsonObject("completion_tokens_details"),"reasoning_tokens");
                if(reasoning>=0)apiReasoning=reasoning;
            }
        }
        /** 缺失字段逐项估算，保留真实字段；混合值 estimated=true，禁止参与校准。 */
        Usage resolve(List<Message> messages,String output) {
            Usage u=new Usage();u.estimated=apiInput<0||apiOutput<0;
            u.inputTokens=apiInput>=0?apiInput:estimate.total();
            u.outputTokens=apiOutput>=0?apiOutput:TokenCounter.estimate(output);
            u.reasoningTokens=apiReasoning>=0?apiReasoning:0;
            u.totalTokens=u.inputTokens+u.outputTokens;usage=u;return u;
        }
        private static int count(JsonObject o,String key) {
            try {
                if(!o.has(key)||o.get(key).isJsonNull()||!o.get(key).isJsonPrimitive()
                        ||!o.getAsJsonPrimitive(key).isNumber())return -1;
                java.math.BigDecimal value=o.get(key).getAsBigDecimal();
                int n=value.intValueExact();return n>=0?n:-1;
            } catch(RuntimeException invalid) { return -1; }
        }
        JsonObject snapshot() {
            long end=System.nanoTime(),networkFinished=networkEnd>=0?networkEnd:end;JsonObject o=new JsonObject();
            o.addProperty("kind",kind);o.addProperty("recordedAt",System.currentTimeMillis());
            o.addProperty("clientTotalMs",ms(end-started));
            o.addProperty("networkTotalMs",networkStart<0?-1:ms(networkFinished-networkStart));
            o.addProperty("headersMs",headers<0||networkStart<0?-1:ms(headers-networkStart));
            o.addProperty("firstTokenMs",first<0||networkStart<0?-1:ms(first-networkStart));
            o.addProperty("networkGenerationMs",first<0?-1:ms(networkFinished-first));
            o.addProperty("queueMs",-1);o.addProperty("serverQueueMs",-1);
            o.addProperty("timingScope","client_wall_clock_including_callbacks; -1=unknown; not_server_compute");
            o.addProperty("sseObserved",sse);o.addProperty("cachedImageRejection",cachedRejection);
            o.addProperty("finishReason",finish);
            o.addProperty("failure",failure==null?"none":failure.name());
            o.addProperty("recovery",failure==null?"none":LlmRecovery.action(failure).name());
            if(recovery!=null)o.addProperty("recovery",recovery.name());
            o.add("localInputEstimate",estimate.toJson());
            o.addProperty("totalInputCalibrationRatio",apiInput>=0&&apiOutput>=0&&estimate.total()>0
                    ?(double)apiInput/estimate.total():-1);
            o.addProperty("calibrationScope","same_request_total_only; no_threshold_or_model_changes");
            JsonObject actual=new JsonObject();actual.addProperty("inputTokens",apiInput);
            actual.addProperty("outputTokens",apiOutput);actual.addProperty("reasoningTokens",apiReasoning);
            actual.addProperty("componentTokens","unknown");o.add("apiUsage",actual);
            if(usage!=null) {
                JsonObject resolved=new JsonObject();resolved.addProperty("inputTokens",usage.inputTokens);
                resolved.addProperty("outputTokens",usage.outputTokens);resolved.addProperty("estimated",usage.estimated);
                o.add("resolvedUsage",resolved);
            }
            return o;
        }
        private static long ms(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
    }

    synchronized void record(Attempt attempt) {
        JsonObject snapshot=attempt.snapshot();snapshot.addProperty("observationId",++attempts);
        recent.addLast(snapshot);while(recent.size()>MAX_RECENT)recent.removeFirst();
    }
    public synchronized long attempts() { return attempts; }
    public synchronized List<JsonObject> recent() {
        List<JsonObject> out=new ArrayList<JsonObject>();for(JsonObject o:recent)out.add(o.deepCopy());return out;
    }
    /** 完成顺序游标；若首条ID大于 cursor+1，说明有界历史已淘汰，调用方应报告缺口。 */
    public synchronized List<JsonObject> recentAfter(long cursor) {
        List<JsonObject> out=new ArrayList<JsonObject>();
        for(JsonObject o:recent)if(o.get("observationId").getAsLong()>cursor)out.add(o.deepCopy());
        return out;
    }
}
