package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.ProcessCleanup;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Explicit session-local approval of an immutable hook snapshot. Never enabled by a model tool. */
public final class SessionHooks {
    private static final int MAX_CONFIG=65536, MAX_OUTPUT=16384;
    private final Path root, config;
    private final java.util.function.Supplier<Path> currentRoot;
    private final Set<Process> active=Collections.newSetFromMap(new ConcurrentHashMap<Process,Boolean>());
    private final java.util.concurrent.atomic.AtomicLong cancellationEpoch=new java.util.concurrent.atomic.AtomicLong();
    private volatile Approved approved;
    private volatile JsonObject last;
    private static final class Approved {
        final String hash; final List<Hook> hooks;
        Approved(String hash,List<Hook> hooks){this.hash=hash;this.hooks=hooks;}
    }
    private static final class Hook {
        String id,event,tool,action; List<String> command; int timeout;
    }
    public SessionHooks(Path root){this(root,()->root);}
    public SessionHooks(com.minion.core.tools.Workspace workspace){this(Paths.get(workspace.workDir()),()->Paths.get(workspace.workDir()));}
    private SessionHooks(Path root,java.util.function.Supplier<Path> currentRoot){this.root=root.toAbsolutePath().normalize();this.currentRoot=currentRoot;config=this.root.resolve(".minion/hooks.json");}
    private boolean scopeCurrent(){return root.equals(currentRoot.get().toAbsolutePath().normalize());}
    private byte[] readConfiguration() throws IOException {
        if(!scopeCurrent())throw new IOException("项目根已改变，请在新会话重新配置并批准Hooks");
        if(!PathsGuard.inside(root.toString(),config)||!Files.isRegularFile(config)||Files.size(config)>MAX_CONFIG)
            throw new IOException("hooks.json不存在、越界或超过64KiB");
        byte[] b=Files.readAllBytes(config);if(b.length>MAX_CONFIG)throw new IOException("hooks.json过大");return b;
    }
    public String configurationPreview() throws IOException {
        byte[] bytes=readConfiguration();parse(bytes);
        JsonObject out=new JsonObject();out.addProperty("path",config.toString());out.addProperty("hash",hash(bytes));
        out.addProperty("config",new String(bytes,StandardCharsets.UTF_8));return out.toString();
    }
    /** Only a user-confirmed UI route calls this; approval is deliberately not persisted in project files. */
    public synchronized void approve(String expectedHash) throws IOException {
        byte[] bytes=readConfiguration();String actual=hash(bytes);
        if(!actual.equals(expectedHash))throw new IOException("配置在预览后改变，请重新查看并批准");
        approved=new Approved(actual,parse(bytes));
    }
    public synchronized void disable(){approved=null;cancelRunning();}
    public void cancelRunning(){
        cancellationEpoch.incrementAndGet();
        final List<Process> processes=new ArrayList<Process>(active);
        if(processes.isEmpty())return;
        Thread t=new Thread(()->{for(Process p:processes)if(p.isAlive())ProcessCleanup.terminate(p);},"minion-hook-cancel");
        t.setDaemon(true);t.start();
    }
    public JsonObject status(){
        Approved a=approved;JsonObject out=new JsonObject();out.addProperty("enabled",a!=null&&scopeCurrent());
        out.addProperty("workspaceScopeCurrent",scopeCurrent());
        out.addProperty("approvalScope","this_session_only");out.addProperty("path",config.toString());
        if(a!=null){out.addProperty("approvedHash",a.hash);out.addProperty("hookCount",a.hooks.size());}
        JsonObject r=last;if(r!=null)out.add("lastRun",r.deepCopy());return out;
    }
    public boolean matches(String event,String tool,JsonObject args){
        Approved a=approved;if(a==null||!scopeCurrent())return false;
        for(Hook h:a.hooks)if(matches(h,event,tool,args))return true;return false;
    }
    private static boolean matches(Hook h,String event,String tool,JsonObject args){
        String action=args!=null&&args.has("action")&&args.get("action").isJsonPrimitive()?args.get("action").getAsString():"";
        return h.event.equals(event)&&(h.tool.equals("*")||h.tool.equalsIgnoreCase(tool))
                &&(h.action.isEmpty()||h.action.equals(action));
    }
    /** Disabled route has no filesystem access, process, hashing, or model request. */
    public JsonObject run(String event,String tool,JsonObject args,JsonObject metadata) throws InterruptedException {
        Approved a=approved;JsonObject report=new JsonObject();report.addProperty("event",event);report.addProperty("status","passed");
        long epoch=cancellationEpoch.get();
        JsonArray runs=new JsonArray();report.add("runs",runs);
        if(a==null)return report;
        for(Hook h:a.hooks){
            if(!matches(h,event,tool,args))continue;
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("hook cancelled");
            JsonObject receipt=new JsonObject();receipt.addProperty("hookId",h.id);receipt.addProperty("configHash",a.hash);
            receipt.addProperty("startedAt",System.currentTimeMillis());receipt.addProperty("tool",tool);
            runs.add(receipt);
            try {
                JsonObject input=metadata==null?new JsonObject():metadata.deepCopy();input.addProperty("event",event);
                input.addProperty("tool",tool);
                JsonObject arguments=args==null?new JsonObject():args.deepCopy();
                byte[] argumentBytes=arguments.toString().getBytes(StandardCharsets.UTF_8);
                if(argumentBytes.length>4096){
                    JsonObject bounded=new JsonObject();bounded.addProperty("argumentsOmitted",true);bounded.addProperty("sha256",hash(argumentBytes));
                    for(String key:Arrays.asList("path","outputPath","action"))if(arguments.has(key)&&arguments.get(key).isJsonPrimitive()){
                        String value=arguments.get(key).getAsString();bounded.addProperty(key,value.substring(0,Math.min(512,value.length())));
                    }
                    arguments=bounded;
                }
                input.add("arguments",arguments);
                java.util.concurrent.locks.Lock lock=ToolExecutionGuard.exclusiveWorkspaceLock();lock.lockInterruptibly();
                try{
                    if(approved!=a||!a.hash.equals(hash(readConfiguration())))throw new IOException("配置改变或已禁用，需重新批准");
                    execute(h,input,receipt,a,epoch);
                }finally{lock.unlock();}
            }catch(IOException|RuntimeException e){receipt.addProperty("status","failed");receipt.addProperty("reason",e.getMessage());}
            receipt.addProperty("finishedAt",System.currentTimeMillis());last=receipt.deepCopy();
            if(!"passed".equals(receipt.get("status").getAsString())){report.addProperty("status","failed");break;}
        }
        return report;
    }
    private void execute(Hook h,JsonObject input,JsonObject receipt,Approved expected,long epoch) throws IOException,InterruptedException {
        if(cancellationEpoch.get()!=epoch)throw new IOException("Hook运行已取消");
        Process process=new ProcessBuilder(h.command).directory(root.toFile()).redirectErrorStream(true).start();
        active.add(process);
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        ExecutorService io=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"minion-hook-io");t.setDaemon(true);return t;});
        Future<?> drain=io.submit(()->{try(InputStream in=process.getInputStream()){
            byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){synchronized(output){int keep=Math.min(n,Math.max(0,MAX_OUTPUT-output.size()));if(keep>0)output.write(b,0,keep);}}
        }catch(IOException ignored){}});
        Future<?> writer=null;
        try {
            if(approved!=expected||!scopeCurrent()||cancellationEpoch.get()!=epoch)throw new IOException("Hook已停用、取消或项目改变，已请求停止子进程");
            // Metadata is data on stdin; never interpolated into a shell command.
            byte[] payload=input.toString().getBytes(StandardCharsets.UTF_8);
            if(payload.length>8192)throw new IOException("Hook元数据超过8KiB，请减少参数或改用更窄的工具匹配");
            writer=io.submit(()->{try(OutputStream stdin=process.getOutputStream()){stdin.write(payload);}catch(IOException e){throw new java.io.UncheckedIOException(e);}});
            boolean ended=process.waitFor(h.timeout,TimeUnit.MILLISECONDS);
            if(!ended){ProcessCleanup.terminate(process);receipt.addProperty("status","failed");receipt.addProperty("reason","hook_timeout");}
            else {int exit=process.exitValue();receipt.addProperty("exitCode",exit);receipt.addProperty("status",exit==0?"passed":"failed");}
            if(ended)try{writer.get(500,TimeUnit.MILLISECONDS);}catch(ExecutionException|TimeoutException e){receipt.addProperty("status","failed");receipt.addProperty("reason","hook_input_delivery_failed");}
            try{drain.get(500,TimeUnit.MILLISECONDS);}catch(ExecutionException|TimeoutException ignored){}
            synchronized(output){receipt.addProperty("output",new String(output.toByteArray(),StandardCharsets.UTF_8));receipt.addProperty("outputMayBeTruncated",output.size()>=MAX_OUTPUT);}
        }finally {if(process.isAlive())ProcessCleanup.terminate(process);active.remove(process);if(writer!=null)writer.cancel(true);drain.cancel(true);io.shutdownNow();}
    }
    private static List<Hook> parse(byte[] bytes) throws IOException {
        try {
            JsonObject o=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray hooks=o.getAsJsonArray("hooks");if(hooks==null||hooks.size()>8)throw new IllegalArgumentException("hooks需数组，最多8项");
            List<Hook> out=new ArrayList<Hook>();Set<String> ids=new HashSet<String>();
            for(JsonElement e:hooks){JsonObject v=e.getAsJsonObject();Hook h=new Hook();h.id=text(v,"id");h.event=text(v,"event");h.tool=text(v,"tool");h.action=text(v,"action");
                if(!h.id.matches("[A-Za-z0-9_-]{1,64}")||!ids.add(h.id)||!Arrays.asList("before_tool","after_tool","before_complete","after_compact").contains(h.event))throw new IllegalArgumentException("Hook id/event非法或重复");
                if(h.tool.isEmpty())h.tool="*";h.timeout=v.has("timeoutMs")?v.get("timeoutMs").getAsInt():10000;
                if(h.timeout<100||h.timeout>60000)throw new IllegalArgumentException("Hook超时范围100..60000ms");
                h.command=new ArrayList<String>();JsonArray command=v.getAsJsonArray("command");
                if(command==null||command.size()==0||command.size()>32)throw new IllegalArgumentException("command必须为非空argv数组");
                for(JsonElement arg:command){String s=arg.getAsString();if(s.indexOf('\0')>=0||s.length()>4096)throw new IllegalArgumentException("command参数非法");h.command.add(s);}
                out.add(h);
            }return out;
        }catch(RuntimeException e){throw new IOException("Hook配置无效: "+e.getMessage(),e);}
    }
    private static String text(JsonObject o,String key){return o.has(key)?o.get(key).getAsString():"";}
    private static String hash(byte[] bytes){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format("%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
