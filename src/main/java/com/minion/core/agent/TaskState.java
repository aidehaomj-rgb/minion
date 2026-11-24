package com.minion.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable control-plane state. Summaries and child results must never rewrite this record. */
public final class TaskState {
    public String id;
    public String objective;
    /** 当前指令修订；与检查点分开，旧步骤不能越过新的用户纠正。 */
    public Long instructionRevision;
    public Long checkpointRevision;
    /** 用户真实输入的时间顺序优先于队列被 drain 的技术顺序；null 兼容旧记录。 */
    public String latestUserRequirement;
    public String latestUserEventId;
    public String status = "idle";
    /** User-selected execution semantics; unknown/missing values mean NORMAL. */
    public String executionMode = ExecutionMode.NORMAL.id;
    public transient volatile String runtimePhase;
    public long startedAt, lastFinishedAt, activeMillis, turnStartedAt;
    public String latestProgress;
    /** Durable reason for a non-completed turn; visible in /task after restart. */
    public String pauseReason;
    /** Machine-readable pause cause; a budget boundary is not a completed task. */
    public String pauseCode;
    /** Bounded, per-task automatic continuation at a safe tool-round boundary. */
    public int automaticContinuations;
    public static final int MAX_AUTOMATIC_CONTINUATIONS = 3;
    /** null兼容旧控制记录；原始事件和旧会话不因缺字段而重写。 */
    public ContinuationBudget continuationBudget;
    public TaskWorkflow workflow;
    public List<InstructionChange> instructionChanges;
    public Long taskInputBaseline, taskOutputBaseline;
    public com.google.gson.JsonObject observedPhaseTimings;
    public com.google.gson.JsonArray lastValidationSnapshot;
    public long validationSnapshotAt;
    public static final class InstructionChange {
        public String kind, text, source = "user_input";
        public long revision, time;
    }
    public List<String> guidance = new ArrayList<String>();
    public List<Child> children = new ArrayList<Child>();
    public String nextStep, processedRange;
    public List<FileEvidence> checkpointFiles = new ArrayList<FileEvidence>();
    public List<Evidence> evidence = new ArrayList<Evidence>();
    public StopGate.Decision stopDecision;
    public List<String> referenceContext=new ArrayList<String>();
    public boolean referenceContextTruncated;

    public static final class Evidence {
        public String id;
        public String taskId, scope, tool, result;
        public Long revision;
        public long time;
        public boolean success;
        /** Null on legacy records: never infer a mutation from missing arguments. */
        public Boolean mutation;
        public FileEvidence file;
        public com.google.gson.JsonObject validation;
        public FileEvidence validationFile;
    }

    public static final class Child {
        public String id, taskId, assignment, status, result, transcript;
        public String mode="worker", allowedTools="", model;
        public boolean isolatedOutput;
        public String outputDir;
        public int contextTokens,maxContextTokens,inputTokens,outputTokens,reasoningTokens;
        public boolean usageEstimated;
        public StopGate.Decision stopDecision;
        public Long revision;
        public String coordinatedStep;
        public Integer coordinatedAttempt;
    }

    public synchronized boolean begin(String input, boolean forceNew) {
        boolean keep = !forceNew && objective != null
                && (isContinuation(input) || (!"completed".equals(status) && isSupplementToOpenTask(input)));
        if (!keep) {
            id = UUID.randomUUID().toString();
            objective = input;
            latestUserRequirement = input;
            latestUserEventId = null;
            instructionRevision = 1L; checkpointRevision = 0L;
            startedAt=System.currentTimeMillis();lastFinishedAt=0;activeMillis=0;
            latestProgress = null;
            automaticContinuations = 0;
            continuationBudget = new ContinuationBudget();
            workflow = null;
            instructionChanges = new ArrayList<InstructionChange>();
            taskInputBaseline=null;taskOutputBaseline=null;
            lastValidationSnapshot=null;validationSnapshotAt=0;
            guidance = new ArrayList<String>();
            recordInstruction("start",input);
            nextStep=null; processedRange=null; checkpointFiles=new ArrayList<FileEvidence>();
            stopDecision=null;
            referenceContext=new ArrayList<String>();referenceContextTruncated=false;
        } else if (input != null && !input.trim().isEmpty()) {
            addGuidance(input);
            // Explicit human continuation opens a fresh bounded window; only
            // automatic continuations consume the automatic budget.
            automaticContinuations = 0;
            if (continuationBudget != null) continuationBudget.manualResume();
        }
        status = "running";
        pauseReason = null;
        pauseCode = null;
        turnStartedAt=System.currentTimeMillis();
        if(id==null)id=UUID.randomUUID().toString();
        return !keep;
    }

