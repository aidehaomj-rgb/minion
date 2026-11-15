package com.minion.core.llm;

import com.google.gson.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class StopReviewHttpTest {
    private MockResponse review(String reason,boolean tool) {
        JsonObject root=new JsonObject(),choice=new JsonObject(),message=new JsonObject();JsonArray choices=new JsonArray();
        message.addProperty("content","{\"status\":\"completed\"}");if(tool){JsonArray calls=new JsonArray();calls.add(new JsonObject());message.add("tool_calls",calls);}
        choice.addProperty("finish_reason",reason);choice.add("message",message);choices.add(choice);root.add("choices",choices);
        JsonObject usage=new JsonObject();usage.addProperty("prompt_tokens",31);usage.addProperty("completion_tokens",7);root.add("usage",usage);
        return new MockResponse().setHeader("Content-Type","application/json").setBody(root.toString());
    }
    private DeepSeekClient client(MockWebServer server,String provider) {
        return new DeepSeekClient(server.url("/").toString(),"test","deepseek-v4-flash",true,"high",provider);
    }
    @Test public void reviewIsNonStreamingBoundedToolFreeAndMetered() throws Exception {
        MockWebServer server=new MockWebServer();server.start();DeepSeekClient client=client(server,"deepseek");
        try {
            server.enqueue(review("stop",false));StopReviewResponse result=client.reviewStop("current-snapshot","review-system");
            JsonObject body=JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
            assertFalse(body.get("stream").getAsBoolean());assertEquals(1024,body.get("max_tokens").getAsInt());
            assertFalse(body.has("tools"));assertFalse(body.has("tool_choice"));assertFalse(body.has("stream_options"));
            assertEquals("disabled",body.getAsJsonObject("thinking").get("type").getAsString());
            assertEquals(2,body.getAsJsonArray("messages").size());assertEquals(31,result.usage.inputTokens);assertEquals(7,result.usage.outputTokens);
        } finally {client.close();server.shutdown();}
    }
    @Test public void timeoutCancelsOnlyReviewNotConcurrentExecution() throws Exception {
        MockWebServer server=new MockWebServer();server.start();DeepSeekClient client=client(server,"deepseek");ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            client.stopReviewTimeoutMs=100;server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            Future<?> audit=pool.submit(()->{try{client.reviewStop("snapshot","system");fail("Expected timeout");}catch(LlmException expected){assertTrue(expected.getMessage().contains("失败"));}});
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream").setBodyDelay(300,TimeUnit.MILLISECONDS)
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"正常执行\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            StringBuilder out=new StringBuilder();client.streamChat(Collections.singletonList(Message.user("执行任务")),null,new StreamHandler(){
                public void onContent(String s){out.append(s);}public void onThinking(String s){}public void onFinish(String s,Usage u,List<ToolCall> calls){}
            });
            audit.get(3,TimeUnit.SECONDS);assertEquals("正常执行",out.toString());
        } finally {pool.shutdownNow();client.close();server.shutdown();}
    }
    @Test public void truncatedOrToolBearingReviewIsNotAccepted() throws Exception {
        MockWebServer server=new MockWebServer();server.start();DeepSeekClient client=client(server,"deepseek");
        try {for(MockResponse response:Arrays.asList(review("length",false),review("stop",true))) {
            server.enqueue(response);try{client.reviewStop("snapshot","system");fail();}catch(LlmException expected){assertTrue(expected.getMessage().contains("完整"));}
        }} finally {client.close();server.shutdown();}
    }
    @Test public void nullableOrEmptyToolFieldIsCompatible() throws Exception {
        MockWebServer server=new MockWebServer();server.start();DeepSeekClient client=client(server,"deepseek");
        try{for(JsonElement empty:Arrays.asList(JsonNull.INSTANCE,new JsonArray())){
            JsonObject root=JsonParser.parseString("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}").getAsJsonObject();
            root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").add("tool_calls",empty);
            server.enqueue(new MockResponse().setBody(root.toString()));assertEquals("{}",client.reviewStop("input","system").text);
        }}finally{client.close();server.shutdown();}
    }
    @Test public void successfulAndErrorBodiesShareSameReadCap() throws Exception {
        MockWebServer server=new MockWebServer();server.start();DeepSeekClient client=client(server,"deepseek");
        char[] chars=new char[65537];Arrays.fill(chars,'x');String oversized=new String(chars);
        try{for(int code:new int[]{200,503}){
            server.enqueue(new MockResponse().setResponseCode(code).setBody(oversized));
            try{client.reviewStop("input","system");fail("Oversized audit must fail closed");}catch(LlmException expected){assertTrue(expected.getMessage().contains("过长"));}
        }}finally{client.close();server.shutdown();}
    }
    @Test public void qwenReviewDisablesThinkingWithoutAffectingConfiguredModel() throws Exception {
        MockWebServer server=new MockWebServer();server.start();DeepSeekClient client=new DeepSeekClient(server.url("/").toString(),"test","qwen3.8-27b",true,"high","qwen");
        try{server.enqueue(review("stop",false));client.reviewStop("input","system");JsonObject body=JsonParser.parseString(server.takeRequest().getBody().readUtf8()).getAsJsonObject();
            assertEquals("qwen3.8-27b",body.get("model").getAsString());assertFalse(body.get("enable_thinking").getAsBoolean());
            assertFalse(body.has("tools"));assertFalse(body.get("stream").getAsBoolean());
        }finally{client.close();server.shutdown();}
    }
    @Test public void remoteAgentProviderDoesNotReceiveAuditInstructions() throws Exception {
        MockWebServer server=new MockWebServer();server.start();DeepSeekClient client=client(server,"sfm-agent");
        try{try{client.reviewStop("input","system");fail();}catch(LlmException expected){assertTrue(expected.getMessage().contains("隔离结束检查"));}
            assertEquals(0,server.getRequestCount());
        }finally{client.close();server.shutdown();}
    }
}
