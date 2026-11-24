package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.llm.Message;
import com.minion.core.tools.ToolResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 完成裁决快照（项8 拆分第一步）：自 AgentLoop 纯机械搬移，门禁语义零变化。
 *  不持有 AgentLoop 引用；每轮可变状态经 StopContext 传入。 */
final class StopAdjudicator {

    /** 每轮可变字段快照 + 共用谓词，避免裁决器反向依赖主循环。 */
    static final class StopContext {
        final ChildTaskHost childHost;
        final RuntimeJobRegistry runtimeJobs;
        final String runtimeJobProblem;
        final BooleanSupplier hasPendingTodos;
        StopContext(ChildTaskHost childHost, RuntimeJobRegistry runtimeJobs,
                String runtimeJobProblem, BooleanSupplier hasPendingTodos) {
            this.childHost = childHost;
            this.runtimeJobs = runtimeJobs;
            this.runtimeJobProblem = runtimeJobProblem;
            this.hasPendingTodos = hasPendingTodos;
        }
    }

    private final Session session;
    private final ReliabilityService reliability;
    private final Predicate<Path> evidencePathAllowed;
    private final Supplier<ExecutionMode> executionMode;

    StopAdjudicator(Session session, ReliabilityService reliability,
            Predicate<Path> evidencePathAllowed, Supplier<ExecutionMode> executionMode) {
        this.session = session;
        this.reliability = reliability;
        this.evidencePathAllowed = evidencePathAllowed;
        this.executionMode = executionMode;
    }

    boolean hasStructuredWork() {
        synchronized(session.taskState) { if(session.taskState.workflow!=null)return true; }
        synchronized(session.todos) { if (!session.todos.items.isEmpty()) return true; }
        return !session.taskState.childSnapshot().isEmpty();
    }

