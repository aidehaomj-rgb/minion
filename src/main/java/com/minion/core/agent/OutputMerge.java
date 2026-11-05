package com.minion.core.agent;

import com.google.gson.Gson;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.tools.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;

/** 显式预览/备份/应用；合并成功不是最终验证通过。 */
public final class OutputMerge {
    public static final class Plan {
        public Path source,target;
        public String taskId,childId;
        public String sourceHash,targetHash;
        public long sourceSize;
        public String preview;
    }
    private static final class Issued {
        Path root,outputRoot,source,target,outputIdentity,sourceIdentity,targetIdentity;
        String taskId,childId,sourceHash,targetHash,preview;
        long sourceSize;
        ChildTaskIsolation.Record isolation;
        boolean consumed;
    }
    // 现有GUI跨实例inspect/apply：只接受本进程签发的未篡改Plan，弱引用不泄漏预览。
    private static final Map<Plan,Issued> ISSUED=Collections.synchronizedMap(new WeakHashMap<Plan,Issued>());
    public static final class AppliedFile {
        public String source,target,sourceHash,beforeHash,afterHash,backupId,status;
        public long appliedAt;
    }
    public static final class MergeRecord {
        public int version=1;
        public String taskId,childId,status,validationReport;
        public List<AppliedFile> files=new ArrayList<AppliedFile>();
        public List<String> validationReceiptIds=new ArrayList<String>();
        public List<String> priorReceiptIds=new ArrayList<String>();
    }
    private final Workspace workspace;
    private final CheckpointStore checkpoints;
    private final Gson gson=new Gson();
    public OutputMerge(Workspace workspace,CheckpointStore checkpoints){this.workspace=workspace;this.checkpoints=checkpoints;}
    public Plan inspect(TaskState.Child child,String relativeSource,String relativeTarget)throws Exception {
        ChildTaskIsolation.Record isolation=null;
        if(child!=null&&child.outputDir!=null){Path root=Paths.get(workspace.workDir()).toRealPath(),output=Paths.get(child.outputDir).toAbsolutePath().normalize();
            if(output.startsWith(root.resolve(".minion/git-worktrees"))||Files.exists(output.resolve(".git"))){isolation=new ChildTaskIsolation(root).load(output.getFileName().toString());if(!output.equals(Paths.get(isolation.worktree)))throw new IOException("代码worktree与生命周期登记不匹配");}}
        return inspect(child,relativeSource,relativeTarget,isolation);
    }
    public Plan inspect(TaskState.Child child,String relativeSource,String relativeTarget,ChildTaskIsolation.Record isolation)throws Exception {
        if(child==null||!child.isolatedOutput||!reviewed(child.status))throw new IOException("需先核对子Agent结果，且该子Agent必须使用独立输出目录");
        Path root=Paths.get(workspace.workDir()).toRealPath(),outputRoot=Paths.get(child.outputDir).toAbsolutePath().normalize();
        if(isolation==null&&(outputRoot.startsWith(root.resolve(".minion/git-worktrees"))||Files.exists(outputRoot.resolve(".git"))))throw new IOException("代码worktree合并必须提供真实隔离登记，不能绕过派发基线");
        Path source=relative(outputRoot,relativeSource),target=relative(root,relativeTarget);checkPaths(root,outputRoot,source,target);
        if(Files.size(source)>CheckpointStore.MAX_SNAPSHOT_BYTES||(Files.exists(target)&&Files.size(target)>CheckpointStore.MAX_SNAPSHOT_BYTES))throw new IOException("合并文件超过50MB检查点上限");
        Plan p=new Plan();p.taskId=child.taskId;p.childId=child.id;p.source=source;p.target=target;p.sourceSize=Files.size(source);p.sourceHash=FileEvidence.digest(source);p.targetHash=FileEvidence.digest(target);
        if(isolation!=null){if(!Objects.equals(isolation.taskId,child.taskId)||!Paths.get(isolation.worktree).equals(outputRoot))throw new IOException("代码隔离不属于本任务/输出目录");String baseline=new ChildTaskIsolation(root).expectedTargetHash(isolation,root.relativize(target).toString());if(!baseline.equals(p.targetHash))throw new IOException("代码目标偏离派发基线");}
        StringBuilder b=new StringBuilder("源: ").append(source).append("\n目标: ").append(target).append("\n源SHA256: ").append(p.sourceHash).append("\n目标SHA256: ").append(p.targetHash).append("\n字节: ").append(p.sourceSize);
        if(p.sourceSize<50000&&(!Files.exists(target)||Files.size(target)<50000)){
            String a=new String(Files.readAllBytes(source),StandardCharsets.UTF_8),old=Files.exists(target)?new String(Files.readAllBytes(target),StandardCharsets.UTF_8):"";
            if(a.indexOf('\0')<0&&old.indexOf('\0')<0)b.append("\n--- 目标预览 ---\n").append(old.substring(0,Math.min(old.length(),6000))).append("\n+++ 子Agent产物预览 +++\n").append(a.substring(0,Math.min(a.length(),6000)));
        }p.preview=b.append("\n合并后仍需主任务联合验证回执").toString();
        Issued i=new Issued();i.root=root;i.outputRoot=outputRoot;i.source=source;i.target=target;i.taskId=p.taskId;i.childId=p.childId;i.sourceHash=p.sourceHash;i.targetHash=p.targetHash;i.sourceSize=p.sourceSize;i.preview=p.preview;
        i.outputIdentity=realIdentity(outputRoot);i.sourceIdentity=realIdentity(source);i.targetIdentity=realIdentity(target);
        if(isolation!=null)i.isolation=gson.fromJson(gson.toJson(isolation),ChildTaskIsolation.Record.class);ISSUED.put(p,i);return p;
    }
    public String apply(Plan plan,TaskState state)throws Exception {
        Issued issued=ISSUED.get(plan);if(issued==null)throw new IOException("合并计划不是有效预览，必须重新inspect");
        Lock workspaceLock=ToolExecutionGuard.exclusiveWorkspaceLock();workspaceLock.lockInterruptibly();
        List<java.util.concurrent.locks.ReentrantLock> locks=PathLocks.forPaths(Arrays.asList(issued.source,issued.target));int acquired=0;
        try{for(java.util.concurrent.locks.ReentrantLock lock:locks){lock.lockInterruptibly();acquired++;}
            synchronized(state){
                checkPlan(plan,issued);TaskState.Child current=state.child(issued.childId);
                if(current==null||!Objects.equals(state.id,issued.taskId)||!Objects.equals(current.taskId,issued.taskId)||!reviewed(current.status)||!current.isolatedOutput||!Paths.get(current.outputDir).toAbsolutePath().normalize().equals(issued.outputRoot))throw new IOException("子任务状态/输出根已变，合并预览失效");
                if(!Paths.get(workspace.workDir()).toRealPath().equals(issued.root))throw new IOException("工作区已变化");checkPaths(issued.root,issued.outputRoot,issued.source,issued.target);
                if(!issued.outputIdentity.equals(realIdentity(issued.outputRoot))||!issued.sourceIdentity.equals(realIdentity(issued.source))||!issued.targetIdentity.equals(realIdentity(issued.target)))throw new IOException("合并目录/文件真实路径身份变化，禁止覆盖");
                if(issued.isolation!=null)new ChildTaskIsolation(issued.root).expectedTargetHash(issued.isolation,issued.root.relativize(issued.target).toString());
                if(!issued.sourceHash.equals(FileEvidence.digest(issued.source))||!issued.targetHash.equals(FileEvidence.digest(issued.target)))throw new IOException("源或目标已修改，必须重新预览，不能覆盖");
                byte[] bytes=Files.readAllBytes(issued.source);if(bytes.length!=issued.sourceSize||!issued.sourceHash.equals(hash(bytes)))throw new IOException("源在读取期间变化，未合并");
                MergeRecord record=loadRecord(state.id,current.id);if(!record.files.isEmpty()&&!"merged_needs_validation".equals(record.status))throw new IOException("已有合并状态不明/已关闭，不得继续合并");for(AppliedFile old:record.files)if(old.target.equals(issued.target.toString()))throw new IOException("同一子任务已提交过此目标；禁止重复输出，需重新核对分工");
                CheckpointStore.Entry backup=checkpoints.create(issued.target,"child-merge");if(!issued.targetHash.equals(backup.beforeHash)||!issued.targetHash.equals(FileEvidence.digest(issued.target)))throw new IOException("备份期间目标已变化，未覆盖；检查点: "+backup.id);
                AppliedFile file=new AppliedFile();file.source=issued.source.toString();file.target=issued.target.toString();file.sourceHash=issued.sourceHash;file.beforeHash=issued.targetHash;file.backupId=backup.id;file.status="applying";
                record.files.add(file);record.priorReceiptIds=new ArrayList<String>();for(TaskState.Evidence evidence:state.evidence)if(Objects.equals(evidence.taskId,state.id)&&evidence.id!=null)record.priorReceiptIds.add(evidence.id);record.status="applying_needs_review";saveRecord(record);issued.consumed=true;
                try{
                    AtomicFiles.writeBytes(issued.target,bytes);checkpoints.seal(backup);file.afterHash=FileEvidence.digest(issued.target);if(!issued.sourceHash.equals(file.afterHash))throw new IOException("写后哈希不符（可能并发修改），保留检查点人工核对");
                    file.appliedAt=System.currentTimeMillis();file.status="merged_needs_validation";record.status="merged_needs_validation";saveRecord(record);
                    if(issued.isolation!=null)new ChildTaskIsolation(issued.root).markMerged(issued.isolation);setChildStatus(state,current.id,"merged_needs_validation");
                    return "已合并: "+issued.target+"\n检查点: "+backup.id+"\nSHA256: "+file.afterHash+"\n状态: merged_needs_validation（尚未联合验证，不能宣称任务完成）";
                }catch(Exception e){file.status="apply_failed_needs_review";record.status="apply_failed_needs_review";setChildStatus(state,current.id,"merge_failed_needs_review");try{saveRecord(record);}catch(Exception suppressed){e.addSuppressed(suppressed);}throw new IOException("合并失败/状态不明；保留备份 "+backup.id+"，不可盲目重试: "+e.getMessage(),e);}
            }
        }finally{for(int i=acquired-1;i>=0;i--)locks.get(i).unlock();workspaceLock.unlock();}
    }
    /** 只由主Agent宿主调用；要求当前任务、合并后的真实联合验证回执与未变的目标哈希。 */
    public MergeRecord validateFromParent(String childId,TaskState state,boolean success,String report,List<String> receiptIds)throws Exception {
        Lock lock=ToolExecutionGuard.exclusiveWorkspaceLock();lock.lockInterruptibly();try{synchronized(state){
            TaskState.Child child=state.child(childId);if(child==null||!Objects.equals(child.taskId,state.id)||!"merged_needs_validation".equals(child.status)||report==null||report.trim().isEmpty())throw new IOException("子任务未等待联合验证/缺报告");
            MergeRecord record=loadRecord(state.id,childId);if(!"merged_needs_validation".equals(record.status)||record.files.isEmpty())throw new IOException("合并账本不是待验证状态");long last=0;
            for(AppliedFile f:record.files){Path p=Paths.get(f.target);if(!PathsGuard.inside(workspace.workDir(),p)||!"merged_needs_validation".equals(f.status)||!Objects.equals(f.afterHash,FileEvidence.digest(p)))throw new IOException("合并文件变化/状态不明，需重新核对");last=Math.max(last,f.appliedAt);}
            if(receiptIds==null||receiptIds.isEmpty())throw new IOException("需合并后的联合测试回执id");Set<String> seen=new HashSet<String>();
            for(String id:receiptIds){if(id==null||!seen.add(id)||(record.priorReceiptIds!=null&&record.priorReceiptIds.contains(id)))throw new IOException("重复/合并前的回执id");boolean found=false;for(TaskState.Evidence e:state.evidence)if(Objects.equals(id,e.id)&&Objects.equals(e.taskId,state.id)&&"root".equals(e.scope)&&e.time>=last&&e.success==success){found=true;break;}if(!found)throw new IOException("缺少本任务合并后的主Agent"+(success?"成功":"失败")+"回执: "+id);}
            record.status=success?"validated":"validation_failed";record.validationReport=report;record.validationReceiptIds=new ArrayList<String>(receiptIds);saveRecord(record);setChildStatus(state,childId,record.status);return record;
        }}finally{lock.unlock();}
    }
    public MergeRecord record(String taskId,String childId)throws Exception{return loadRecord(taskId,childId);}
    private MergeRecord loadRecord(String taskId,String childId)throws Exception{Path file=recordPath(taskId,childId);if(!Files.exists(file)){MergeRecord r=new MergeRecord();r.taskId=taskId;r.childId=childId;return r;}if(Files.size(file)>4*1024*1024)throw new IOException("合并账本过大");MergeRecord r=gson.fromJson(new String(Files.readAllBytes(file),StandardCharsets.UTF_8),MergeRecord.class);if(r==null||r.version!=1||!Objects.equals(r.taskId,taskId)||!Objects.equals(r.childId,childId)||r.files==null)throw new IOException("合并账本损坏");return r;}
    private void saveRecord(MergeRecord record)throws Exception{AtomicFiles.writeText(recordPath(record.taskId,record.childId),gson.toJson(record),StandardCharsets.UTF_8);}
    private Path recordPath(String task,String child)throws IOException{if(task==null||child==null||!task.matches("[A-Za-z0-9_.-]{1,128}")||!child.matches("[A-Za-z0-9_.-]{1,128}")||"..".equals(task)||"..".equals(child))throw new IOException("任务ID非法");Path p=Paths.get(workspace.workDir()).resolve(".minion/child-merge-records").resolve(task).resolve(child+".json").toAbsolutePath().normalize();if(!PathsGuard.inside(workspace.workDir(),p))throw new IOException("合并账本越界");return p;}
    private void setChildStatus(TaskState state,String id,String status){List<TaskState.Child> next=new ArrayList<TaskState.Child>();for(TaskState.Child old:state.children){TaskState.Child c=gson.fromJson(gson.toJson(old),TaskState.Child.class);if(Objects.equals(c.id,id)&&Objects.equals(c.taskId,state.id))c.status=status;next.add(c);}state.children=next;}
    private static boolean reviewed(String status){return "reviewed".equals(status)||"merged_needs_validation".equals(status);}
    private static Path realIdentity(Path p)throws Exception{Path probe=p;List<Path> tail=new ArrayList<Path>();while(!Files.exists(probe,LinkOption.NOFOLLOW_LINKS)){tail.add(probe.getFileName());probe=probe.getParent();}Path result=probe.toRealPath();for(int n=tail.size()-1;n>=0;n--)result=result.resolve(tail.get(n));return result.normalize();}
    private static Path relative(Path root,String raw)throws IOException{if(raw==null||Paths.get(raw).isAbsolute()||raw.replace('\\','/').matches("(^|.*/)\\.\\.(/.*|$)"))throw new IOException("只允许目录内相对路径");return root.resolve(raw).toAbsolutePath().normalize();}
    private static void checkPaths(Path root,Path output,Path source,Path target)throws Exception{if(!PathsGuard.inside(root.toString(),output)||!PathsGuard.inside(output.toString(),source)||!PathsGuard.inside(root.toString(),target)||target.startsWith(output)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.isDirectory(target)||Files.isSymbolicLink(target))throw new IOException("源或目标路径越界/不存在/类型不符");Path realTarget=realIdentity(target);if(realTarget.startsWith(realIdentity(output)))throw new IOException("合并目标指向子输出自身");Path relative=root.relativize(realTarget);if(relative.getNameCount()>0&&Arrays.asList(".git",".minion").contains(relative.getName(0).toString().toLowerCase(Locale.ROOT)))throw new IOException("禁止合并覆盖Git/协调元数据");}
    private static void checkPlan(Plan p,Issued i)throws IOException{if(i.consumed||!Objects.equals(p.taskId,i.taskId)||!Objects.equals(p.childId,i.childId)||!Objects.equals(p.source,i.source)||!Objects.equals(p.target,i.target)||!Objects.equals(p.sourceHash,i.sourceHash)||!Objects.equals(p.targetHash,i.targetHash)||!Objects.equals(p.preview,i.preview)||p.sourceSize!=i.sourceSize)throw new IOException("合并计划已使用或被篡改，需重新预览");}
    private static String hash(byte[] bytes)throws Exception{StringBuilder b=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))b.append(String.format("%02x",value&255));return b.toString();}
}
