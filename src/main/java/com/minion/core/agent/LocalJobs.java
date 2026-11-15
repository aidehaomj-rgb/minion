package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** App-local scheduler ledger. No service installation, catch-up backlog or automatic enabling. */
public final class LocalJobs {
    public static final class Job {
        public String id=UUID.randomUUID().toString(),title="",kind="timer",prompt="",modelFingerprint,watchDir="",status="disabled",lastFingerprint="";
        public boolean enabled;
        public int intervalMinutes=60;
        public long lastRun;
        public List<String> allowedTools=new ArrayList<String>(Arrays.asList("Read","ViewImage","Glob","Grep","Knowledge"));
    }
    private static final Set<String> SAFE_TOOLS=new HashSet<String>(Arrays.asList("Read","ViewImage","Glob","Grep","Knowledge","Document","Excel"));
    private static boolean validTools(List<String> names){return names!=null&&!names.isEmpty()&&SAFE_TOOLS.containsAll(names)&&new HashSet<String>(names).size()==names.size();}
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private final Workspace workspace;
    private final Path path;
    private List<Job> jobs;
    private final Map<String,Long> next=new HashMap<String,Long>(),stableAt=new HashMap<String,Long>();
    private final Map<String,String> seen=new HashMap<String,String>();
    private String active;
    public LocalJobs(Workspace w,Path root)throws IOException {workspace=w;path=root.resolve("jobs.json");guard();
        if(!Files.exists(path))jobs=new ArrayList<Job>();
        else try {
            Job[] parsed=G.fromJson(new String(Files.readAllBytes(path),StandardCharsets.UTF_8),Job[].class);
            if(parsed==null||parsed.length>10)throw new IllegalArgumentException("任务列表缺失或超过10项");
            jobs=new ArrayList<Job>(Arrays.asList(parsed));
            Set<String> ids=new HashSet<String>();
            for(Job j:jobs)if(j==null||j.id==null||!j.id.matches("[A-Za-z0-9-]+")||!ids.add(j.id)
                    ||!Arrays.asList("timer","file").contains(j.kind)||j.intervalMinutes<1||!validTools(j.allowedTools)
                    ||j.prompt==null||j.prompt.trim().isEmpty()||j.prompt.length()>12000
                    ||("file".equals(j.kind)&&(j.watchDir==null||j.watchDir.trim().isEmpty())))
                throw new IllegalArgumentException("任务字段缺失或非法");
        } catch(RuntimeException e) {throw new IOException("计划任务记录损坏；原文件未改动，请在工作台检查 jobs.json",e);}
        boolean changed=false;for(Job j:jobs)if("dispatched".equals(j.status)){j.enabled=false;j.status="paused: 上次执行状态未知，请人工检查";changed=true;}if(changed)save();}
    private void guard()throws IOException {if(!PathsGuard.inside(workspace.workDir(),path))throw new IOException("计划任务记录越界");}
    private void save()throws IOException {guard();AtomicFiles.writeText(path,G.toJson(jobs),StandardCharsets.UTF_8);}
    public synchronized String json(){return G.toJson(jobs);}
    public synchronized List<Job> snapshot(){
        return Arrays.asList(G.fromJson(G.toJson(jobs),Job[].class));
    }
    public synchronized void saveJob(String id, Job form, String fingerprint)throws IOException {
        if(form==null)throw new IllegalArgumentException("任务不能为空");
        int index=id==null?-1:indexOf(id);
        if(id==null&&jobs.size()>=10)throw new IllegalArgumentException("每个会话最多 10 项自动化");
        if(id!=null&&id.equals(active))throw new IllegalStateException("任务运行中，完成后再编辑");
        Job job=new Job();
        if(id!=null){job.id=id;job.lastRun=jobs.get(index).lastRun;}
        job.title=form.title==null?"":form.title.trim();
        job.prompt=form.prompt==null?"":form.prompt.trim();
        job.kind=form.kind;job.intervalMinutes=form.intervalMinutes;
        job.watchDir="file".equals(job.kind)&&form.watchDir!=null?form.watchDir.trim():"";
        if(job.title.length()>100||job.prompt.isEmpty()||job.prompt.length()>12000
                ||!Arrays.asList("timer","file").contains(job.kind)||job.intervalMinutes<1
                ||!validTools(form.allowedTools))throw new IllegalArgumentException("检查任务名称、提示词、触发条件和只读工具");
        if("file".equals(job.kind)){
            if(job.watchDir.isEmpty())throw new IllegalArgumentException("请输入监听目录");
            watchFingerprint(job.watchDir);
        }
        job.allowedTools=new ArrayList<String>(form.allowedTools);
        job.modelFingerprint=fingerprint;
        List<Job> candidate=new ArrayList<Job>(jobs);
        if(index<0)candidate.add(job);else candidate.set(index,job);
        commit(candidate);reset(job.id);
    }
    public synchronized void setEnabled(String id,boolean enabled,String fingerprint)throws IOException {
        int index=indexOf(id);Job job=copy(jobs.get(index));
        if(enabled&&id.equals(active))throw new IllegalStateException("任务正在运行");
        if(enabled&&"file".equals(job.kind))watchFingerprint(job.watchDir);
        job.enabled=enabled;
        if(enabled)job.modelFingerprint=fingerprint;
        // Preserve the crash-recovery marker when pausing an in-flight job.
        if(!id.equals(active))job.status=enabled?"armed":"disabled";
        List<Job> candidate=new ArrayList<Job>(jobs);candidate.set(index,job);
        commit(candidate);reset(id);
    }
    public synchronized void deleteJob(String id)throws IOException {
        if(id!=null&&id.equals(active))throw new IllegalStateException("任务运行中，完成后再删除");
        List<Job> candidate=new ArrayList<Job>(jobs);candidate.remove(indexOf(id));
        commit(candidate);reset(id);
    }
    public synchronized void finish(String id,boolean succeeded)throws IOException {
        if(id==null||!id.equals(active))return;
        int index=indexOf(id);Job job=copy(jobs.get(index));
        job.status=succeeded?(job.enabled?"completed":"disabled"):"paused: 上次运行未完成，需人工处理";
        if(!succeeded)job.enabled=false;
        List<Job> candidate=new ArrayList<Job>(jobs);candidate.set(index,job);
        commit(candidate);active=null;
    }
    private int indexOf(String id){
        for(int i=0;i<jobs.size();i++)if(Objects.equals(id,jobs.get(i).id))return i;
        throw new IllegalArgumentException("任务已不存在，请刷新列表");
    }
    private Job copy(Job j){return G.fromJson(G.toJson(j),Job.class);}
    private void reset(String id){next.remove(id);seen.remove(id);stableAt.remove(id);}
    private void commit(List<Job> candidate)throws IOException {
        List<Job> old=jobs;jobs=candidate;
        try{save();}catch(IOException e){jobs=old;throw e;}
    }
    public synchronized void configure(String json,String fingerprint)throws IOException {
        Job[] parsed=G.fromJson(json,Job[].class);if(parsed==null||parsed.length>10)throw new IllegalArgumentException("最多10个计划任务");Set<String> ids=new HashSet<String>();List<Job> candidate=new ArrayList<Job>();
        for(Job j:parsed){if(j.id==null)j.id=UUID.randomUUID().toString();if(!j.id.matches("[A-Za-z0-9-]+")||!ids.add(j.id))throw new IllegalArgumentException("任务ID非法/重复");
            if(!Arrays.asList("timer","file").contains(j.kind)||j.intervalMinutes<1||j.prompt==null||j.prompt.trim().isEmpty()||j.prompt.length()>12000||!validTools(j.allowedTools))throw new IllegalArgumentException("需要timer/file、正整数分钟、非空提示词及只读工具列表");
            if("file".equals(j.kind)){if(j.watchDir==null||j.watchDir.trim().isEmpty())throw new IllegalArgumentException("file任务必须填写watchDir");watchFingerprint(j.watchDir);}j.modelFingerprint=fingerprint;j.enabled=false;j.status="disabled";j.lastRun=0;candidate.add(j);}
        if(active!=null)throw new IllegalArgumentException("计划任务运行中，先暂停该任务");List<Job> old=jobs;jobs=candidate;try{save();}catch(IOException e){jobs=old;throw e;}next.clear();seen.clear();stableAt.clear();
    }
    public synchronized void enable(boolean enabled)throws IOException {
        List<Job> candidate=new ArrayList<Job>();
        for(Job original:jobs){Job j=copy(original);j.enabled=enabled;if(!j.id.equals(active))j.status=enabled?"armed":"disabled";candidate.add(j);}
        commit(candidate);next.clear();seen.clear();stableAt.clear();
    }
    /** Only one job per bound session; file changes need two stable scans over >=30 seconds. */
    public synchronized Job tick(long now,boolean busy,boolean lastSucceeded,String fingerprint)throws IOException {
        if(active!=null){if(busy)return null;finish(active,lastSucceeded);}
        if(busy)return null;
        for(Job j:jobs){if(!j.enabled)continue;if(!Objects.equals(j.modelFingerprint,fingerprint)){j.enabled=false;j.status="paused: 模型配置已改变";save();continue;}
            boolean due=false;
            if("timer".equals(j.kind)){if(!next.containsKey(j.id)){next.put(j.id,now+j.intervalMinutes*60000L);continue;}due=now>=next.get(j.id);}
            else {String current;try{current=watchFingerprint(j.watchDir);}catch(IOException e){j.enabled=false;j.status="paused: 监听目录不可访问";save();continue;}if(!seen.containsKey(j.id)){seen.put(j.id,current);j.lastFingerprint=current;save();continue;}
                if(!current.equals(seen.get(j.id))){seen.put(j.id,current);stableAt.put(j.id,now);continue;}
                due=!current.equals(j.lastFingerprint)&&stableAt.containsKey(j.id)&&now-stableAt.get(j.id)>=30000;}
            if(due){
                Job dispatched=copy(j);dispatched.lastRun=now;dispatched.status="dispatched";
                if("file".equals(j.kind))dispatched.lastFingerprint=seen.get(j.id);
                List<Job> candidate=new ArrayList<Job>(jobs);candidate.set(indexOf(j.id),dispatched);
                commit(candidate);active=j.id;next.put(j.id,now+j.intervalMinutes*60000L);return copy(dispatched);
            }
        }return null;
    }
    private String watchFingerprint(String raw)throws IOException {
        Path dir=PathsGuard.resolve(workspace.cwd().toString(),raw).toAbsolutePath().normalize();if(!(PathsGuard.inside(workspace.workDir(),dir)||PathsGuard.insideExtra(workspace,dir))||!Files.isDirectory(dir))throw new IOException("监听目录必须存在且已获项目授权");
        List<String> files=new ArrayList<String>();try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir)){for(Path p:stream){if(p.getFileName().toString().startsWith(".")||!Files.isRegularFile(p))continue;if(!PathsGuard.inside(dir.toString(),p))throw new IOException("监听文件链接越界");if(files.size()>=2000)throw new IOException("监听目录超过2000文件，请缩小目录");files.add(p.getFileName()+":"+Files.size(p)+":"+Files.getLastModifiedTime(p).toMillis());}}
        Collections.sort(files);return StopGate.digest(String.join("\n",files));
    }
}
