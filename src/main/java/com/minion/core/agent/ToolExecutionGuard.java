package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.*;

/** One file mutation at a time, plus stale-read rejection for direct file writes. */
public final class ToolExecutionGuard {
    // Shared knowledge directories can cross project roots. A process-wide lock
    // conservatively serializes mutations, while model requests and reads stay parallel.
    private static final ReentrantReadWriteLock FILE_LOCK=new ReentrantReadWriteLock(true);
    private static final ReentrantLock BROWSER_LOCK=new ReentrantLock(true);
    public static Lock exclusiveWorkspaceLock(){return FILE_LOCK.writeLock();}
    private final Workspace workspace;
    private final boolean child;
    public interface MutationAuthorizer { void authorize(String name,JsonObject args)throws Exception; }
    private MutationAuthorizer mutationAuthorizer;
    public void mutationAuthorizer(MutationAuthorizer authorizer){mutationAuthorizer=authorizer;}
    private ReliabilityService reliability;
    // The agent dispatches tool calls on worker threads, so this turn-scoped policy
    // must be visible across that pool. SessionManager serializes turns per session.
    private volatile Set<String> scheduledTools;
    public void scheduledReadOnly(Set<String> allowed){scheduledTools=Collections.unmodifiableSet(new HashSet<String>(allowed));}
    public void clearScheduled(){scheduledTools=null;}
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
    private ReentrantReadWriteLock lock() {
        return FILE_LOCK;
    }
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
        if(Arrays.asList("Read","ViewImage","Glob","Grep").contains(name)) return true;
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
    public ToolResult execute(Tool tool,JsonObject args) throws Exception {
        Set<String> scheduled=scheduledTools;
        if(scheduled!=null&&(!scheduled.contains(tool.name())||!readOnly(tool.name(),args)))
            return ToolResult.error("本地计划任务仅允许已选定的只读动作；请手动启动需要写入或执行代码的任务: "+tool.name());
        if(reliability!=null&&!reliability.permits(tool.name(),args))return ToolResult.error("严格本地兼容模式禁止此工具: "+tool.name()+"。请使用固定Document/Excel工作流或由用户调整模式；此模式不是OS沙箱。");
        if(control(tool.name())) { // Never hold workspace locks while waiting for a child/user.
            ReliabilityService.Step step=reliability==null?null:reliability.start(taskId.get(),scope,tool.name(),args,"task".equals(tool.name()));
            ToolResult result=tool.execute(args);if(step!=null)reliability.finish(step,result);return result;
        }
        // Known single-path writes can run alongside other paths. Unknown shell/code stays exclusive.
        boolean single=(Arrays.asList("Read","ViewImage","Write","Edit").contains(tool.name())
                ||(("Document".equals(tool.name())||"Excel".equals(tool.name()))&&readOnly(tool.name(),args)))&&args.has("path")&&!args.has("paths");
        Path resource=single?PathsGuard.resolve(workspace.cwd().toString(),args.get("path").getAsString()).toAbsolutePath().normalize():null;
        if(resource!=null&&Files.exists(resource))resource=resource.toRealPath();
        boolean browserCall=browser(tool.name(),args);
        boolean networkOnly="WebFetch".equals(tool.name())||("ArtifactVerification".equals(tool.name())&&args.has("action")&&"http".equals(args.get("action").getAsString()));
        // BrowserSession is shared across conversations. A stuck download must not
        // make another browser turn wait forever for its synchronized methods.
        if(browserCall&&!BROWSER_LOCK.tryLock(2,TimeUnit.SECONDS))
            return ToolResult.errorWithoutSideEffects("浏览器正在被另一会话使用；本次未执行，请稍后重试或先停止占用浏览器的会话");
        Lock held=browserCall||networkOnly?null:(single||readOnly(tool.name(),args)?lock().readLock():lock().writeLock());
        if(held!=null)held.lockInterruptibly();
        ReentrantLock resourceLock=resource==null?null:PathLocks.forPath(resource);boolean acquired=false;
        try {
            if(resourceLock!=null){resourceLock.lockInterruptibly();acquired=true;}
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
            finally {com.minion.core.checkpoint.CheckpointStore.endGroup(succeeded);}
            if(step!=null)reliability.finish(step,result);
            if(result.ok&&path!=null) synchronized(observed) { observed.put(key,FileEvidence.digest(path)); }
            return result;
        } finally {if(acquired)resourceLock.unlock(); if(held!=null)held.unlock(); if(browserCall)BROWSER_LOCK.unlock(); }
    }
}
