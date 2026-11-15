package com.minion.core.llm;

import com.google.gson.*;
import com.minion.core.context.ContextBudget;
import okhttp3.mockwebserver.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** 本机 HTTP/SSE 故障注入和观测证据；不发真实项目材料。 */
public class DeepSeekTelemetryBatchETest {
    private MockWebServer server;private DeepSeekClient client;
    @Before public void setup() throws Exception {
        server=new MockWebServer();server.start();
        client=new DeepSeekClient(server.url("/").toString(),"not-in-telemetry","unchanged-model",true,"high","deepseek");
    }
    @After public void cleanup() throws Exception {client.close();server.shutdown();}
    private StreamHandler sink(){return new StreamHandler(){public void onFinish(String r,Usage u,List<ToolCall> c){}};}
    private JsonObject last(){List<JsonObject> list=client.telemetry().recent();return list.get(list.size()-1);}
    private List<Message> messages(){return Collections.singletonList(Message.user("synthetic"));}
    private List<JsonObject> tools(){return Collections.singletonList(JsonParser.parseString("{\"type\":\"function\",\"function\":{\"name\":\"echo\",\"parameters\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}}}}}").getAsJsonObject());}
    private MockResponse ok(String usage){return ModelCapabilitiesBatchETest.sse(JsonParser.parseString("{\"content\":\"OK\"}").getAsJsonObject(),"stop",usage);}
    @Test public void constructorDoesNotProbeOrCreateMeasurements() {
        assertEquals(0,server.getRequestCount());assertEquals(0,client.telemetry().attempts());assertTrue(client.telemetry().recent().isEmpty());
    }
    @Test public void firstTokenAndNetworkGenerationAreMeasuredAndQueueIsUnknown() throws Exception {
        server.enqueue(ok("{\"prompt_tokens\":100,\"completion_tokens\":10}").setBodyDelay(80,TimeUnit.MILLISECONDS));
        client.streamChat(messages(),tools(),sink());JsonObject m=last();
        long first=m.get("firstTokenMs").getAsLong(),total=m.get("networkTotalMs").getAsLong();
        assertTrue(first>=50);assertTrue(total>=first);assertTrue(m.get("headersMs").getAsLong()<=first);
        assertTrue(m.get("networkGenerationMs").getAsLong()>=0);assertEquals(-1,m.get("queueMs").getAsInt());
        assertEquals(-1,m.get("serverQueueMs").getAsInt());assertTrue(m.get("sseObserved").getAsBoolean());
        assertEquals(100,m.getAsJsonObject("apiUsage").get("inputTokens").getAsInt());
        assertFalse(m.getAsJsonObject("resolvedUsage").get("estimated").getAsBoolean());
        assertTrue(m.getAsJsonObject("localInputEstimate").get("toolSchemas").getAsInt()>0);
        assertFalse(m.toString().contains("not-in-telemetry"));assertFalse(m.toString().contains("synthetic"));
    }
    @Test public void emptyUsageCannotMasqueradeAsActualZeroUsage() throws Exception {
        server.enqueue(ok("{}"));final Usage[] u={null};
        client.streamChat(messages(),tools(),new StreamHandler(){public void onFinish(String r,Usage v,List<ToolCall> c){u[0]=v;}});
        assertTrue(u[0].estimated);assertEquals(ContextBudget.estimate(messages(),tools()).total(),u[0].inputTokens);
        assertEquals(-1,last().getAsJsonObject("apiUsage").get("inputTokens").getAsInt());
    }
    @Test public void partialUsageRetainsTrueFieldsButDoesNotCalibrate() throws Exception {
        server.enqueue(ok("{\"prompt_tokens\":200}"));final Usage[] u={null};
        client.streamChat(messages(),null,new StreamHandler(){public void onFinish(String r,Usage v,List<ToolCall> c){u[0]=v;}});
        assertEquals(200,u[0].inputTokens);assertTrue(u[0].estimated);assertTrue(u[0].outputTokens>0);
        assertEquals(-1,last().getAsJsonObject("apiUsage").get("outputTokens").getAsInt());
        assertFalse(new UsageTracker().observeCalibration(repeat('a'),ContextBudget.estimate(messages(),null),u[0]));
    }
    @Test public void invalidUsageCountsAreNotTreatedAsReported() throws Exception {
        server.enqueue(ok("{\"prompt_tokens\":-2,\"completion_tokens\":1.5}"));client.streamChat(messages(),null,sink());
        assertEquals(-1,last().getAsJsonObject("apiUsage").get("inputTokens").getAsInt());
        assertTrue(last().getAsJsonObject("resolvedUsage").get("estimated").getAsBoolean());
    }
    @Test public void emptyDeltasAndEmptyToolArrayAreNotFirstToken() throws Exception {
        server.enqueue(ModelCapabilitiesBatchETest.sse(JsonParser.parseString("{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"\",\"tool_calls\":[]}").getAsJsonObject(),"stop",null));
        client.streamChat(messages(),null,sink());assertEquals(-1,last().get("firstTokenMs").getAsInt());
        assertEquals(-1,last().get("networkGenerationMs").getAsInt());
    }
    @Test public void toolOutputEstimationIncludesArgumentsAndRecordsFirstIncrement() throws Exception {
        JsonObject delta=JsonParser.parseString("{\"tool_calls\":[{\"index\":0,\"id\":\"t1\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"code\\\":\\\"long synthetic tool value\\\"}\"}}]}").getAsJsonObject();
        server.enqueue(ModelCapabilitiesBatchETest.sse(delta,"tool_calls",null));final Usage[] u={null};
        client.streamChat(messages(),tools(),new StreamHandler(){public void onFinish(String r,Usage v,List<ToolCall> c){u[0]=v;assertEquals(1,c.size());}});
        assertTrue(u[0].estimated);assertTrue(u[0].outputTokens>0);assertTrue(last().get("firstTokenMs").getAsInt()>=0);
    }
    private List<Message> imageMessages() {
        ImagePart image=new ImagePart();image.mime="image/png";image.base64="c3ludGhldGlj";
        Message m=Message.user("inspect synthetic image");m.images=Collections.singletonList(image);return Collections.singletonList(m);
    }
    @Test public void repeatedImage400DoesNotRetryOrStripImagesAndCanBeManuallyRetested() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("image_url unsupported; tool_choice required not supported"));
        for(int i=0;i<2;i++)try{client.streamChatForAction(imageMessages(),tools(),sink());fail();}
            catch(LlmException e){assertFalse(e.retryable);assertEquals(LlmRecovery.Category.IMAGE_REJECTED,LlmRecovery.classify(e));}
        assertEquals(1,server.getRequestCount());assertTrue(last().get("cachedImageRejection").getAsBoolean());
        assertEquals(-1,last().get("networkTotalMs").getAsInt());
        JsonObject sent=JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        assertEquals(2,sent.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content").size());
        client.clearRejectedImageRequests();server.enqueue(ok(null));client.streamChatForAction(imageMessages(),tools(),sink());
        assertEquals(2,server.getRequestCount());assertFalse(last().get("cachedImageRejection").getAsBoolean());
    }
    @Test public void imageBadRequestNotExplicitlyRejectedIsNotCached() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("tool_call_id pair missing"));server.enqueue(ok(null));
        try{client.streamChat(imageMessages(),null,sink());fail();}catch(LlmException expected){}
        client.streamChat(imageMessages(),null,sink());assertEquals(2,server.getRequestCount());
    }
    @Test public void malformedJsonBecomesClassifiedNonRetryableFailure() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {bad json\n\n"));
        try{client.streamChat(messages(),null,sink());fail();}catch(LlmException e){assertFalse(e.retryable);assertEquals(LlmRecovery.Category.FORMAT,LlmRecovery.classify(e));}
        assertEquals("FORMAT",last().get("failure").getAsString());assertEquals(1,server.getRequestCount());
    }
    @Test public void malformedDeltaShapeAlsoUsesLlmExceptionContract() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":[]}}]}\n\n"));
        try{client.streamChat(messages(),null,sink());fail();}catch(LlmException e){assertEquals(LlmRecovery.Category.FORMAT,LlmRecovery.classify(e));}
        assertEquals("FORMAT",last().get("failure").getAsString());
    }
    @Test public void reviewHasItsOwnNetworkMetricAndPartialUsageRemainsEstimated() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}],\"usage\":{\"prompt_tokens\":18}}"));
        StopReviewResponse r=client.reviewStop("synthetic","synthetic-system");assertEquals(18,r.usage.inputTokens);assertTrue(r.usage.estimated);
        assertEquals("stop_review_nonstream",last().get("kind").getAsString());assertEquals(-1,last().get("firstTokenMs").getAsInt());
        assertTrue(last().get("networkTotalMs").getAsInt()>=0);assertFalse(last().get("sseObserved").getAsBoolean());
    }
    @Test public void emptyTrailingUsageDoesNotEraseEarlierActualCounts() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":15,\"completion_tokens\":2}}\n\ndata: {\"choices\":[],\"usage\":{}}\n\ndata: [DONE]\n\n"));
        client.streamChat(messages(),null,sink());assertEquals(15,last().getAsJsonObject("apiUsage").get("inputTokens").getAsInt());
        assertFalse(last().getAsJsonObject("resolvedUsage").get("estimated").getAsBoolean());
        assertTrue(last().get("totalInputCalibrationRatio").getAsDouble()>0);
    }
    @Test public void truncatedStreamRetainsFinishContractAndClassifiesTelemetry() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"));
        client.streamChat(messages(),null,new StreamHandler(){public void onFinish(String r,Usage u,List<ToolCall> c){assertEquals("incomplete",r);}});
        assertEquals("TRUNCATED",last().get("failure").getAsString());assertEquals("incomplete",last().get("finishReason").getAsString());
    }
    @Test public void rateLimitRecordsNoFirstTokenAndNeverRetriesInsideClient() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("busy"));
        try{client.streamChat(messages(),null,sink());fail();}catch(LlmException e){assertTrue(e.retryable);}
        assertEquals("RATE_LIMIT",last().get("failure").getAsString());assertEquals(-1,last().get("firstTokenMs").getAsInt());assertEquals(1,server.getRequestCount());
    }
    @Test public void reasoningAndToolPairsAreUnmodifiedOnWire() throws Exception {
        server.enqueue(ok(null));ToolCall call=new ToolCall();call.id="t";call.name="echo";call.arguments="{}";
        Message a=Message.assistant("doing");a.reasoningContent="original reasoning";a.toolCalls=Collections.singletonList(call);
        Message tool=new Message();tool.role=Message.Role.TOOL;tool.toolCallId="t";tool.name="echo";tool.content="done";
        client.streamChat(Arrays.asList(a,tool,Message.user("continue")),tools(),sink());
        JsonObject body=JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
        JsonArray wire=body.getAsJsonArray("messages");assertEquals("unchanged-model",body.get("model").getAsString());
        assertEquals("original reasoning",wire.get(0).getAsJsonObject().get("reasoning_content").getAsString());
        assertEquals("t",wire.get(0).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("t",wire.get(1).getAsJsonObject().get("tool_call_id").getAsString());
    }
    @Test public void concurrentRequestsHaveIndependentUsageAndSnapshots() throws Exception {
        for(int i=0;i<8;i++)server.enqueue(ok("{\"prompt_tokens\":12,\"completion_tokens\":4}"));
        ExecutorService pool=Executors.newFixedThreadPool(4);try {
            List<Future<?>> futures=new ArrayList<Future<?>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->{try{client.streamChat(messages(),null,sink());}catch(LlmException e){throw new RuntimeException(e);}}));
            for(Future<?> f:futures)f.get(5,TimeUnit.SECONDS);
            assertEquals(8,client.telemetry().attempts());assertEquals(8,client.telemetry().recent().size());
            assertEquals(3,client.telemetry().recentAfter(5).size());
            assertEquals(6,client.telemetry().recentAfter(5).get(0).get("observationId").getAsInt());
            for(JsonObject o:client.telemetry().recent())assertEquals(12,o.getAsJsonObject("apiUsage").get("inputTokens").getAsInt());
            last().addProperty("finishReason","changed");assertEquals("stop",last().get("finishReason").getAsString());
        } finally {pool.shutdownNow();}
    }
    @Test public void requestHistoryIsBounded() throws Exception {
        for(int i=0;i<35;i++){server.enqueue(ok(null));client.streamChat(messages(),null,sink());}
        assertEquals(35,client.telemetry().attempts());assertEquals(32,client.telemetry().recent().size());
    }
    static String repeat(char c){char[] chars=new char[64];Arrays.fill(chars,c);return new String(chars);}
}
