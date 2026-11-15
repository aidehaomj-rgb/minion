package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** User-owned control plane. Never accepts settings from a model tool response. */
public final class ReliabilityService {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private final Workspace workspace;
    private final Path root;
    private Settings settings;
    private String brokenReason;
    private final Map<String,Step> pendingMutations=new HashMap<String,Step>();
    private boolean pendingLoaded;
    private static String pendingKey(Step s){return s.taskId+"\n"+s.tool+"\n"+s.argumentsHash;}
    private void loadPending()throws IOException {if(pendingLoaded)return;for(Step s:steps())if(s.mutation&&Arrays.asList("started","uncertain").contains(s.state))pendingMutations.put(pendingKey(s),s);pendingLoaded=true;}
    public static final class Settings {
        public boolean strictCompatibility;
        public String taskId;
        public JsonArray rules=new JsonArray();
    }
    public static final class Step {
        public String id,taskId,scope,tool,argumentsHash,state,resultHash,note;
        public boolean mutation,ok;
        public long startedAt,endedAt;
    }
    public ReliabilityService(Workspace w,String sessionId) {
        workspace=w;
        if(sessionId==null||!sessionId.matches("[A-Za-z0-9-]+"))throw new IllegalArgumentException("会话ID非法");
        root=Paths.get(w.workDir(),".minion","reliability",sessionId).toAbsolutePath().normalize();
        try {guard();settings=Files.exists(root.resolve("settings.json"))?G.fromJson(read(root.resolve("settings.json")),Settings.class):new Settings();}
        catch(Exception e){settings=new Settings();settings.strictCompatibility=true;brokenReason="可靠执行设置无法读取，已进入严格模式: "+e.getMessage();}
        if(settings==null)settings=new Settings();if(settings.rules==null)settings.rules=new JsonArray();
    }
    private void guard() throws IOException {if(!PathsGuard.inside(workspace.workDir(),root))throw new IOException("控制记录目录经链接越界");}
    private static String read(Path p)throws IOException {if(Files.size(p)>2*1024*1024)throw new IOException("控制记录超过2MB");return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
    private void write(Path p,Object data)throws IOException {guard();AtomicFiles.writeText(p,G.toJson(data),StandardCharsets.UTF_8);}
    public Path root(){return root;}
    public Workspace workspace(){return workspace;}
    public synchronized Settings settings(){return G.fromJson(G.toJson(settings),Settings.class);}
    public synchronized void configure(String taskId,String rules,boolean strict)throws IOException {
        JsonElement parsed=JsonParser.parseString(rules);if(!parsed.isJsonArray())throw new IllegalArgumentException("规则必须为JSON数组");
        AcceptanceRules.validate(parsed.getAsJsonArray());
        if(parsed.getAsJsonArray().size()>0&&(taskId==null||taskId.isEmpty()))throw new IllegalArgumentException("先启动任务，再登记本任务验收规则");
        Settings next=new Settings();next.taskId=taskId;next.rules=parsed.getAsJsonArray().deepCopy();next.strictCompatibility=strict;
        write(root.resolve("settings.json"),next);settings=next;brokenReason=null;
    }
    public synchronized JsonObject evaluate(TaskState state,java.util.function.Predicate<Path> allowed) {
        Settings s=settings();String id;synchronized(state){id=state.id;}
        if(brokenReason!=null){JsonObject bad=new JsonObject();bad.addProperty("status","unknown");bad.addProperty("reason",brokenReason);return bad;}
        return AcceptanceRules.evaluate(Objects.equals(s.taskId,id)?s.rules:new JsonArray(),workspace,state,allowed);
    }
    public synchronized boolean permits(String name,JsonObject args) {
        if(!settings.strictCompatibility)return true;
        if(Arrays.asList("Read","ViewImage","Glob","Grep","TodoWrite","AskUserQuestion","AgentControl","Context","task","DiscoverTools").contains(name))return true;
        if("ArtifactVerification".equals(name))return ToolExecutionGuard.readOnly(name,args);
        if("Build".equals(name))return ToolExecutionGuard.readOnly(name,args);
        // Fixed document/spreadsheet workflows still pass their own path/operation gates.
        return "Document".equals(name)||"Excel".equals(name)||"Artwork".equals(name)||("Knowledge".equals(name)&&ToolExecutionGuard.readOnly(name,args));
    }
    public synchronized Step start(String task,String scope,String tool,JsonObject args,boolean mutation)throws IOException {
        String hash=StopGate.digest(args.toString());
        loadPending();Step previous=pendingMutations.get(task+"\n"+tool+"\n"+hash);
        if(mutation&&previous!=null)throw new IOException("该操作上次结果不确定，禁止盲目重试。请在任务面板核对步骤 "+previous.id);
        Step step=new Step();step.id=UUID.randomUUID().toString();step.taskId=task;step.scope=scope;step.tool=tool;
        step.argumentsHash=hash;step.startedAt=System.currentTimeMillis();step.state="started";step.mutation=mutation;
        write(root.resolve("steps").resolve(step.id+".json"),step);if(mutation)pendingMutations.put(pendingKey(step),step);return step;
    }
    public synchronized void finish(Step step,ToolResult result)throws IOException {
        step.ok=result.ok;step.resultHash=StopGate.digest(result.output==null?"":result.output);step.endedAt=System.currentTimeMillis();
        step.state=result.ok||!step.mutation?"completed":result.noSideEffect?"failed_no_effect":"uncertain";
        write(root.resolve("steps").resolve(step.id+".json"),step);
        if("uncertain".equals(step.state))pendingMutations.put(pendingKey(step),step);
        else pendingMutations.remove(pendingKey(step));
    }
    public synchronized List<Step> steps()throws IOException {
        guard();List<Step> all=new ArrayList<Step>();Path dir=root.resolve("steps");if(!Files.isDirectory(dir))return all;
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir,"*.json")){for(Path p:stream){Step s=G.fromJson(read(p),Step.class);if(s==null||s.id==null||s.state==null)throw new IOException("步骤记录损坏: "+p.getFileName());all.add(s);}}
        all.sort(Comparator.comparingLong(s->s.startedAt));return all;
    }
    public synchronized JsonArray unresolved(String task) {
        JsonArray out=new JsonArray();if(brokenReason!=null){JsonObject bad=new JsonObject();bad.addProperty("state","unreadable");bad.addProperty("note",brokenReason);out.add(bad);}
        try {loadPending();for(Step s:pendingMutations.values())if(s.mutation&&Objects.equals(task,s.taskId))out.add(G.toJsonTree(s));}
        catch(Exception e){JsonObject bad=new JsonObject();bad.addProperty("state","unreadable");bad.addProperty("note","步骤日志无法读取，请人工检查");out.add(bad);}return out;
    }
    /** An interrupted operation or unreadable journal has no trustworthy result at all. */
    public static boolean hasUnobservedOutcome(JsonArray steps) {
        for(JsonElement element:steps) {
            if(!element.isJsonObject())return true;
            JsonObject step=element.getAsJsonObject();
            if(!step.has("state")||step.get("state").isJsonNull())return true;
            String state=step.get("state").getAsString();
            if("started".equals(state)||"unreadable".equals(state))return true;
        }
        return false;
    }
    public static String unresolvedSummary(JsonArray steps) {
        List<String> items=new ArrayList<String>();
        for(JsonElement element:steps) {
            if(!element.isJsonObject()){items.add("日志格式未知");continue;}
            JsonObject step=element.getAsJsonObject();
            String tool=field(step,"tool"),state=field(step,"state"),id=field(step,"id");
            items.add((tool.isEmpty()?"步骤":tool)+" "+(id.isEmpty()?"ID未知":id)+" ("+(state.isEmpty()?"状态未知":state)+")");
            if(items.size()>=3)break;
        }
        return String.join("、",items)+(steps.size()>items.size()?" 等"+steps.size()+"项":"");
    }
    private static String field(JsonObject object,String name) {
        JsonElement value=object.get(name);
        return value==null||value.isJsonNull()?"":value.getAsString();
    }
    public synchronized void acknowledge(String id,String note)throws IOException {
        if(id==null||!id.matches("[A-Za-z0-9-]+")||note==null||note.trim().isEmpty())throw new IllegalArgumentException("需要步骤ID和实际核对说明");
        Path p=root.resolve("steps").resolve(id+".json");Step s=G.fromJson(read(p),Step.class);
        if(!Arrays.asList("started","uncertain").contains(s.state))throw new IllegalArgumentException("只能核对不确定的步骤");
        s.state="verified";s.note=note.substring(0,Math.min(note.length(),2000));s.endedAt=System.currentTimeMillis();write(p,s);pendingMutations.remove(pendingKey(s));
    }
}