    StopGate.Snapshot snapshot(String candidate,List<StopGate.Receipt> current,int historicalCount,int fromIndex,StopContext ctx) {
        JsonObject facts=new JsonObject();List<StopGate.Receipt> receipts=new ArrayList<StopGate.Receipt>();
        List<FileEvidence> files=new ArrayList<FileEvidence>();
        List<FileEvidence> checkpoints;
        List<FileEvidence> outputFiles=new ArrayList<FileEvidence>();
        String id,goal,range,next;List<String> guidance;
        long revision;
        boolean hasCheckpoint,finalCheckpoint,workflowReady;
        java.util.Set<String> workflowReceipts;
        java.util.Map<String,FileEvidence> checkpointReads=new java.util.HashMap<String,FileEvidence>();
        synchronized(session.taskState) {
            id=session.taskState.id;goal=session.taskState.objective;guidance=new ArrayList<String>(session.taskState.guidance);
            revision=session.taskState.revision();
            facts.addProperty("instructionRevision",revision);
            range=session.taskState.processedRange;next=session.taskState.currentNextStep();
            hasCheckpoint=session.taskState.checkpointRevision!=null&&session.taskState.checkpointRevision>0;
            finalCheckpoint=java.util.Objects.equals(session.taskState.checkpointRevision,revision)
                    &&next!=null&&next.trim().isEmpty()&&range!=null&&!range.trim().isEmpty();
            workflowReceipts=CompletionEvidence.readyWorkflowReceipts(session.taskState);
            workflowReady=session.taskState.workflow==null||!workflowReceipts.isEmpty();
            facts.addProperty("latestUserRequirement",session.taskState.latestRequirement());
            if(session.taskState.instructionChanges!=null)facts.add("instructionChanges",new com.google.gson.Gson().toJsonTree(
                    session.taskState.instructionChanges.subList(Math.max(0,session.taskState.instructionChanges.size()-8),session.taskState.instructionChanges.size())));
            facts.add("referenceContext",new com.google.gson.Gson().toJsonTree(session.taskState.referenceContext));
            facts.addProperty("referenceContextTruncated",session.taskState.referenceContextTruncated);
            com.google.gson.JsonArray children=new com.google.gson.JsonArray();
            int childCount=0,reviewedCount=0;
            for(TaskState.Child child:session.taskState.childSnapshot()) {
                childCount++;
                if("reviewed".equals(child.status)||"validated".equals(child.status)){reviewedCount++;continue;}
                if("cancelled".equals(child.status))continue;
                JsonObject c=new JsonObject();c.addProperty("id",child.id);c.addProperty("status",child.status);
                c.addProperty("assignment",child.assignment==null?"":child.assignment.substring(0,Math.min(300,child.assignment.length())));children.add(c);
            }
            facts.add("pendingChildren",children);facts.addProperty("childCount",childCount);facts.addProperty("reviewedChildren",reviewedCount);
            checkpoints=new ArrayList<FileEvidence>(session.taskState.checkpointFiles);
            java.util.Map<String,FileEvidence> checkpointByPath=new java.util.HashMap<String,FileEvidence>();
            if(finalCheckpoint)for(FileEvidence file:checkpoints)checkpointByPath.put(file.path,file);
            int historicalShown=0;
            for(int i=session.taskState.evidence.size()-1;i>=0;i--) {
                TaskState.Evidence e=session.taskState.evidence.get(i);if(!id.equals(e.taskId)||!"root".equals(e.scope))continue;
                if(i<historicalCount&&historicalShown++<24) {
                    StopGate.Receipt prior=new StopGate.Receipt("evidence:"+i,e.tool,"",e.success?ToolResult.success(e.result):ToolResult.error(e.result),e.mutation);
                    prior.revision=e.revision;receipts.add(0,prior);
                }
                if(e.file!=null) {
                    files.add(e.file);
                    if(Boolean.TRUE.equals(e.mutation)&&java.util.Arrays.asList("Write","Edit","Excel","Document","Database").contains(e.tool)
                            &&!internalEvidencePath(e.file.path))outputFiles.add(e.file);
                    FileEvidence saved=checkpointByPath.get(e.file.path);
                    if(saved!=null && e.success && ("Read".equals(e.tool)||"ArtifactVerification".equals(e.tool))
                            && java.util.Objects.equals(e.revision,revision) && saved.sha256!=null && saved.sha256.equals(e.file.sha256))
                        checkpointReads.put(e.file.path,e.file);
                }
            }
            java.util.Collections.reverse(files);
            java.util.Collections.reverse(outputFiles);
            files.addAll(checkpoints);
        }
        // 文件IO不占用任务台账锁，避免大文件核验阻塞JavaFX任务面板/子Agent进度。
        StopGate.FileProbe probe=new StopGate.FileProbe(evidencePathAllowed);
        com.google.gson.JsonArray checkpointFacts=probe.facts(checkpoints,checkpoints.size());
        StringBuilder checkpointNote=new StringBuilder("处理范围: ").append(range).append("\n下一步: ")
                .append(next==null?"旧计划已失效或未登记，按最新要求判断":next);
        for(com.google.gson.JsonElement element:checkpointFacts) {
            JsonObject f=element.getAsJsonObject();
            checkpointNote.append("\n文件 [").append(StopGate.unchanged(f)?"unchanged":f.get("status").getAsString())
                    .append("] ").append(f.get("path").getAsString());
        }
        facts.addProperty("checkpoint",checkpointNote.toString());
        facts.add("checkpointFiles",checkpointFacts);
        facts.add("knownFiles",probe.facts(files,12));
        // Refresh + actual re-read can supersede the old write's hash. Refresh alone cannot.
        outputFiles.replaceAll(f->checkpointReads.getOrDefault(f.path,f));
        com.google.gson.JsonArray outputs=probe.facts(outputFiles,64);
        facts.add("outputFiles",outputs);
        facts.addProperty("todos",session.todos.render());facts.addProperty("toolsUsed",!receipts.isEmpty()||!current.isEmpty());
        facts.addProperty("structured",hasStructuredWork());
        TaskValidation.Probe validation=new TaskValidation.Probe(evidencePathAllowed);
        com.google.gson.JsonArray validationSnapshot=validation.summaries(session.taskState);
        facts.add("validationReports",validationSnapshot);
        synchronized(session.taskState){session.taskState.lastValidationSnapshot=validationSnapshot.deepCopy();session.taskState.validationSnapshotAt=System.currentTimeMillis();}
        synchronized(session.taskState) {
            facts.addProperty("executionPhase",session.taskState.executionPhase());
            if(session.taskState.workflow!=null) facts.add("workflow",new com.google.gson.Gson().toJsonTree(session.taskState.workflow));
        }
        JsonObject acceptance=reliability.evaluate(session.taskState,evidencePathAllowed);
        facts.add("acceptance",acceptance);
        com.google.gson.JsonArray uncertain=reliability.unresolved(id);facts.add("uncertainSteps",uncertain);
        com.google.gson.JsonArray priorReplies=new com.google.gson.JsonArray();
        for(int i=session.messages.size()-2;i>=Math.max(0,fromIndex)&&priorReplies.size()<4;i--) {
            Message m=session.messages.get(i);if(m.role!=Message.Role.ASSISTANT||m.content==null||(m.toolCalls!=null&&!m.toolCalls.isEmpty()))continue;
            JsonObject part=new JsonObject();part.addProperty("text",m.content.substring(0,Math.min(2000,m.content.length())));
            part.addProperty("truncated",m.content.length()>2000);priorReplies.add(part);
        }
        facts.add("earlierDeliveredReplies",priorReplies);
        synchronized(session.pendingSupplements){facts.add("pendingSupplements",new com.google.gson.Gson().toJsonTree(session.pendingSupplements));}
        receipts.addAll(current);
        String blocking=pendingExecutionReason(checkpointFacts,validation,ctx);
        if(!"passed".equals(acceptance.get("status").getAsString()))blocking=(blocking==null?"":blocking+"；")+"用户登记的程序验收规则未通过或无法核验";
        if(uncertain.size()>0&&ReliabilityService.hasUnobservedOutcome(uncertain))
            blocking=(blocking==null?"":blocking+"；")+"有工具步骤执行结果不可读取（"+ReliabilityService.unresolvedSummary(uncertain)
                    +"）：用只读方式核对实际副作用后调用 AgentControl verify_step(stepId,note) 解除，禁止重跑原操作";
        com.google.gson.JsonArray rules=acceptance.getAsJsonArray("results");
        // Only current live file-verification receipts can skip the remote reviewer.
        // Historical evidence is still available in the ordinary semantic check and for audited workflow steps.
        List<String> proof=blocking==null&&uncertain.size()==0&&workflowReady&&(next==null||next.trim().isEmpty())
                &&outputFiles.stream().map(f->f.path).distinct().limit(65).count()<=64
                ?CompletionEvidence.deliveredProof(candidate,revision,hasCheckpoint,finalCheckpoint,checkpointFacts,outputs,validationSnapshot,
                    workflowReceipts,current,rules==null?new com.google.gson.JsonArray():rules,
                    CompletionContract.from(goal,guidance).mutation,checkpointReads.keySet(),
                    !executionMode.get().requiresCompletionReview())
                :java.util.Collections.emptyList();
        return new StopGate.Snapshot(id,goal,guidance,facts,receipts,candidate,blocking,proof);
    }

