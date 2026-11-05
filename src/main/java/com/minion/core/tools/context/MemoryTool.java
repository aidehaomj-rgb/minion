package com.minion.core.tools.context;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.context.ProjectMemoryStore;
import com.minion.core.context.TokenCounter;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.storage.HistoryArchive;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 项目级持久记忆与任务交接，内容位于工作区 .minion/memory。 */
public final class MemoryTool implements Tool {
    private final Workspace workspace; private final CheckpointStore checkpoints;
    private final SessionStore sessions;
    private final String sessionId;
    private final com.minion.core.agent.Session session;
    private final Path globalRoot;
    public MemoryTool(Workspace workspace,CheckpointStore checkpoints){this(workspace,checkpoints,null,null);}
    public MemoryTool(Workspace workspace,CheckpointStore checkpoints,SessionStore sessions,String sessionId){
        this(workspace,checkpoints,sessions,sessionId,null);
    }
    public MemoryTool(Workspace workspace,CheckpointStore checkpoints,SessionStore sessions,String sessionId,
                      com.minion.core.agent.Session session){
        this(workspace,checkpoints,sessions,sessionId,session,null);
    }
    public MemoryTool(Workspace workspace,CheckpointStore checkpoints,SessionStore sessions,String sessionId,
                      com.minion.core.agent.Session session,Path globalRoot){
        this.workspace=workspace;this.checkpoints=checkpoints;this.sessions=sessions;this.sessionId=sessionId;this.session=session;
        this.globalRoot=globalRoot;
    }
    @Override public String name(){return "Memory";}
    @Override public String description(){return "项目长期记忆：index/remember/verify/list/read/edit/forget/delete/unverify/disable/enable/configure/rebuild_index；supersedes/conflicts/aliases/source_paths 为字符串数组，conditions 支持 task_id/path_prefix/valid_from/valid_until。来源指纹变化后需重核，替代者须核验后才淘汰旧结论。query 可带 task/path/since/until 过滤；auto_extract(mode=on/off/status) 项目候选默认关闭。全局个人档案：global_remember(type=identity/user/feedback,content) 保存待核验身份/偏好，global_verify 后跨项目使用；global_list/global_search/global_edit/global_unverify/global_forget 管理；global_generate(mode=on/off/status) 控制真实用户输入候选提取（默认关），global_use(mode=on/off/status) 控制跨项目注入。share(id) 仅可把已核验通用项目偏好复制到全局待核验区。任何全局变更须用户当前明确要求。绝不跨项目检索私人对话。read_task/search_history/read_history 只读当前会话证据。";}
    @Override public JsonObject schema(){JsonObject schema=SchemaGenerator.objectSchema("项目记忆",new String[]{"action","content","query","mode","type","source","id","offset","limit","path","task","since","until","supersedes","conflicts","aliases","source_paths","conditions"},new String[]{"action"});
        JsonObject props=schema.getAsJsonObject("properties");
        for(String key:new String[]{"supersedes","conflicts","aliases","source_paths"}){
            JsonObject a=new JsonObject();a.addProperty("type","array");
            JsonObject item=new JsonObject();item.addProperty("type","string");a.add("items",item);props.add(key,a);
        }
        JsonObject c=SchemaGenerator.objectSchema("适用条件；无任务/路径上下文时不注入",new String[]{"task_id","path_prefix","valid_from","valid_until"},new String[]{});
        props.add("conditions",c);return schema;
    }
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return "delete".equals(a)||"disable".equals(a)||"enable".equals(a)||"configure".equals(a)||"update".equals(a)||"handoff".equals(a)||"remember".equals(a)||"edit".equals(a)||"forget".equals(a)||"verify".equals(a)||"unverify".equals(a)||"share".equals(a)||(a.startsWith("global_")&&!"global_list".equals(a)&&!"global_search".equals(a)&&!(("global_use".equals(a)||"global_generate".equals(a))&&"status".equalsIgnoreCase(text(args,"mode"))))||("auto_extract".equals(a)&&!"status".equalsIgnoreCase(text(args,"mode")));}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        String a=text(args,"action").toLowerCase(Locale.ROOT);
        if("delete".equals(a))a="forget";if("global_delete".equals(a))a="global_forget";
        Path file="handoff".equals(a)?root().resolve("handoff.md"):root().resolve("project.md");
        ProjectMemoryStore typed=new ProjectMemoryStore(Paths.get(workspace.workDir()));
        if("auto_extract".equals(a)){
            String mode=text(args,"mode").toLowerCase(Locale.ROOT);
            if("on".equals(mode) && !explicitUserRequest("auto"))
                return ToolResult.error("开启自动记忆提取须由当前用户明确提出；模型不能自行开启");
            if("on".equals(mode)||"off".equals(mode)) typed.setAutoExtractEnabled("on".equals(mode));
            else if(!"status".equals(mode))return ToolResult.error("mode 需为 on/off/status；默认关闭");
            return ToolResult.success("本项目自动候选提取: "+(typed.autoExtractEnabled()?"开启":"关闭")
                    +"；仅保存待核验候选，不自动写入跨项目记忆");
        }
        if("share".equals(a)||a.startsWith("global_")){
            if(globalRoot==null)return ToolResult.error("当前入口未配置跨项目个人偏好存储");
            if(("share".equals(a)||"global_configure".equals(a)||"global_disable".equals(a)||"global_enable".equals(a)||"global_verify".equals(a)||"global_forget".equals(a)
                    ||"global_remember".equals(a)||"global_edit".equals(a)||"global_unverify".equals(a)
                    ||("global_generate".equals(a)&&!"status".equalsIgnoreCase(text(args,"mode")))
                    ||("global_use".equals(a)&&!"status".equalsIgnoreCase(text(args,"mode"))))
                    && !explicitUserRequest("global"))
                return ToolResult.error("跨项目记忆变更须由当前用户明确提出，不能由模型自行决定");
            if ("global_verify".equals(a) && !explicitUserRequest("verify_global"))
                return ToolResult.error("全局记忆核验须由当前用户明确确认，不能在保存候选的同一指令里自行通过");
            if (("global_remember".equals(a) || "share".equals(a))
                    && !explicitUserRequest("save_global"))
                return ToolResult.error("保存/共享全局记忆须由当前用户明确提出");
            if (("global_generate".equals(a) || "global_use".equals(a))
                    && "on".equalsIgnoreCase(text(args,"mode")) && !explicitUserRequest("enable_global"))
                return ToolResult.error("开启全局记忆功能须由当前用户明确提出");
            ProjectMemoryStore shared=ProjectMemoryStore.global(globalRoot);
            if("global_generate".equals(a)||"global_use".equals(a)){
                String mode=text(args,"mode").toLowerCase(Locale.ROOT);
                if(!("on".equals(mode)||"off".equals(mode)||"status".equals(mode)))
                    return ToolResult.error("mode 需为 on/off/status");
                if(!"status".equals(mode)){
                    if("global_generate".equals(a))shared.setGlobalGenerateEnabled("on".equals(mode));
                    else shared.setGlobalUseEnabled("on".equals(mode));
                }
                return ToolResult.success("全局个人记忆候选提取: "+(shared.globalGenerateEnabled()?"开启":"关闭")
                        +"；全局记忆注入: "+(shared.globalUseEnabled()?"开启":"关闭")
                        +"；候选始终待核验，项目私人对话不共享");
            }
            if("global_remember".equals(a)){
                ProjectMemoryStore.Entry entry=shared.remember(text(args,"type"),text(args,"content"),
                        "用户明确要求；当前会话 "+(sessionId==null?"未知":sessionId),details(args,null));
                return ToolResult.success("已保存全局待核验个人记忆 "+entry.id+"；审核后用 global_verify(id)");
            }
            if("global_edit".equals(a)){
                ProjectMemoryStore.Entry entry=shared.edit(text(args,"id"),text(args,"content"),
                        "用户明确更正；当前会话 "+(sessionId==null?"未知":sessionId),details(args,shared.read(text(args,"id"))));
                return ToolResult.success("已修改全局记忆 "+entry.id+"，已降为待核验");
            }
            if("global_configure".equals(a)){
                ProjectMemoryStore.Entry entry=shared.setDetails(text(args,"id"),details(args,shared.read(text(args,"id"))));
                return ToolResult.success("全局关系/条件已修改，须人工重新核验 "+entry.id);
            }
            if("global_disable".equals(a)||"global_enable".equals(a)){
                ProjectMemoryStore.Entry entry=shared.setDisabled(text(args,"id"),"global_disable".equals(a));
                return ToolResult.success("全局记忆 "+entry.id+" 状态: "+entry.state+"；启用后仍需人工核验");
            }
            if("global_unverify".equals(a)){
                ProjectMemoryStore.Entry entry=shared.setVerified(text(args,"id"),false);
                return ToolResult.success("已停用全局记忆 "+entry.id);
            }
            if("share".equals(a)){
                ProjectMemoryStore.Entry source=typed.read(text(args,"id"));
                if(!source.verified)return ToolResult.error("只有已核验项目记忆可申请共享");
                if(!("user".equals(source.type)||"feedback".equals(source.type)))
                    return ToolResult.error("跨项目仅允许共享个人偏好/用户纠正；项目事实和私人会话不能共享");
                ProjectMemoryStore.Entry copy=shared.remember(source.type,source.text,
                        "用户显式申请共享；源项目记忆ID="+source.id);
                return ToolResult.success(copy.verified?"该跨项目偏好已核验存在 "+copy.id
                        :"已复制到跨项目待核验区 "+copy.id
                        +"；仍不能被其他项目召回。请审核内容后执行 global_verify(id)");
            }
            if("global_verify".equals(a)){
                ProjectMemoryStore.Entry entry=shared.setVerified(text(args,"id"),true);
                return ToolResult.success("已核验跨项目个人偏好 "+entry.id+"；其他项目后续对话可按需召回");
            }
            if("global_forget".equals(a)){
                ProjectMemoryStore.Entry entry=shared.forget(text(args,"id"));
                return ToolResult.success("已删除跨项目个人偏好 "+entry.id);
            }
            if("global_list".equals(a)){
                StringBuilder out=new StringBuilder("全局个人身份/偏好（待核验不会召回；自动候选默认关闭）：");
                int count=0;for(ProjectMemoryStore.Entry entry:shared.list()){
                    if(count++>=100){out.append("\n…仅展示前100条");break;}
                    out.append("\n").append(entry.id).append(" [")
                            .append(state(entry)).append("] ")
                            .append(entry.text).append(" | 来源: ").append(entry.source);
                }
                return ToolResult.success(out.toString());
            }
            if("global_search".equals(a)){
                String query=text(args,"query");if(query.trim().isEmpty())return ToolResult.error("需要 query");
                StringBuilder out=new StringBuilder();for(ProjectMemoryStore.Entry entry:shared.search(query,10))
                    out.append(entry.id).append(" [已核验跨项目偏好] ").append(entry.text)
                            .append(" | 来源: ").append(entry.source).append('\n');
                return ToolResult.success(out.length()==0?"未找到":out.toString().trim());
            }
        }
        if("remember".equals(a)){
            ProjectMemoryStore.Entry entry=typed.remember(text(args,"type"),text(args,"content"),text(args,"source"),details(args,null));
            return ToolResult.success("已保存待核验项目记忆 "+entry.id+"（"+entry.type+"），来源: "+entry.source+"；核对来源后使用 verify 才会自动检索注入");
        }
        if("configure".equals(a)){
            ProjectMemoryStore.Entry entry=typed.setDetails(text(args,"id"),details(args,typed.read(text(args,"id"))));
            return ToolResult.success("关系/适用条件/来源已更新，已降为待核验 "+entry.id);
        }
        if("disable".equals(a)||"enable".equals(a)){
            ProjectMemoryStore.Entry entry=typed.setDisabled(text(args,"id"),"disable".equals(a));
            return ToolResult.success("记忆 "+entry.id+" 状态: "+entry.state+"；重新启用须核验");
        }
        if("rebuild_index".equals(a)){typed.rebuildIndex();return ToolResult.success("已清除派生索引；下次检索按需构建，不重写历史");}
        if("verify".equals(a)||"unverify".equals(a)){
            ProjectMemoryStore.Entry entry=typed.setVerified(text(args,"id"),"verify".equals(a));
            return ToolResult.success("项目记忆 "+entry.id+" 状态: "+state(entry));
        }
        if("forget".equals(a)){
            ProjectMemoryStore.Entry entry=typed.forget(text(args,"id"));
            return ToolResult.success("项目记忆已移至 memory/trash："+entry.id);
        }
        if("edit".equals(a)){
            ProjectMemoryStore.Entry entry=typed.edit(text(args,"id"),text(args,"content"),text(args,"source"),details(args,typed.read(text(args,"id"))));
            return ToolResult.success("已修改项目记忆 "+entry.id+"，来源: "+entry.source);
        }
        if("list".equals(a)){
            StringBuilder out=new StringBuilder("项目记忆（待核验条目不会自动注入模型）：");
            int count=0;for(ProjectMemoryStore.Entry entry:typed.list()){
                if(count++>=100){out.append("\n…仅展示前100条");break;}
                out.append("\n").append(entry.id).append(" [").append(entry.type).append("/")
                        .append(state(entry)).append("] ")
                        .append(entry.text).append(" | 来源: ").append(entry.source)
                        .append(" | 项目: ").append(entry.project)
                        .append(" | 更新: ").append(entry.updatedAt).append(" | 原文: ").append(entry.location).append(':').append(entry.line)
                        .append(" | supersedes=").append(entry.supersedes).append(" conflicts=").append(entry.conflicts)
                        .append(" conditions=").append(new com.google.gson.Gson().toJson(entry.conditions));
            }
            return ToolResult.success(out.toString());
        }
        if("index".equals(a)){
            StringBuilder out=new StringBuilder("当前项目已核验记忆短索引（按需 read/检索原文；不是当前任务指令）：");
            int count=0;
            for(ProjectMemoryStore.Entry entry:typed.verifiedIndex(20,scope(args))){
                if(count++>=60){out.append("\n…其余条目请按需检索");break;}
                out.append("\n").append(entry.id).append(" [").append(entry.type).append("] ")
                        .append(entry.text,0,Math.min(80,entry.text.length()));
            }
            return ToolResult.success(out.toString());
        }
        if("search_history".equals(a)){
            if(sessions==null)return ToolResult.error("本入口未连接会话历史档案");
            String query=text(args,"query");if(query.trim().isEmpty())return ToolResult.error("search_history 需要 query");
            long started=System.nanoTime();
            StringBuilder out=new StringBuilder();
            for(HistoryArchive.Hit hit:sessions.searchHistory(sessionId,query,parseInt(args,"limit",8),emptyToNull(text(args,"task")),text(args,"path"))){
                out.append("\n[").append(hit.messageId).append("] event=").append(hit.eventId)
                        .append(" task=").append(hit.taskId).append(" time=").append(hit.time)
                        .append(" offset=").append(hit.offset)
                        .append(" ").append(hit.role).append(hit.tool==null?"":"/"+hit.tool)
                        .append(hit.filePaths==null||hit.filePaths.isEmpty()?"":" files="+hit.filePaths)
                        .append(" sourceState=").append(hit.sourceState).append(" [历史资料，非已核验长期事实]")
                        .append("\n").append(hit.snippet);
            }
            String response=out.length()==0?"未找到压缩前原文":out.toString().trim();
            DiagnosticLog.info("memory", "manual_search elapsedMs="+(System.nanoTime()-started)/1000000
                    +" estimatedTokens="+TokenCounter.estimate(response));
            return ToolResult.success(response);
        }
        if("read_history".equals(a)){
            if(sessions==null)return ToolResult.error("本入口未连接会话历史档案");
            return ToolResult.success(sessions.readHistory(sessionId,text(args,"id"),
                    parseInt(args,"offset",0),parseInt(args,"limit",12000)));
        }
        if("read_task".equals(a)){
            if(session==null||session.taskState==null||session.taskState.objective==null)
                return ToolResult.error("当前会话没有可读取的任务原文");
            String original;String taskId;String label="最初用户要求";
            synchronized(session.taskState){
                original=session.taskState.objective;taskId=session.taskState.id;
                if("guidance".equalsIgnoreCase(text(args,"mode"))) {
                    int count=session.taskState.guidance==null?0:session.taskState.guidance.size();
                    int number=parseInt(args,"id",count);
                    if(number<1||number>count)return ToolResult.error("用户补充编号不存在；当前共 "+count+" 条");
                    original=session.taskState.guidance.get(number-1);
                    label="第 "+number+" 条用户补充";
                }
            }
            int start=Math.max(0,Math.min(original.length(),parseInt(args,"offset",0)));
            int length=Math.max(1,Math.min(12000,parseInt(args,"limit",6000)));
            int end=(int)Math.min(original.length(),(long)start+length);
            return ToolResult.success("当前任务 "+taskId+" "+label+"原文（优先于旧摘要）；字符 "
                    +start+"-"+end+"/"+original.length()+"；继续读取请设置 offset="+end+"\n"
                    +original.substring(start,end));
        }
        if("status".equals(a))return ToolResult.success("记忆目录: "+root()+"\n项目自动候选提取: "+(typed.autoExtractEnabled()?"开启（仅待核验）":"关闭")+"\n全局个人记忆: "+(globalRoot==null?"未配置":globalRoot.resolve(".minion/memory/global-entries"))+"\n全局候选提取: "+(globalRoot==null?"未配置":ProjectMemoryStore.global(globalRoot).globalGenerateEnabled()?"开启":"关闭")+"\n全局记忆注入: "+(globalRoot==null?"未配置":ProjectMemoryStore.global(globalRoot).globalUseEnabled()?"开启":"关闭")+"\n项目记忆: "+describe(root().resolve("project.md"))+"\n交接记录: "+describe(root().resolve("handoff.md")));
        if("read".equals(a)&&!text(args,"id").isEmpty()){
            ProjectMemoryStore.Entry entry=typed.read(text(args,"id"));
            return ToolResult.success(entry.id+" ["+state(entry)+"] "+entry.text+"\n来源: "+entry.source+"\n原文: "+entry.location+":"+entry.line);
        }
        if("read".equals(a))return ToolResult.success(Files.exists(file)?new String(Files.readAllBytes(file),StandardCharsets.UTF_8):"尚无项目记忆");
        if("search".equals(a)){String q=text(args,"query");if(q.trim().isEmpty())return ToolResult.error("search 缺少 query");return search(q,args);}
        if("update".equals(a)||"handoff".equals(a)){String content=text(args,"content");if(content.trim().isEmpty())return ToolResult.error(a+" 缺少 content");Files.createDirectories(file.getParent());if(checkpoints!=null)checkpoints.create(file,"Memory."+a);String mode=text(args,"mode");String body=("replace".equalsIgnoreCase(mode)?"":"\n\n## "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())+"\n")+content.trim()+"\n";if("replace".equalsIgnoreCase(mode))Files.write(file,body.getBytes(StandardCharsets.UTF_8));else Files.write(file,body.getBytes(StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);return ToolResult.success("已更新: "+file);}
        return ToolResult.error("未知 action: "+a+"（支持项目/全局记忆管理、global_generate/global_use、当前会话历史检索）");
    }
    private ToolResult search(String q,JsonObject args)throws Exception{
        StringBuilder out=new StringBuilder();
        // 老版手写 project/handoff 仍可查，但必须明确标为未经核验的历史资料。
        boolean filtered=!text(args,"task").isEmpty()||!text(args,"path").isEmpty()||!text(args,"since").isEmpty()||!text(args,"until").isEmpty();
        if(!filtered)for(String name:new String[]{"project.md","handoff.md"}){
            Path p=root().resolve(name);if(!Files.exists(p))continue;
            String[] lines=new String(Files.readAllBytes(p),StandardCharsets.UTF_8).split("\\r?\\n");
            for(int i=0;i<lines.length;i++)if(lines[i].toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                out.append(p.toAbsolutePath()).append(':').append(i+1).append(" [旧版记录/未经核验，不是当前决定] ").append(lines[i]).append('\n');
        }
        for(ProjectMemoryStore.Entry entry:new ProjectMemoryStore(Paths.get(workspace.workDir())).search(q,parseInt(args,"limit",10),scope(args)))
            out.append(entry.id).append(" [").append(entry.type).append("/已核验] ").append(entry.text)
                    .append(" | 来源: ").append(entry.source).append(" | 原文: ").append(entry.location).append(':').append(entry.line).append('\n');
        return ToolResult.success(out.length()==0?"未找到":out.toString().trim());
    }

    private ProjectMemoryStore.Scope scope(JsonObject args){
        ProjectMemoryStore.Scope s=new ProjectMemoryStore.Scope();s.taskId=emptyToNull(text(args,"task"));
        if(s.taskId==null&&session!=null&&session.taskState!=null)s.taskId=session.taskState.id;
        s.path=emptyToNull(text(args,"path"));s.createdAfter=longValue(args,"since");s.createdBefore=longValue(args,"until");return s;
    }
    private static String state(ProjectMemoryStore.Entry e){return e.state==null?(e.verified?"已核验":"待核验"):e.state+"/来源="+e.sourceState;}
    private static String emptyToNull(String s){return s==null||s.trim().isEmpty()?null:s.trim();}
    private static long longValue(JsonObject o,String key){String v=text(o,key);if(v.isEmpty())return 0;return Long.parseLong(v);}
    private static List<String> strings(JsonObject o,String key,List<String> fallback){
        if(!o.has(key))return fallback;
        List<String> values=new ArrayList<String>();JsonElement value=o.get(key);
        if(value==null||value.isJsonNull())return values;
        if(value.isJsonArray()){for(JsonElement item:value.getAsJsonArray())values.add(item.getAsString());}
        else for(String item:value.getAsString().split("[,，;；\\n]"))if(!item.trim().isEmpty())values.add(item.trim());
        return values;
    }
    private static ProjectMemoryStore.Details details(JsonObject args,ProjectMemoryStore.Entry old){
        boolean has=false;for(String key:new String[]{"supersedes","conflicts","aliases","source_paths","conditions"})if(args.has(key))has=true;
        if(!has)return null;
        ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();
        d.supersedes=strings(args,"supersedes",old==null?null:old.supersedes);
        d.conflicts=strings(args,"conflicts",old==null?null:old.conflicts);
        d.aliases=strings(args,"aliases",old==null?null:old.aliases);
        d.sourcePaths=strings(args,"source_paths",old==null?null:old.sourcePaths);
        d.conditions=old==null?null:old.conditions;
        if(args.has("conditions")){
            if(args.get("conditions").isJsonNull())d.conditions=null;
            else{
                JsonObject c=args.getAsJsonObject("conditions");d.conditions=new ProjectMemoryStore.Conditions();
                d.conditions.taskId=emptyToNull(text(c,"task_id"));d.conditions.pathPrefix=emptyToNull(text(c,"path_prefix"));
                d.conditions.validFrom=longValue(c,"valid_from");d.conditions.validUntil=longValue(c,"valid_until");
            }
        }
        return d;
    }
    private Path root(){return Paths.get(workspace.workDir()).resolve(".minion").resolve("memory");}
    private boolean explicitUserRequest(String kind) {
        if(session==null||session.messages==null)return false;
        String current="";
        boolean authored=false;
        for(int i=session.messages.size()-1;i>=0;i--)
            if(session.messages.get(i)!=null&&session.messages.get(i).role==com.minion.core.llm.Message.Role.USER){
                current=session.messages.get(i).content==null?"":session.messages.get(i).content;
                authored=session.messages.get(i).userAuthored;
                break;
            }
        if(!authored)return false;
        if(current.length()>4000)current=current.substring(current.length()-4000);
        if("auto".equals(kind))return current.matches("(?s).*(?:自动记忆|自动提取|自动保存.*记忆).*");
        boolean scope=current.matches("(?s).*(?:跨项目|所有项目|全局).*(?:共享|记忆|偏好|规则|删除|忘记).*")
                || current.matches("(?s).*(?:共享|记忆|偏好|规则|删除|忘记).*(?:跨项目|所有项目|全局).*");
        if(!scope)return false;
        if("verify_global".equals(kind))return current.matches("(?s).*(?:确认|核验|审核通过|批准).*");
        if("save_global".equals(kind))return current.matches("(?s).*(?:共享|记住|保存|记录|存为|建立).*")
                && !current.matches("(?s).*(?:不要|禁止|别|不准).*(?:共享|记住|保存|记录|存为|建立).*");
        if("enable_global".equals(kind))return current.matches("(?s).*(?:开启|启用|打开|恢复|允许|使用).*")
                && !current.matches("(?s).*(?:不要|禁止|别|不准|关闭|停用).*(?:开启|启用|打开|恢复|允许|使用).*");
        return true;
    }
    private static String describe(Path p){try{return Files.exists(p)?Files.size(p)+" bytes，修改 "+Files.getLastModifiedTime(p):"不存在";}catch(Exception e){return "读取失败";}}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int parseInt(JsonObject o,String key,int fallback){try{return Integer.parseInt(text(o,key));}catch(Exception ignored){return fallback;}}
}
