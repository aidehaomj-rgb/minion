package com.minion.core.agent;

import com.google.gson.Gson;
import com.minion.core.tools.AtomicFiles;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.dev.GitCommand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 代码子任务的 worktree 生命周期与不可洗白的派发基线；永不提交/强清/checkout 用户文件。 */
public final class ChildTaskIsolation {
    public static final class Record {
        public int version=1;
        public String taskId, stepId, name, repository, worktree, head, gitMarkerHash;
        public String status="creating", error;
        public List<String> scope=new ArrayList<String>();
        public Map<String,String> baseline=new TreeMap<String,String>();
    }
    private static final Map<String,Object> LOCKS=new ConcurrentHashMap<String,Object>();
    private final Path root;
    private final Gson gson=new Gson();
    private final Object lock;
    public ChildTaskIsolation(Path root)throws Exception{
        this.root=root.toRealPath();String key=this.root.toString().toLowerCase(Locale.ROOT);Object fresh=new Object();Object prior=LOCKS.putIfAbsent(key,fresh);lock=prior==null?fresh:prior;
    }
    /** scope 内有未提交/未跟踪文件即拒绝，避免用 HEAD 丢失用户在途代码；其他范围的脏文件不动。 */
    public Record create(String taskId,String stepId,String name,List<String> scope)throws Exception{synchronized(lock){
        required(taskId,"taskId");required(stepId,"stepId");Path target=target(name),recordFile=recordFile(name);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)||Files.exists(recordFile))throw new IOException("隔离名称已存在，不能覆盖/复用");
        if(!root.equals(Paths.get(git(root,"rev-parse","--show-toplevel").trim()).toRealPath()))throw new IOException("必须使用仓库根工作区");
        Record record=new Record();record.taskId=taskId;record.stepId=stepId;record.name=name;record.repository=root.toString();record.worktree=target.toString();record.scope=normalizeScopes(scope);
        record.head=git(root,"rev-parse","--verify","HEAD").trim();
        assertScopeClean(record);List<String> list=new ArrayList<String>(Arrays.asList("ls-files","-z"));filters(list,record.scope);
        String files=git(root,list);int count=0;for(String file:files.split("\u0000"))if(!file.isEmpty()){
            if(++count>10000)throw new IOException("基线文件超过10000，需收窄scope");Path p=safe(root,file);
            if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>50L*1024*1024)throw new IOException("基线包含非普通文件/超大文件，请收窄: "+file);
            record.baseline.put(file,FileEvidence.digest(p));
        }
        assertScopeClean(record);if(!record.head.equals(git(root,"rev-parse","HEAD").trim()))throw new IOException("创建前HEAD已变化");
        save(record);Files.createDirectories(target.getParent());
        try{
            git(root,"worktree","add","--detach",target.toString(),record.head);
            record.gitMarkerHash=FileEvidence.digest(target.resolve(".git"));record.status="ready";
            assertScopeClean(record);if(!record.head.equals(git(root,"rev-parse","HEAD").trim()))throw new IOException("创建期间主基线已变化");
            save(record);return copy(record);
        }catch(Exception e){record.status="creation_failed_needs_review";record.error=e.toString();save(record);throw e;} // 不删除失败/超时可能产生的 worktree。
    }}
    public Record load(String name)throws Exception{synchronized(lock){Path file=recordFile(name);if(!Files.isRegularFile(file)||Files.size(file)>4*1024*1024)throw new IOException("隔离登记不存在/过大");Record r=gson.fromJson(new String(Files.readAllBytes(file),StandardCharsets.UTF_8),Record.class);checkIdentity(r);return copy(r);}}
    public Record start(Record supplied)throws Exception{synchronized(lock){Record r=registered(supplied);if(!"ready".equals(r.status))throw new IOException("隔离尚未就绪/已占用");assertMarker(r);r.status="running";save(r);return copy(r);}}
    /** 必须在真实执行线程已经退出时调用；取消请求本身不能解除running保护。 */
    public Record returned(Record supplied,boolean success)throws Exception{synchronized(lock){Record r=registered(supplied);if(!"running".equals(r.status))throw new IOException("隔离不在运行态");r.status=success?"returned_needs_review":"failed_needs_review";save(r);return copy(r);}}
    public Record reviewFromParent(Record supplied,String report)throws Exception{synchronized(lock){Record r=registered(supplied);if(!"returned_needs_review".equals(r.status))throw new IOException("隔离尚未返回待复核");required(report,"主Agent复核报告");r.status="ready";save(r);return copy(r);}}
    public Record recoverQuiescentFromParent(Record supplied,String report)throws Exception{synchronized(lock){Record r=registered(supplied);if(!"running".equals(r.status))throw new IOException("隔离无重启遗留运行状态");required(report,"旧执行已退出的核对依据");r.status="failed_needs_review";r.error=report;save(r);return copy(r);}}
    /** 合并每个目标前调用；新目标必须在派发时不存在，已有文件必须仍等于派发基线。 */
    public String expectedTargetHash(Record supplied,String relativeTarget)throws Exception{synchronized(lock){
        Record record=registered(supplied);if(!Arrays.asList("ready","merged_needs_validation").contains(record.status))throw new IOException("worktree生命周期不允许合并: "+record.status);
        assertMarker(record);if(!record.head.equals(git(root,"rev-parse","HEAD").trim()))throw new IOException("主仓库HEAD基线变化，必须重新派发/主Agent复核");
        String relative=normalizeRelative(relativeTarget);if(!inScope(record.scope,relative))throw new IOException("合并目标不在登记scope");
        Path target=safe(root,relative);String expected=record.baseline.containsKey(relative)?record.baseline.get(relative):"<missing>";
        if(!expected.equals(FileEvidence.digest(target)))throw new IOException("目标偏离派发基线（含用户未提交变化），禁止覆盖: "+relative);
        return expected;
    }}
    public void markMerged(Record supplied)throws Exception{synchronized(lock){Record record=registered(supplied);if(!Arrays.asList("ready","merged_needs_validation").contains(record.status))throw new IOException("隔离状态不允许合并");record.status="merged_needs_validation";save(record);}}
    public void validateFromParent(Record supplied,boolean success,String report)throws Exception{synchronized(lock){Record record=registered(supplied);if(!"merged_needs_validation".equals(record.status))throw new IOException("尚未合并");required(report,"联合验证报告");record.status=success?"validated":"validation_failed";record.error=success?null:report;save(record);}}
    /** 只移除登记且完全干净（含ignored文件）的 worktree；拒绝 --force。 */
    public void remove(String name)throws Exception{synchronized(lock){
        Record record=load(name);if("removed".equals(record.status)||"running".equals(record.status)||"creating".equals(record.status))throw new IOException("worktree已移除/仍被任务占用，不能清理");assertMarker(record);
        if(!record.head.equals(git(Paths.get(record.worktree),"rev-parse","HEAD").trim()))throw new IOException("worktree HEAD已变化，保留待人工检查");
        if(!git(Paths.get(record.worktree),"status","--porcelain=v1","--untracked-files=all","--ignored").isEmpty())throw new IOException("dirty/ignored worktree保留，禁止强制清理");
        git(root,"worktree","remove",record.worktree);record.status="removed";save(record);
    }}
    private void assertScopeClean(Record record)throws Exception{List<String> args=new ArrayList<String>(Arrays.asList("status","--porcelain=v1","--untracked-files=all"));filters(args,record.scope);if(!git(root,args).isEmpty())throw new IOException("scope中存在用户未提交修改/未跟踪文件；不会覆盖或从旧HEAD代替它们");}
    private Record registered(Record supplied)throws Exception{if(supplied==null)throw new IOException("无隔离登记");Record actual=load(supplied.name);if(!Objects.equals(actual.taskId,supplied.taskId)||!Objects.equals(actual.stepId,supplied.stepId)||!Objects.equals(actual.head,supplied.head)||!Objects.equals(actual.worktree,supplied.worktree))throw new IOException("隔离登记不匹配");return actual;}
    private void assertMarker(Record r)throws Exception{if(r.gitMarkerHash==null||!r.gitMarkerHash.equals(FileEvidence.digest(Paths.get(r.worktree).resolve(".git"))))throw new IOException("worktree身份变化/创建不完整，保留人工核对");if(!PathsGuard.inside(root.toString(),Paths.get(r.worktree)))throw new IOException("worktree路径已越界");}
    private void checkIdentity(Record r)throws Exception{if(r==null||r.version!=1||!root.toString().equals(r.repository)||!target(r.name).toString().equals(r.worktree)||r.scope==null||r.baseline==null)throw new IOException("隔离登记损坏/版本不符");}
    private Path target(String name)throws IOException{if(name==null||!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))throw new IOException("worktree名称非法");Path p=root.resolve(".minion/git-worktrees").resolve(name).normalize();if(!PathsGuard.inside(root.toString(),p))throw new IOException("worktree路径越界");return p;}
    private Path recordFile(String name)throws IOException{target(name);Path p=root.resolve(".minion/git-worktree-records").resolve(name+".json");if(!PathsGuard.inside(root.toString(),p))throw new IOException("登记路径越界");return p;}
    private void save(Record r)throws Exception{AtomicFiles.writeText(recordFile(r.name),gson.toJson(r),StandardCharsets.UTF_8);}
    private Record copy(Record r){return gson.fromJson(gson.toJson(r),Record.class);}
    private List<String> normalizeScopes(List<String> scopes)throws Exception{if(scopes==null||scopes.isEmpty())throw new IOException("代码隔离必须显式scope");List<String> result=new ArrayList<String>();for(String s:scopes){String n=normalizeRelative(s);safe(root,n);if(!result.contains(n))result.add(n);}return result;}
    private static String normalizeRelative(String s)throws IOException{if(s==null||s.trim().isEmpty())throw new IOException("相对路径为空");s=s.replace('\\','/');Path p=Paths.get(s);if(p.isAbsolute()||s.contains(":")||s.contains("*")||s.contains("?")||s.matches("(^|.*/)\\.\\.(/.*|$)"))throw new IOException("只支持相对字面路径前缀");String n=p.normalize().toString().replace('\\','/');return n.isEmpty()?".":n;}
    private static boolean inScope(List<String> scope,String path){for(String s:scope)if(".".equals(s)||path.equals(s)||path.startsWith(s+"/"))return true;return false;}
    private static Path safe(Path root,String relative)throws IOException{Path p=root.resolve(relative).normalize();if(!p.startsWith(root)||!PathsGuard.inside(root.toString(),p)||Files.isSymbolicLink(p))throw new IOException("隔离/基线路径越界或符号链接: "+relative);return p;}
    private static void filters(List<String> args,List<String> scope){args.add("--");for(String s:scope)args.add(":(top,literal)"+s);args.add(":(top,exclude).minion");}
    private static void required(String s,String name)throws IOException{if(s==null||s.trim().isEmpty())throw new IOException("缺少 "+name);}
    private static String git(Path root,String...args)throws Exception{return git(root,Arrays.asList(args));}
    private static String git(Path root,List<String> args)throws Exception{GitCommand.Result r=GitCommand.run(root,args,30000,4*1024*1024);if(r.exitCode!=0||r.truncated)throw new IOException("Git隔离操作失败/输出不完整: "+r.text()+r.stderr);return r.text();}
}