    /** Resume without inserting a synthetic USER instruction into the durable transcript. */
    public synchronized boolean beginAutomaticContinuation() {
        if (!canAutomaticallyContinue()) return false;
        automaticContinuations++;
        if (continuationBudget != null) continuationBudget.continued();
        status="running";pauseCode=null;pauseReason=null;turnStartedAt=System.currentTimeMillis();
        return true;
    }

    public synchronized boolean canAutomaticallyContinue() {
        return "paused".equals(status) && "round_limit".equals(pauseCode) && id != null && objective != null
                && (continuationBudget == null ? automaticContinuations < MAX_AUTOMATIC_CONTINUATIONS
                : continuationBudget.permits(automaticContinuations));
    }

    public synchronized void observeToolProgress(String tool, String args, boolean ok, String output, FileEvidence file) {
        if (continuationBudget == null) continuationBudget = new ContinuationBudget();
        continuationBudget.observe(tool,args,ok,output,file);
    }

    /** 保持旧GUI状态兼容，另外提供精确执行阶段，不把等待/重试当作完成。 */
    public synchronized String executionPhase() {
        if ("completed".equals(status)) return "completed";
        if ("running".equals(status) && ("waiting_user".equals(runtimePhase)||"waiting_external".equals(runtimePhase)||"verifying".equals(runtimePhase)||"retrying".equals(runtimePhase))) return runtimePhase;
        if ("running".equals(status)) return "running";
        if ("interrupted".equals(pauseCode)) return "interrupted";
        if ("round_limit".equals(pauseCode)) return "paused_budget";
        if (stopDecision != null && "waiting_user".equals(stopDecision.status)) return "waiting_user";
        return "paused".equals(status) ? "blocked" : status;
    }

    public static boolean isContinuation(String text) {
        if (text == null) return false;
        if (text.matches("(?is).*(?:新任务|另起任务|另一个项目|另外一个项目|换个项目|换一个项目|换个任务|换一个任务).*")) return false;
        return refersToOriginalRequest(text) || text.trim().matches("(?is)(继续.*|接着.*|恢复.*|完成.*(之前|未完成).*|为什么.*(停|不执行|不生成).*|怎么.*(停|不执行|不生成).*|又.*(停|不执行|不生成).*|好的|好|确认|同意|ok|yes|1)");
    }

    /** An explicit reference to the still-active original task is guidance, not a fresh goal. */
    public static boolean refersToOriginalRequest(String text) {
        if (text == null) return false;
        String s = text.trim();
        return s.matches("(?is).*(?:最开始|一开始|最初|起初|前面|之前|刚才)(?:.{0,24})(?:说的|提的|要求|任务|目标|交代|内容|约定).*" )
                && !s.matches("(?is).*(?:新任务|另外一个项目|换个项目|另起任务).*" );
    }

    /** Additive corrections to a paused/running task must not erase its first instruction. */
    private static boolean isSupplementToOpenTask(String text) {
        if(text==null)return false;
        String s=text.trim();
        return !s.matches("(?is).*(?:新任务|另一个项目|换个项目|重新开始一个).*" )
                && s.matches("(?is)^(?:还有(?:就是|一点|一个问题)?|另外(?:还)?|再(?:补充|加上|修改|改一下)|补充(?:一下)?|以及|同时|你(?:漏了|忘了)|别忘了)[，,：:。\\s]*.+");
    }

    /** Recover a small, source-labelled span from the immutable original request, including its clipped middle. */
    public synchronized String relevantOriginalExcerpt(String query) {
        if (objective == null || objective.length() <= 12000 || query == null) return "";
        String key=query.replaceAll("(?is)最开始|一开始|最初|起初|前面|之前|刚才|说的|提的|要求|任务|目标|内容|继续|问题|记得|忘了", "")
                .replaceAll("[\\s\\p{Punct}，。？！：；、]+", "");
        if (key.length() < 2) {
            if(!refersToOriginalRequest(query))return "";
            int middle=objective.length()/2;
            int start=Math.max(0,middle-1000),end=Math.min(objective.length(),middle+1000);
            return "[原始用户要求中段抽样，字符 "+start+"-"+end+"/"+objective.length()
                    +"；如有遗漏用 Memory.read_task 按页读取] "+objective.substring(start,end);
        }
        java.util.LinkedHashSet<String> terms=new java.util.LinkedHashSet<String>();
        for(int i=0;i+1<key.length();i++) terms.add(key.substring(i,i+2).toLowerCase(java.util.Locale.ROOT));
        int best=-1,score=0;
        String lower=objective.toLowerCase(java.util.Locale.ROOT);
        for(int start=4000;start<Math.max(4000,objective.length()-4000);start+=500) {
            int end=Math.min(objective.length()-4000,start+700);
            if(end<=start)break;
            String chunk=lower.substring(start,end);int n=0;
            for(String term:terms)if(chunk.contains(term))n++;
            if(n>score){score=n;best=start;}
        }
        if(best<0)return "";
        return "[原始用户要求中段，字符 "+best+"-"+Math.min(objective.length(),best+700)+"/"+objective.length()+"] "
                +objective.substring(best,Math.min(objective.length(),best+700));
    }

