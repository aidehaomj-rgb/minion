package com.minion.core.llm;

import com.google.gson.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** 使用本机模拟 HTTP 服务验证恢复请求的协议，不调用真实模型。 */
public class DeepSeekActionRecoveryV27Test {
    private MockWebServer server;
    private DeepSeekClient client;
    @Before public void setup() throws Exception {
        server=new MockWebServer();server.start();
        client=new DeepSeekClient(server.url("/").toString(),"test-key","deepseek-v4-flash-0731",true,"high","deepseek");
    }
    @After public void cleanup() throws Exception {client.close();server.shutdown();}
    private MockResponse ok() {return new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");}
    private List<JsonObject> tools() {
        return Collections.singletonList(JsonParser.parseString("{\"type\":\"function\",\"function\":{\"name\":\"Read\",\"description\":\"读取文件\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}}").getAsJsonObject());
    }
    private JsonObject request() throws Exception {
        RecordedRequest r=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(r);
        return JsonParser.parseString(r.getBody().readUtf8()).getAsJsonObject();
    }
    private StreamHandler sink(){return new StreamHandler(){ public void onFinish(String reason,Usage u,List<ToolCall> calls){} };}
    @Test public void withoutThinkingExplicitlyDisablesDeepseek() throws Exception {
        server.enqueue(ok());client.streamChatWithoutThinking(Collections.singletonList(Message.user("继续")),tools(),sink());
        JsonObject r=request();assertEquals("disabled",r.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(r.has("reasoning_effort"));assertFalse(r.has("tool_choice"));
    }
    @Test public void actionRecoveryRequiresToolOnlyWithThinkingDisabledAndPreservesHistory() throws Exception {
        server.enqueue(ok());Message a=Message.assistant("先检查");a.reasoningContent="原始思考";
        client.streamChatForAction(Arrays.asList(a,Message.user("落实工具动作")),tools(),sink());
        JsonObject r=request();assertEquals("required",r.get("tool_choice").getAsString());
        assertEquals("disabled",r.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(r.get("parallel_tool_calls").getAsBoolean());
        assertEquals("原始思考",r.getAsJsonArray("messages").get(0).getAsJsonObject().get("reasoning_content").getAsString());
    }
    @Test public void unsupportedToolChoiceFallsBackOnceAndIsRememberedForThisClient() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"tool_choice required is not supported\"}}"));server.enqueue(ok());server.enqueue(ok());
        client.streamChatForAction(Collections.singletonList(Message.user("继续")),tools(),sink());
        assertEquals("required",request().get("tool_choice").getAsString());
        assertFalse(request().has("tool_choice"));
        client.streamChatForAction(Collections.singletonList(Message.user("继续")),tools(),sink());
        assertFalse(request().has("tool_choice"));assertEquals(3,server.getRequestCount());
    }
    @Test public void unrelatedBadRequestMustNotBeRetried() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"invalid tool arguments JSON\"}}"));
        try {client.streamChatForAction(Collections.singletonList(Message.user("继续")),tools(),sink());fail();}
        catch(LlmException e){assertEquals(400,e.httpCode);}
        assertEquals(1,server.getRequestCount());
    }
    @Test public void noToolsAndOrdinaryRequestsAreNeverForced() throws Exception {
        server.enqueue(ok());server.enqueue(ok());
        client.streamChatForAction(Collections.singletonList(Message.user("hi")),null,sink());assertFalse(request().has("tool_choice"));
        client.streamChat(Collections.singletonList(Message.user("hi")),tools(),sink());
        JsonObject r=request();assertFalse(r.has("tool_choice"));assertEquals("enabled",r.getAsJsonObject("thinking").get("type").getAsString());
    }
}
