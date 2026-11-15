package com.minion.core.tools.dev;

import com.minion.core.tools.ToolArguments;

import com.google.gson.JsonObject;
import com.minion.core.agent.RuntimeJobCancellation;
import com.minion.core.agent.RuntimeJobRegistry;
import com.minion.core.agent.RuntimeJobRegistry.Handle;
import com.minion.core.agent.RuntimeJobRegistry.JobRecord;
import com.minion.core.agent.RuntimeJobRegistry.Status;
import com.minion.core.agent.RuntimeJobRegistry.WaitResult;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.ProcessCleanup;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Session-local live handles, backed by the unified durable runtime-job ledger. */
public final class ProcessTool implements Tool {
    private static final int MAX_OUTPUT=120_000;
    private static final int MAX_LIVE_PROCESSES=32;
    private static final ThreadPoolExecutor CANCELLATIONS=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(128),r->{Thread t=new Thread(r,"minion-process-cancel");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    /** Called outside tool/registry locks; must be short/non-blocking, e.g. enqueue a UI update. */
    public interface WaitObserver { void onWait(JobRecord job,boolean waiting); }
    private final Workspace workspace;
    private final Object lifecycle=new Object();
    private final Map<String,Managed> processes=new LinkedHashMap<String,Managed>();
    private final String legacyScope=UUID.randomUUID().toString();
    private RuntimeJobRegistry registry;
    private Path explicitLedger;
    private boolean ownsRegistry,closed;
    private Supplier<String> taskId=()->"legacy-process-"+legacyScope;
    private LongSupplier revision=()->1L;
    private int attempt=1;
    private volatile WaitObserver waitObserver;
    private volatile String controlError;

    /** Compatible legacy constructor. Creates a unique durable ledger lazily, never overwrites another session. */
    public ProcessTool(Workspace workspace){this.workspace=workspace;}
    /** Explicit stable session path enables reopening/recovery; no execution is reattached. */
    public ProcessTool(Workspace workspace,Path ledger){this(workspace);explicitLedger=ledger;}
    public ProcessTool(Workspace workspace,RuntimeJobRegistry registry){this(workspace);this.registry=registry;}

    /**
     * Bind before the first start. Suppliers are sampled only when submitting, not when receiving
     * an exit event. The main loop must also call registry.bindTask on a new user revision, even
     * if it does not submit another process. The injected registry remains owned by its caller.
     */
    public void bind(RuntimeJobRegistry registry,Supplier<String> taskId,LongSupplier revision,WaitObserver observer) {
        if(registry==null||taskId==null||revision==null)throw new NullPointerException("registry/taskId/revision");
        synchronized(lifecycle) {
            if(closed||this.registry!=null&&this.registry!=registry||!processes.isEmpty())throw new IllegalStateException("bind before process use");
            this.registry=registry;this.ownsRegistry=false;this.taskId=taskId;this.revision=revision;this.waitObserver=observer;
        }
    }

    /** Fixed-snapshot convenience for standalone callers/tests; no retry or restart is performed. */
    public void bindTask(String taskId,long revision) throws IOException {bindTask(taskId,revision,1);}
    public void bindTask(final String taskId,final long revision,int attempt) throws IOException {
        if(attempt<1)throw new IllegalArgumentException("attempt must be >=1");
        synchronized(lifecycle) {
            ensureOpen();runtimeJobs().bindTask(taskId,revision);
            this.taskId=()->taskId;this.revision=()->revision;this.attempt=attempt;
        }
    }

    public RuntimeJobRegistry runtimeJobs() throws IOException {
        synchronized(lifecycle) {
            ensureOpen();
            if(registry==null) {
                Path root=Paths.get(workspace.workDir()).toAbsolutePath().normalize();
                Path path=explicitLedger==null?root.resolve(".minion/runtime-jobs/process-"+legacyScope+".json"):explicitLedger.toAbsolutePath().normalize();
                if(!PathsGuard.inside(root.toString(),path))throw new IOException("runtime ledger outside workspace");
                registry=new RuntimeJobRegistry(path);ownsRegistry=true;
            }
            return registry;
        }
    }

    public String controlError(){return controlError;}

    /**
     * Non-blocking UI stop propagation: no registry I/O or killtree on the calling thread.
     * Only pending/live one-shot handles of this exact task/revision are targeted; default background
     * services and recovered PIDs are untouched. Future completion is control cleanup, NOT
     * deliverable success. Caller should cancel its wait token/fence the task immediately too.
     */
    public CompletableFuture<Integer> cancelForeground(final String taskId,final long revision,final String reason) {
        final CompletableFuture<Integer> result=new CompletableFuture<Integer>();
        if(taskId==null||revision<1){result.completeExceptionally(new IllegalArgumentException("taskId/revision"));return result;}
        try {CANCELLATIONS.execute(()->{
            List<Managed> victims=new ArrayList<Managed>();IOException failure=null;
            try {
                synchronized(lifecycle) {
                    if(registry!=null)for(Managed m:processes.values()) {
                        JobRecord r=registry.get(m.handle.jobId);
                        // A just-exited process may still be draining output: fence its pending
                        // receipt too, rather than losing the user's stop in that small window.
                        if(r.waitForCompletion&&taskId.equals(r.taskId)&&revision==r.revision&&(!r.status.settled()||m.process.isAlive()))victims.add(m);
                    }
                    // Fence every victim before any potentially slow OS termination.
                    for(Managed m:victims)try{m.jobs.cancel(m.handle.jobId,reason);}catch(IOException e){controlError=e.toString();failure=e;}
                }
                for(Managed m:victims)if(m.process.isAlive())ProcessCleanup.terminate(m.process);
                if(failure==null)result.complete(victims.size());else result.completeExceptionally(failure);
            } catch(Throwable e){controlError=e.toString();result.completeExceptionally(e);}
        });} catch(RejectedExecutionException e){controlError="process cancel queue full: "+e;result.completeExceptionally(e);}
        return result;
    }
    @Override public String name(){return "Process";}
    @Override public String description(){return "后台进程：start/list/status/output/wait/stop/clean；start 可设 waitForCompletion=true 标记前台一次性任务（默认 false 服务不阻止完成）。wait 有界且可中断；启动/停止需确认，不自动重启。";}
    @Override public JsonObject schema(){
        JsonObject schema=SchemaGenerator.objectSchema("进程管理；id 为返回的 jobId；wait 的 timeoutMillis 默认30000，最大86400000",
                new String[]{"action","command","cwd","id","offset","limit","timeoutMillis","waitForCompletion"},new String[]{"action"});
        schema.getAsJsonObject("properties").getAsJsonObject("waitForCompletion").addProperty("type","boolean");
        schema.getAsJsonObject("properties").getAsJsonObject("waitForCompletion").addProperty("description","显式前台 one-shot；默认 false，不自动重试/重启");
        return schema;
    }
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return "start".equals(a)||"stop".equals(a)||"clean".equals(a);}