    /** 仅当前任务的结构化事实；旧任务子Agent不能阻挡新问题。 */
    private String pendingExecutionReason(com.google.gson.JsonArray checkpointFiles,TaskValidation.Probe validation,StopContext ctx) {
        List<String> reasons = new ArrayList<String>();
        ChildTaskHost host=ctx.childHost;if(host!=null){host.sync();String reason=host.blocking();if(reason!=null)reasons.add(reason);}
        synchronized(session.taskState) {
            if(session.taskState.workflow!=null) {
                String workflowReason=session.taskState.workflow.blocking(session.taskState,evidencePathAllowed,validation);
                if(!workflowReason.isEmpty())reasons.add(workflowReason);
            }
        }
        if (ctx.hasPendingTodos.getAsBoolean()) reasons.add("主任务待办尚未完成");
        RuntimeJobRegistry jobs=ctx.runtimeJobs;
        if(ctx.runtimeJobProblem!=null)reasons.add(ctx.runtimeJobProblem);
        if(jobs!=null)for(RuntimeJobRegistry.JobRecord job:jobs.foregroundJobs(session.taskState.id,session.taskState.revision())) {
            if(!job.status.settled()||job.status==RuntimeJobRegistry.Status.UNKNOWN) {
                reasons.add("外部作业尚未确认结果："+job.jobId+" ["+job.status+"]");break;
            }
        }
        for (TaskState.Child child : session.taskState.childSnapshot()) {
            if(child.coordinatedStep!=null&&"superseded".equals(child.status))continue;
            if (!"reviewed".equals(child.status)&&!"validated".equals(child.status)&&!"cancelled".equals(child.status)) {
                reasons.add("merged_needs_validation".equals(child.status)
                        ?"子Agent合并后尚未联合验证；实际Build验证通过后调用AgentControl validate_merge(childId="+child.id+",receiptIds=实际回执ID,note=核对依据)"
                        :"子Agent尚未核对或仍在运行/失败（" + child.id + "，" + child.status
                                + "）：运行中请调 AgentControl child_wait，已返回或已由主Agent接管失败项请调 AgentControl review(childId,note)，放弃停滞子任务请调 AgentControl child_cancel(childId,note)");
                break;
            }
        }
        for(com.google.gson.JsonElement element:checkpointFiles) {
            JsonObject file=element.getAsJsonObject();
            if(StopGate.unchanged(file))continue;
            String status=file.get("status").getAsString(),path=file.get("path").getAsString();
            if("permission_denied".equals(status))reasons.add("断点证据文件已超出当前权限："+path);
            else reasons.add("断点证据文件已变更、缺失或无法核验："+path+"；只核对这个文件，合法更新请刷新断点");
            break;
        }
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    private static boolean internalEvidencePath(String path){
        if(path==null)return true;
        String normalized=path.replace('\\','/').toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("/.session/tmp/")||normalized.contains("/.minion/tool-results/")
                ||normalized.contains("/.minion/reports/");
    }
}