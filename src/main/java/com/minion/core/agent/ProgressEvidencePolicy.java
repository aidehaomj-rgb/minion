package com.minion.core.agent;

import com.google.gson.*;
import java.util.*;

/** Shared progress accounting, not an evidence filter: raw receipts remain unchanged. */
final class ProgressEvidencePolicy {
    private ProgressEvidencePolicy() {}
    static boolean controlOnly(String tool,String args) {
        if(tool==null||Arrays.asList("TodoWrite","AgentControl","Context","Skill","AskUserQuestion","task","DiscoverTools","RuntimeJobs").contains(tool))return true;
        if(!Arrays.asList("Process","DbMysql","DbPostgres","DbOracle","Logs","Permission","BrowserAction").contains(tool))return false;
        try {
            String action=JsonParser.parseString(args).getAsJsonObject().get("action").getAsString().trim().toLowerCase(Locale.ROOT);
            if("Process".equals(tool))return Arrays.asList("list","status","wait","output").contains(action);
            if(Arrays.asList("DbMysql","DbPostgres","DbOracle").contains(tool))return Arrays.asList("status","wait").contains(action);
            if("Logs".equals(tool))return Arrays.asList("status","tail").contains(action);
            if("Permission".equals(tool))return Arrays.asList("status","audit").contains(action);
            return Arrays.asList("status","wait","wait_selector","network_idle","download_status").contains(action);
        }catch(RuntimeException malformed){return true;}
    }
    static String stableOutput(String tool,String output) {
        if("Diagnostics".equals(tool))return String.valueOf(output)
                .replaceAll("(?m)^时间: [^\\r\\n]*(?:\\r?\\n|$)","")
                .replaceAll("(?m)^报告已导出: [^\\r\\n]*(?:\\r?\\n|$)","").trim();
        if(!"Build".equals(tool)&&!"ArtifactVerification".equals(tool))return String.valueOf(output);
        try {
            JsonObject raw=JsonParser.parseString(output).getAsJsonObject(),stable=new JsonObject();
            for(String key:new String[]{"type","kind","action","status","scope","sourceDigest","sourceDigestAfter","tests","failures","errors","skipped","path","sha256","url","bodySha256","statusCode"})
                if(raw.has(key))stable.add(key,raw.get(key));
            if("ArtifactVerification".equals(tool)&&raw.has("facts"))stable.add("facts",artifactFacts(raw.get("facts")));
            // UUIDs, report/log timestamps and durations alone are not business progress.
            return stable.toString();
        }catch(RuntimeException unknown){return String.valueOf(output);}
    }
    private static JsonElement artifactFacts(JsonElement facts) {
        if(facts.isJsonArray()) {
            JsonArray result=new JsonArray();for(JsonElement item:facts.getAsJsonArray())result.add(artifactFacts(item));return result;
        }
        if(!facts.isJsonObject())return facts;
        JsonObject raw=facts.getAsJsonObject(),stable=new JsonObject();
        for(String key:new String[]{"path","sha256","size","url","httpStatus","bytes","scope","status",
                "expectedHashMatched","expectedTextMatched","browserVerified","interactionVerified","browserHttpResponseObserved",
                "text","selector","frameSelector","format","width","height","platform","architecture",
                "cell","sheet","actualValue","formula","formulaCache","textMatched","valueMatched","formulaCacheMatched"})
            if(raw.has(key))stable.add(key,raw.get(key));
        for(String key:new String[]{"http","image","executable","checks","interaction","office","screenshot"})
            if(raw.has(key))stable.add(key,artifactFacts(raw.get(key)));
        return stable;
    }
}
