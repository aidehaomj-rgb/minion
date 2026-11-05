package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.ModelConfig;
import com.minion.core.llm.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ModelProbeTest {
    @Test public void syntheticProbeRequiresActualToolArgumentsAndFingerprintChanges()throws Exception {
        ModelConfig m=new ModelConfig();m.displayName="test";m.modelName="test";m.url="http://127.0.0.1:1234";m.apiKey="hidden";m.maxOutputTokens=1000;
        JsonObject result=ModelProbe.run(m,ignored->new SyntheticClient(),false);
        assertEquals("passed",result.getAsJsonArray("cases").get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("passed",result.getAsJsonArray("cases").get(1).getAsJsonObject().get("status").getAsString());
        String fp=ModelProbe.fingerprint(m);m.stableContextTokens=80000;assertNotEquals(fp,ModelProbe.fingerprint(m));
        fp=ModelProbe.fingerprint(m);m.maxContextTokens=50000;assertNotEquals(fp,ModelProbe.fingerprint(m));
        assertFalse(result.toString().contains("hidden"));
    }
    private static final class SyntheticClient implements LlmClient {
        public void streamChat(List<Message> request,List<JsonObject> tools,StreamHandler h){
            String text=request.get(0).content;
            String code=text.replaceAll("(?s).*code=([a-z0-9]+).*","$1");
            if(tools!=null&&!tools.isEmpty()){ToolCall c=new ToolCall();c.id="x";c.name="probe_echo";c.arguments="{\"code\":\""+code+"\"}";h.onFinish("tool_calls",new Usage(),Collections.singletonList(c));}
            else{String answer=text.replaceAll("(?s).*\\\"code\\\":\\\"([a-z0-9]+)\\\".*","$1");h.onContent("{\"code\":\""+answer+"\"}");h.onFinish("stop",new Usage(),Collections.emptyList());}
        }
        public String completeChat(List<Message> m,String prompt){return "";}
    }
}
