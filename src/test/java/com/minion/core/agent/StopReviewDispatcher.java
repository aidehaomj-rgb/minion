package com.minion.core.agent;

import com.google.gson.*;
import okhttp3.mockwebserver.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** 真实HTTP旧用例独立提供核验响应，不消耗执行流的脚本响应。 */
public final class StopReviewDispatcher extends Dispatcher {
    private final BlockingQueue<MockResponse> streams=new LinkedBlockingQueue<MockResponse>();
    public void enqueue(MockResponse response){streams.add(response);}
    @Override public MockResponse dispatch(RecordedRequest request) {
        JsonObject body=JsonParser.parseString(request.getBody().clone().readUtf8()).getAsJsonObject();
        if(body.has("stream")&&!body.get("stream").getAsBoolean()) {
            JsonArray messages=body.getAsJsonArray("messages");JsonObject input=JsonParser.parseString(messages.get(messages.size()-1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
            JsonObject facts=input.getAsJsonObject("facts");String candidate=input.get("candidate").getAsString();
            String status=LegacyStopFixtures.status(candidate,facts.get("toolsUsed").getAsBoolean(),facts.get("structured").getAsBoolean());
            JsonObject decision=new JsonObject();for(String key:new String[]{"taskId","version","ticket"})decision.add(key,input.get(key));
            decision.addProperty("status",status);decision.addProperty("kind",facts.get("toolsUsed").getAsBoolean()?"task":"answer");decision.addProperty("reason","HTTP核验: "+status);decision.addProperty("nextStep","核对实际文件");
            JsonArray evidence=new JsonArray();evidence.add("reply");decision.add("evidence",evidence);
            for(JsonElement item:input.getAsJsonArray("receipts")){JsonObject receipt=item.getAsJsonObject();if(receipt.get("ok").getAsBoolean()){evidence.add(receipt.get("ref"));break;}}
            JsonObject root=new JsonObject(),choice=new JsonObject(),message=new JsonObject();JsonArray choices=new JsonArray();
            message.addProperty("content",decision.toString());choice.add("message",message);choice.addProperty("finish_reason","stop");choices.add(choice);root.add("choices",choices);
            return new MockResponse().setHeader("Content-Type","application/json").setBody(root.toString());
        }
        MockResponse response=streams.poll();return response==null?new MockResponse().setResponseCode(500).setBody("No scripted stream"):response;
    }
}
