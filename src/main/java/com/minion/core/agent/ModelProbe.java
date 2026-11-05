package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.ModelConfig;
import com.minion.core.llm.*;
import java.util.*;
import java.util.concurrent.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** 仅用户显式触发的合成探测；不发项目文件、不自动重配上下文。 */
public final class ModelProbe {
    public static String fingerprint(ModelConfig m){return StopGate.digest(m.url+"\n"+StopGate.digest(String.valueOf(m.apiKey))+"\n"+m.modelName+"\n"+m.provider+"\n"+m.sessionId+"\n"+m.thinking+"\n"+m.reasoningEffort+"\n"+m.maxOutputTokens+"\n"+m.maxContextTokens+"\n"+m.compressThreshold+"\n"+m.stableContextTokens+"\n"+m.keepRecentMessages);}
    public static final class Response {
        public String text="",finish;
        public java.util.List<ToolCall> calls=new ArrayList<ToolCall>();
        public Usage usage;
        public long millis;
        public boolean streamObserved,usageObserved;
    }
    public static Response request(LlmClient client,java.util.List<Message> messages,java.util.List<JsonObject> tools)throws Exception {
        return request(client,messages,tools,false);
    }
    private static Response request(LlmClient client,java.util.List<Message> messages,java.util.List<JsonObject> tools,boolean structured)throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"minion-manual-probe");t.setDaemon(true);return t;});
        long start=System.nanoTime();Response result=new Response();StringBuilder body=new StringBuilder();
        final LlmException[] error=new LlmException[1];
        Future<?> future=executor.submit(()->{
            StreamHandler sink=new StreamHandler(){
                public void onContent(String d){body.append(d);}
                public boolean shouldStopResponse(){return body.length()>12000;}
                public void onError(LlmException e){error[0]=e;}
                public void onFinish(String reason,Usage usage,java.util.List<ToolCall> calls){result.finish=reason;result.usage=usage;if(calls!=null)result.calls=calls;}
            };
            try {
                if(structured)((DeepSeekClient)client).probeStructuredOutput(messages,sink);
                else client.streamChatWithoutThinking(messages,tools,sink);
            } catch(LlmException e){throw new CompletionException(e);}
        });
        try {
            try { future.get(35,TimeUnit.SECONDS); }
            catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof CompletionException)cause=cause.getCause();if(cause instanceof Exception)throw (Exception)cause;throw e;}
            if(error[0]!=null)throw error[0];
            if(body.length()>12000)throw new IllegalStateException("探测回复过长");
            if(!"stop".equals(result.finish)&&!"tool_calls".equals(result.finish))
                throw new IllegalStateException("探测未完整结束");
            result.text=body.toString();result.millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            if(client instanceof DeepSeekClient) {
                java.util.List<JsonObject> metrics=((DeepSeekClient)client).telemetry().recent();
                if(!metrics.isEmpty()) {
                    JsonObject metric=metrics.get(metrics.size()-1);
                    result.streamObserved=metric.get("sseObserved").getAsBoolean();
                    JsonObject api=metric.getAsJsonObject("apiUsage");
                    result.usageObserved=api.get("inputTokens").getAsInt()>=0&&api.get("outputTokens").getAsInt()>=0;
                }
            }
            return result;
        } finally {future.cancel(true);client.cancel();client.close();executor.shutdownNow();}
    }
    public static JsonObject run(ModelConfig model,java.util.function.Function<ModelConfig,LlmClient> factory,boolean image)throws Exception {
        if("sfm-agent".equalsIgnoreCase(model.provider))throw new IllegalArgumentException("远端有状态Agent不能隔离探测；未发送合成任务到执行会话");
        JsonObject result=new JsonObject();long now=System.currentTimeMillis();
        result.addProperty("fingerprint",fingerprint(model));result.addProperty("model",model.displayName);result.addProperty("time",now);
        result.addProperty("configuredContext",model.maxContextTokens);
        result.addProperty("contextStatus","unknown: 合成小请求不能证明服务端最大上下文");
        JsonArray cases=new JsonArray();
        EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State> states=new EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State>(ModelCapabilityProfile.Capability.class);
        for(ModelCapabilityProfile.Capability c:ModelCapabilityProfile.Capability.values())states.put(c,ModelCapabilityProfile.State.UNKNOWN);
        String token=UUID.randomUUID().toString().substring(0,8);ModelConfig small=model.copy();small.thinking=false;small.maxOutputTokens=1024;
        java.util.List<Message> jsonPrompt=Collections.singletonList(Message.user("只返回JSON对象{\"code\":\""+token+"\"}"));
        JsonObject text=probeCase("结构化正文");
        try {
            Response r=request(factory.apply(small),jsonPrompt,Collections.emptyList());
            text.addProperty("status",matchesCode(r.text,token)?"passed":"failed");text.addProperty("elapsedMs",r.millis);
            observe(r,states);
        } catch(Exception e){failure(text,e,null,states);}cases.add(text);
        JsonObject schema=JsonParser.parseString("{\"type\":\"function\",\"function\":{\"name\":\"probe_echo\",\"description\":\"合成协议检查，不会执行\",\"parameters\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}},\"required\":[\"code\"]}}}").getAsJsonObject();
        JsonObject tool=probeCase("工具协议");
        try {
            Response r=request(factory.apply(small),Collections.singletonList(Message.user("只调用probe_echo，code="+token)),Collections.singletonList(schema));
            boolean ok=r.calls.size()==1&&r.calls.get(0).id!=null&&!r.calls.get(0).id.isEmpty()
                    &&"probe_echo".equals(r.calls.get(0).name)&&matchesCode(r.calls.get(0).arguments,token);
            tool.addProperty("status",ok?"passed":"failed");tool.addProperty("elapsedMs",r.millis);observe(r,states);
            if(ok)states.put(ModelCapabilityProfile.Capability.TOOLS,ModelCapabilityProfile.State.SUPPORTED);
        } catch(Exception e){failure(tool,e,ModelCapabilityProfile.Capability.TOOLS,states);}cases.add(tool);
        // 提示词 JSON 不是接口结构化能力。只有真实发送 response_format 的客户端才能探测此项。
        LlmClient structuredClient=factory.apply(small);
        JsonObject structured=probeCase("接口结构化输出(json_object，非JSON Schema)");
        if(structuredClient instanceof DeepSeekClient) {
            try {
                Response r=request(structuredClient,jsonPrompt,Collections.emptyList(),true);boolean ok=matchesCode(r.text,token);
                structured.addProperty("status",ok?"passed":"failed");structured.addProperty("elapsedMs",r.millis);observe(r,states);
                if(ok)states.put(ModelCapabilityProfile.Capability.STRUCTURED_OUTPUT,ModelCapabilityProfile.State.SUPPORTED);
            } catch(Exception e){failure(structured,e,ModelCapabilityProfile.Capability.STRUCTURED_OUTPUT,states);}
        } else {structuredClient.close();structured.addProperty("note","client_probe_not_available");}
        cases.add(structured);
        if(image) {
            String visual=UUID.randomUUID().toString().substring(0,5).toUpperCase(Locale.ROOT);
            BufferedImage png=new BufferedImage(350,100,BufferedImage.TYPE_INT_RGB);Graphics2D g=png.createGraphics();
            try {g.setColor(Color.WHITE);g.fillRect(0,0,350,100);g.setColor(Color.BLACK);g.setFont(new Font("SansSerif",Font.BOLD,48));g.drawString(visual,30,65);}finally{g.dispose();}
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(png,"png",bytes);
            ImagePart part=new ImagePart();part.mime="image/png";part.name="synthetic.png";part.base64=Base64.getEncoder().encodeToString(bytes.toByteArray());
            Message msg=Message.user("读图中五位字符，只返回字符，不能猜测。");msg.images=Collections.singletonList(part);JsonObject v=probeCase("合成图片识别");
            try {
                Response r=request(factory.apply(small),Collections.singletonList(msg),Collections.emptyList());boolean ok=visual.equals(r.text.trim());
                v.addProperty("status",ok?"passed":"failed");v.addProperty("elapsedMs",r.millis);observe(r,states);
                if(ok)states.put(ModelCapabilityProfile.Capability.VISION,ModelCapabilityProfile.State.SUPPORTED);
            } catch(Exception e){failure(v,e,ModelCapabilityProfile.Capability.VISION,states);}cases.add(v);
        }
        ModelCapabilityProfile profile=new ModelCapabilityProfile(fingerprint(model),fingerprint(small),now,
                now+ModelCapabilityProfile.DEFAULT_TTL_MS,0,states);
        result.add("profile",profile.describe(now));result.add("cases",cases);return result;
    }
    /** 从本次探测报告重建白名单档案，不把自由文本/接口凭据写入档案。 */
    public static ModelCapabilityProfile profile(JsonObject report) {
        JsonObject o=report.getAsJsonObject("profile");
        EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State> states=new EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State>(ModelCapabilityProfile.Capability.class);
        for(ModelCapabilityProfile.Capability c:ModelCapabilityProfile.Capability.values())
            states.put(c,ModelCapabilityProfile.State.valueOf(o.getAsJsonObject("capabilities").get(c.name()).getAsString()));
        return new ModelCapabilityProfile(o.get("fingerprint").getAsString(),o.get("probeFingerprint").getAsString(),
                o.get("observedAt").getAsLong(),o.get("expiresAt").getAsLong(),0,states);
    }
    private static JsonObject probeCase(String name){JsonObject o=new JsonObject();o.addProperty("name",name);o.addProperty("status","unknown");return o;}
    private static boolean matchesCode(String json,String token) {
        try {JsonObject o=JsonParser.parseString(json).getAsJsonObject();return o.has("code")&&token.equals(o.get("code").getAsString());}
        catch(RuntimeException invalid){return false;}
    }
    private static void observe(Response r,EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State> states) {
        if(r.streamObserved)states.put(ModelCapabilityProfile.Capability.STREAMING,ModelCapabilityProfile.State.SUPPORTED);
        if(r.usageObserved)states.put(ModelCapabilityProfile.Capability.USAGE,ModelCapabilityProfile.State.SUPPORTED);
    }
    private static void failure(JsonObject item,Exception error,ModelCapabilityProfile.Capability capability,
                                EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State> states) {
        item.addProperty("status","unknown");item.addProperty("note",error.getClass().getSimpleName());
        if(error instanceof InterruptedException){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("探测被中断");}
        if(!(error instanceof LlmException))return;
        LlmException e=(LlmException)error;LlmRecovery.Category category=LlmRecovery.classify(e);item.addProperty("errorCategory",category.name());
        String detail=String.valueOf(e.body).toLowerCase(Locale.ROOT);
        boolean unsupported=(e.httpCode==400||e.httpCode==422)&&(detail.contains("unsupported")||detail.contains("not support")||detail.contains("不支持"));
        boolean rejected=capability==ModelCapabilityProfile.Capability.VISION&&category==LlmRecovery.Category.IMAGE_REJECTED;
        rejected|=unsupported&&capability==ModelCapabilityProfile.Capability.TOOLS&&(detail.contains("tools")||detail.contains("tool calling"));
        rejected|=unsupported&&capability==ModelCapabilityProfile.Capability.STRUCTURED_OUTPUT&&detail.contains("response_format");
        if(rejected){states.put(capability,ModelCapabilityProfile.State.UNSUPPORTED);item.addProperty("status","unsupported");}
    }
    public static JsonObject evaluate(ModelConfig model,JsonArray samples,java.util.function.Function<ModelConfig,LlmClient> factory)throws Exception {
        if(samples.size()<1||samples.size()>20)throw new IllegalArgumentException("评测需要1-20个经你确认的匿名样本");JsonObject report=new JsonObject();report.addProperty("fingerprint",fingerprint(model));report.addProperty("model",model.displayName);report.addProperty("time",System.currentTimeMillis());JsonArray records=new JsonArray();int passed=0,input=0,output=0,reportedInput=0,reportedOutput=0,estimatedInput=0,estimatedOutput=0;long elapsed=0;
        ModelConfig small=model.copy();small.maxOutputTokens=1024;
        for(JsonElement item:samples){JsonObject s=item.getAsJsonObject();String prompt=s.get("prompt").getAsString(),expected=s.get("expected").getAsString();if(prompt.length()>8000||expected.length()>4000)throw new IllegalArgumentException("匿名样本过长");JsonObject r=new JsonObject();r.addProperty("id",AcceptanceRules.str(s,"id"));
            try{Response answer=request(factory.apply(small),Collections.singletonList(Message.user(prompt)),Collections.emptyList());boolean ok="contains".equals(AcceptanceRules.str(s,"match"))?answer.text.contains(expected):answer.text.trim().equals(expected);r.addProperty("status",ok?"passed":"failed");r.addProperty("response",answer.text);r.addProperty("elapsedMs",answer.millis);elapsed+=answer.millis;if(ok)passed++;if(answer.usage!=null){input+=answer.usage.inputTokens;output+=answer.usage.outputTokens;r.addProperty("usageEstimated",answer.usage.estimated);if(answer.usage.estimated){estimatedInput+=answer.usage.inputTokens;estimatedOutput+=answer.usage.outputTokens;}else{reportedInput+=answer.usage.inputTokens;reportedOutput+=answer.usage.outputTokens;}}}
            catch(Exception e){r.addProperty("status","unknown");r.addProperty("note",e.getClass().getSimpleName());}records.add(r);
        }report.addProperty("passed",passed);report.addProperty("total",samples.size());report.addProperty("inputTokens",input);report.addProperty("outputTokens",output);report.addProperty("reportedInputTokens",reportedInput);report.addProperty("reportedOutputTokens",reportedOutput);report.addProperty("estimatedOrMixedInputTokens",estimatedInput);report.addProperty("estimatedOrMixedOutputTokens",estimatedOutput);report.addProperty("elapsedMs",elapsed);report.add("records",records);return report;
    }
}