    /** Retrieve only older task-local corrections that fell out of the bounded prompt. */
    public synchronized String relevantGuidanceExcerpts(String query) {
        if(guidance==null||guidance.size()<=12||query==null)return "";
        String key=query.replaceAll("(?is)最开始|一开始|最初|前面|之前|刚才|说的|提的|要求|任务|内容|继续|忘了","")
                .replaceAll("[\\s\\p{Punct}，。？！：；、]+","").toLowerCase(java.util.Locale.ROOT);
        java.util.LinkedHashSet<String> terms=new java.util.LinkedHashSet<String>();
        for(int i=0;i+1<key.length();i++)terms.add(key.substring(i,i+2));
        int best=-1,bestScore=0;
        for(int i=0;i<guidance.size()-12;i++) {
            String item=guidance.get(i);if(item==null)continue;
            String lower=item.toLowerCase(java.util.Locale.ROOT);int score=0;
            for(String term:terms)if(lower.contains(term))score++;
            if(score>bestScore){bestScore=score;best=i;}
        }
        if(best<0) {
            if(!refersToOriginalRequest(query))return "";
            best=0;
        }
        String item=guidance.get(best);
        return "[当前任务第"+(best+1)+"条早期用户补充；完整原文可用 Memory.read_task mode=guidance id="+(best+1)+"] "
                +clip(item,800);
    }

    public synchronized void addGuidance(String text) {
        if (text == null || text.trim().isEmpty()) return;
        // Repeated resume/acknowledgment clicks are not new constraints. They must not
        // push real requirements out of the bounded task anchor after compaction.
        if (text.trim().matches("(?is)(继续|继续执行|继续完成之前的任务|继续未完成的任务|接着执行|恢复任务|好的|好|确认|同意|ok|yes|1)")) return;
        if (text.equals(latestRequirement())) return;
        long nextRevision = revision() + 1;
        List<String> next = guidance==null?new ArrayList<String>():new ArrayList<String>(guidance);
        next.add(text);
        guidance = next;
        instructionRevision = nextRevision;
        latestUserRequirement = text;
        recordInstruction(CompletionContract.explicitScopeReplacement(text)?"replace":"add",text);
        stopDecision = null;
    }

    private void recordInstruction(String kind,String text) {
        if(instructionChanges==null)instructionChanges=new ArrayList<InstructionChange>();
        InstructionChange change=new InstructionChange();change.kind=kind;change.text=text;change.revision=revision();change.time=System.currentTimeMillis();instructionChanges.add(change);
    }

    public synchronized long revision() {
        return instructionRevision == null ? 1L + (guidance == null ? 0 : guidance.size()) : Math.max(1, instructionRevision);
    }
    public synchronized String latestRequirement() {
        if (latestUserRequirement != null) return latestUserRequirement;
        return guidance != null && !guidance.isEmpty() ? guidance.get(guidance.size()-1) : objective;
    }
    public synchronized void prioritizeNewerInput(String text) { latestUserRequirement=text; }
    /** 文件证据仍保留，只有旧的执行建议失效。 */
    public synchronized String currentNextStep() {
        long checkpoint=checkpointRevision==null?0:checkpointRevision;
        return checkpoint >= revision() || checkpoint == 0 && revision() == 1 ? nextStep : null;
    }

