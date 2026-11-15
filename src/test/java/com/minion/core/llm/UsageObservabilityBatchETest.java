package com.minion.core.llm;

import com.google.gson.*;
import com.minion.core.context.ContextBudget;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class UsageObservabilityBatchETest {
    private Usage usage(int input,int output,boolean estimated){Usage u=new Usage();u.inputTokens=input;u.outputTokens=output;u.estimated=estimated;return u;}
    @Test public void legacyTotalsAndReportedEstimatedSubtotalsStaySeparate() {
        UsageTracker tracker=new UsageTracker();tracker.record(usage(100,20,false));tracker.record(usage(30,5,true));
        assertEquals(130,tracker.sessionInput());assertEquals(25,tracker.sessionOutput());assertEquals(155,tracker.sessionTotal());
        JsonObject b=tracker.breakdown();assertEquals(100,b.get("reportedInputTokens").getAsInt());assertEquals(30,b.get("estimatedOrMixedInputTokens").getAsInt());
        UsageTracker restored=new UsageTracker();restored.restore(tracker);assertEquals(b,restored.breakdown());
        restored.reset();assertEquals(0,restored.sessionTotal());assertEquals(0,restored.breakdown().get("reportedRequests").getAsInt());assertNull(restored.last());
    }
    @Test public void oldArchiveDoesNotInventActualSourceEvidence() {
        UsageTracker tracker=new Gson().fromJson("{\"sessionInput\":123,\"sessionOutput\":8}",UsageTracker.class);
        assertEquals(123,tracker.sessionInput());assertEquals(0,tracker.breakdown().get("reportedInputTokens").getAsInt());
        assertEquals(123,tracker.breakdown().get("unattributedHistoricalInputTokens").getAsInt());
    }
    @Test public void calibrationUsesSameRequestAndResetsAcrossFingerprintWithoutChangingEstimates() {
        UsageTracker tracker=new UsageTracker();ContextBudget.Estimate e=ContextBudget.estimate(Collections.singletonList(Message.user("text")),null);
        assertFalse(tracker.observeCalibration(DeepSeekTelemetryBatchETest.repeat('a'),e,usage(100,1,true)));
        assertTrue(tracker.observeCalibration(DeepSeekTelemetryBatchETest.repeat('a'),e,usage(e.total()*2,1,false)));
        assertEquals(2.0,tracker.breakdown().get("totalInputRatio").getAsDouble(),0.001);
        assertTrue(tracker.observeCalibration(DeepSeekTelemetryBatchETest.repeat('b'),e,usage(e.total()*3,1,false)));
        assertEquals(1,tracker.breakdown().get("calibrationSamples").getAsInt());assertEquals(3.0,tracker.breakdown().get("totalInputRatio").getAsDouble(),0.001);
        assertEquals(e.total(),ContextBudget.estimate(Collections.singletonList(Message.user("text")),null).total());
        assertTrue(ContextBudget.describe(Collections.emptyList(),null,1000000,900000,150000,"balanced").contains("自动压缩线: 150000"));
    }
    @Test public void componentsAreLocalEstimatesAndDoNotInventActualImageCost() {
        Message m=Message.user("text");ImagePart image=new ImagePart();m.images=Collections.singletonList(image);
        ToolCall call=new ToolCall();call.name="echo";call.arguments="{}";Message a=Message.assistant("ok");a.toolCalls=Collections.singletonList(call);a.reasoningContent="thinking";
        JsonObject schema=JsonParser.parseString("{\"schema\":\"synthetic\"}").getAsJsonObject();
        ContextBudget.Estimate e=ContextBudget.estimate(Arrays.asList(m,a),Collections.singletonList(schema));
        assertEquals(ImagePart.IMAGE_TOKENS,e.images);assertTrue(e.schema>0);assertTrue(e.args>0);assertTrue(e.reasoning>0);
        String described=ContextBudget.describeUsage(e,usage(100,20,false));assertTrue(described.contains("分项实际token=unknown"));
    }
    @Test public void unobservedPhasesStayUnknownAndSpanCloseIsIdempotent() {
        PhaseTimings t=new PhaseTimings();assertEquals(-1,t.snapshot().getAsJsonObject("TOOL").get("elapsedMs").getAsInt());
        PhaseTimings.Span span=t.begin(PhaseTimings.Phase.TOOL);span.close();span.close();
        assertEquals(1,t.snapshot().getAsJsonObject("TOOL").get("observations").getAsInt());
        assertEquals(-1,t.snapshot().getAsJsonObject("COMPRESSION").get("elapsedMs").getAsInt());
        t.record(PhaseTimings.Phase.COMPRESSION,12);assertEquals(12,t.snapshot().getAsJsonObject("COMPRESSION").get("elapsedMs").getAsInt());
        t.reset();assertEquals(-1,t.snapshot().getAsJsonObject("TOOL").get("elapsedMs").getAsInt());
    }
    @Test public void recoveryIsCategorizedWithoutExecutingAnyRecovery() {
        assertEquals(LlmRecovery.Category.CONTEXT_LIMIT,LlmRecovery.classify(LlmException.of(400,"maximum context length exceeded")));
        assertEquals(LlmRecovery.Category.IMAGE_REJECTED,LlmRecovery.classify(LlmException.of(400,"image_url invalid")));
        assertEquals(LlmRecovery.Action.KEEP_PARTIAL_AND_REPAIR,LlmRecovery.action(LlmRecovery.Category.TRUNCATED));
        assertEquals(LlmRecovery.Action.CHANGE_REQUEST,LlmRecovery.action(LlmRecovery.Category.IMAGE_REJECTED));
        assertEquals(LlmRecovery.Action.CHECK_CONFIGURATION,LlmRecovery.action(new LlmException(LlmException.Type.NETWORK,"DNS",false)));
    }
}
