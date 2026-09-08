package com.minion.core.tools.context;

import com.google.gson.JsonObject;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 项目级持久记忆与任务交接，内容位于工作区 .minion/memory。 */
public final class MemoryTool implements Tool {
    private final Workspace workspace; private final CheckpointStore checkpoints;
    public MemoryTool(Workspace workspace,CheckpointStore checkpoints){this.workspace=workspace;this.checkpoints=checkpoints;}
    @Override public String name(){return "Memory";}
    @Override public String description(){return "项目长期记忆：status/read/search/update/handoff；保存约定、数据口径、待办和会话交接";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("项目记忆",new String[]{"action","content","query","mode"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return "update".equals(a)||"handoff".equals(a);}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        String a=text(args,"action").toLowerCase(Locale.ROOT);Path file="handoff".equals(a)?root().resolve("handoff.md"):root().resolve("project.md");
        if("status".equals(a))return ToolResult.success("记忆目录: "+root()+"\n项目记忆: "+describe(root().resolve("project.md"))+"\n交接记录: "+describe(root().resolve("handoff.md")));
        if("read".equals(a))return ToolResult.success(Files.exists(file)?new String(Files.readAllBytes(file),StandardCharsets.UTF_8):"尚无项目记忆");
        if("search".equals(a)){String q=text(args,"query");if(q.trim().isEmpty())return ToolResult.error("search 缺少 query");return search(q);}
        if("update".equals(a)||"handoff".equals(a)){String content=text(args,"content");if(content.trim().isEmpty())return ToolResult.error(a+" 缺少 content");Files.createDirectories(file.getParent());if(checkpoints!=null)checkpoints.create(file,"Memory."+a);String mode=text(args,"mode");String body=("replace".equalsIgnoreCase(mode)?"":"\n\n## "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())+"\n")+content.trim()+"\n";if("replace".equalsIgnoreCase(mode))Files.write(file,body.getBytes(StandardCharsets.UTF_8));else Files.write(file,body.getBytes(StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);return ToolResult.success("已更新: "+file);}
        return ToolResult.error("未知 action: "+a+"（支持 status/read/search/update/handoff）");
    }
    private ToolResult search(String q)throws Exception{StringBuilder out=new StringBuilder();for(String name:new String[]{"project.md","handoff.md"}){Path p=root().resolve(name);if(!Files.exists(p))continue;String[] lines=new String(Files.readAllBytes(p),StandardCharsets.UTF_8).split("\\r?\\n");for(int i=0;i<lines.length;i++)if(lines[i].toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))out.append(name).append(':').append(i+1).append("  ").append(lines[i]).append('\n');}return ToolResult.success(out.length()==0?"未找到":out.toString().trim());}
    private Path root(){return workspace.cwd().resolve(".minion").resolve("memory");}
    private static String describe(Path p){try{return Files.exists(p)?Files.size(p)+" bytes，修改 "+Files.getLastModifiedTime(p):"不存在";}catch(Exception e){return "读取失败";}}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
}
