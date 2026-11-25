package com.minion.core.agent;

import com.minion.core.tools.ToolArguments;

import com.google.gson.*;
import com.minion.core.tools.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Lazy per-task/revision adapter. Coordinator decisions alone never count as parent verification.
 * No model, filesystem scan, timer or restored job is started until a structured task is requested. */
public final class ChildTaskHost implements AutoCloseable {
    private final TaskState state;
    private final Path root;
    private final String sessionId;
    private final String taskId;
    private final long revision;
    private final ChildTaskCoordinator coordinator;
    private final Runnable save;
    private final Gson gson=new Gson();
    private boolean recovered, closed;

    public ChildTaskHost(Path root,String sessionId,TaskState state,int capacity,
                         ChildTaskCoordinator.Runner runner,Runnable save)throws Exception {
        this.state=state;this.taskId=state.id;this.revision=state.revision();this.save=save;this.root=root;this.sessionId=sessionId;
        coordinator=new ChildTaskCoordinator(root,taskId,revision,capacity,runner);
        Path journal=root.resolve(".minion/child-coordination").resolve(component(sessionId))
                .resolve(component(taskId)+"-r"+revision+".json");
        try{if(Files.exists(journal)){coordinator.restoreFromFile(journal);recovered=true;}else coordinator.journal(journal);}
        catch(Exception e){coordinator.close();throw e;}
    }
    private static String component(String s)throws IOException {
        if(s==null||!s.matches("[A-Za-z0-9_-]{1,128}"))throw new IOException("协调记录ID非法");return s;
    }
    public ChildTaskCoordinator coordinator(){return coordinator;}
    public boolean matches(){synchronized(state){return Objects.equals(taskId,state.id)&&revision==state.revision();}}
    public ToolResult submit(JsonObject args){
        if(!matches()||closed)return ToolResult.errorWithoutSideEffects("子任务所属修订已失效，请重新读取当前要求");
        if(recovered)return ToolResult.errorWithoutSideEffects("已恢复协调记录，先child_status核对；child_resume_queue需明确旧执行已退出的依据，不自动重放");
        // 文案给出当前修订号：模型抄到旧修订号时能直接改成正确的值
        if(!args.has("revision")||args.get("revision").isJsonNull())return ToolResult.errorWithoutSideEffects("缺少 revision：当前任务修订号为 "+revision+"（任务状态里“指令修订: "+revision+"”）");
        if(args.get("revision").getAsLong()!=revision)return ToolResult.errorWithoutSideEffects("revision 已过期：当前任务修订号为 "+revision+"，收到 "+args.get("revision")
                +"。用户指令已变更，请按最新指令重新派发，不要把旧分工送入新指令");
        ToolResult result=coordinator.submit(args);sync();return result;
    }
    /** 批量登记：守卫与单条 submit 完全一致；整批原子，任一条非法整批不登记。 */
    public ToolResult submitBatch(JsonObject args){
        if(!matches()||closed)return ToolResult.errorWithoutSideEffects("子任务所属修订已失效，请重新读取当前要求");
        if(recovered)return ToolResult.errorWithoutSideEffects("已恢复协调记录，先child_status核对；child_resume_queue需明确旧执行已退出的依据，不自动重放");
        if(!args.has("revision")||args.get("revision").isJsonNull())return ToolResult.errorWithoutSideEffects("缺少 revision：当前任务修订号为 "+revision+"（任务状态里“指令修订: "+revision+"”）");
        if(args.get("revision").getAsLong()!=revision)return ToolResult.errorWithoutSideEffects("revision 已过期：当前任务修订号为 "+revision+"，收到 "+args.get("revision")
                +"。用户指令已变更，请按最新指令重新派发，不要把旧分工送入新指令");
        if(!args.has("assignments")||!args.get("assignments").isJsonArray())return ToolResult.errorWithoutSideEffects("批量登记需要 assignments 数组：每个元素是一条子任务分工");
        JsonArray items=args.getAsJsonArray("assignments");
        if(items.size()==0||items.size()>64)return ToolResult.errorWithoutSideEffects("assignments 须为 1..64 条分工，收到 "+items.size());
        List<JsonObject> parsed=new ArrayList<JsonObject>();
        for(JsonElement e:items){
            if(!e.isJsonObject())return ToolResult.errorWithoutSideEffects("assignments 元素必须是对象（含 stepId/description/scope/acceptance 等）");
            parsed.add(e.getAsJsonObject());
        }
        try{
            List<ChildTaskCoordinator.Record> records=coordinator.submitBatchArgs(parsed);
            sync();
            return ToolResult.success("已批量登记排队 "+records.size()+" 条（不是子任务完成），依赖就绪即并行执行，主Agent需复核：\n"+gson.toJson(records));
        }catch(Exception e){return ToolResult.errorWithoutSideEffects("子任务批量未登记: "+e.getMessage());}
    }
    public void sync(){
        ChildTaskCoordinator.Snapshot snapshot=coordinator.snapshot();
        synchronized(state){
            for(ChildTaskCoordinator.Record r:snapshot.records)if(r.childId!=null){
                TaskState.Child child=state.child(r.childId);
                if(child==null){
                    child=new TaskState.Child();child.id=r.childId;child.taskId=r.taskId;child.revision=r.revision;
                    child.coordinatedStep=r.assignment.stepId;child.coordinatedAttempt=r.attempt;child.assignment=r.assignment.description;child.mode=r.assignment.mode;
                    child.allowedTools=r.assignment.allowedTools;child.isolatedOutput=r.assignment.isolatedOutput||r.assignment.requiresMerge;
                    child.outputDir=r.executionRoot;child.transcript=root.resolve(".minion/subagents").resolve(sessionId).resolve(r.childId+".json").toString();
                    List<TaskState.Child> next=new ArrayList<TaskState.Child>(state.children);next.add(child);state.children=next;
                }
                String status=r.status;
                // OutputMerge owns these states. Never turn a merge into a plain "reviewed" result.
                if("reviewed".equals(status)&&Arrays.asList("merged_needs_validation","validated","merge_failed_needs_review").contains(child.status))continue;
                state.setChildStatus(r.childId,closed?"superseded":status);
            }
        }
    }
    public JsonObject status(int offset,int limit){
        sync();ChildTaskCoordinator.Snapshot snapshot=coordinator.snapshot();JsonObject out=new JsonObject();
        out.addProperty("type","minion.child-coordination.v1");out.addProperty("taskId",taskId);out.addProperty("revision",revision);
        out.addProperty("currentRevision",state.revision());out.addProperty("recoveryHold",recovered);
        out.addProperty("activeExecutions",coordinator.activeExecutions());out.addProperty("total",snapshot.records.size());
        out.addProperty("capacity",coordinator.capacity());
        if(coordinator.controlError()!=null)out.addProperty("controlError",coordinator.controlError());
        int start=Math.max(0,Math.min(offset,snapshot.records.size())),end=Math.min(snapshot.records.size(),start+Math.max(1,Math.min(16,limit)));
        JsonArray records=new JsonArray();for(ChildTaskCoordinator.Record r:snapshot.records.subList(start,end)){
            JsonObject item=new JsonObject();item.addProperty("stepId",r.assignment.stepId);item.addProperty("attempt",r.attempt);
            item.addProperty("childId",r.childId);item.addProperty("mode",r.assignment.mode);item.addProperty("status",r.status);
            item.add("scope",gson.toJsonTree(r.assignment.scope));item.add("dependsOn",gson.toJsonTree(r.assignment.dependsOn));
            item.addProperty("acceptance",clip(r.assignment.acceptance,1200));item.addProperty("error",clip(r.error,1200));
            item.addProperty("executionRoot",r.executionRoot);item.addProperty("requiresMerge",r.assignment.requiresMerge);
            if(r.outcome!=null){item.addProperty("summary",clip(r.outcome.summary,2000));item.addProperty("acceptanceReport",clip(r.outcome.acceptanceReport,2000));item.add("evidencePaths",gson.toJsonTree(r.outcome.evidencePaths));}
            records.add(item);
        }out.add("records",records);out.addProperty("nextOffset",end);return out;
    }
    public String blocking(){
        if(!matches())return null;if(recovered)return "子任务协调记录重启后等待人工核对，不自动重放";
        if(coordinator.controlError()!=null)return "子任务协调器故障: "+coordinator.controlError();
        for(ChildTaskCoordinator.Record r:coordinator.snapshot().records){
            boolean explicitlyCancelled="cancelled".equals(r.status)
                    && r.error!=null && !r.error.startsWith("依赖未通过:") && !"协调器关闭".equals(r.error);
            if(!Arrays.asList("reviewed","validated").contains(r.status)&&!explicitlyCancelled)
                return "子任务 "+r.assignment.stepId+" ["+r.status+"]；使用AgentControl child_status/child_review/child_cancel/child_wait核对";
            if(!explicitlyCancelled&&r.assignment.requiresMerge&&!"validated".equals(r.status))
                return "子任务 "+r.assignment.stepId+" 尚未合并和联合验证";
        }return null;
    }
    /** Parent sleeps without billing model calls when all available work is physically executing.
     * Any result/failure needing review wakes the parent; blocked dependencies are not counted as running. */
    public boolean onlyExecuting(){
        if(recovered||closed)return false;
        boolean pending=coordinator.activeExecutions()>0;
        for(ChildTaskCoordinator.Record r:coordinator.snapshot().records){
            if(!Arrays.asList("queued","running","reviewed","validated").contains(r.status))return false;
            if(r.assignment.requiresMerge&&"reviewed".equals(r.status))return false;
            if("queued".equals(r.status)&&coordinator.capacity()>0)pending=true;
        }return pending;
    }
    public boolean mutationsPending(){
        if(coordinator.activeExecutions()>0)return true;
        if(closed||recovered||coordinator.capacity()==0)return false;
        List<ChildTaskCoordinator.Record> records=coordinator.snapshot().records;
        Map<String,ChildTaskCoordinator.Record> byId=new HashMap<String,ChildTaskCoordinator.Record>();
        for(ChildTaskCoordinator.Record r:records)byId.put(r.assignment.stepId,r);
        for(ChildTaskCoordinator.Record r:records)if("worker".equals(r.assignment.mode)&&"queued".equals(r.status)){
            boolean ready=true;for(String dependency:r.assignment.dependsOn){ChildTaskCoordinator.Record dep=byId.get(dependency);
                if(dep==null||!("validated".equals(dep.status)||!dep.assignment.requiresMerge&&"reviewed".equals(dep.status)))ready=false;
            }if(ready)return true;
        }
        return false;
    }
    public ToolResult control(JsonObject args)throws Exception {
        if(!matches()||closed)return ToolResult.errorWithoutSideEffects("协调记录不属于当前指令修订");
        String action=text(args,"action"),step=text(args,"stepId"),note=text(args,"note");
        if("child_status".equals(action))return ToolResult.success(status(number(args,"offset",0),number(args,"limit",8)).toString());
        if("child_wait".equals(action)){
            int timeout=number(args,"timeoutMillis",10000);if(timeout<0||timeout>30000)return ToolResult.errorWithoutSideEffects("等待须为0..30000毫秒");
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
            do{if(!onlyExecuting())break;Thread.sleep(50);}while(System.nanoTime()<end);
            return ToolResult.success(status(number(args,"offset",0),number(args,"limit",8)).toString());
        }
        if(note.trim().isEmpty())return ToolResult.errorWithoutSideEffects("协调控制必须有note实际依据");
        if("child_resume_queue".equals(action)){coordinator.confirmRecoveryQuiescent(note);recovered=false;save.run();return ToolResult.success(status(0,8).toString());}
        if("child_capacity".equals(action)){coordinator.setCapacity(number(args,"capacity",2));return ToolResult.success(status(0,8).toString());}
        ChildTaskCoordinator.Record r=coordinator.record(step);if(r==null)return ToolResult.errorWithoutSideEffects("stepId未登记");
        int attempt=number(args,"attempt",0);if(attempt!=r.attempt)return ToolResult.errorWithoutSideEffects("需child_status中的当前attempt，防止过期控制");
        if("child_cancel".equals(action))coordinator.cancel(step,note);
        else if("child_retry".equals(action)){
            if(!args.has("sideEffectsChecked")||!args.get("sideEffectsChecked").getAsBoolean())return ToolResult.errorWithoutSideEffects("重试需sideEffectsChecked=true，先实际核对未知副作用；不自动重放");
            coordinator.retryFromParent(step,attempt,note,true);
        }else if("child_review".equals(action)){
            List<String> receipts=receipts(args);validateReview(r,receipts);
            coordinator.reviewFromParent(step,attempt,note,receipts);
            if(r.childId!=null)state.reviewChild(r.childId);
        }else if("child_reject".equals(action))coordinator.rejectFromParent(step,attempt,note);
        else return ToolResult.errorWithoutSideEffects("未知子任务控制动作");
        sync();save.run();return ToolResult.success(status(0,8).toString());
    }
    private void validateReview(ChildTaskCoordinator.Record r,List<String> ids)throws Exception {
        if(ids.isEmpty())throw new IOException("需主Agent实际读取/验证子结果后的receiptIds，不能以状态查询冒充复核");
        boolean fileRead=false;synchronized(state){for(String id:ids){TaskState.Evidence found=null;
            for(TaskState.Evidence e:state.evidence)if(id.equals(e.id)&&"root".equals(e.scope)&&e.success&&Objects.equals(taskId,e.taskId)&&e.time>=r.finishedAt&&!Arrays.asList("AgentControl","task","TodoWrite","Context","DiscoverTools").contains(e.tool))found=e;
            if(found==null)throw new IOException("回执不是子结果返回后的当前主任务实际成功动作: "+id);
            if(found.file!=null&&"unchanged".equals(found.file.verify()))for(FileEvidence expected:r.evidence)
                if(Objects.equals(expected.path,found.file.path)&&Objects.equals(expected.sha256,found.file.sha256))fileRead=true;
        }}if(!fileRead)throw new IOException("至少实际Read/验证一个子任务返回的证据文件，再登记其主Agent回执ID");
    }
    /** Mirror existing OutputMerge facts; coordinator cannot invent a merge or accept stale tests. */
    public void refreshMerges(Workspace workspace,com.minion.core.checkpoint.CheckpointStore checkpoints)throws Exception {
        for(ChildTaskCoordinator.Record r:coordinator.snapshot().records){
            if(!r.assignment.requiresMerge||r.childId==null||!"reviewed".equals(r.status))continue;
            OutputMerge.MergeRecord merge=new OutputMerge(workspace,checkpoints).record(taskId,r.childId);
            if(merge==null||!"validated".equals(merge.status))continue;
            boolean fresh=true;
            for(OutputMerge.AppliedFile file:merge.files)if(!Objects.equals(file.afterHash,FileEvidence.digest(Paths.get(file.target))))fresh=false;
            synchronized(state){for(String id:merge.validationReceiptIds){boolean found=false;for(TaskState.Evidence e:state.evidence)
                if(id.equals(e.id)&&Objects.equals(taskId,e.taskId)&&"root".equals(e.scope))
                    for(String kind:Arrays.asList("test","build","command"))if(TaskValidation.passed(e,kind,p->PathsGuard.inside(workspace.workDir(),p)))found=true;
                if(!found)fresh=false;
            }}if(!fresh||merge.validationReceiptIds.isEmpty())continue;
            List<String> paths=new ArrayList<String>();for(OutputMerge.AppliedFile f:merge.files)paths.add(f.target);
            coordinator.markMerged(r.assignment.stepId,r.attempt,paths);
            coordinator.validateFromParent(r.assignment.stepId,r.attempt,true,merge.validationReport,merge.validationReceiptIds);
        }
    }
    public void cancelAll(String reason){try{coordinator.cancelAll(reason);}catch(Exception ignored){}sync();}
    @Override public void close(){closed=true;coordinator.close();sync();}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int number(JsonObject o,String k,int fallback){return ToolArguments.integer(o,k,fallback);}
    private static List<String> receipts(JsonObject args){List<String> out=new ArrayList<String>();for(String s:text(args,"receiptIds").split("[,;；\\s]+"))if(!s.isEmpty())out.add(s);return out;}
    private static String clip(String s,int n){return s==null?null:s.length()<=n?s:s.substring(0,n)+"…[见原记录]";}
}