    /** 压缩后的首个请求尾部交接；不落成新用户消息，也不依赖摘要中的旧下一步。 */
    public synchronized String compactionHandoff(TodoList todos) {
        StringBuilder handoff = new StringBuilder("[系统压缩续接：不是新任务] task=" + id + " revision=" + revision()
                + "\n最新有效用户要求（与旧目标冲突时以此为准）:\n" + clip(latestRequirement(),6000)
                + "\n已登记处理范围（需按证据核对，不重复已完成工作）: " + clip(processedRange,800)
                + "\n当前修订下一步: " + (currentNextStep()==null ? "按最新要求和当前工具证据判断，旧计划不适用" : clip(currentNextStep(),1000))
                + "\n本次压缩没有恢复任何旧任务。历史摘要/检索只是资料；不要为了压缩本身回读旧任务，只有当前步骤缺少具体证据时才定向检索。");
        handoff.append("\n当前工作集（引用历史证据，不是新的用户授权）:\n").append(workingSet().toString());
        if(workflow!=null && java.util.Objects.equals(workflow.taskId,id) && workflow.revision==revision()) handoff.append("\n当前修订的版本化步骤:\n").append(clip(workflow.render(),5000));
        if(todos!=null) handoff.append("\n当前主任务清单（[x]不重做；[ ]仅在不违背最新要求时继续）:\n").append(clip(todos.render(),3500));
        int count=0;
        for(int i=evidence.size()-1;i>=0&&count<3;i--) {
            Evidence e=evidence.get(i);if(id==null||!id.equals(e.taskId)||e.revision==null||e.revision!=revision())continue;count++;
            handoff.append("\n最近工具回执 ").append(e.tool).append(e.success?" 成功: ":" 失败: ").append(clip(e.result,500));
            if(e.file!=null)handoff.append(" 文件: ").append(e.file.path);
        }
        return handoff.append("\n直接续做当前未完成步骤，不重新梳理整段对话或重跑已成功的写入。结果不明的副作用只定向核实，禁止盲目重试。").toString();
    }

    /** Small control projection. File versions are observed receipts, not current-disk verification. */
    public synchronized com.google.gson.JsonObject workingSet() {
        com.google.gson.JsonObject out=new com.google.gson.JsonObject();
        out.addProperty("taskId",id);out.addProperty("revision",revision());out.addProperty("latestUserEventId",latestUserEventId);
        out.addProperty("latestRequirement",clip(latestRequirement(),3000));out.addProperty("nextStep",currentNextStep());
        com.google.gson.JsonArray active=new com.google.gson.JsonArray(),remaining=new com.google.gson.JsonArray(),files=new com.google.gson.JsonArray();
        if(workflow!=null && java.util.Objects.equals(workflow.taskId,id) && workflow.revision==revision()) {
            for(TaskWorkflow.Step step:workflow.steps) {
                if(!"running".equals(step.status)&&!"pending".equals(step.status)&&!"blocked".equals(step.status))continue;
                com.google.gson.JsonObject item=new com.google.gson.JsonObject();item.addProperty("stepId",step.id);
                item.addProperty("status",step.status);item.addProperty("scope",clip(step.scope,300));item.addProperty("acceptance",clip(step.acceptance,400));
                if("running".equals(step.status)){if(active.size()<4)active.add(item);}else if(remaining.size()<8)remaining.add(item);
            }
        }
        java.util.Set<String> paths=new java.util.HashSet<String>();
        for(int i=evidence.size()-1;i>=0&&files.size()<4;i--) {
            Evidence e=evidence.get(i);
            if(!java.util.Objects.equals(id,e.taskId)||e.revision==null||e.revision!=revision()||e.file==null||!e.success||!paths.add(e.file.path))continue;
            com.google.gson.JsonObject file=new com.google.gson.JsonObject();file.addProperty("receiptId",e.id);file.addProperty("path",e.file.path);
            file.addProperty("observedSha256",e.file.sha256);file.addProperty("observedAt",e.time);files.add(file);
        }
        out.add("activeSteps",active);out.add("remainingSteps",remaining);out.add("observedFiles",files);
        return out;
    }

    /** One-time V22 migration; tool, skill and repair messages are not tasks. */
    public synchronized void restoreFromHistory(List<com.minion.core.llm.Message> messages) {
        if(objective != null) return;
        int goalIndex = -1;
        for(int i=messages.size()-1;i>=0;i--) {
            com.minion.core.llm.Message m=messages.get(i);
            if(m.content==null || m.role!=com.minion.core.llm.Message.Role.USER || m.pinned || m.supplement || m.toolImage) continue;
            if(m.summary) {
                String start="【当前任务原文】\n", end="\n【历史背景】\n";
                int a=m.content.indexOf(start), b=m.content.indexOf(end,a+start.length());
                if(a>=0 && b>a) { objective=m.content.substring(a+start.length(),b); goalIndex=i; break; }
            } else if(m.isUserInstruction() && !isContinuation(m.content)) {
                objective=m.content; goalIndex=i; break;
            }
        }
        if(objective!=null) {
            id=UUID.randomUUID().toString(); status="paused"; instructionRevision=1L;
            for(int i=goalIndex+1;i<messages.size();i++) {
                com.minion.core.llm.Message m=messages.get(i);
                if(m.supplement && m.content!=null) addGuidance(m.content);
            }
        }
    }

