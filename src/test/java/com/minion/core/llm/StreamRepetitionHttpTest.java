package com.minion.core.llm;

import com.google.gson.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StreamRepetitionHttpTest {
    @Test public void sameFrameToolDeltaIsNeverInterruptedOrDiscarded() throws Exception {
        MockWebServer server=new MockWebServer();server.start();
        DeepSeekClient client=new DeepSeekClient(server.url("/").toString(),"key","deepseek",true,"high","deepseek");
        try {
            JsonObject delta=new JsonObject();delta.addProperty("content",RepetitionGuardTest.loopText());
            delta.add("tool_calls",JsonParser.parseString("[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"Read\",\"arguments\":\"{\\\"path\\\":\\\"result_1.json\\\"}\"}}]"));
            JsonObject choice=new JsonObject();choice.add("delta",delta);choice.addProperty("finish_reason","tool_calls");
            JsonArray choices=new JsonArray();choices.add(choice);JsonObject frame=new JsonObject();frame.add("choices",choices);
            server.enqueue(new MockResponse().setBody("data: "+frame+"\n\ndata: [DONE]\n\n"));
            final List<ToolCall> calls=new ArrayList<ToolCall>();final String[] finish={null};
            RepetitionGuard g=new RepetitionGuard(new StreamHandler(){public void onFinish(String r,Usage u,List<ToolCall> c){finish[0]=r;calls.addAll(c);}});
            client.streamChat(Collections.singletonList(Message.user("检查")),null,g);
            assertFalse(g.triggered());assertEquals("tool_calls",finish[0]);assertEquals(1,calls.size());assertEquals("call-1",calls.get(0).id);
        } finally {client.close();server.shutdown();}
    }
    @Test public void closesOnlyCurrentResponseAndNextRequestStillWorks() throws Exception {
        MockWebServer server=new MockWebServer();server.start();
        DeepSeekClient client=new DeepSeekClient(server.url("/").toString(),"key","deepseek",true,"high","deepseek");
        try {
            StringBuilder wire=new StringBuilder();String text=RepetitionGuardTest.loopText();
            for(String line:text.split("\n")) {
                JsonObject d=new JsonObject();d.addProperty("content",line+"\n");JsonObject choice=new JsonObject();choice.add("delta",d);
                JsonArray choices=new JsonArray();choices.add(choice);JsonObject frame=new JsonObject();frame.add("choices",choices);
                wire.append("data: ").append(frame).append("\n\n");
            }
            server.enqueue(new MockResponse().setBody(wire.toString()));
            server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"正常回复\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            final String[] finish={null};final Usage[] usage={null};final StringBuilder seen=new StringBuilder();
            StreamHandler sink=new StreamHandler(){public void onContent(String d){seen.append(d);}public void onFinish(String r,Usage u,List<ToolCall> c){finish[0]=r;usage[0]=u;assertTrue(c.isEmpty());}};
            client.streamChat(Collections.singletonList(Message.user("继续校验")),null,new RepetitionGuard(sink));
            assertEquals(RepetitionGuard.FINISH,finish[0]);assertTrue(usage[0].estimated);assertTrue(seen.length()<text.length());
            client.streamChat(Collections.singletonList(Message.user("继续")),null,sink);assertEquals("stop",finish[0]);
        } finally {client.close();server.shutdown();}
    }
}
