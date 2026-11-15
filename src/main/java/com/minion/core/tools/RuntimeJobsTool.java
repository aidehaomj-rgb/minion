package com.minion.core.tools;

import com.google.gson.*;
import com.minion.core.agent.RuntimeJobRecovery;

/** Host registers on MAIN agent only. No write/retry/cancel/acknowledge action is exposed. */
public final class RuntimeJobsTool implements Tool {
    public interface Factory { RuntimeJobRecovery create() throws Exception; }
    private final Factory factory;
    /** Compatibility constructor. This tool never closes the host-owned registry. */
    public RuntimeJobsTool(RuntimeJobRecovery recovery){
        java.util.Objects.requireNonNull(recovery,"recovery");this.factory=()->recovery;
    }
    /** Construction, schema and discovery do not invoke factory or access the ledger. */
    public RuntimeJobsTool(Factory factory){this.factory=java.util.Objects.requireNonNull(factory,"factory");}
    public static void registerForMainAgent(ToolRegistry registry,RuntimeJobRecovery recovery){
        registry.register(new RuntimeJobsTool(recovery));
    }
    public static void registerForMainAgent(ToolRegistry registry,Factory factory){
        registry.register(new RuntimeJobsTool(factory));
    }
    public String name(){return "RuntimeJobs";}
    public String description(){return "Read-only runtime jobs: list(offset/limit, newest first, metadata only), inspect, reconcile authorized existing resultPath hashes and ledger external IDs. Audit bodies omitted from model context. Never reconnect, retry, cancel or promote UNKNOWN to completed.";}
    public JsonObject schema(){
        JsonObject s=SchemaGenerator.objectSchema(description(),new String[]{"action","jobId","previousSha256","expectedExternalId"},new String[]{"action"});
        JsonArray values=new JsonArray();for(String a:new String[]{"list","inspect","reconcile"})values.add(a);
        s.getAsJsonObject("properties").getAsJsonObject("action").add("enum",values);
        for(String key:new String[]{"offset","limit"}){JsonObject n=new JsonObject();n.addProperty("type","integer");n.addProperty("minimum","offset".equals(key)?0:1);if("limit".equals(key))n.addProperty("maximum",50);s.getAsJsonObject("properties").add(key,n);}
        s.addProperty("additionalProperties",false);return s;
    }
    public ToolResult execute(JsonObject args){
        try {
            for(String key:args.keySet())if(!java.util.Arrays.asList("action","jobId","previousSha256","expectedExternalId","offset","limit").contains(key))
                throw new IllegalArgumentException("unsupported argument: "+key);
            String action=text(args,"action");
            if(!java.util.Arrays.asList("list","inspect","reconcile").contains(action))
                throw new IllegalArgumentException("action must be list/inspect/reconcile");
            String id=text(args,"jobId"),previous=text(args,"previousSha256"),external=text(args,"expectedExternalId");
            if(!"list".equals(action)&&(id==null||id.trim().isEmpty()))throw new IllegalArgumentException("jobId required");
            if(previous!=null&&!previous.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid previousSha256");
            int offset=number(args,"offset",0),limit=number(args,"limit",20);
            if(limit<1||limit>50)throw new IllegalArgumentException("limit must be 1..50");
            if(!"list".equals(action)&&(args.has("offset")||args.has("limit")))throw new IllegalArgumentException("pagination applies to list only");
            return executeRead(action,id,previous,external,offset,limit);
        }catch(RuntimeException e){return ToolResult.errorWithoutSideEffects("RuntimeJobs rejected request: "+e.getMessage());}
    }
    private ToolResult executeRead(String action,String id,String previous,String external,int offset,int limit){
        try {
            RuntimeJobRecovery recovery=java.util.Objects.requireNonNull(factory.create(),"factory returned null");JsonObject out;
            if("list".equals(action)){
                java.util.List<com.minion.core.agent.RuntimeJobRegistry.JobRecord> all=recovery.list();java.util.Collections.reverse(all);
                int start=Math.min(offset,all.size()),end=Math.min(all.size(),start+limit);JsonArray rows=new JsonArray();
                for(int i=start;i<end;i++)rows.add(summary(all.get(i)));
                out=new JsonObject();out.addProperty("total",all.size());out.addProperty("nextOffset",end);out.addProperty("historyOmitted",true);out.add("jobs",rows);
            }else{
                RuntimeJobRecovery.Report report="inspect".equals(action)?recovery.inspect(id):recovery.reconcile(id,previous,external);
                com.minion.core.agent.RuntimeJobRegistry.JobRecord job=report.job;report.job=null;
                out=new Gson().toJsonTree(report).getAsJsonObject();out.add("job",summary(job));
            }
            return ToolResult.success(out.toString());
        }catch(Exception e){
            // Host lazy initialization may itself persist restart fences; do not claim no side effects.
            return ToolResult.error("RuntimeJobs unavailable: "+e.getMessage());
        }
    }
    private static JsonObject summary(com.minion.core.agent.RuntimeJobRegistry.JobRecord r){
        com.minion.core.agent.RuntimeJobRegistry.JobRecord v=new com.minion.core.agent.RuntimeJobRegistry.JobRecord();
        v.jobId=r.jobId;v.taskId=r.taskId;v.revision=r.revision;v.attempt=r.attempt;v.version=r.version;v.kind=r.kind;
        v.ownerEpoch=r.ownerEpoch;v.status=r.status;v.resultPath=r.resultPath;v.externalInstanceId=r.externalInstanceId;
        v.createdAt=r.createdAt;v.updatedAt=r.updatedAt;v.finishedAt=r.finishedAt;v.exitCode=r.exitCode;
        v.fenced=r.fenced;v.cancelRequested=r.cancelRequested;v.recovered=r.recovered;v.waitForCompletion=r.waitForCompletion;
        v.description=bounded(r.description,1000);v.detail=bounded(r.detail,1500);v.fenceReason=bounded(r.fenceReason,1000);v.lateResult=r.lateResult;
        JsonObject out=new Gson().toJsonTree(v).getAsJsonObject();out.remove("history");out.remove("recoveryReviews");
        out.addProperty("historyEntryCount",r.history==null?0:r.history.size());out.addProperty("historyOmitted",true);
        out.addProperty("recoveryReviewCount",r.recoveryReviews==null?0:r.recoveryReviews.size());
        out.addProperty("metadataExcerpt",true);return out;
    }
    private static String bounded(String text,int max){return text==null?null:text.length()<=max?text:text.substring(0,max)+"[excerpt; full data in original ledger]";}
    private static int number(JsonObject args,String key,int fallback){
        if(!args.has(key))return fallback;JsonElement n=args.get(key);
        if(!n.isJsonPrimitive()||!n.getAsJsonPrimitive().isNumber()||!n.getAsString().matches("[0-9]+"))throw new IllegalArgumentException(key+" must be a nonnegative integer");
        return Integer.parseInt(n.getAsString());
    }
    private static String text(JsonObject a,String k){
        if(!a.has(k)||a.get(k).isJsonNull())return null;
        if(!a.get(k).isJsonPrimitive()||!a.getAsJsonPrimitive(k).isString())throw new IllegalArgumentException(k+" must be string");
        return a.get(k).getAsString();
    }
}