    public synchronized void progress(String text) {
        if (text != null && !text.trim().isEmpty()) latestProgress = clip(text, 2400);
    }

    public synchronized Child startChild(String assignment) {
        Child c = new Child();
        c.id = UUID.randomUUID().toString(); c.taskId = id;c.revision=revision();
        c.assignment = assignment; c.status = "running";
        List<Child> next = new ArrayList<Child>(children);
        next.add(c); children = next;
        return c;
    }

    public synchronized void finishChild(String childId, String result, boolean success, String path) {
        List<Child> next = new ArrayList<Child>();
        for (Child old : children) {
            if (!old.id.equals(childId)) { next.add(old); continue; }
            Child c = copy(old);
            c.status=success ? "returned_needs_review" : "failed_or_interrupted";
            c.result=clip(result, 1400); c.transcript=path; next.add(c);
        }
        children = next;
    }

    public synchronized void finish(boolean success) { finish(success,null,null); }
    public synchronized void finish(boolean success,String reason) { finish(success,reason,null); }
    public synchronized void finish(boolean success,String reason,String code) {
        runtimePhase=null;
        long now=System.currentTimeMillis();if(turnStartedAt>0){activeMillis+=Math.max(0,now-turnStartedAt);turnStartedAt=0;}
        lastFinishedAt=now;status = success ? "completed" : "paused";
        pauseCode=success?null:code;
        pauseReason=success?null:clip(reason!=null&&!reason.trim().isEmpty()?reason:
                stopDecision!=null&&stopDecision.reason!=null?stopDecision.reason:
                        "本轮在完成核验前停止；请查看最近错误或从断点继续",1000);
    }
    public synchronized void recordStop(StopGate.Decision decision) {stopDecision=decision;}
    /** 有界保留指代所需的最近对话，不把旧任务变成当前指令。 */
    public synchronized void bindReferenceHistory(List<com.minion.core.llm.Message> history) {
        List<String> refs=new ArrayList<String>();boolean clipped=false;
        for(int i=history.size()-1;i>=0&&refs.size()<4;i--) {
            com.minion.core.llm.Message m=history.get(i);
            if(m==null||m.summary||m.pinned||m.toolImage||m.content==null||m.content.trim().isEmpty())continue;
            if(m.role==com.minion.core.llm.Message.Role.USER&&m.content.startsWith("[系统"))continue;
            if(m.role!=com.minion.core.llm.Message.Role.USER&&m.role!=com.minion.core.llm.Message.Role.ASSISTANT)continue;
            if(m.toolCalls!=null&&!m.toolCalls.isEmpty())continue;
            clipped|=m.content.length()>2400;refs.add(m.role+": "+clip(m.content,2400));
        }
        java.util.Collections.reverse(refs);referenceContext=refs;referenceContextTruncated=clipped;
    }
    public synchronized void childStop(String childId,StopGate.Decision decision) {
        List<Child> next=new ArrayList<Child>();for(Child old:children){Child c=copy(old);if(c.id.equals(childId))c.stopDecision=decision;next.add(c);}children=next;
    }

    public synchronized void recoverAfterRestart() {
        if (workflow != null) workflow.recoverAfterRestart();
        if("running".equals(status)) { status="paused"; pauseCode="restart"; pauseReason="程序在任务进行中退出；请核对最后工具副作用后从断点继续"; }
        turnStartedAt=0; // An interrupted interval has no reliable end time.
        List<Child> next=new ArrayList<Child>();
        for(Child old:children) {
            if(!"running".equals(old.status)) { next.add(old); continue; }
            Child c=copy(old);
            c.status="interrupted_needs_review"; c.result=old.result; c.transcript=old.transcript; next.add(c);
        }
        children=next;
    }

