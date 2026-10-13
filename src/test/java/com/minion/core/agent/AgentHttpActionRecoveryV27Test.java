package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** 覆盖主循环→真实序列化/SSE→工具执行→恢复普通模式，避免只验证测试桩。 */
public class AgentHttpActionRecoveryV27Test {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void planningLoopRecoversThroughRealHttpToolCalls() throws Exception {
        MockWebServer server=new MockWebServer();server.start();
        DeepSeekClient client=new DeepSeekClient(server.url("/").toString(),"test-key","deepseek-v4-flash-0731",true,"high","deepseek");
        Workspace ws=new Workspace(tmp.getRoot().getPath());ToolRegistry registry=new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        AgentLoop loop=new AgentLoop(client,registry,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new AgentUi(){},
                new ContextManager(900000,.9,50,client,0),ws,Session.create(ws.workDir(),"deepseek-v4-flash-0731"));
        try {
            StopReviewDispatcher dispatcher=new StopReviewDispatcher();server.setDispatcher(dispatcher);
            dispatcher.enqueue(tool("scan"));dispatcher.enqueue(text("我先检查实际数据。"));
            dispatcher.enqueue(text("先调用Bash构建补全清单并分块。"));dispatcher.enqueue(tool("verify"));
            dispatcher.enqueue(text("全部结果已生成并核验。"+AgentLoop.TASK_COMPLETE_MARKER));
            loop.runUserTurn("补全原文并交付");assertTrue(loop.lastTurnSucceeded());
            assertEquals(8,server.getRequestCount());
            List<JsonObject> requests=new ArrayList<JsonObject>();
            for(int i=0;i<8;i++) {
                RecordedRequest r=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(r);
                JsonObject body=JsonParser.parseString(r.getBody().readUtf8()).getAsJsonObject();
                if(body.get("stream").getAsBoolean())requests.add(body);
            }
            assertEquals(5,requests.size());
            assertFalse(requests.get(0).has("tool_choice"));
            assertEquals("required",requests.get(3).get("tool_choice").getAsString());
            assertEquals("disabled",requests.get(3).getAsJsonObject("thinking").get("type").getAsString());
            assertFalse(requests.get(4).has("tool_choice"));
            assertEquals("enabled",requests.get(4).getAsJsonObject("thinking").get("type").getAsString());
            assertEquals(2,loop.messages().stream().filter(m -> m.role==Message.Role.TOOL).count());
            assertEquals(900000,loop.contextManager().maxTokens());
        } finally {loop.shutdown();client.close();server.shutdown();}
    }
    private MockResponse text(String text) {
        JsonObject delta=new JsonObject();delta.addProperty("content",text);
        return response(delta,"stop");
    }
    private MockResponse tool(String id) {
        JsonObject delta=new JsonObject();
        JsonArray calls=new JsonArray();JsonObject call=new JsonObject();call.addProperty("index",0);call.addProperty("id",id);call.addProperty("type","function");
        JsonObject fn=new JsonObject();fn.addProperty("name","example");fn.addProperty("arguments","{\"text\":\"核验成功\"}");call.add("function",fn);calls.add(call);delta.add("tool_calls",calls);
        return response(delta,"tool_calls");
    }
    private MockResponse response(JsonObject delta,String reason) {
        JsonObject root=new JsonObject(),choice=new JsonObject();JsonArray choices=new JsonArray();
        choice.add("delta",delta);choice.addProperty("finish_reason",reason);choices.add(choice);root.add("choices",choices);
        return new MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: "+root+"\n\ndata: [DONE]\n\n");
    }
}
