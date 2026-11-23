package com.minion.core.agent;

import com.google.gson.*;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

/** 当前任务的版本化步骤，不从历史摘要或子Agent自由文本反向生成执行目标。 */
public final class TaskWorkflow {
    public String taskId;
    public long revision, version;
    public List<Step> steps = new ArrayList<Step>();
    public List<Change> changes = new ArrayList<Change>();
    public static final class Step {
        public String id, title, scope, acceptance;
        public String validationKind="";
        public String status = "pending", note = "";
        public int receiptOffset;
        public List<String> dependsOn = new ArrayList<String>(), receipts = new ArrayList<String>();
        public List<FileEvidence> files = new ArrayList<FileEvidence>();
    }
    public static final class Change {
        public String kind, stepId, replacement, userRequirement, userRequirementHash, note;
        public long revision, time;
    }
    private static final Gson G = new Gson();
    private static String text(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : ""; }
    private static List<String> strings(JsonObject o, String key) {
        List<String> result = new ArrayList<String>();
        if (o.has(key)) for (JsonElement e : o.getAsJsonArray(key)) { String s = e.getAsString(); if (!result.contains(s)) result.add(s); }
        return result;
    }
    public static TaskWorkflow configure(TaskState state, JsonArray definitions, long expectedRevision) {
        if (state.id == null || expectedRevision != state.revision()) throw new IllegalArgumentException("任务修订已改变，请先读取当前状态");
        if (definitions.size() == 0 || definitions.size() > 64) throw new IllegalArgumentException("步骤数量须为1-64");
        TaskWorkflow next = new TaskWorkflow(); next.taskId = state.id; next.revision = expectedRevision;
        TaskWorkflow previous = state.workflow;
        if (previous != null && state.id.equals(previous.taskId)) {
            next.changes = G.fromJson(G.toJson(previous.changes), new com.google.gson.reflect.TypeToken<List<Change>>(){}.getType());
            next.version = previous.version;
        }
        Set<String> ids = new HashSet<String>();
        for (JsonElement element : definitions) {
            JsonObject o = element.getAsJsonObject(); Step s = new Step();
            s.id = text(o,"id"); s.title = text(o,"title"); s.scope = text(o,"scope"); s.acceptance = text(o,"acceptance");
            s.validationKind=text(o,"validationKind");
            if(!Arrays.asList("","test","build","syntax_check","command").contains(s.validationKind))throw new IllegalArgumentException("validationKind须test/build/syntax_check/command或省略");
            if (!s.id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(s.id)) throw new IllegalArgumentException("步骤ID非法或重复");
            if (s.title.isEmpty() || s.title.length()>2000 || s.scope.isEmpty() || s.scope.length()>1000
                    || s.acceptance.isEmpty() || s.acceptance.length()>2000) throw new IllegalArgumentException("每步需非空title/scope/acceptance，且不能过长");
            s.dependsOn = strings(o,"dependsOn");
            if (previous != null && state.id.equals(previous.taskId)) {
                Step old = previous.find(s.id);
                if (old != null) {
                    if (!Objects.equals(old.title,s.title) || !Objects.equals(old.scope,s.scope)
                            || !Objects.equals(old.acceptance,s.acceptance) || !Objects.equals(old.dependsOn,s.dependsOn)||!Objects.equals(old.validationKind,s.validationKind))
                        throw new IllegalArgumentException("不能用相同ID改写旧步骤，请先登记取消/替代并给新步骤新ID: "+s.id);
                    s = G.fromJson(G.toJson(old), Step.class);
                }
            }
            next.steps.add(s);
        }
        if (previous != null && state.id.equals(previous.taskId)) {
            List<String> missing = new ArrayList<String>();
            for (Step old : previous.steps) if (!ids.contains(old.id)) missing.add(old.id);
            if (!missing.isEmpty())
                throw new IllegalArgumentException("不能从计划中抹去旧步骤，取消/替代记录须保留: " + missing
                        + "（可先用 AgentControl(action=workflow) 查看原步骤完整定义）");
        }
        Map<String,Integer> colors = new HashMap<String,Integer>();
        for (Step s : next.steps) next.visit(s, colors);
        next.change("rebase", "", "", state, "绑定当前用户修订，旧完成记录仅是证据，不替代新要求");
        return next;
    }
    private void visit(Step s, Map<String,Integer> colors) {
        Integer c=colors.get(s.id); if (c!=null) { if(c==1) throw new IllegalArgumentException("步骤依赖存在环"); return; }
        colors.put(s.id,1);
        for(String id:s.dependsOn) { Step dependency=find(id); if(dependency==null)throw new IllegalArgumentException("依赖不存在: "+id);visit(dependency,colors); }
        colors.put(s.id,2);
    }
    private Step find(String id) { for(Step s:steps)if(s.id.equals(id))return s;return null; }
    private void change(String kind,String step,String replacement,TaskState state,String note) {
        Change c=new Change();c.kind=kind;c.stepId=step;c.replacement=replacement;c.revision=state.revision();
        String original=state.latestRequirement();c.userRequirement=clip(original,2000);c.userRequirementHash=StopGate.digest(String.valueOf(original));
        c.note=note;c.time=System.currentTimeMillis();changes.add(c);version++;
    }
    /** TaskV2 在同任务新修订下创建步骤时同步修订号（保留全部历史步骤与审计日志，不抹除旧记录）。 */
    public void rebaseRevision(TaskState state, String note) {
        if (state == null || !Objects.equals(taskId, state.id)) return;
        if (revision == state.revision()) return;
        revision = state.revision();
        change("rebase", "", "", state, note == null || note.isEmpty() ? "TaskV2 同步当前用户修订" : note);
    }
    public void transition(TaskState state,String stepId,String action,List<String> receipts,List<FileEvidence> files,String note,String replacement) {
        transition(state,stepId,action,receipts,files,note,replacement,p->true);
    }
    public void transition(TaskState state,String stepId,String action,List<String> receipts,List<FileEvidence> files,String note,String replacement,Predicate<Path> allowed) {
        if (!Objects.equals(taskId,state.id) || revision != state.revision()) throw new IllegalArgumentException("旧计划已失效，需以当前revision重新plan_steps绑定");
        Step s=find(stepId); if(s==null)throw new IllegalArgumentException("步骤不存在");
        if(note!=null&&note.length()>4000)throw new IllegalArgumentException("note最多4000字符，完整证据请保留文件路径");
        if(receipts.size()>128||new HashSet<String>(receipts).size()!=receipts.size())throw new IllegalArgumentException("证据ID须唯一且最多128条");
        if ("start".equals(action)) {
            if(!Arrays.asList("pending","blocked").contains(s.status))throw new IllegalArgumentException("步骤不能重复开始");
            for(String id:s.dependsOn)if(!"completed".equals(find(id).status))throw new IllegalArgumentException("前置步骤尚未完成: "+id);
            s.status="running";
            s.receiptOffset=state.evidence.size();
        } else if ("complete".equals(action)) {
            if(!"running".equals(s.status))throw new IllegalArgumentException("只可验收正在运行的步骤");
            if(receipts.isEmpty())throw new IllegalArgumentException("步骤验收需本任务实际成功工具证据ID");
            for(String ref:receipts) {
                boolean found=false;for(int i=Math.max(0,s.receiptOffset);i<state.evidence.size();i++){TaskState.Evidence e=state.evidence.get(i);if(ref.equals(e.id)&&Objects.equals(taskId,e.taskId)&&e.success&&"root".equals(e.scope)
                        && !Arrays.asList("AgentControl","TodoWrite","Context","Skill","AskUserQuestion","DiscoverTools").contains(e.tool))found=true;
                }
                if(!found)throw new IllegalArgumentException("证据不属于当前任务或不是有效成功回执: "+ref);
            }
            if(note==null||note.trim().isEmpty())throw new IllegalArgumentException("需note说明如何满足验收条件，不能只凭完成标记");
            for(FileEvidence f:files)if(!"unchanged".equals(f.verify()))throw new IllegalArgumentException("证据文件已改变");
            if(s.validationKind!=null&&!s.validationKind.isEmpty()) {
                boolean validated=false;for(TaskState.Evidence e:state.evidence)if(receipts.contains(e.id)&&TaskValidation.passed(e,s.validationKind,allowed))validated=true;
                if(!validated)throw new IllegalArgumentException("该步骤要求真实"+s.validationKind+"通过且源码/报告未变；语法检查、零测试或未配置不能替代");
            }
            s.receipts=new ArrayList<String>(receipts);s.files=new ArrayList<FileEvidence>(files);s.status="completed";
        } else if ("reopen".equals(action)) {
            if(!"completed".equals(s.status)||note==null||note.trim().isEmpty())throw new IllegalArgumentException("仅已完成步骤可凭具体重核原因重新打开");
            s.status="pending";
        } else if ("cancel".equals(action)||"supersede".equals(action)) {
            if(note==null||note.trim().isEmpty())throw new IllegalArgumentException("取消/替代需说明与最新用户要求的关系");
            if("supersede".equals(action)&&(find(replacement)==null||s.id.equals(replacement)))throw new IllegalArgumentException("替代步骤须存在且不同于原步骤");
            s.status="cancel".equals(action)?"cancelled":"superseded";
        } else if ("block".equals(action)) {
            if(note==null||note.trim().isEmpty())throw new IllegalArgumentException("阻塞需具体原因");
            if(!Arrays.asList("pending","running","blocked").contains(s.status))throw new IllegalArgumentException("终态步骤不可改为阻塞");
            s.status="blocked";
        } else throw new IllegalArgumentException("stepAction须start/complete/block/cancel/supersede/reopen");
        s.note=note==null?"":note;change(action,stepId,replacement,state,s.note);
    }
    public String blocking(TaskState state,Predicate<Path> allowed) {
        return blocking(state,allowed,new TaskValidation.Probe(allowed));
    }
    String blocking(TaskState state,Predicate<Path> allowed,TaskValidation.Probe validation) {
        if(!Objects.equals(taskId,state.id)||revision!=state.revision())return "工作流修订已过期，先按最新要求重新绑定计划";
        for(Step s:steps) {
            if(!Arrays.asList("completed","cancelled","superseded").contains(s.status))return "工作流步骤未完成: "+s.id+" ["+s.status+"] "+s.title;
            if("completed".equals(s.status))for(FileEvidence f:s.files)try {
                if(!allowed.test(Paths.get(f.path))||!"unchanged".equals(f.verify()))return "步骤证据文件已改变/不可访问: "+s.id;
            }catch(Exception e){return "步骤证据无法核验: "+s.id;}
            if("completed".equals(s.status)&&s.validationKind!=null&&!s.validationKind.isEmpty()) {
                boolean valid=false;for(TaskState.Evidence e:state.evidence)if(s.receipts.contains(e.id)&&validation.passed(e,s.validationKind)){valid=true;break;}
                if(!valid)return "步骤验证已过期/缺失，需重新运行并reopen核验: "+s.id;
            }
        }
        return "";
    }
    public void recoverAfterRestart() { for(Step s:steps)if("running".equals(s.status)){s.status="blocked";s.note="进程退出，先核对该步骤实际副作用，再决定恢复";} }
    public String render() {
        StringBuilder b=new StringBuilder("工作流 task=").append(taskId).append(" revision=").append(revision).append(" version=").append(version);
        List<String> completed=new ArrayList<String>(),cancelled=new ArrayList<String>();
        for(Step s:steps)if("completed".equals(s.status))completed.add(s.id);else if("cancelled".equals(s.status)||"superseded".equals(s.status))cancelled.add(s.id);
        b.append("\n总步骤 ").append(steps.size()).append("；已完成 ").append(completed.size()).append("；取消/替代 ").append(cancelled.size()).append("。优先续做运行/未完成步骤：");
        boolean full=false;
        for(String status:Arrays.asList("running","blocked","pending"))for(Step s:steps)if(status.equals(s.status)&&!full) {
            b.append("\n").append(s.id).append(" [").append(s.status).append("] ").append(clip(s.title,300))
                .append(" | 范围 ").append(clip(s.scope,120)).append(" | 前置 ").append(s.dependsOn).append(" | 验收 ").append(clip(s.acceptance,300));
            if(s.validationKind!=null&&!s.validationKind.isEmpty())b.append(" | 验证类型 ").append(s.validationKind);
            if(b.length()>9000){b.setLength(9000);b.append("\n[剩余未完成步骤用AgentControl workflow分页查看]");full=true;}
        }
        b.append("\n已完成ID（不要重做，原证据按步骤分页读取）: ").append(clip(completed.toString(),4000));
        if(!cancelled.isEmpty())b.append("\n取消/替代ID（不能擅自恢复）: ").append(clip(cancelled.toString(),1500));
        b.append("\n步骤完成仅证明所引用回执/文件版本；业务正确性仍需独立核验。取消/替代不能抹去最新用户要求。");
        return b.toString();
    }
    private static String clip(String text,int limit){return text==null?"":text.substring(0,Math.min(limit,text.length()));}
}