    private static Child copy(Child c) { com.google.gson.Gson g=new com.google.gson.Gson(); return g.fromJson(g.toJson(c),Child.class); }
    private List<Child> currentChildRefs() {
        java.util.Map<String,Integer> latest=new java.util.HashMap<String,Integer>();
        for(Child c:children)if(id!=null&&id.equals(c.taskId)&&c.coordinatedStep!=null&&(c.revision==null||c.revision==revision()))
            latest.put(c.coordinatedStep,Math.max(latest.getOrDefault(c.coordinatedStep,0),c.coordinatedAttempt==null?1:c.coordinatedAttempt));
        List<Child> result=new ArrayList<Child>(); for(Child c:children) if(id!=null&&id.equals(c.taskId)
                &&(c.coordinatedStep==null||(c.revision==null||c.revision==revision())
                &&latest.get(c.coordinatedStep)==(c.coordinatedAttempt==null?1:c.coordinatedAttempt))) result.add(c); return result;
    }
    public synchronized List<Child> childSnapshot() {
        List<Child> out=new ArrayList<Child>();for(Child c:currentChildRefs())out.add(copy(c));return out;
    }
    /** Bounded UI projection: never JSON-serializes complete child transcripts every timer tick. */
    public synchronized List<Child> childRows(int limit) {
        List<Child> refs=currentChildRefs(),out=new ArrayList<Child>();int max=Math.max(1,Math.min(64,limit));
        for(int i=Math.max(0,refs.size()-max);i<refs.size();i++){
            Child c=refs.get(i),r=new Child();r.id=c.id;r.taskId=c.taskId;r.revision=c.revision;
            r.assignment=clip(c.assignment,1000);r.result=clip(c.result,4096);r.status=c.status;r.mode=c.mode;
            r.allowedTools=c.allowedTools;r.model=c.model;r.isolatedOutput=c.isolatedOutput;r.outputDir=c.outputDir;
            r.transcript=c.transcript;r.coordinatedStep=c.coordinatedStep;r.coordinatedAttempt=c.coordinatedAttempt;
            r.contextTokens=c.contextTokens;r.maxContextTokens=c.maxContextTokens;r.inputTokens=c.inputTokens;
            r.outputTokens=c.outputTokens;r.reasoningTokens=c.reasoningTokens;r.usageEstimated=c.usageEstimated;
            out.add(r);
        }return out;
    }
    public synchronized Child child(String childId) { for(Child c:children) if(c.id.equals(childId)) return copy(c); return null; }
    public synchronized void setChildStatus(String childId,String status){
        List<Child> next=new ArrayList<Child>();for(Child old:children){Child c=copy(old);if(c.id.equals(childId))c.status=status;next.add(c);}children=next;
    }
    public synchronized void coordinateChild(String childId,String step){
        coordinateChild(childId,step,1);
    }
    public synchronized void coordinateChild(String childId,String step,int attempt){
        List<Child> next=new ArrayList<Child>();for(Child old:children){Child c=copy(old);if(c.id.equals(childId)){c.coordinatedStep=step;c.coordinatedAttempt=attempt;}next.add(c);}children=next;
    }
    public synchronized void configureChild(String childId,String mode,String tools,String model,String path) {
        List<Child> next=new ArrayList<Child>();
        for(Child old:children) { Child c=copy(old); if(c.id.equals(childId)) { c.mode=mode; c.allowedTools=tools; c.model=model; c.transcript=path; c.status="running"; } next.add(c); }
        children=next;
    }
    public synchronized void isolateChild(String childId,String directory){
        List<Child> next=new ArrayList<Child>();for(Child old:children){Child c=copy(old);if(c.id.equals(childId)){c.isolatedOutput=true;c.outputDir=directory;}next.add(c);}children=next;
    }
    public synchronized void childStats(String childId,int used,int max,com.minion.core.llm.Usage usage) {
        List<Child> next=new ArrayList<Child>();
        for(Child old:children) { Child c=copy(old); if(c.id.equals(childId)) {
            c.contextTokens=used; c.maxContextTokens=max;
            if(usage!=null) { c.inputTokens+=usage.inputTokens; c.outputTokens+=usage.outputTokens; c.reasoningTokens+=usage.reasoningTokens; c.usageEstimated|=usage.estimated; }
        } next.add(c); } children=next;
    }
    public synchronized boolean reviewChild(String childId) {
        Child target=child(childId);
        if(target==null||!id.equals(target.taskId)) return false;
        boolean returned="returned_needs_review".equals(target.status);
        boolean failedTakeover=target.coordinatedStep==null
                && ("failed_or_interrupted".equals(target.status)||"failed".equals(target.status));
        if(!returned&&!failedTakeover) return false;
        String nextStatus=returned?"reviewed":"cancelled";
        List<Child> next=new ArrayList<Child>(); for(Child old:children) { Child c=copy(old); if(c.id.equals(childId)) c.status=nextStatus; next.add(c); } children=next; return true;
    }
    public synchronized boolean cancelChild(String childId,String reason) {
        Child target=child(childId);
        if(target==null||!id.equals(target.taskId)||target.coordinatedStep!=null) return false;
        if(!java.util.Arrays.asList("running","failed_or_interrupted","failed","cancelled").contains(target.status)) return false;
        List<Child> next=new ArrayList<Child>();
        for(Child old:children) {
            Child c=copy(old);
            if(c.id.equals(childId)) {
                c.status="cancelled";
                String note="已取消/由主Agent接管"+(reason==null||reason.trim().isEmpty()?"":"："+reason.trim());
                c.result=c.result==null||c.result.trim().isEmpty()?note:clip(c.result+"\n"+note,12000);
            }
            next.add(c);
        }
        children=next;
        return true;
    }
    public synchronized boolean hasPendingChildReviews() {
        for(Child c:children) if(id!=null&&id.equals(c.taskId)&&"returned_needs_review".equals(c.status)) return true;
        return false;
    }
    public synchronized void recordEvidence(String scope,String tool,boolean ok,String output,FileEvidence file) {
        recordEvidence(scope,tool,ok,output,file,null);
    }
    public synchronized void recordEvidence(String scope,String tool,boolean ok,String output,FileEvidence file,Boolean mutation) {
        Child owner=scope==null||"root".equals(scope)?null:child(scope);
        Evidence e=new Evidence(); e.id=UUID.randomUUID().toString();e.taskId=owner==null?id:owner.taskId; e.scope=scope; e.tool=tool; e.success=ok; e.time=System.currentTimeMillis(); e.result=clip(output,800); e.file=file;e.mutation=mutation;
        e.revision=owner==null?revision():owner.revision;
        List<Evidence> next=new ArrayList<Evidence>(evidence); next.add(e); evidence=next;
    }
    public synchronized void recordToolEvidence(String tool,boolean ok,String output,FileEvidence file,Boolean mutation,java.util.function.Predicate<java.nio.file.Path> allowed) {
        recordEvidence("root",tool,ok,output,file,mutation);
        Evidence actual=evidence.get(evidence.size()-1);actual.validation=TaskValidation.report(tool,output);
        if(actual.validation!=null)try {
            java.nio.file.Path report=java.nio.file.Paths.get(actual.validation.get("reportPath").getAsString()).toAbsolutePath().normalize();
            if(allowed.test(report))actual.validationFile=FileEvidence.capture(report);
        }catch(Exception ignored){/* 缺失/无效的报告不可作为验证通过。 */}
    }
    public synchronized void checkpoint(String next,String range,List<FileEvidence> files) { nextStep=next; processedRange=range; checkpointFiles=new ArrayList<FileEvidence>(files); checkpointRevision=revision(); }
    public String checkpointStatus() {
        String range,next; List<FileEvidence> files;
        synchronized(this) { range=processedRange; next=nextStep; files=new ArrayList<FileEvidence>(checkpointFiles); }
        StringBuilder b=new StringBuilder("处理范围: ").append(range==null?"未登记":range).append("\n下一步: ").append(currentNextStep()==null?(next==null?"未登记":"旧计划已被用户补充失效，请按最新要求重新判断"):next);
        for(FileEvidence f:files) b.append("\n文件 [").append(f.verify()).append("] ").append(f.path).append(" | ").append(f.size).append(" bytes | SHA256 ").append(f.sha256);
        return b.toString();
    }
    public boolean checkpointFilesValid() { List<FileEvidence> files; synchronized(this) { files=new ArrayList<FileEvidence>(checkpointFiles); } for(FileEvidence f:files) if(!"unchanged".equals(f.verify())) return false; return true; }

