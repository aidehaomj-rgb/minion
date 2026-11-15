package com.minion.core.llm;

import com.google.gson.*;
import com.minion.core.agent.ModelProbe;
import com.minion.core.config.ModelConfig;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.minion.core.llm.ModelCapabilityProfile.Capability.*;
import static com.minion.core.llm.ModelCapabilityProfile.State.*;

/** 仅本机协议模拟/离线档案，不能冒充真实内网模型能力验收。 */
public class ModelCapabilitiesBatchETest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private ModelConfig config() {
        ModelConfig m=new ModelConfig();m.url="http://127.0.0.1:9/v1/chat/completions";
        m.apiKey="secret-never-store";m.modelName="same-model";m.displayName="test";
        m.provider="deepseek";m.thinking=true;m.reasoningEffort="high";m.maxOutputTokens=8192;
        m.maxContextTokens=1000000;m.stableContextTokens=150000;m.compressThreshold=0.8;m.keepRecentMessages=8;return m;
    }
    private DeepSeekClient client(ModelConfig m) {
        return new DeepSeekClient(m.url,m.apiKey,m.modelName,m.thinking,m.reasoningEffort,m.provider,m.maxOutputTokens,m.sessionId);
    }
    @Test public void fingerprintBindsEveryInterfaceSettingButNotDisplayName() {
        ModelConfig m=config();String fp=ModelProbe.fingerprint(m);assertEquals(64,fp.length());
        m.displayName="renamed";assertEquals(fp,ModelProbe.fingerprint(m));
        ModelConfig x=m.copy();x.url+="/other";assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.apiKey="rotated";assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.modelName="other";assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.provider="qwen";assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.sessionId="other-session";assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.thinking=false;assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.reasoningEffort="low";assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.maxOutputTokens++;assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.maxContextTokens++;assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.compressThreshold=0.7;assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.stableContextTokens++;assertNotEquals(fp,ModelProbe.fingerprint(x));
        x=m.copy();x.keepRecentMessages++;assertNotEquals(fp,ModelProbe.fingerprint(x));
    }
    @Test public void missingProfileIsUnknownAndCreatesNothing() throws Exception {
        Path dir=temp.getRoot().toPath().resolve("not-created");
        ModelCapabilityProfile p=new ModelCapabilityStore(dir).load(ModelProbe.fingerprint(config()));
        for(ModelCapabilityProfile.Capability c:ModelCapabilityProfile.Capability.values())assertEquals(UNKNOWN,p.state(c,System.currentTimeMillis()));
        assertFalse(Files.exists(dir));
    }
    @Test public void expiresInvalidatesAndStoresNoCredentialsOrAddress() throws Exception {
        ModelConfig m=config();long now=System.currentTimeMillis();String fp=ModelProbe.fingerprint(m);
        EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State> states=new EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State>(ModelCapabilityProfile.Capability.class);
        states.put(TOOLS,SUPPORTED);states.put(VISION,UNSUPPORTED);
        ModelCapabilityStore store=new ModelCapabilityStore(temp.getRoot().toPath());
        store.save(new ModelCapabilityProfile(fp,fp,now,now+100,0,states));
        ModelCapabilityProfile p=store.load(fp);assertEquals(SUPPORTED,p.state(TOOLS,now));assertEquals(UNKNOWN,p.state(USAGE,now));
        assertEquals(UNKNOWN,p.state(TOOLS,now+100));assertEquals(UNKNOWN,p.state(TOOLS,now-1));
        String saved=new String(Files.readAllBytes(temp.getRoot().toPath().resolve(fp+".json")),StandardCharsets.UTF_8);
        assertFalse(saved.contains(m.apiKey));assertFalse(saved.contains(m.url));
        store.invalidate(fp,now+1);assertEquals(UNKNOWN,store.load(fp).state(TOOLS,now+2));
        m.apiKey="new-secret";assertEquals(UNKNOWN,store.load(ModelProbe.fingerprint(m)).state(TOOLS,now));
    }
    @Test public void corruptOrFutureSchemaIsUnknown() throws Exception {
        String fp=ModelProbe.fingerprint(config());Path p=temp.getRoot().toPath().resolve(fp+".json");
        ModelCapabilityStore store=new ModelCapabilityStore(temp.getRoot().toPath());
        Files.write(p,"not json".getBytes(StandardCharsets.UTF_8));assertEquals(UNKNOWN,store.load(fp).state(TOOLS,1));
        Files.write(p,"{\"schemaVersion\":99}".getBytes(StandardCharsets.UTF_8));assertEquals(UNKNOWN,store.load(fp).state(TOOLS,1));
    }
    @Test(expected=IllegalArgumentException.class) public void unsafeFingerprintIsRejected() throws Exception {
        new ModelCapabilityStore(temp.getRoot().toPath()).load("../credentials");
    }
    private static class Echo implements LlmClient {
        public void streamChat(List<Message> messages,List<JsonObject> tools,StreamHandler h) {
            String text=messages.get(0).content;
            if(tools!=null&&!tools.isEmpty()) {
                String code=text.substring(text.indexOf("code=")+5);ToolCall c=new ToolCall();c.id="id";c.name="probe_echo";c.arguments="{\"code\":\""+code+"\"}";
                h.onFinish("tool_calls",new Usage(),Collections.singletonList(c));
            } else {h.onContent(text.substring(text.indexOf('{')));h.onFinish("stop",new Usage(),Collections.emptyList());}
        }
        public String completeChat(List<Message> m,String p){return "";}
    }
    @Test public void promptJsonAndCallbacksDoNotProveStructuredApiStreamingOrUsage() throws Exception {
        JsonObject report=ModelProbe.run(config(),m->new Echo(),false);ModelCapabilityProfile p=ModelProbe.profile(report);long now=System.currentTimeMillis();
        assertEquals(SUPPORTED,p.state(TOOLS,now));assertEquals(UNKNOWN,p.state(STRUCTURED_OUTPUT,now));
        assertEquals(UNKNOWN,p.state(STREAMING,now));assertEquals(UNKNOWN,p.state(USAGE,now));assertEquals(UNKNOWN,p.state(VISION,now));
        assertNotEquals(p.fingerprint,p.probeFingerprint);assertFalse(report.toString().contains(config().apiKey));
    }
    @Test public void actualWireProbeUsesSameModelAndSyntheticDataAndStoresScope() throws Exception {
        MockWebServer server=new MockWebServer();final List<JsonObject> bodies=Collections.synchronizedList(new ArrayList<JsonObject>());
        server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request) {
            JsonObject b=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();bodies.add(b);
            String prompt=b.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            JsonObject delta=new JsonObject();String finish="stop";
            if(b.has("tools")) {
                String code=prompt.substring(prompt.indexOf("code=")+5);
                delta=JsonParser.parseString("{\"tool_calls\":[{\"index\":0,\"id\":\"probe-id\",\"function\":{\"name\":\"probe_echo\",\"arguments\":\"{\\\"code\\\":\\\""+code+"\\\"}\"}}]}").getAsJsonObject();finish="tool_calls";
            } else delta.addProperty("content",prompt.substring(prompt.indexOf('{')));
            return sse(delta,finish,"{\"prompt_tokens\":30,\"completion_tokens\":12}");
        }});
        server.start();try {
            ModelConfig m=config();m.url=server.url("/chat").toString();String before=ModelProbe.fingerprint(m);
            JsonObject report=ModelProbe.run(m,this::client,false);ModelCapabilityProfile p=ModelProbe.profile(report);long now=System.currentTimeMillis();
            assertEquals(3,server.getRequestCount());assertEquals(SUPPORTED,p.state(TOOLS,now));assertEquals(SUPPORTED,p.state(STRUCTURED_OUTPUT,now));
            assertEquals(SUPPORTED,p.state(STREAMING,now));assertEquals(SUPPORTED,p.state(USAGE,now));assertEquals(UNKNOWN,p.state(VISION,now));
            assertEquals(before,ModelProbe.fingerprint(m));assertEquals(150000,m.stableContextTokens);assertTrue(m.thinking);
            for(JsonObject b:bodies){assertEquals(m.modelName,b.get("model").getAsString());assertEquals(1024,b.get("max_tokens").getAsInt());assertEquals("disabled",b.getAsJsonObject("thinking").get("type").getAsString());}
            assertEquals("json_object",bodies.get(2).getAsJsonObject("response_format").get("type").getAsString());
            assertFalse(report.toString().contains(m.apiKey));assertFalse(report.toString().contains(m.url));
        } finally {server.shutdown();}
    }
    @Test public void transportFailuresStayUnknownAndDoNotSerializeErrorBodies() throws Exception {
        JsonObject report=ModelProbe.run(config(),m->new Echo(){@Override public void streamChat(List<Message> a,List<JsonObject> b,StreamHandler h){h.onError(new LlmException(LlmException.Type.NETWORK,"private-body",true));}},false);
        for(ModelCapabilityProfile.Capability c:ModelCapabilityProfile.Capability.values())assertEquals(UNKNOWN,ModelProbe.profile(report).state(c,System.currentTimeMillis()));
        assertFalse(report.toString().contains("private-body"));
    }
    @Test public void explicitParameterRejectionIsUnsupportedButBadAnswerIsUnknown() throws Exception {
        MockWebServer server=new MockWebServer();server.start();try {
            server.enqueue(sse(JsonParser.parseString("{\"content\":\"wrong\"}").getAsJsonObject(),"stop",null));
            server.enqueue(new MockResponse().setResponseCode(400).setBody("tools are not supported"));
            server.enqueue(new MockResponse().setResponseCode(400).setBody("response_format is not supported"));
            server.enqueue(new MockResponse().setResponseCode(400).setBody("image_url is not supported"));
            ModelConfig m=config();m.url=server.url("/").toString();ModelCapabilityProfile p=ModelProbe.profile(ModelProbe.run(m,this::client,true));
            long now=System.currentTimeMillis();assertEquals(UNSUPPORTED,p.state(TOOLS,now));assertEquals(UNSUPPORTED,p.state(STRUCTURED_OUTPUT,now));
            assertEquals(UNSUPPORTED,p.state(VISION,now));assertEquals(UNKNOWN,p.state(USAGE,now));assertEquals(SUPPORTED,p.state(STREAMING,now));
            assertEquals(4,server.getRequestCount());
        } finally {server.shutdown();}
    }
    @Test public void statefulGatewayProbeIsRejectedBeforeConstructingClient() throws Exception {
        ModelConfig m=config();m.provider="sfm-agent";AtomicInteger calls=new AtomicInteger();
        try {ModelProbe.run(m,c->{calls.incrementAndGet();return new Echo();},true);fail();}catch(IllegalArgumentException expected){}
        assertEquals(0,calls.get());
    }
    static MockResponse sse(JsonObject delta,String finish,String usage) {
        String body="data: {\"choices\":[{\"delta\":"+delta+",\"finish_reason\":\""+finish+"\"}]}\n\n";
        if(usage!=null)body+="data: {\"choices\":[],\"usage\":"+usage+"}\n\n";
        return new MockResponse().setHeader("Content-Type","text/event-stream").setBody(body+"data: [DONE]\n\n");
    }
}
