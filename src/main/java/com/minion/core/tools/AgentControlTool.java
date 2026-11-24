package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.*;
import java.nio.file.*;
import java.util.*;

/** Explicit stage checkpoints and review. Only the orchestrator gets this tool. */
public final class AgentControlTool implements Tool {
    private final AgentLoop loop;
    private final Workspace workspace;
    public AgentControlTool(AgentLoop loop,Workspace workspace) { this.loop=loop; this.workspace=workspace; }
    public String name() { return "AgentControl"; }
    public String description() { return "任务控制：hooks_status/status/checkpoint/verify/review/refresh_checkpoint；verify_step(stepId,note)用实际核对说明解除结果不确定的历史工具步骤（核对文件/进程/输出后调用，禁止编造）；plan_steps(revision,steps数组：id/title/scope/acceptance/dependsOn/validationKind)；step_change(revision,stepId,stepAction=start/complete/block/cancel/supersede/reopen)，complete需启动后真实receiptIds/note；workflow/evidence分页；review_changes(paths)独立只读审查；validate_merge(childId,receiptIds,note,validationKind默认test)核对合并后真实Build报告。异步子任务：child_status(offset/limit)、child_wait(timeoutMillis≤30000)、child_review(stepId,attempt,receiptIds,note)需返回后实际Read证据、child_reject/child_cancel(支持childId或stepId+note取消停滞子任务)、child_retry另需sideEffectsChecked=true、child_capacity(capacity=0..2)、child_resume_queue需旧执行退出依据note。子控制动作不是业务完成证据，修订变化停止旧分工"; }
    public JsonObject schema() {
        JsonObject schema=SchemaGenerator.objectSchema(description(),new String[]{"action","nextStep","processedRange","files","childId","note","revision","steps","stepId","stepAction","receiptIds","replacement","offset","limit","paths","validationKind"},new String[]{"action"});
        JsonObject props=schema.getAsJsonObject("properties");
        for(String key:Arrays.asList("attempt","capacity","timeoutMillis")){JsonObject p=new JsonObject();p.addProperty("type","integer");props.add(key,p);}
        JsonObject sideEffectsChecked=new JsonObject();sideEffectsChecked.addProperty("type","boolean");props.add("sideEffectsChecked",sideEffectsChecked);
        for(String key:Arrays.asList("revision","offset","limit"))props.getAsJsonObject(key).addProperty("type","integer");
        JsonObject step=SchemaGenerator.objectSchema("步骤定义；ID用英数字/下划线/短横线，不包含完成状态",new String[]{"id","title","scope","acceptance","dependsOn","validationKind"},new String[]{"id","title","scope","acceptance"});
        step.getAsJsonObject("properties").getAsJsonObject("id").addProperty("pattern","[A-Za-z0-9_-]{1,64}");
        JsonObject dependencies=new JsonObject();dependencies.addProperty("type","array");JsonObject item=new JsonObject();item.addProperty("type","string");dependencies.add("items",item);step.getAsJsonObject("properties").add("dependsOn",dependencies);
        JsonObject steps=new JsonObject();steps.addProperty("type","array");steps.add("items",step);props.add("steps",steps);
        return schema;
    }
    public ToolResult execute(JsonObject args) throws Exception {
        TaskState state=loop.session().taskState; String action=text(args,"action");
        if("hooks_status".equals(action))return ToolResult.success(loop.hooks().status().toString());
        if("verify_step".equals(action)) {
            String stepId=text(args,"stepId"),note=text(args,"note");
            if(stepId.isEmpty()||note.trim().isEmpty())
                return ToolResult.errorWithoutSideEffects("verify_step 需要步骤ID和实际核对说明（如何确认了该步骤的副作用）");
            try {
                loop.reliability().acknowledge(stepId,note);
                return ToolResult.success("步骤 "+stepId+" 已核对并解除不确定状态；完成门禁将不再因它阻断");
            } catch(Exception invalid){return ToolResult.error("核对失败: "+invalid.getMessage());}
        }
        if("cancel_child".equals(action)){args=args.deepCopy();args.addProperty("action","child_cancel");action="child_cancel";}
        if(action.startsWith("child_"))return loop.controlChildren(args);
        if("review_changes".equals(action))return loop.reviewChanges(args);
        if("validate_merge".equals(action)) {
            String kind=text(args,"validationKind");if(kind.isEmpty())kind="test";
            if(!Arrays.asList("test","build","command").contains(kind)||text(args,"note").trim().isEmpty())return ToolResult.errorWithoutSideEffects("联合验证需validationKind=test/build/command及具体note，默认test");
            List<String> ids=new ArrayList<String>();for(String ref:text(args,"receiptIds").split("[,;；\\s]+"))if(!ref.isEmpty())ids.add(ref);
            if(ids.isEmpty())return ToolResult.errorWithoutSideEffects("需合并后的真实Build验证回执ID");
            synchronized(state){for(String id:ids){boolean passed=false;for(TaskState.Evidence evidence:state.evidence)if(id.equals(evidence.id)&&Objects.equals(state.id,evidence.taskId)&&TaskValidation.passed(evidence,kind,loop::evidencePathAllowed))passed=true;
                if(!passed)return ToolResult.errorWithoutSideEffects("联合验证回执未通过或已过期，不能以文件存在/状态查询代替: "+id);}}
            try {
                com.minion.core.checkpoint.CheckpointStore checkpoints=new com.minion.core.checkpoint.CheckpointStore(Paths.get(workspace.workDir()),Paths.get(workspace.workDir(),".minion","checkpoints",loop.session().id));
                com.minion.core.agent.OutputMerge.MergeRecord record=new OutputMerge(workspace,checkpoints).validateFromParent(text(args,"childId"),state,true,text(args,"note"),ids);
                loop.persistSession();return ToolResult.success(new com.google.gson.Gson().toJson(record));
            }catch(Exception invalid){return ToolResult.errorWithoutSideEffects("合并尚未通过联合验证: "+invalid.getMessage());}
        }
        if("evidence".equals(action)) { synchronized(state) {
            int offset=args.has("offset")?Math.max(0,args.get("offset").getAsInt()):0;
            int limit=args.has("limit")?Math.max(1,Math.min(16,args.get("limit").getAsInt())):8;
            List<TaskState.Evidence> current=new ArrayList<TaskState.Evidence>();
            for(TaskState.Evidence evidence:state.evidence)if(Objects.equals(state.id,evidence.taskId))current.add(evidence);
            int start=Math.min(offset,current.size()),end=Math.min(current.size(),start+limit);
            JsonObject out=new JsonObject();out.addProperty("taskId",state.id);out.addProperty("total",current.size());out.addProperty("nextOffset",end);
            out.add("receipts",new com.google.gson.Gson().toJsonTree(current.subList(start,end)));
            return ToolResult.success(out.toString());
        }}
        if("plan_steps".equals(action)||"step_change".equals(action)||"workflow".equals(action)) {
            try { synchronized(state) {
                if("workflow".equals(action)) {
                    if(state.workflow==null)return ToolResult.success("尚未登记版本化步骤；原有任务及断点仍有效");
                    int offset=args.has("offset")?Math.max(0,args.get("offset").getAsInt()):0;
                    int limit=args.has("limit")?Math.max(1,Math.min(16,args.get("limit").getAsInt())):8;
                    com.google.gson.JsonObject out=new com.google.gson.JsonObject();out.addProperty("taskId",state.id);out.addProperty("revision",state.workflow.revision);
                    out.addProperty("currentRevision",state.revision());out.addProperty("total",state.workflow.steps.size());
                    int end=Math.min(state.workflow.steps.size(),offset+limit);
                    out.add("steps",new com.google.gson.Gson().toJsonTree(state.workflow.steps.subList(Math.min(offset,end),end)));
                    out.addProperty("nextOffset",end);return ToolResult.success(out.toString());
                }
                if(!args.has("revision")||args.get("revision").getAsLong()!=state.revision())return ToolResult.errorWithoutSideEffects("任务修订已改变或未提供revision（当前 revision="+state.revision()+"），请传 revision="+state.revision()+" 重试");
                if("plan_steps".equals(action)) {
                    com.google.gson.JsonElement definitions=args.get("steps");
                    if(definitions==null)return ToolResult.errorWithoutSideEffects("缺少steps");
                    if(definitions.isJsonPrimitive())definitions=com.google.gson.JsonParser.parseString(definitions.getAsString());
                    state.workflow=TaskWorkflow.configure(state,definitions.getAsJsonArray(),state.revision());
                } else {
                    if(state.workflow==null)return ToolResult.errorWithoutSideEffects("先plan_steps登记步骤");
                    List<FileEvidence> files=new ArrayList<FileEvidence>();
                    for(String raw:text(args,"files").split("[\\r\\n;；]+"))if(!raw.trim().isEmpty()) {
                        if(files.size()>=30)throw new IllegalArgumentException("每步最多30个证据文件");
                        Path p=PathsGuard.resolve(workspace.cwd().toString(),raw.trim()).toAbsolutePath().normalize();
                        if(!loop.evidencePathAllowed(p))throw new IllegalArgumentException("证据路径未授权");
                        FileEvidence file=FileEvidence.capture(p);if(!"present".equals(file.status))throw new IllegalArgumentException("证据文件不存在");files.add(file);
                    }
                    List<String> receipts=new ArrayList<String>();for(String ref:text(args,"receiptIds").split("[,;；\\s]+"))if(!ref.isEmpty())receipts.add(ref);
                    state.workflow.transition(state,text(args,"stepId"),text(args,"stepAction"),receipts,files,text(args,"note"),text(args,"replacement"),loop::evidencePathAllowed);
                }
                loop.persistSession();return ToolResult.success(state.workflow.render());
            }}catch(IllegalArgumentException e){return ToolResult.errorWithoutSideEffects(e.getMessage());}
        }
        if("status".equals(action)) return ToolResult.success(state.render(loop.session().todos)+"\n实时断点核验:\n"+loop.authorizedCheckpointStatus());
        if("verify".equals(action)) return ToolResult.success(loop.authorizedCheckpointStatus());
        if("refresh_checkpoint".equals(action)) {
            String note=text(args,"note").trim();
            if(note.isEmpty()) return ToolResult.error("refresh_checkpoint需note说明实际复核及文件合法更新的原因；不能仅凭完成声明刷新");
            synchronized(state) {
                if(state.checkpointFiles.isEmpty()) return ToolResult.error("没有已登记断点文件；请用checkpoint登记实际交付文件");
                List<FileEvidence> refreshed=new ArrayList<FileEvidence>();
                for(FileEvidence old:state.checkpointFiles) {
                    Path p=Paths.get(old.path).toAbsolutePath().normalize();
                    if(!loop.evidencePathAllowed(p)) return ToolResult.error("断点路径权限不允许，未刷新: "+p);
                    FileEvidence e=FileEvidence.capture(p);
                    if(!"present".equals(e.status)) return ToolResult.error("断点文件不存在，未刷新，原记录保留: "+p);
                    refreshed.add(e);
                }
                state.checkpoint("",state.processedRange,refreshed);
                state.recordEvidence("root","checkpoint-review",true,note,null);
            }
            return ToolResult.success("复核后断点已刷新，下一步已清除；哈希仅核验文件版本，不代表业务正确。\n"+loop.authorizedCheckpointStatus());
        }
        if("checkpoint".equals(action)) {
            List<FileEvidence> files=new ArrayList<FileEvidence>();
            for(String raw:text(args,"files").split("[\\r\\n;；]+")) if(!raw.trim().isEmpty()) {
                Path p=PathsGuard.resolve(workspace.cwd().toString(),raw.trim()).toAbsolutePath().normalize();
                if(!loop.evidencePathAllowed(p)) return ToolResult.error("断点证据需位于工作区、会话临时目录或已允许目录: "+p);
                FileEvidence e=FileEvidence.capture(p); if(!"present".equals(e.status)) return ToolResult.error("断点文件不存在，未保存完成声明: "+p); files.add(e);
                if(files.size()>30) return ToolResult.error("每个断点最多30个文件，请分阶段登记");
            }
            state.checkpoint(text(args,"nextStep"),text(args,"processedRange"),files);
            return ToolResult.success("断点已保存（存在/哈希核验，不代表业务结论正确）\n"+loop.authorizedCheckpointStatus());
        }
        if("review".equals(action)) {
            if(text(args,"note").trim().isEmpty()) return ToolResult.error("review需note说明实际核对的证据");
            if(!state.reviewChild(text(args,"childId"))) return ToolResult.error("只能核对当前任务已返回或已失败的子Agent；若子Agent仍在运行且需放弃，请调用 AgentControl(action=\"child_cancel\",childId=...,note=...)");
            state.recordEvidence("root","child-review",true,text(args,"note"),null); loop.persistSession(); return ToolResult.success("子Agent已登记核对");
        }
        return ToolResult.error("action支持status/checkpoint/verify/review/refresh_checkpoint/plan_steps/step_change/workflow/evidence/review_changes/validate_merge");
    }
    private static String text(JsonObject o,String key) { return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():""; }
}