    // Intentionally NOT synchronized: a wait must never prevent status/stop/revision events.
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action=text(args,"action").toLowerCase(Locale.ROOT);
        if("start".equals(action))return start(text(args,"command"),text(args,"cwd"),bool(args,"waitForCompletion",false));
        if("list".equals(action))return list();
        if("status".equals(action))return status(text(args,"id"));
        if("output".equals(action))return output(text(args,"id"),integer(args,"offset",0),integer(args,"limit",30_000));
        if("wait".equals(action)) {
            try {
                WaitResult result=await(text(args,"id"),longValue(args,"timeoutMillis",30_000),null);
                return ToolResult.success("等待结果: "+result.reason+"（超时/中断等待不会重提进程）\n"+render(result.job)
                        +(result.error==null?"":"\n账本错误: "+result.error));
            } catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        }
        if("stop".equals(action))return stop(text(args,"id"));
        if("clean".equals(action))return clean();
        return ToolResult.errorWithoutSideEffects("未知 action: "+action+"（支持 start/list/status/output/wait/stop/clean）");
    }

    /** A cancellable, bounded event wait; cancelling this wait alone never stops/restarts the job. */
    public WaitResult await(String id,long timeoutMillis,RuntimeJobCancellation cancellation) throws IOException,InterruptedException {
        RuntimeJobRegistry jobs=runtimeJobs();JobRecord job=processRecord(jobs,id);WaitObserver observer=waitObserver;
        try {
            if(observer!=null)observer.onWait(job,true);
            return jobs.awaitTerminal(id,timeoutMillis,cancellation);
        } finally {if(observer!=null)observer.onWait(jobs.get(id),false);}
    }

    private ToolResult start(String command,String cwdText,boolean foreground) throws Exception {
        if(command.trim().isEmpty())return ToolResult.errorWithoutSideEffects("start 缺少 command");
        synchronized(lifecycle) {
            ensureOpen();
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("process start cancelled");
            Path cwd=cwdText.trim().isEmpty()?workspace.cwd():workspace.cwd().resolve(cwdText).normalize().toAbsolutePath();
            ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),cwd);if(guard!=null)return guard;
            if(!Files.isDirectory(cwd))return ToolResult.errorWithoutSideEffects("工作目录不存在: "+cwd);
            int live=0;for(Managed m:processes.values())if(m.process.isAlive())live++;
            if(live>=MAX_LIVE_PROCESSES)return ToolResult.errorWithoutSideEffects("运行中进程已达32个，请先停止不需要的服务");
            RuntimeJobRegistry jobs=runtimeJobs();
            String task=taskId.get();long rev=revision.getAsLong();
            if(task==null||!task.equals(taskId.get()))return ToolResult.errorWithoutSideEffects("任务绑定正在变化，进程未启动");
            Path outputDir=jobs.ledgerPath().getParent().resolve("process-output");
            if(!PathsGuard.inside(workspace.workDir(),outputDir))return ToolResult.errorWithoutSideEffects("进程结果路径越界");
            Files.createDirectories(outputDir);
            Path result=outputDir.resolve(UUID.randomUUID().toString()+".log");
            // CREATE_NEW plus a random name never overwrites an earlier result.
            Files.write(result,new byte[0],StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            Handle handle=jobs.register(task,rev,attempt,"process",command+"\n目录: "+cwd,result.toString(),foreground);
            List<String> invocation=new ArrayList<String>();
            if(isWindows()){invocation.add("cmd.exe");invocation.add("/d");invocation.add("/s");invocation.add("/c");invocation.add(com.minion.core.tools.WindowsShell.frame(command));}
            else{invocation.add("sh");invocation.add("-lc");invocation.add(command);}
            Process process=null;
            try {
                ProcessBuilder pb=new ProcessBuilder(invocation).directory(cwd.toFile()).redirectErrorStream(true);
                pb.environment().put("PYTHONIOENCODING","utf-8");
                // Final pre-submit check after the durable reservation; no implicit resubmission.
                if(Thread.currentThread().isInterrupted()){jobs.cancel(handle.jobId,"interrupted before process launch");throw new InterruptedException("process start cancelled");}
                if(!jobs.isCurrent(jobs.get(handle.jobId)))return ToolResult.errorWithoutSideEffects("任务版本已变化，登记已归档，进程未启动: "+handle.jobId);
                process=pb.start();
                Managed managed=new Managed(handle,process,result,jobs);
                processes.put(handle.jobId,managed);
                // Begin draining before any further disk I/O; terminal notification waits for it.
                managed.reader.start();
                long pid=ProcessCleanup.pidOf(process);
                jobs.publish(handle,Status.RUNNING,pid>0?"local-pid:"+pid:"local-handle:"+handle.jobId,result.toString(),null,"local process started; PID is not reconnect authority");
                managed.watcher.start();
                return ToolResult.success("已启动后台进程 "+handle.jobId+"\n"+render(jobs.get(handle.jobId))
                        +"\nwaitForCompletion="+foreground+"（不是任务已完成；不自动重试/重启）");
            } catch(Exception|LinkageError e) {
                if(process!=null)ProcessCleanup.terminate(process);
                try{jobs.publish(handle,process==null?Status.FAILED:Status.UNKNOWN,null,result.toString(),null,"launch/control failure: "+e);}catch(IOException failure){controlError=failure.toString();}
                throw e;
            }
        }
    }

    private ToolResult list() throws IOException {
        StringBuilder out=new StringBuilder();
        for(JobRecord r:runtimeJobs().list())if("process".equals(r.kind))out.append(render(r)).append('\n');
        return ToolResult.success(out.length()==0?"没有后台进程":out.toString().trim());
    }
    private ToolResult status(String id) throws IOException {return ToolResult.success(render(processRecord(runtimeJobs(),id)));}
    private ToolResult output(String id,int offset,int limit) throws IOException {
        JobRecord r=processRecord(runtimeJobs(),id);
        if(r.resultPath==null)return ToolResult.error("无结果路径: "+id);
        Path path=Paths.get(r.resultPath);
        if(!PathsGuard.inside(workspace.workDir(),path))return ToolResult.errorWithoutSideEffects("历史结果路径不在当前工作区: "+path);
        // Bounded read even if an external editor has replaced/enlarged the result file.
        StringBuilder all=new StringBuilder();char[] chars=new char[4096];
        try(Reader reader=new InputStreamReader(Files.newInputStream(path),StandardCharsets.UTF_8)) {
            int n;while(all.length()<MAX_OUTPUT&&(n=reader.read(chars,0,Math.min(chars.length,MAX_OUTPUT-all.length())))!=-1)all.append(chars,0,n);
        }
        int from=Math.max(0,Math.min(offset,all.length()));int to=Math.min(all.length(),from+Math.max(1,Math.min(limit,60_000)));
        return ToolResult.success(render(r)+"\n输出范围: "+from+"-"+to+" / "+all.length()+"（最多保留120000字符）\n"+all.substring(from,to));
    }

    private ToolResult stop(String id) throws IOException {
        Managed managed;RuntimeJobRegistry jobs=runtimeJobs();
        synchronized(lifecycle) {
            JobRecord record=processRecord(jobs,id);managed=processes.get(id);
            if(managed==null) {
                if(record.status==Status.COMPLETED||record.status==Status.FAILED||record.status==Status.CANCELLED)return ToolResult.success("进程已结算；未重启\n"+render(record));
                return ToolResult.errorWithoutSideEffects("无本次运行的进程句柄，物理停止未确认；不会按旧PID结束、重连或重提\n"+render(record));
            }
            // Durable cancel fence precedes physical termination, so a racing success is archival.
            jobs.cancel(id,"Process stop requested by user");
        }
        if(managed.process.isAlive())ProcessCleanup.terminate(managed.process);
        return ToolResult.success("已请求停止进程 "+id+"；进程退出仍以回执为准，不自动重启\n"+render(jobs.get(id)));
    }

    private ToolResult clean() throws IOException {
        List<String> ids; synchronized(lifecycle){ensureOpen();ids=new ArrayList<String>(processes.keySet());}
        for(String id:ids)stop(id);
        // Do not erase durable history or discard handles before shutdown can kill a failed stop.
        return ToolResult.success("已请求清理 "+ids.size()+" 个本次进程；账本、结果和取消围栏保留，不重启任何进程");
    }

    /** Permanently closes this tool; calling start after window/session shutdown is rejected. */
    public void shutdown() {
        List<Managed> active;RuntimeJobRegistry jobs;boolean own;
        synchronized(lifecycle) {
            if(closed)return;closed=true;active=new ArrayList<Managed>(processes.values());jobs=registry;own=ownsRegistry;
            for(Managed m:active)try{jobs.cancel(m.handle.jobId,"Process window/session shutdown");}catch(IOException e){controlError=e.toString();}
        }
        // Still terminate live handles if persistence failed; never use recovered PIDs.
        for(Managed m:active)if(m.process.isAlive())ProcessCleanup.terminate(m.process);
        boolean interrupted=Thread.interrupted();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        for(Managed m:active)try{long left=end-System.nanoTime();if(left>0)TimeUnit.NANOSECONDS.timedJoin(m.watcher,left);}catch(InterruptedException e){interrupted=true;break;}
        if(own&&jobs!=null)try{jobs.close();}catch(IOException e){controlError=e.toString();}
        if(interrupted)Thread.currentThread().interrupt();
    }

    private void ensureOpen() throws IOException {if(closed)throw new IOException("Process tool closed; user-stopped window/session is never restarted");}
    private static JobRecord processRecord(RuntimeJobRegistry jobs,String id){JobRecord r=jobs.get(id);if(!"process".equals(r.kind))throw new IllegalArgumentException("not a process job: "+id);return r;}
    private static String render(JobRecord r){
        return "进程 "+r.jobId+" "+r.status+(r.exitCode==null?"":" EXIT "+r.exitCode)
                +"\ntaskId="+r.taskId+" revision="+r.revision+" attempt="+r.attempt+" waitForCompletion="+r.waitForCompletion
                +"\nexternalInstanceId="+r.externalInstanceId+" resultPath="+r.resultPath
                +(r.fenced?"\nFENCED: "+r.fenceReason:"")+(r.cancelRequested?"\ncancelRequested=true（不等于物理退出）":"")
                +(r.lateResult?"\n归档观察: "+r.observedStatus+" exit="+r.observedExitCode:"")
                +(r.detail==null?"":"\n"+r.detail)+"\n"+r.description;
    }
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){return ToolArguments.integer(o,k,d);}
    private static long longValue(JsonObject o,String k,long d){return ToolArguments.longVal(o,k,d);}
    private static boolean bool(JsonObject o,String k,boolean d){
        if(!o.has(k))return d;String value=o.get(k).getAsString();
        if(!"true".equalsIgnoreCase(value)&&!"false".equalsIgnoreCase(value))throw new IllegalArgumentException(k+" must be true/false");
        return Boolean.parseBoolean(value);
    }

    private final class Managed {
        final Handle handle;final Process process;final Path result;final RuntimeJobRegistry jobs;final Thread reader,watcher;
        volatile String outputError;
        Managed(Handle handle,Process process,Path result,RuntimeJobRegistry jobs) {
            this.handle=handle;this.process=process;this.result=result;this.jobs=jobs;
            reader=new Thread(this::readOutput,"minion-process-output-"+handle.jobId);reader.setDaemon(true);
            watcher=new Thread(this::watchExit,"minion-process-exit-"+handle.jobId);watcher.setDaemon(true);
        }
        private void readOutput() {
            // Chunked rather than readLine(): one giant line cannot exhaust memory or bypass cap.
            try(Reader in=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8);
                Writer out=Files.newBufferedWriter(result,StandardCharsets.UTF_8,StandardOpenOption.APPEND)) {
                char[] buffer=new char[4096];int n,total=0;
                while((n=in.read(buffer))!=-1) {
                    int keep=Math.min(n,MAX_OUTPUT-total);if(keep>0){out.write(buffer,0,keep);out.flush();total+=keep;}
                    // Continue draining discarded bytes: a full pipe must not deadlock the child.
                }
            } catch(Exception e){outputError=e.toString();}
        }
        private void watchExit() {
            try {
                int code=process.waitFor();reader.join(2000);
                Status end=outputError!=null||reader.isAlive()?Status.UNKNOWN:code==0?Status.COMPLETED:Status.FAILED;
                String detail=reader.isAlive()?"process exited but output pipe remains open; descendant may still be running"
                        :outputError==null?"local process exited; this is not parent deliverable acceptance":"output persistence failed: "+outputError;
                jobs.publish(handle,end,null,result.toString(),code,detail);
            } catch(InterruptedException e){Thread.currentThread().interrupt();controlError="process exit observer interrupted (not resubmitted)";}
            catch(IOException e){controlError=e.toString();}
        }
    }
}
