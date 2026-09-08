package com.minion.core.tools.skills;

import com.google.gson.JsonObject;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 本地技能目录管理；所有变更在下一次新建会话时生效。 */
public final class SkillAdminTool implements Tool {
    private final Path root; private final Workspace workspace;
    public SkillAdminTool(String skillsDir,Workspace workspace){this.root=java.nio.file.Paths.get(skillsDir).toAbsolutePath().normalize();this.workspace=workspace;}
    @Override public String name(){return "SkillAdmin";}
    @Override public String description(){return "本地技能管理与流程封装：capture 可把已验证的分析流程及工作区脚本/模板保存为标准 Skill；也支持 list/read/validate/create/update/enable/disable/import。新技能在新建会话后生效";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("技能管理与流程封装",new String[]{"action","name","description","content","path","resourcePaths"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return !("list".equals(a)||"read".equals(a)||"validate".equals(a));}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        String a=text(args,"action").toLowerCase(Locale.ROOT),name=safeName(text(args,"name"));Files.createDirectories(root);
        if("list".equals(a)){List<Skill> skills=new SkillManager(root.toString()).scan();StringBuilder out=new StringBuilder("外部技能（").append(skills.size()).append("）：");for(Skill s:skills)out.append('\n').append("- ").append(s.hint()).append("  ").append(s.file);try(Stream<Path>st=Files.walk(root,2)){st.filter(p->p.getFileName().toString().equals("SKILL.md.disabled")).forEach(p->out.append('\n').append("- [disabled] ").append(p.getParent().getFileName()));}return ToolResult.success(out.toString());}
        if(name.isEmpty()&&!"import".equals(a))return ToolResult.error(a+" 缺少合法 name（请使用字母、数字、点、下划线或连字符）");
        Path dir=root.resolve(name),file=dir.resolve("SKILL.md"),disabled=dir.resolve("SKILL.md.disabled");
        if("read".equals(a))return Files.exists(file)?ToolResult.success(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)):ToolResult.error("技能不存在或已禁用: "+name);
        if("validate".equals(a)){Path f=Files.exists(file)?file:disabled;if(!Files.exists(f))return ToolResult.error("技能不存在: "+name);String body=new String(Files.readAllBytes(f),StandardCharsets.UTF_8);return ToolResult.success(body.startsWith("---")&&body.contains("\n---")&&body.contains("description:")?"格式有效: "+f:"格式不完整：需要 YAML frontmatter 和 description");}
        if("create".equals(a)||"update".equals(a)||"capture".equals(a)){if(("create".equals(a)||"capture".equals(a))&&Files.exists(file))return ToolResult.error("技能已存在: "+name+"；如需覆盖请使用 update");String content=text(args,"content");if(content.trim().isEmpty())return ToolResult.error(a+" 缺少 content");String desc=text(args,"description").replace("\n"," ").trim();if("capture".equals(a)&&desc.isEmpty())return ToolResult.error("capture 缺少 description（必须说明以后何时调用）");Files.createDirectories(dir);String body="---\nname: "+name+"\ndescription: "+(desc.isEmpty()?"本地自定义技能":desc)+"\n---\n\n"+content.trim()+"\n";Files.write(file,body.getBytes(StandardCharsets.UTF_8));Files.deleteIfExists(disabled);int resources=copyResources(text(args,"resourcePaths"),dir.resolve("assets"));return ToolResult.success("已保存技能: "+file+(resources>0?"；已收录 "+resources+" 个资源文件":"")+"（新建会话后生效，请用 validate 校验）");}
        if("disable".equals(a)){if(!Files.exists(file))return ToolResult.error("技能不存在或已禁用: "+name);Files.move(file,disabled,StandardCopyOption.REPLACE_EXISTING);return ToolResult.success("已禁用: "+name+"（新建会话后生效）");}
        if("enable".equals(a)){if(!Files.exists(disabled))return ToolResult.error("未找到已禁用技能: "+name);Files.move(disabled,file,StandardCopyOption.REPLACE_EXISTING);return ToolResult.success("已启用: "+name+"（新建会话后生效）");}
        if("import".equals(a))return importSkill(text(args,"path"),text(args,"name"));
        return ToolResult.error("未知 action（支持 capture/list/read/validate/create/update/enable/disable/import）");
    }
    private int copyResources(String raw,Path assets)throws Exception{if(raw==null||raw.trim().isEmpty())return 0;int count=0;for(String item:raw.split(";")){if(item.trim().isEmpty())continue;Path source=workspace.cwd().resolve(item.trim()).normalize().toAbsolutePath();ToolResult g=PathsGuard.errorIfOutside(workspace.workDir(),source);if(g!=null)throw new IllegalArgumentException(g.output);if(!Files.isRegularFile(source))throw new IllegalArgumentException("资源文件不存在: "+item);Files.createDirectories(assets);Path target=assets.resolve(source.getFileName().toString());Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);count++;}return count;}
    private ToolResult importSkill(String raw,String requested)throws Exception{if(raw.trim().isEmpty())return ToolResult.error("import 缺少工作区内 path");Path source=workspace.cwd().resolve(raw).normalize().toAbsolutePath();ToolResult g=PathsGuard.errorIfOutside(workspace.workDir(),source);if(g!=null)return g;boolean directory=Files.isDirectory(source);Path sourceDir=directory?source:source.getParent();Path skillFile=directory?source.resolve("SKILL.md"):source;if(!Files.isRegularFile(skillFile))return ToolResult.error("未找到 SKILL.md: "+source);String name=safeName(requested);if(name.isEmpty())name=safeName(sourceDir.getFileName().toString());if(name.isEmpty())return ToolResult.error("无法确定技能名");Path target=root.resolve(name);if(directory)copyTree(sourceDir,target);else{Files.createDirectories(target);Files.copy(skillFile,target.resolve("SKILL.md"),StandardCopyOption.REPLACE_EXISTING);}return ToolResult.success("已导入技能"+(directory?"及配套资源":"")+": "+name+"（新建会话后生效）");}
    private static void copyTree(Path source,Path target)throws Exception{try(Stream<Path> st=Files.walk(source)){for(java.util.Iterator<Path>it=st.iterator();it.hasNext();){Path p=it.next();Path rel=source.relativize(p);Path dest=target.resolve(rel);if(Files.isDirectory(p))Files.createDirectories(dest);else if(Files.isRegularFile(p)){Files.createDirectories(dest.getParent());Files.copy(p,dest,StandardCopyOption.REPLACE_EXISTING);}}}}
    private static String safeName(String s){String n=s==null?"":s.trim();return n.matches("[A-Za-z0-9._-]{1,80}")?n:"";}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
}
