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
        public String id=UUID.randomUUID().toString(),kind="timer",prompt="",modelFingerprint,watchDir="",status="disabled",lastFingerprint="";
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
    public synchronized void configure(String json,String fingerprint)throws IOException {
        Job[] parsed=G.fromJson(json,Job[].class);if(parsed==null||parsed.length>10)throw new IllegalArgumentException("最多10个计划任务");Set<String> ids=new HashSet<String>();List<Job> candidate=new ArrayList<Job>();
        for(Job j:parsed){if(j.id==null)j.id=UUID.randomUUID().toString();if(!j.id.matches("[A-Za-z0-9-]+")||!ids.add(j.id))throw new IllegalArgumentException("任务ID非法/重复");
            if(!Arrays.asList("timer","file").contains(j.kind)||j.intervalMinutes<1||j.prompt==null||j.prompt.trim().isEmpty()||j.prompt.length()>12000||!validTools(j.allowedTools))throw new IllegalArgumentException("需要timer/file、正整数分钟、非空提示词及只读工具列表");
            if("file".equals(j.kind)){if(j.watchDir==null||j.watchDir.trim().isEmpty())throw new IllegalArgumentException("file任务必须填写watchDir");watchFingerprint(j.watchDir);}j.modelFingerprint=fingerprint;j.enabled=false;j.status="disabled";j.lastRun=0;candidate.add(j);}
        if(active!=null)throw new IllegalArgumentException("计划任务运行中，先暂停该任务");List<Job> old=jobs;jobs=candidate;try{save();}catch(IOException e){jobs=old;throw e;}next.clear();seen.clear();stableAt.clear();
    }
    public synchronized void enable(boolean enabled)throws IOException {
        for(Job j:jobs){j.enabled=enabled;j.status=enabled?"armed":"disabled";}next.clear();seen.clear();stableAt.clear();save();
    }
    /** Only one job per bound session; file changes need two stable scans over >=30 seconds. */
    public synchronized Job tick(long now,boolean busy,boolean lastSucceeded,String fingerprint)throws IOException {
        if(active!=null){if(busy)return null;for(Job j:jobs)if(j.id.equals(active)){j.status=lastSucceeded?"completed":"paused: 上次运行未完成，需人工处理";if(!lastSucceeded)j.enabled=false;}active=null;save();}
        if(busy)return null;
        for(Job j:jobs){if(!j.enabled)continue;if(!Objects.equals(j.modelFingerprint,fingerprint)){j.enabled=false;j.status="paused: 模型配置已改变";save();continue;}
            boolean due=false;
            if("timer".equals(j.kind)){if(!next.containsKey(j.id)){next.put(j.id,now+j.intervalMinutes*60000L);continue;}due=now>=next.get(j.id);}
            else {String current;try{current=watchFingerprint(j.watchDir);}catch(IOException e){j.enabled=false;j.status="paused: 监听目录不可访问";save();continue;}if(!seen.containsKey(j.id)){seen.put(j.id,current);j.lastFingerprint=current;save();continue;}
                if(!current.equals(seen.get(j.id))){seen.put(j.id,current);stableAt.put(j.id,now);continue;}
                due=!current.equals(j.lastFingerprint)&&stableAt.containsKey(j.id)&&now-stableAt.get(j.id)>=30000;if(due)j.lastFingerprint=current;}
            if(due){j.lastRun=now;j.status="dispatched";active=j.id;next.put(j.id,now+j.intervalMinutes*60000L);save();return G.fromJson(G.toJson(j),Job.class);}
        }return null;
    }
    private String watchFingerprint(String raw)throws IOException {
        Path dir=PathsGuard.resolve(workspace.cwd().toString(),raw).toAbsolutePath().normalize();if(!(PathsGuard.inside(workspace.workDir(),dir)||PathsGuard.insideExtra(workspace,dir))||!Files.isDirectory(dir))throw new IOException("监听目录必须存在且已获项目授权");
        List<String> files=new ArrayList<String>();try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir)){for(Path p:stream){if(p.getFileName().toString().startsWith(".")||!Files.isRegularFile(p))continue;if(!PathsGuard.inside(dir.toString(),p))throw new IOException("监听文件链接越界");if(files.size()>=2000)throw new IOException("监听目录超过2000文件，请缩小目录");files.add(p.getFileName()+":"+Files.size(p)+":"+Files.getLastModifiedTime(p).toMillis());}}
        Collections.sort(files);return StopGate.digest(String.join("\n",files));
    }
}