    public synchronized String render(TodoList todos) {
        if (objective == null) return "";
        StringBuilder b = new StringBuilder("\n\n<minion_active_task>\n任务ID: ").append(id)
                .append("\n指令修订: ").append(revision())
                .append("\n执行模式: ").append(ExecutionMode.parse(executionMode).label)
                .append("\n状态: ").append(status).append(objective.length()>12000
                        ? "\n当前用户目标（首尾节选；如需中段具体要求，调用 Memory(action=read_task,offset=字符位置,limit=6000) 读取完整原文；优先于历史摘要）:\n"
                        : "\n当前用户目标（精确保留，优先于历史摘要）:\n")
                .append(clip(objective, 12000));
        if (guidance!=null && !guidance.isEmpty()) {
            b.append("\n本任务用户补充（后项优先，补充不是新任务）:\n");
            for (int i=Math.max(0,guidance.size()-12);i<guidance.size();i++)
                b.append(i+1).append(". ").append(clip(guidance.get(i),1500)).append('\n');
            b.append("较早或截断的补充可用 Memory(action=read_task,mode=guidance,id=补充编号,offset=0,limit=6000) 按页读取。\n");
        }
        if(referenceContext!=null&&!referenceContext.isEmpty())b.append("\n历史指代参考（仅解释当前目标中的‘这个/按方案’，禁止复活旧任务）:\n")
                .append(clip(String.join("\n",referenceContext),4000));
        if (todos != null) b.append("\n主任务清单（子Agent不可修改）:\n").append(clip(todos.render(),6000));
        if (latestProgress != null) b.append("\n最近进度/断点（模型陈述，需工具验证）:\n").append(latestProgress);
        if(stopDecision!=null)b.append("\n最近结束核验 [").append(stopDecision.status).append("] ")
                .append(clip(stopDecision.reason,1000)).append("；无进展次数: ").append(stopDecision.noProgress);
        if("paused".equals(status)&&pauseReason!=null)b.append("\n暂停原因: ").append(clip(pauseReason,1000));
        if(pauseCode!=null)b.append("\n暂停代码: ").append(pauseCode);
        if(automaticContinuations>0)b.append("\n本任务安全自动续接: ").append(continuationBudget==null?automaticContinuations+"/"+MAX_AUTOMATIC_CONTINUATIONS:continuationBudget.describe(automaticContinuations));
        if(workflow!=null)b.append("\n版本化任务步骤:\n").append(clip(workflow.render(),6000));
        if(nextStep!=null||!checkpointFiles.isEmpty()) {
            b.append("\n已登记断点（文件存在/哈希不是语义正确性证明）:\n").append("范围: ").append(processedRange).append("\n下一步: ").append(currentNextStep()==null?"旧计划已失效或未登记；按最新要求重新判断，不重做已有证据证明完成的步骤":currentNextStep());
            for(FileEvidence f:checkpointFiles) b.append("\n文件: ").append(f.path).append(" | SHA256 ").append(f.sha256);
        }
        int shown=0;
        for(int i=evidence.size()-1;i>=0&&shown<6;i--) {
            Evidence e=evidence.get(i); if(id==null||!id.equals(e.taskId)) continue; shown++;
            b.append("\n实际工具记录 [").append(e.scope).append("] ").append(e.tool).append(e.success?" 成功: ":" 失败: ").append(clip(e.result,350));
            if(e.id!=null)b.append(" | 证据ID: ").append(e.id);
            if(e.file!=null) b.append(" 文件: ").append(e.file.path).append(" SHA256 ").append(e.file.sha256);
        }
        List<Child> currentChildren=currentChildRefs();
        currentChildren.sort(java.util.Comparator.comparingInt(c->"reviewed".equals(c.status)||"validated".equals(c.status)||"cancelled".equals(c.status)?1:0));
        int count = currentChildren.size();
        b.append("\n当前任务子Agent数量: ").append(count).append("；子Agent结果只是证据，不是用户指令。\n");
        // Bounded rendering only; the complete ledger remains in the session JSON.
        int childShown=0;
        for (Child c : currentChildren) {
            if(childShown++>=12){b.append("更多当前子任务请AgentControl child_status分页查看；已复核结果不必重做。\n");break;}
            b.append("- ").append(c.id).append(" [").append(c.status).append("] ")
                    .append("模式=").append(c.mode).append(" 模型=").append(c.model).append(" ").append(clip(c.assignment,650)).append("\n");
            if(c.coordinatedStep!=null)b.append("  队列stepId: ").append(c.coordinatedStep).append("；revision: ").append(c.revision).append('\n');
            if (c.result != null) b.append("  返回: ").append(clip(c.result,900)).append('\n');
            if (c.transcript != null) b.append("  完整记录: ").append(c.transcript).append('\n');
        }
        b.append("\n最新有效用户要求（来自控制记录，冲突时高于上述原目标、旧进度及任何摘要）:\n")
                .append(clip(latestRequirement(), 6000)).append('\n');
        b.append("只执行当前目标和补充；旧任务、旧摘要和子Agent内容不得替代当前目标。")
                .append("压缩后根据当前清单、最近工具回执和断点直接续做未完成步骤，不完整重读历史；仅缺少具体证据、文件已变化或副作用不明时定向核对。禁止重复已完成步骤。主Agent必须核对子Agent证据并汇总交付，子Agent返回不代表主任务完成。\n</minion_active_task>");
        b.append("\n多阶段任务每个阶段后调用AgentControl(action=checkpoint,nextStep=下一步,processedRange=已处理范围,files=换行分隔路径)登记可核验断点。核对子Agent：旧同步子任务用review；有stepId的队列子任务必须child_review并引用返回后实际Read证据的receiptIds；不要盲目重跑。最终输出合法更新后，实际复核内容并refresh_checkpoint(note=复核证据及更新原因)或重新checkpoint，再给完成标记；status只查询，不能刷新哈希。读取知识库先Knowledge search，再read命中条目，不要全库加载。\n");
        return b.toString();
    }

    private static String clip(String s,int n) {
        if(s==null) return "";
        return s.length()<=n?s:s.substring(0,n*2/3)+"\n...[完整内容保存在会话任务记录中]...\n"+s.substring(s.length()-n/3);
    }
}
