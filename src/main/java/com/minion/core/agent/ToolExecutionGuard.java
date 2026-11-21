package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.*;

/** Proven resource locks, conservative unknown-code exclusion, and stale-read rejection. */
public final class ToolExecutionGuard {
    // Unknown code can touch shared directories outside its project. Known resources
    // share the read gate and take ordered PathLocks; unknown scopes take the write gate.
    private static final ReentrantReadWriteLock FILE_LOCK=new ReentrantReadWriteLock(true);
    private static final ReentrantLock BROWSER_LOCK=new ReentrantLock(true);
    public static Lock exclusiveWorkspaceLock(){return FILE_LOCK.writeLock();}
    private final Workspace workspace;
    private final boolean child;
    private final AtomicLong lastWaitNanos=new AtomicLong();
    private final AtomicLong totalWaitNanos=new AtomicLong();
    private final AtomicLong lockAttempts=new AtomicLong();
    private volatile java.util.function.LongConsumer waitObserver;
    /** Called on the acquiring worker once per attempt, in milliseconds; never includes tool execution. */
    public void waitObserver(java.util.function.LongConsumer observer){waitObserver=observer;}
    /** Most recently finished acquisition attempt, including interrupted/timeout attempts. */
    public long lastLockWaitMillis(){return TimeUnit.NANOSECONDS.toMillis(lastWaitNanos.get());}
    public long totalLockWaitMillis(){return TimeUnit.NANOSECONDS.toMillis(totalWaitNanos.get());}
    public long lockAttemptCount(){return lockAttempts.get();}
    public interface MutationAuthorizer { void authorize(String name,JsonObject args)throws Exception; }
    private MutationAuthorizer mutationAuthorizer;
    public void mutationAuthorizer(MutationAuthorizer authorizer){mutationAuthorizer=authorizer;}
    private ReliabilityService reliability;
    // The agent dispatches tool calls on worker threads, so this turn-scoped policy
    // must be visible across that pool. SessionManager serializes turns per session.
    private volatile Set<String> scheduledTools;
    public void scheduledReadOnly(Set<String> allowed){scheduledTools=Collections.unmodifiableSet(new HashSet<String>(allowed));}
    public void clearScheduled(){scheduledTools=null;}
    /** PLAN/REVIEW are mechanically read-only even if the model ignores its prompt rules. */
    private volatile boolean readOnlyTurn;
    public void setReadOnlyTurn(boolean enabled){readOnlyTurn=enabled;}
    private java.util.function.Supplier<String> taskId;
    private String scope="root";
    public void journal(ReliabilityService service,java.util.function.Supplier<String> task,String scope){this.reliability=service;this.taskId=task;this.scope=scope;}
    private final List<String> extraRoots=new ArrayList<String>();
    public void allowTrackingRoot(String root) { if(root!=null&&!root.isEmpty()) extraRoots.add(root); }
    private boolean trackable(Path path) {
        if(PathsGuard.inside(workspace.workDir(),path)||PathsGuard.insideExtra(workspace,path)) return true;
        for(String root:extraRoots) if(PathsGuard.inside(root,path)) return true;
        return false;
    }
    private final Map<String,String> observed=new HashMap<String,String>();
    public ToolExecutionGuard(Workspace workspace,boolean child) { this.workspace=workspace; this.child=child; }
    private static boolean control(String name) {
        return Arrays.asList("task","AgentControl","TodoWrite","AskUserQuestion","Skill","Context","DiscoverTools").contains(name);
    }
    private static boolean browser(String name,JsonObject args) {
        // Browser calls can wait for navigation/downloads. They share BrowserSession's
        // own monitor, but must not hold the process-wide file lock while waiting.
        if("ArtifactVerification".equals(name)&&args.has("action")&&"webpage".equals(args.get("action").getAsString()))return true;
        return Arrays.asList("Browser","BrowserAction","BrowserEval","BrowserDebug","BrowserScreenshot")
                .contains(name);
    }
    public static boolean readOnly(String name,JsonObject args) {
        if("WebFetch".equals(name)) return true;
        if("Files".equals(name)&&args.has("action")&&!args.get("action").isJsonNull())
            return Arrays.asList("tree","search","read","read_chunk","summary","metadata","list")
                    .contains(args.get("action").getAsString().toLowerCase(Locale.ROOT));
        if("Browser".equals(name)&&args.has("action")&&!args.get("action").isJsonNull())
            return "status".equals(args.get("action").getAsString().toLowerCase(Locale.ROOT));
        if("BrowserAction".equals(name)&&args.has("action")&&!args.get("action").isJsonNull())
            return Arrays.asList("text","table","links","scroll","wait_selector","network_idle","paginate","manual_takeover")
                    .contains(args.get("action").getAsString().toLowerCase(Locale.ROOT));
        if("BrowserDebug".equals(name)&&args.has("action")&&!args.get("action").isJsonNull())
            return Arrays.asList("network","console","page").contains(args.get("action").getAsString().toLowerCase(Locale.ROOT));
        if("Process".equals(name)&&args.has("action"))
            return Arrays.asList("list","status","output","wait").contains(args.get("action").getAsString());
        if(Arrays.asList("Read","ViewImage","Glob","Grep","CodeIndex").contains(name)) return true;
        if("RuntimeJobs".equals(name)&&args.has("action"))return Arrays.asList("list","inspect","reconcile").contains(args.get("action").getAsString());
        if("Bash".equals(name)&&args.has("command")&&!args.get("command").isJsonNull())
            return BashTool.provablyReadOnly(args.get("command").getAsString());
        if(args.has("action")&&!args.get("action").isJsonNull()) {
            String action=args.get("action").getAsString().toLowerCase(Locale.ROOT);
            if("ArtifactVerification".equals(name))return com.minion.core.tools.dev.ArtifactVerificationTool.readOnlyAction(action);
            if("Git".equals(name))return Arrays.asList("status","diff","log","branches","review","review_snapshot","worktree_list").contains(action);
            if("Build".equals(name))return Arrays.asList("detect","artifacts","verify").contains(action);
            if("Artwork".equals(name))return com.minion.core.tools.artwork.ArtworkTool.readOnlyAction(action);
            if("Document".equals(name))return com.minion.core.tools.data.DocumentTool.readOnlyAction(action);
            if("Excel".equals(name))return com.minion.core.tools.data.ExcelTool.readOnlyAction(action)
                    &&(!args.has("filter")||args.get("filter").isJsonNull()||args.get("filter").getAsString().trim().isEmpty());
        }
        return "Knowledge".equals(name)&&args.has("action")&&Arrays.asList("search","list","read").contains(args.get("action").getAsString());
    }
    private SessionHooks hooks;
    private com.minion.core.hooks.HookSystem ccHooks;
    /** §4.7 CC 语义 Hook（默认 null=未接线；PreToolUse/PostToolUse/PostToolUseFailure） */
    public void ccHooks(com.minion.core.hooks.HookSystem system){this.ccHooks=system;}
    private java.util.function.Supplier<JsonObject> metadata=JsonObject::new;
    private java.util.function.BooleanSupplier admission=()->true;
    public void lifecycle(SessionHooks hooks,java.util.function.Supplier<JsonObject> metadata,
                          java.util.function.BooleanSupplier admission){
        this.hooks=hooks;if(metadata!=null)this.metadata=metadata;if(admission!=null)this.admission=admission;
    }
    public ToolResult execute(Tool tool,JsonObject args) throws Exception {
        // §4.7 PreToolUse：先于权限/执行；hook deny 阻断、updatedInput 替换后重过全部校验、
        // ask 不放宽（后续管线按自身规则询问）。allow 不跳过任何自检（只收紧不放宽）。
        com.minion.core.hooks.HookSystem cc=ccHooks;
        if(cc!=null&&cc.enabled()&&cc.matches(com.minion.core.hooks.HookSystem.PRE_TOOL_USE,tool.name())) {
            JsonObject payload=metadata.get().deepCopy();
            payload.addProperty("tool_name",tool.name());
            payload.add("tool_input",args.deepCopy());
            com.minion.core.hooks.HookSystem.Outcome pre=
                    cc.run(com.minion.core.hooks.HookSystem.PRE_TOOL_USE,tool.name(),payload);
            if(pre.blocked)
                return ToolResult.errorWithoutSideEffects("Hook 阻断（PreToolUse）: "+pre.reason);
            if(pre.updatedInput!=null)args=pre.updatedInput;   // 换参后由下方管线重新完整校验
        }
        final JsonObject callArgs=args;   // lambda 捕获需 effectively final
        if(readOnlyTurn&&!readOnly(tool.name(),args))
            return ToolResult.errorWithoutSideEffects("当前为计划/审查只读回合；"+tool.name()+" 的此动作不会执行");
        Set<String> scheduled=scheduledTools;
        if(scheduled!=null&&(!scheduled.contains(tool.name())||!readOnly(tool.name(),args)))
            return ToolResult.errorWithoutSideEffects("计划任务不允许此工具动作");
        if(reliability!=null&&!reliability.permits(tool.name(),args))return ToolResult.errorWithoutSideEffects("本地兼容模式禁止此工具");
        // Approved commands may touch unknown resources; serialize only matching hook invocations.
        boolean hooked=scheduled==null&&hooks!=null&&(hooks.matches("before_tool",tool.name(),args)||hooks.matches("after_tool",tool.name(),args));
        if(hooked)FILE_LOCK.writeLock().lockInterruptibly();
        try{ToolResult result=ToolInvocationPipeline.run(tool.name(),callArgs,hooked?hooks:null,metadata.get(),admission,
                ()->executeCore(tool,callArgs));
            // §4.7 PostToolUse / PostToolUseFailure：事后反馈（systemMessage 进日志；阻断仅记录，无法回滚）
            if(cc!=null&&cc.enabled()){
                String postEvent=result.ok?com.minion.core.hooks.HookSystem.POST_TOOL_USE
                        :com.minion.core.hooks.HookSystem.POST_TOOL_USE_FAILURE;
                if(cc.matches(postEvent,tool.name())){
                    JsonObject payload=metadata.get().deepCopy();
                    payload.addProperty("tool_name",tool.name());
                    payload.add("tool_input",args.deepCopy());
                    payload.addProperty("tool_response",result.preview());
                    com.minion.core.hooks.HookSystem.Outcome post=cc.run(postEvent,tool.name(),payload);
                    if(post.blocked)com.minion.core.diagnostics.DiagnosticLog.warn("hooks",
                            "PostToolUse hook 阻断（事后，仅记录）: "+post.reason);
                }
            }
            return result;
        }
        catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        finally{if(hooked)FILE_LOCK.writeLock().unlock();}
    }
    private ToolResult executeCore(Tool tool,JsonObject args) throws Exception {
        if(readOnlyTurn&&!readOnly(tool.name(),args))
            return ToolResult.error("当前为计划/审查只读回合，禁止写入、执行型命令或外部副作用: "+tool.name());
        Set<String> scheduled=scheduledTools;
        if(scheduled!=null&&(!scheduled.contains(tool.name())||!readOnly(tool.name(),args)))
            return ToolResult.error("本地计划任务仅允许已选定的只读动作；请手动启动需要写入或执行代码的任务: "+tool.name());
        if(reliability!=null&&!reliability.permits(tool.name(),args))return ToolResult.error("严格本地兼容模式禁止此工具: "+tool.name()+"。请使用固定Document/Excel工作流或由用户调整模式；此模式不是OS沙箱。");
        if(control(tool.name())) { // Never hold workspace locks while waiting for a child/user.
            ReliabilityService.Step step=reliability==null?null:reliability.start(taskId.get(),scope,tool.name(),args,"task".equals(tool.name()));
            // finish 必须在 finally：控制类工具抛异常/中断时若漏 finish，步骤永久停在
            // started → pendingMutations 永久占用 → 完成门禁无限 block（停不下来的实证根因）
            ToolResult result;
            try { result=tool.execute(args); }
            catch(Throwable failure) {
                if(step!=null)try{reliability.finish(step,ToolResult.error("步骤异常终止: "+failure.getClass().getSimpleName()+": "+failure.getMessage()));}
                catch(java.io.IOException journalFailure){/* 账本写失败不能掩盖原始异常 */}
                throw failure;
            }
            if(step!=null)reliability.finish(step,result);
            return result;
        }
        ToolResourceScope resources=ToolResourceScope.resolve(tool.name(),args,workspace);
        boolean browserCall=browser(tool.name(),args);
        boolean networkOnly=tool instanceof com.minion.core.tools.db.DbTool||"WebFetch".equals(tool.name())||("ArtifactVerification".equals(tool.name())&&args.has("action")&&"http".equals(args.get("action").getAsString()));
        // BrowserSession is shared across conversations. A stuck download must not
        // make another browser turn wait forever for its synchronized methods.
        boolean processObservation="Process".equals(tool.name())&&readOnly(tool.name(),args);
        Lock held=browserCall||networkOnly||processObservation?null:(resources.known||readOnly(tool.name(),args)?FILE_LOCK.readLock():FILE_LOCK.writeLock());
        List<ReentrantLock> acquired=new ArrayList<ReentrantLock>();
        boolean browserAcquired=false,gateAcquired=false;
        try {
            long waitStarted=System.nanoTime();
            try {
                if(browserCall){
                    browserAcquired=BROWSER_LOCK.tryLock(2,TimeUnit.SECONDS);
                    if(!browserAcquired)return ToolResult.errorWithoutSideEffects("浏览器正在被另一会话使用；本次未执行，请稍后重试或先停止占用浏览器的会话");
                }
                if(held!=null){held.lockInterruptibly();gateAcquired=true;}
                // Stripe order avoids AB/BA deadlocks, including hash collisions and aliases.
                for(ReentrantLock resourceLock:PathLocks.forPaths(resources.paths)){
                    resourceLock.lockInterruptibly();acquired.add(resourceLock);
                }
            } finally {
                long waited=System.nanoTime()-waitStarted;
                lastWaitNanos.set(waited);totalWaitNanos.addAndGet(waited);lockAttempts.incrementAndGet();
                java.util.function.LongConsumer observer=waitObserver;
                if(observer!=null)try{observer.accept(TimeUnit.NANOSECONDS.toMillis(waited));}
                catch(RuntimeException ignored){/* Telemetry must not alter authorization or leak locks. */}
            }
            Path path=null; String key=null;
            if(Arrays.asList("Read","Write","Edit").contains(tool.name())&&args.has("path")) {
                path=PathsGuard.resolve(workspace.cwd().toString(),args.get("path").getAsString()).toAbsolutePath().normalize();
                if(Files.exists(path)) path=path.toRealPath(); key=path.toString().toLowerCase(Locale.ROOT);
                // Do not inspect an external file before the tool's own permission gate.
                if(!trackable(path)) { path=null; key=null; }
            }
            if(path!=null&&!"Read".equals(tool.name())) {
                String current=FileEvidence.digest(path), seen;
                synchronized(observed) { seen=observed.get(key); }
                if(seen!=null&&!seen.equals(current)) return ToolResult.error("并行文件冲突：文件在上次Read之后已改变，未执行写入。请重新Read并合并修改: "+path);
                if(child&&Files.exists(path)&&seen==null) return ToolResult.error("子Agent修改已有文件必须先Read，避免覆盖其他Agent结果: "+path);
            }
            boolean mutation=!readOnly(tool.name(),args);
            if(mutation&&mutationAuthorizer!=null)try{mutationAuthorizer.authorize(tool.name(),args);}
            catch(Exception denied){if(denied instanceof InterruptedException)Thread.currentThread().interrupt();return ToolResult.errorWithoutSideEffects("写入范围/调度核对未通过，本次未执行: "+denied.getMessage());}
            ReliabilityService.Step step=reliability==null?null:reliability.start(taskId.get(),scope,tool.name(),args,mutation);
            ToolResult result;
            com.minion.core.checkpoint.CheckpointStore.beginGroup(step==null?UUID.randomUUID().toString():step.id);
            boolean succeeded=false;
            try {result=tool.execute(args);succeeded=result.ok;}
            catch(Throwable failure) {
                // finish 必须兜底：工具异常时步骤按“结果不确定”收账（mutation 步骤进 uncertain），
                // 否则永久停在 started，完成门禁会无限 block 且无解除入口
                if(step!=null)try{reliability.finish(step,ToolResult.error("工具异常终止: "+failure.getClass().getSimpleName()+": "+failure.getMessage()));}
                catch(java.io.IOException journalFailure){/* 账本写失败不能掩盖原始异常 */}
                throw failure;
            }
            finally {com.minion.core.checkpoint.CheckpointStore.endGroup(succeeded);}
            if(step!=null)reliability.finish(step,result);
            if(result.ok&&path!=null) synchronized(observed) { observed.put(key,FileEvidence.digest(path)); }
            return result;
        } finally {
            for(int i=acquired.size()-1;i>=0;i--)acquired.get(i).unlock();
            if(gateAcquired)held.unlock();
            if(browserAcquired)BROWSER_LOCK.unlock();
        }
    }
}
