package com.minion.core.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.tools.AtomicFiles;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.TaskTool;
import com.minion.core.tools.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * F 控制面。独立执行器，不占主循环 tools 池等待；子结果不是主任务的完成证据。
 * 宿主负责把 scope 落实到实际工具策略/隔离工作区；此类不是 OS 沙箱。
 */
public final class ChildTaskCoordinator implements AutoCloseable, TaskTool.Dispatcher {
    public interface Runner { Outcome execute(Assignment assignment, Cancellation cancellation) throws Exception; }
    public static final class Assignment {
        public String stepId, description, prompt="", acceptance, mode="worker", allowedTools="";
        public List<String> scope=new ArrayList<String>(), dependsOn=new ArrayList<String>();
        public boolean isolatedOutput, requiresMerge;
        /** 从登记开始计时，包含排队；1 秒到 24 小时，防止依赖无限等待。 */
        public long timeoutMillis=30*60*1000L;
    }
    public static final class Outcome {
        public boolean success;
        public String summary, acceptanceReport;
        public List<String> evidencePaths=new ArrayList<String>();
        public static Outcome returned(String summary,String report,List<String> paths){Outcome o=new Outcome();o.success=true;o.summary=summary;o.acceptanceReport=report;o.evidencePaths=new ArrayList<String>(paths);return o;}
        public static Outcome failed(String reason){Outcome o=new Outcome();o.summary=reason;return o;}
    }
    public static final class Record {
        public Assignment assignment;
        public String id, childId, taskId, status="queued", error, parentReview, validationReport;
        public long revision, submittedAt, startedAt, finishedAt;
        public int attempt=1;
        public Outcome outcome;
        public String executionRoot;
        public ChildTaskIsolation.Record isolation;
        public List<FileEvidence> evidence=new ArrayList<FileEvidence>(), mergedFiles=new ArrayList<FileEvidence>();
        public List<String> reviewReceiptIds=new ArrayList<String>(), validationReceiptIds=new ArrayList<String>();
        public List<Attempt> previousAttempts=new ArrayList<Attempt>();
    }
    public static final class Attempt {
        public int number;
        public String childId,status,error,executionRoot;
        public Outcome outcome;
        public ChildTaskIsolation.Record isolation;
        public List<FileEvidence> evidence,mergedFiles;
        public List<String> reviewReceiptIds,validationReceiptIds;
    }
    /** 只含数据，Gson 可直接序列化；不序列化线程/Future/回调。 */
    public static final class Snapshot {
        public int version=1;
        public String workspace, taskId;
        public long revision;
        public List<Record> records=new ArrayList<Record>();
    }
    private static final ThreadFactory DAEMON=r->{Thread t=new Thread(r,"minion-child-control");t.setDaemon(true);return t;};
    private static final ThreadPoolExecutor CANCEL_CALLBACKS=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(128),DAEMON,new ThreadPoolExecutor.AbortPolicy());
    public static final class Cancellation {
        private volatile boolean cancelled;
        private volatile Runnable hook;
        public boolean isCancelled(){return cancelled||Thread.currentThread().isInterrupted();}
        /** 只能登记一个非阻塞钩子，通常为 SubAgentLoop.interrupt；无主线程阻塞调用。 */
        public synchronized void onCancel(Runnable action){if(hook!=null)throw new IllegalStateException("取消钩子已登记");hook=Objects.requireNonNull(action);if(cancelled)invoke(action);}
        public void check() throws InterruptedException{if(isCancelled())throw new InterruptedException("子任务已取消");}
        private synchronized void cancel(){if(cancelled)return;cancelled=true;if(hook!=null)invoke(hook);}
        private static void invoke(Runnable action){try{CANCEL_CALLBACKS.execute(action);}catch(RejectedExecutionException ignored){/* 线程中断仍有效；槽和写租约绝不提前释放。 */}}
    }
    private static final class Running { Thread thread; Cancellation cancellation=new Cancellation(); String lease; }
    private static final Map<String,List<String>> WRITE_LEASES=new HashMap<String,List<String>>();
    private final Path root;
    private final String taskId, owner=UUID.randomUUID().toString();
    private final long revision;
    private final int maximum;
    private final Runner runner;
    private final Gson gson=new Gson();
    private final Map<String,Record> records=new LinkedHashMap<String,Record>();
    private final Map<String,Running> running=new HashMap<String,Running>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(DAEMON);
    private int capacity;
    private boolean closed, recoveryHold;
    private String controlError;
    private Path journal;

    public ChildTaskCoordinator(Path root,String taskId,long revision,int maxConcurrency,Runner runner)throws IOException{
        this.root=root.toRealPath();this.taskId=required(taskId,"taskId");this.revision=revision;
        if(revision<1||maxConcurrency<1||maxConcurrency>16)throw new IllegalArgumentException("revision/concurrency非法（1..16）");
        this.maximum=maxConcurrency;this.capacity=maxConcurrency;this.runner=Objects.requireNonNull(runner);
        timer.scheduleWithFixedDelay(()->{synchronized(this){try{tick();}catch(Throwable e){failControl(e);}}},10,25,TimeUnit.MILLISECONDS);
    }
    /** 可选独立 journal；须在提交前绑定，禁止覆盖已有记录。已有记录用 restore。 */
    public synchronized void journal(Path path)throws Exception{
        ensureOpen();if(!records.isEmpty()||Files.exists(path))throw new IOException("journal必须在提交前绑定且不能覆盖已有文件");
        Path p=path.toAbsolutePath().normalize();if(!PathsGuard.inside(root.toString(),p))throw new IOException("journal越界");journal=p;persist();
    }
    @Override public ToolResult submit(JsonObject args){
        try{
            if(args.has("model"))return ToolResult.errorWithoutSideEffects("子Agent继承父模型，不能指定model");
            Record record=submitBatch(Collections.singletonList(parseAssignment(args))).get(0);
            return ToolResult.success("已登记排队（不是子任务完成），主Agent需复核：\n"+gson.toJson(record));
        }catch(Exception e){return ToolResult.errorWithoutSideEffects("子任务未登记: "+e.getMessage());}
    }
    /** 批量登记（task.assignments）：整批校验，任一条非法整批不登记；依赖就绪后按容量并行执行。 */
    @Override public ToolResult submitBatch(JsonObject args){
        try{
            List<JsonObject> items=new ArrayList<JsonObject>();
            for(JsonElement e:args.getAsJsonArray("assignments"))items.add(e.getAsJsonObject());
            List<Record> records=submitBatchArgs(items);
            return ToolResult.success("已批量登记排队 "+records.size()+" 条（不是子任务完成），依赖就绪即并行执行，主Agent需复核：\n"+gson.toJson(records));
        }catch(Exception e){return ToolResult.errorWithoutSideEffects("子任务批量未登记: "+e.getMessage());}
    }
    public synchronized List<Record> submitBatchArgs(List<JsonObject> items)throws Exception{
        List<Assignment> assignments=new ArrayList<Assignment>();
        for(JsonObject args:items){
            if(args.has("model"))throw new IllegalArgumentException("子Agent继承父模型，不能指定model");
            assignments.add(parseAssignment(args));
        }
        return submitBatch(assignments);
    }
    private Assignment parseAssignment(JsonObject args){
        Assignment a=new Assignment();a.stepId=string(args,"stepId");a.description=string(args,"description");a.prompt=string(args,"prompt");a.acceptance=string(args,"acceptance");
        if(args.has("mode"))a.mode=string(args,"mode");a.allowedTools=string(args,"allowedTools");a.scope=list(args,"scope");a.dependsOn=list(args,"dependsOn");
        if(args.has("isolatedOutput"))a.isolatedOutput=args.get("isolatedOutput").getAsBoolean();if(args.has("requiresMerge"))a.requiresMerge=args.get("requiresMerge").getAsBoolean();
        if(args.has("timeoutMillis"))a.timeoutMillis=args.get("timeoutMillis").getAsLong();
        return a;
    }
    public synchronized List<Record> submitBatch(List<Assignment> assignments)throws Exception{
        ensureOpen();if(assignments==null||assignments.isEmpty()||records.size()+assignments.size()>1024)throw new IllegalArgumentException("批次为空或任务数超过1024");
        Map<String,Record> next=new LinkedHashMap<String,Record>(records);List<Record> added=new ArrayList<Record>();long now=System.currentTimeMillis();
        for(Assignment original:assignments){
            Assignment a=copy(original,Assignment.class);validate(a);
            if(next.containsKey(a.stepId))throw new IllegalArgumentException("重复stepId: "+a.stepId);
            Record record=new Record();record.assignment=a;record.id=UUID.randomUUID().toString();record.taskId=taskId;record.revision=revision;record.submittedAt=now;
            next.put(a.stepId,record);added.add(record);
        }
        validateGraph(next);
        // 唯一交付范围：同一协调记录中的 writer 不重复提交；重做只能显式 retry 原记录。
        validateScopes(next);
        records.putAll(next);
        try{persist();}catch(Exception e){for(Record r:added)records.remove(r.assignment.stepId);throw e;}
        List<Record> result=new ArrayList<Record>();for(Record r:added)result.add(copy(r,Record.class));return result;
    }
    private void validate(Assignment a)throws Exception{
        if(a==null)throw new IllegalArgumentException("空assignment");required(a.stepId,"stepId");required(a.description,"description");required(a.acceptance,"acceptance");
        if(a.description.length()>16000||(a.prompt!=null&&a.prompt.length()>32000)||a.acceptance.length()>8000||(a.allowedTools!=null&&a.allowedTools.length()>2000))throw new IllegalArgumentException("分工过长，请把完整资料落文件并只提交范围/验收/证据路径");
        // 校验文案写成“能照着改”的指引：内网弱模型拿到“stepId/mode非法”这类简略提示时只会放弃派发
        if(!a.stepId.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("stepId 只能由英文、数字、下划线、点、短横线组成（1..128 位），收到「"
                +(a.stepId.length()>60?a.stepId.substring(0,60)+"…":a.stepId)+"」");
        if(!Arrays.asList("worker","explore").contains(a.mode))throw new IllegalArgumentException("mode 只支持 worker（可写，需要 scope）或 explore（只读），收到「"+a.mode+"」");
        if(a.timeoutMillis<1000||a.timeoutMillis>TimeUnit.HOURS.toMillis(24))throw new IllegalArgumentException("timeoutMillis 须为 1000..86400000 毫秒（如 600000 表示 10 分钟），收到 "+a.timeoutMillis);
        if(a.scope==null||a.scope.isEmpty())throw new IllegalArgumentException("缺少 scope：请给出允许写入的相对路径前缀数组（如 [\"src/\"]）；只读任务可填 [\".\"]");
        if(a.scope.size()>128||a.dependsOn==null||a.dependsOn.size()>128)throw new IllegalArgumentException("scope 与 dependsOn 各最多 128 项；没有依赖时 dependsOn 填 []");
        List<String> scopes=new ArrayList<String>();for(String s:a.scope){
            required(s,"scope");s=s.replace('\\','/');Path relative=Paths.get(s);
            if(relative.isAbsolute()||s.contains(":")||s.contains("*")||s.contains("?")||s.matches("(^|.*/)\\.\\.(/.*|$)"))throw new IllegalArgumentException("scope只支持相对路径前缀，禁止glob/越界");
            Path p=root.resolve(relative).normalize();if(!PathsGuard.inside(root.toString(),p))throw new IOException("scope越界/符号链接: "+s);
            Path probe=p;List<Path> tail=new ArrayList<Path>();while(!Files.exists(probe,java.nio.file.LinkOption.NOFOLLOW_LINKS)){tail.add(probe.getFileName());probe=probe.getParent();}
            Path real=probe.toRealPath();for(int i=tail.size()-1;i>=0;i--)real=real.resolve(tail.get(i));
            String canonical=root.relativize(real).toString().replace('\\','/');if(canonical.isEmpty())canonical=".";
            if(writes(a)&&(canonical.length()>1024||reserved(canonical)))throw new IllegalArgumentException("worker范围不能包含协调/Git元数据或Windows路径别名");
            for(String segment:canonical.split("/"))if(!".".equals(segment)&&(segment.endsWith(".")||segment.endsWith(" ")))throw new IllegalArgumentException("scope禁止Windows尾点/空格别名");
            for(String old:scopes)if(overlap(Collections.singletonList(old),Collections.singletonList(canonical)))throw new IllegalArgumentException("scope重复/嵌套: "+s);scopes.add(canonical);
        }a.scope=scopes;
        Set<String> deps=new HashSet<String>();for(String dep:a.dependsOn)if(!deps.add(required(dep,"dependsOn")))throw new IllegalArgumentException("依赖重复");
        if("explore".equals(a.mode)&&a.requiresMerge)throw new IllegalArgumentException("explore不能合并写入");
    }
    private static void validateGraph(Map<String,Record> map){Map<String,Integer> colors=new HashMap<String,Integer>();for(String key:map.keySet())visit(key,map,colors);}
    private static void validateScopes(Map<String,Record> map){List<Record> all=new ArrayList<Record>(map.values());for(int i=0;i<all.size();i++)for(int j=i+1;j<all.size();j++)if(writes(all.get(i).assignment)&&writes(all.get(j).assignment)&&overlap(all.get(i).assignment.scope,all.get(j).assignment.scope))throw new IllegalArgumentException("worker写范围重复/重叠: "+all.get(i).assignment.stepId+" / "+all.get(j).assignment.stepId);}
    private static void visit(String key,Map<String,Record> map,Map<String,Integer> colors){
        Integer color=colors.get(key);if(color!=null){if(color==1)throw new IllegalArgumentException("依赖成环: "+key);return;}colors.put(key,1);
        for(String dep:map.get(key).assignment.dependsOn){if(!map.containsKey(dep))throw new IllegalArgumentException("缺失依赖: "+dep);visit(dep,map,colors);}colors.put(key,2);
    }
    /** 独立物理槽：Future.cancel 不等于旧线程已退出，绝不靠它释放槽。 */
    private void tick()throws Exception{
        if(closed||controlError!=null)return;long now=System.currentTimeMillis();boolean changed=false;
        for(Record r:records.values())if(("queued".equals(r.status)||"running".equals(r.status))&&now-r.submittedAt>=r.assignment.timeoutMillis){stop(r,"timed_out","排队/执行超时");changed=true;}
        boolean propagated;do{propagated=false;for(Record r:records.values())if("queued".equals(r.status))for(String dep:r.assignment.dependsOn)if(failure(records.get(dep).status)){stop(r,"cancelled","依赖未通过: "+dep);changed=propagated=true;break;}}while(propagated);
        if(changed)persist();if(recoveryHold)return;
        for(Record r:records.values()){
            if(running.size()>=capacity)break;
            if(!"queued".equals(r.status)||!dependenciesReady(r))continue;
            try{for(String dep:r.assignment.dependsOn){Record parent=records.get(dep);verify(parent.assignment.requiresMerge?parent.mergedFiles:parent.evidence);}}
            catch(IOException e){r.status="failed";r.error="已复核依赖证据变化: "+e.getMessage();persist();continue;}
            String lease=acquire(r);if(lease==null)continue;
            Running active=new Running();active.lease=lease;int attempt=r.attempt;String step=r.assignment.stepId;
            active.thread=new Thread(()->execute(step,attempt,active),"minion-child-"+step);active.thread.setDaemon(true);
            r.status="running";r.startedAt=now;running.put(step,active);
            try{persist();active.thread.start();}catch(Throwable e){running.remove(step);release(lease);r.status="failed";r.error="启动/持久化失败: "+e;throw new IOException(r.error,e);}
        }
    }
    private void execute(String step,int attempt,Running active){
        Outcome outcome=null;List<FileEvidence> evidence=new ArrayList<FileEvidence>();Throwable error=null;
        try{
            Assignment assignment; synchronized(this){assignment=copy(records.get(step).assignment,Assignment.class);}
            active.cancellation.check();outcome=runner.execute(assignment,active.cancellation);active.cancellation.check();
            if(outcome==null||!outcome.success)throw new IOException(outcome==null?"Runner未返回结构化结果":String.valueOf(outcome.summary));
            required(outcome.summary,"结果摘要");required(outcome.acceptanceReport,"验收报告");
            if(outcome.evidencePaths==null||outcome.evidencePaths.isEmpty()||outcome.evidencePaths.size()>128)throw new IOException("成功结果必须有1..128条证据路径");
            long total=0;Set<String> paths=new HashSet<String>();for(String path:outcome.evidencePaths){
                Path p=PathsGuard.resolve(root.toString(),required(path,"证据路径")).toAbsolutePath().normalize();
                if(!PathsGuard.inside(root.toString(),p)||!Files.isRegularFile(p)||Files.size(p)>50L*1024*1024)throw new IOException("证据越界/缺失/超过50MB: "+p);
                total+=Files.size(p);if(total>50L*1024*1024)throw new IOException("证据总量超过50MB，请只提交精简报告/关键文件");
                FileEvidence e=FileEvidence.capture(p);if(!paths.add(e.path.toLowerCase(Locale.ROOT)))throw new IOException("证据重复: "+p);evidence.add(e);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();error=e;}catch(Throwable e){error=e;}
        finally{synchronized(this){
            Record r=records.get(step);
            if(r!=null&&r.attempt==attempt&&r.isolation!=null)try{r.isolation=new ChildTaskIsolation(root).returned(r.isolation,error==null&&"running".equals(r.status));}catch(Exception e){error=e;r.error="隔离生命周期登记失败: "+e;}
            if(r!=null&&r.attempt==attempt&&"running".equals(r.status)){
                r.finishedAt=System.currentTimeMillis();r.outcome=outcome==null?null:copy(outcome,Outcome.class);
                if(error!=null){r.status="failed";r.error=error.getClass().getSimpleName()+": "+error.getMessage();}
                else{r.status="returned_needs_review";r.evidence=evidence;}
            }
            running.remove(step);release(active.lease);
            try{persist();}catch(Exception e){failControl(e);}
            notifyAll();
        }}
    }
    private boolean dependenciesReady(Record r){for(String dep:r.assignment.dependsOn)if(!accepted(records.get(dep)))return false;return true;}
    private static boolean accepted(Record r){return r.assignment.requiresMerge?"validated".equals(r.status):"reviewed".equals(r.status)||"validated".equals(r.status);}
    /** 只供主 Agent 宿主调用，不注册给子 Agent；receiptIds 应来自本任务成功 Evidence.id。 */
    public synchronized Record reviewFromParent(String stepId,int attempt,String report,List<String> receiptIds)throws Exception{
        ensureOpen();Record r=expected(stepId,attempt,"returned_needs_review");required(report,"主Agent复核报告");checkReceipts(receiptIds);verify(r.evidence);
        if(r.isolation!=null)r.isolation=new ChildTaskIsolation(root).reviewFromParent(r.isolation,report);
        r.parentReview=report;r.reviewReceiptIds=new ArrayList<String>(receiptIds);r.status="reviewed";persist();return copy(r,Record.class);
    }
    public synchronized Record rejectFromParent(String stepId,int attempt,String reason)throws Exception{
        ensureOpen();Record r=expected(stepId,attempt,"returned_needs_review");r.status="failed";r.error=required(reason,"拒绝原因");persist();return copy(r,Record.class);
    }
    public synchronized Record markMerged(String stepId,int attempt,List<String> mergedPaths)throws Exception{
        ensureOpen();Record r=expected(stepId,attempt,"reviewed");if(!r.assignment.requiresMerge)throw new IOException("该任务没有登记合并契约");
        if(mergedPaths==null||mergedPaths.isEmpty())throw new IOException("需真实合并路径");List<FileEvidence> files=new ArrayList<FileEvidence>();
        if(mergedPaths.size()>128)throw new IOException("合并证据最多128项");long total=0;
        for(String path:mergedPaths){Path p=PathsGuard.resolve(root.toString(),path).toAbsolutePath().normalize();if(!PathsGuard.inside(root.toString(),p)||!Files.isRegularFile(p))throw new IOException("合并文件无效");total+=Files.size(p);if(total>50L*1024*1024)throw new IOException("合并证据超过50MB");files.add(FileEvidence.capture(p));}
        r.mergedFiles=files;r.status="merged_needs_validation";persist();return copy(r,Record.class);
    }
    public synchronized Record validateFromParent(String stepId,int attempt,boolean success,String report,List<String> receiptIds)throws Exception{
        ensureOpen();Record r=expected(stepId,attempt,"merged_needs_validation");required(report,"联合验证报告");checkReceipts(receiptIds);verify(r.mergedFiles);
        r.validationReport=report;r.validationReceiptIds=new ArrayList<String>(receiptIds);r.status=success?"validated":"failed";if(!success)r.error="联合验证失败: "+report;persist();return copy(r,Record.class);
    }
    /** 只重试已静止的失败记录；不自动猜测副作用安全性，不创建重复scope记录。 */
    public synchronized Record retryFromParent(String stepId,int attempt,String reason,boolean sideEffectsChecked)throws Exception{
        ensureOpen();Record r=records.get(stepId);if(r==null||r.attempt!=attempt||!failure(r.status)||running.containsKey(stepId)||!sideEffectsChecked)throw new IOException("重试需失败记录、正确attempt、旧执行已退出且已核对副作用");
        required(reason,"重试依据");if(r.attempt>=32)throw new IOException("同一分工手动重试已达32次，请重新检查任务范围/服务状态");
        Attempt prior=new Attempt();prior.number=r.attempt;prior.childId=r.childId;prior.status=r.status;prior.error=r.error;
        prior.executionRoot=r.executionRoot;prior.isolation=r.isolation;prior.outcome=r.outcome;
        prior.evidence=r.evidence;prior.mergedFiles=r.mergedFiles;prior.reviewReceiptIds=r.reviewReceiptIds;prior.validationReceiptIds=r.validationReceiptIds;
        if(r.previousAttempts==null)r.previousAttempts=new ArrayList<Attempt>();r.previousAttempts.add(copy(prior,Attempt.class));
        r.attempt++;r.status="queued";r.error=null;r.outcome=null;r.parentReview=null;r.submittedAt=System.currentTimeMillis();r.startedAt=0;r.finishedAt=0;
        r.childId=null;r.executionRoot=null;r.isolation=null;r.evidence=new ArrayList<FileEvidence>();r.mergedFiles=new ArrayList<FileEvidence>();
        r.reviewReceiptIds=new ArrayList<String>();r.validationReceiptIds=new ArrayList<String>();persist();return copy(r,Record.class);
    }
    public synchronized void bindChild(String stepId,int attempt,String childId)throws Exception{Record r=records.get(stepId);if(r==null||r.attempt!=attempt)throw new IOException("过期step/attempt");r.childId=required(childId,"childId");persist();}
    /** 宿主创建隔离目录后绑定；工具真正写入前还必须调用 authorizeWrite。 */
    public synchronized void bindExecutionRoot(String stepId,int attempt,Path directory)throws Exception{
        ensureOpen();Record r=expected(stepId,attempt,"running");Path real=directory.toRealPath();if(!Files.isDirectory(real)||!PathsGuard.inside(root.toString(),real)||(!r.assignment.isolatedOutput&&!r.assignment.requiresMerge))throw new IOException("未登记隔离契约/执行根越界");r.executionRoot=real.toString();persist();
    }
    public synchronized void bindIsolation(String stepId,int attempt,ChildTaskIsolation.Record isolation)throws Exception{
        ensureOpen();Record r=expected(stepId,attempt,"running");if(!r.assignment.requiresMerge||isolation==null||!taskId.equals(isolation.taskId)||!stepId.equals(isolation.stepId))throw new IOException("代码隔离任务/步骤不匹配");
        if(r.isolation!=null||r.executionRoot!=null)throw new IOException("执行隔离已绑定，不能替换");ChildTaskIsolation service=new ChildTaskIsolation(root);ChildTaskIsolation.Record registered=service.load(isolation.name);if(!"ready".equals(registered.status)||!Objects.equals(registered.worktree,isolation.worktree)||!registered.scope.equals(r.assignment.scope))throw new IOException("代码隔离登记/范围不匹配");r.executionRoot=Paths.get(registered.worktree).toRealPath().toString();r.isolation=service.start(registered);persist();
    }
    /** 主流程在实际文件工具副作用前调用。这里只授权具体路径；不能据此放行任意Bash/Python命令。 */
    public synchronized void authorizeWrite(String stepId,int attempt,Path target)throws Exception{
        ensureOpen();Record r=expected(stepId,attempt,"running");if(!writes(r.assignment))throw new IOException("explore只读，禁止写入");
        Path execution=r.executionRoot==null?root:Paths.get(r.executionRoot);Path path=target.toAbsolutePath().normalize();
        if(!PathsGuard.inside(execution.toString(),path))throw new IOException("写入超出执行根");Path probe=path;List<Path> tail=new ArrayList<Path>();while(!Files.exists(probe,java.nio.file.LinkOption.NOFOLLOW_LINKS)){tail.add(probe.getFileName());probe=probe.getParent();}Path real=probe.toRealPath();for(int i=tail.size()-1;i>=0;i--)real=real.resolve(tail.get(i));
        String relative=execution.relativize(real).toString().replace('\\','/');boolean allowed=false;for(String prefix:r.assignment.scope)if(".".equals(prefix)||relative.equalsIgnoreCase(prefix)||relative.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)+"/")){allowed=true;break;}if(!allowed)throw new IOException("写入超出子任务scope: "+relative);
        if(reserved(relative))throw new IOException("禁止子任务改写Git/协调元数据或Windows路径别名");
    }
    private static boolean reserved(String path){
        String[] parts=path.replace('\\','/').split("/");
        if(parts.length>0&&Arrays.asList(".git",".minion").contains(parts[0].toLowerCase(Locale.ROOT)))return true;
        for(String part:parts)if(!".".equals(part)&&(part.contains(":")||part.endsWith(".")||part.endsWith(" ")))return true;
        return false;
    }
    public synchronized void setCapacity(int available)throws Exception{ensureOpen();if(available<0||available>maximum)throw new IllegalArgumentException("容量越界");capacity=available;}
    public synchronized void cancel(String stepId,String reason)throws Exception{ensureOpen();Record r=records.get(stepId);if(r==null)throw new IOException("step不存在");if("queued".equals(r.status)||"running".equals(r.status)||"returned_needs_review".equals(r.status))stop(r,"cancelled",required(reason,"取消原因"));tick();persist();}
    public synchronized void cancelAll(String reason)throws Exception{ensureOpen();required(reason,"取消原因");for(Record r:records.values())if("queued".equals(r.status)||"running".equals(r.status)||"returned_needs_review".equals(r.status))stop(r,"cancelled",reason);persist();}
    private void stop(Record r,String status,String reason){r.status=status;r.error=reason;r.finishedAt=System.currentTimeMillis();Running active=running.get(r.assignment.stepId);if(active!=null){active.cancellation.cancel();active.thread.interrupt();}}
    public synchronized Record record(String stepId){Record r=records.get(stepId);return r==null?null:copy(r,Record.class);}
    public synchronized int activeExecutions(){return running.size();}
    public synchronized int capacity(){return capacity;}
    public synchronized String controlError(){return controlError;}
    public synchronized Snapshot snapshot(){Snapshot s=new Snapshot();s.workspace=root.toString();s.taskId=taskId;s.revision=revision;for(Record r:records.values())s.records.add(copy(r,Record.class));return s;}
    public synchronized void save(Path path)throws Exception{if(!PathsGuard.inside(root.toString(),path.toAbsolutePath()))throw new IOException("快照路径越界");AtomicFiles.writeText(path,gson.toJson(snapshot()),StandardCharsets.UTF_8);}
    /** 新实例恢复；运行中的旧任务统一转复核，并暂停调度直到宿主确认旧进程静止。 */
    public synchronized void restore(Snapshot snapshot)throws Exception{
        ensureOpen();if(!records.isEmpty()||snapshot==null||snapshot.version!=1||snapshot.records==null||snapshot.records.size()>1024||!taskId.equals(snapshot.taskId)||revision!=snapshot.revision||!root.toString().equals(snapshot.workspace))throw new IOException("协调快照版本/任务/修订/工作区不匹配");
        Map<String,Record> next=new LinkedHashMap<String,Record>();for(Record original:snapshot.records){Record r=copy(original,Record.class);validate(r.assignment);if(!taskId.equals(r.taskId)||r.revision!=revision||r.attempt<1||!Arrays.asList("queued","running","returned_needs_review","reviewed","failed","cancelled","timed_out","interrupted_needs_review","merged_needs_validation","validated").contains(r.status)||next.put(r.assignment.stepId,r)!=null)throw new IOException("协调快照重复/跨任务/状态非法");if("running".equals(r.status)){r.status="interrupted_needs_review";r.error="重启时运行状态不明，不自动重放";}}
        validateGraph(next);validateScopes(next);records.putAll(next);recoveryHold=true;persist();
    }
    public synchronized void restoreFromFile(Path path)throws Exception{
        ensureOpen();Path p=path.toAbsolutePath().normalize();if(!records.isEmpty()||!PathsGuard.inside(root.toString(),p)||!Files.isRegularFile(p)||Files.size(p)>8*1024*1024)throw new IOException("恢复journal越界/不存在/过大/非空协调器");
        Snapshot saved=gson.fromJson(new String(Files.readAllBytes(p),StandardCharsets.UTF_8),Snapshot.class);restore(saved);journal=p;persist();
    }
    public synchronized void confirmRecoveryQuiescent(String report)throws Exception{ensureOpen();required(report,"旧进程已静止的核对依据");recoveryHold=false;persist();}
    /** 有界等待，绝不强杀Java线程；返回false表示仍有物理执行/写租约未释放。 */
    public synchronized boolean awaitQuiescence(long timeoutMillis)throws InterruptedException{
        if(timeoutMillis<0||timeoutMillis>30000)throw new IllegalArgumentException("等待须为0..30000ms");long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(!running.isEmpty()){long left=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(left<=0)return false;wait(Math.max(1,left));}return true;
    }
    @Override public synchronized void close(){if(closed)return;for(Record r:records.values())if("queued".equals(r.status)||"running".equals(r.status))stop(r,"cancelled","协调器关闭");closed=true;timer.shutdownNow();try{persist();}catch(Exception e){controlError=e.toString();}}
    private void persist()throws Exception{if(journal!=null)try{AtomicFiles.writeText(journal,gson.toJson(snapshot()),StandardCharsets.UTF_8);}catch(Exception e){failControl(e);throw e;}}
    private void failControl(Throwable e){controlError=e.toString();for(Record r:records.values())if("running".equals(r.status)||"queued".equals(r.status))stop(r,"failed","协调持久化/调度失败: "+controlError);}
    private void ensureOpen()throws IOException{if(closed||controlError!=null)throw new IOException("协调器关闭/故障: "+controlError);}
    private Record expected(String step,int attempt,String status)throws IOException{Record r=records.get(step);if(r==null||r.attempt!=attempt||!status.equals(r.status))throw new IOException("过期step/attempt或状态不符: "+status);return r;}
    private static void verify(List<FileEvidence> evidence)throws IOException{if(evidence==null||evidence.isEmpty())throw new IOException("无文件证据");long total=0;for(FileEvidence e:evidence){if(e==null||e.path==null||!Files.isRegularFile(Paths.get(e.path))||Files.size(Paths.get(e.path))!=e.size||(total+=e.size)>50L*1024*1024||!"unchanged".equals(e.verify()))throw new IOException("证据缺失/已改变/过大: "+(e==null?"null":e.path));}}
    private static void checkReceipts(List<String> ids){if(ids==null||ids.isEmpty())throw new IllegalArgumentException("主任务成功receipt id不能为空");Set<String> seen=new HashSet<String>();for(String id:ids)if(!seen.add(required(id,"receipt id")))throw new IllegalArgumentException("重复receipt id");}
    private static boolean writes(Assignment a){return !"explore".equals(a.mode);}
    private static boolean failure(String status){return Arrays.asList("failed","cancelled","timed_out","interrupted_needs_review").contains(status);}
    private String acquire(Record record){
        String key=owner+":"+record.assignment.stepId+":"+record.attempt;if(!writes(record.assignment))return key;
        List<String> paths=new ArrayList<String>();for(String s:record.assignment.scope)paths.add(root.resolve(s).normalize().toString().replace('\\','/').toLowerCase(Locale.ROOT));
        synchronized(WRITE_LEASES){for(List<String> held:WRITE_LEASES.values())if(overlap(held,paths))return null;WRITE_LEASES.put(key,paths);}return key;
    }
    private static void release(String lease){synchronized(WRITE_LEASES){WRITE_LEASES.remove(lease);}}
    private static boolean overlap(List<String> a,List<String> b){for(String rawX:a)for(String rawY:b){String x=rawX.toLowerCase(Locale.ROOT),y=rawY.toLowerCase(Locale.ROOT);if(".".equals(x)||".".equals(y)||x.equals(y)||x.startsWith(y+"/")||y.startsWith(x+"/"))return true;}return false;}
    private <T>T copy(T data,Class<T> type){return gson.fromJson(gson.toJson(data),type);}
    private static String required(String s,String name){if(s==null||s.trim().isEmpty())throw new IllegalArgumentException("缺少 "+name);return s;}
    private static String string(JsonObject args,String key){return args.has(key)&&!args.get(key).isJsonNull()?args.get(key).getAsString():"";}
    private static List<String> list(JsonObject args,String key){if(!args.has(key)||!args.get(key).isJsonArray())throw new IllegalArgumentException(key+"必须显式为字符串数组（无依赖用[]）");List<String> out=new ArrayList<String>();for(JsonElement e:args.getAsJsonArray(key))out.add(e.getAsString());return out;}
}
