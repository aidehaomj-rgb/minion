package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.TextFiles;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 离线批量文本处理，避免中小模型为常见清洗任务反复临时编写脚本。 */
public final class TextProcessTool implements Tool {
    private final Workspace workspace;
    private final CheckpointStore checkpoints;

    public TextProcessTool(Workspace workspace, CheckpointStore checkpoints) {
        this.workspace = workspace;
        this.checkpoints = checkpoints;
    }

    @Override public String name() { return "TextProcess"; }
    @Override public String description() {
        return "离线文本处理：inspect/normalize/dedupe/regex_extract/replace/chunk/merge/compare；支持 txt/md/csv/json/xml/log/代码文件，写出时不默认覆盖原文件";
    }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("批量文本清洗、提取、分段与对比",
                new String[]{"action","path","otherPath","paths","outputPath","pattern","replacement","flags","chunkSize"},
                new String[]{"action"});
    }
    @Override public boolean isHighRisk(JsonObject args) {
        String out = text(args, "outputPath");
        return isWrite(text(args,"action")) && (out.isEmpty() || Files.exists(resolve(out)));
    }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action=text(args,"action").toLowerCase(Locale.ROOT);
        if ("merge".equals(action)) return merge(args);
        Path input=requireFile(text(args,"path"));
        if(input==null)return ToolResult.error(action+" 缺少工作区内有效 path");
        String source=TextFiles.decode(Files.readAllBytes(input)).text;
        if("inspect".equals(action))return ToolResult.success(inspect(input,source));
        if("compare".equals(action))return compare(input,source,args);
        Path out=requireOutput(args,action);if(out==null)return ToolResult.error(action+" 需要工作区内 outputPath，且不能覆盖输入文件");
        String result;
        if("normalize".equals(action))result=normalize(source);
        else if("dedupe".equals(action))result=dedupe(source);
        else if("replace".equals(action))result=replace(source,args);
        else if("regex_extract".equals(action))result=extract(source,args);
        else if("chunk".equals(action))result=chunk(source,integer(args,"chunkSize",4000));
        else return ToolResult.error("未知 action（支持 inspect/normalize/dedupe/regex_extract/replace/chunk/merge/compare）");
        checkpoint(out,"TextProcess."+action);Files.createDirectories(out.getParent());Files.write(out,result.getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("已处理并写出: "+out+"\n字符: "+source.length()+" → "+result.length());
    }

    private ToolResult merge(JsonObject args)throws Exception{
        List<String> raw=parsePaths(text(args,"paths"));if(raw.size()<2)return ToolResult.error("merge 的 paths 至少需要两个文件（分号或换行分隔）");
        Path out=requireOutput(args,"merge");if(out==null)return ToolResult.error("merge 需要工作区内 outputPath");StringBuilder merged=new StringBuilder();
        for(String item:raw){Path p=requireFile(item);if(p==null)return ToolResult.error("文件不存在或不在工作区: "+item);if(merged.length()>0)merged.append("\n\n");merged.append(TextFiles.decode(Files.readAllBytes(p)).text);}
        checkpoint(out,"TextProcess.merge");Files.createDirectories(out.getParent());Files.write(out,merged.toString().getBytes(StandardCharsets.UTF_8));return ToolResult.success("已合并 "+raw.size()+" 个文件: "+out);
    }
    private ToolResult compare(Path left,String a,JsonObject args)throws Exception{Path right=requireFile(text(args,"otherPath"));if(right==null)return ToolResult.error("compare 缺少有效 otherPath");String b=TextFiles.decode(Files.readAllBytes(right)).text;Set<String> x=new LinkedHashSet<String>(Arrays.asList(a.split("\\r?\\n",-1))),y=new LinkedHashSet<String>(Arrays.asList(b.split("\\r?\\n",-1)));Set<String> onlyA=new LinkedHashSet<String>(x);onlyA.removeAll(y);Set<String> onlyB=new LinkedHashSet<String>(y);onlyB.removeAll(x);return ToolResult.success("左文件: "+left+"\n右文件: "+right+"\n左侧独有行="+onlyA.size()+"，右侧独有行="+onlyB.size()+"，共同唯一行="+(x.size()-onlyA.size())+"\n\n[左侧独有示例]\n"+sample(onlyA)+"\n[右侧独有示例]\n"+sample(onlyB));}
    private static String inspect(Path p,String s){String[] lines=s.split("\\r?\\n",-1);int blank=0;Set<String> unique=new LinkedHashSet<String>();for(String line:lines){if(line.trim().isEmpty())blank++;unique.add(line);}return "文件: "+p+"\n字符="+s.length()+"，行="+lines.length+"，空行="+blank+"，重复行="+(lines.length-unique.size());}
    private static String normalize(String s){return s.replace("\r\n","\n").replace('\r','\n').replace('\u3000',' ').replaceAll("[ \\t]+(?=\\n)","").replaceAll("\\n{3,}","\n\n").trim()+"\n";}
    private static String dedupe(String s){Set<String> seen=new LinkedHashSet<String>();for(String line:s.split("\\r?\\n",-1))if(!line.trim().isEmpty())seen.add(line);return join(seen,"\n")+"\n";}
    private static String replace(String s,JsonObject a){String pattern=text(a,"pattern");if(pattern.isEmpty())throw new IllegalArgumentException("replace 缺少 pattern");return compile(pattern,text(a,"flags")).matcher(s).replaceAll(text(a,"replacement"));}
    private static String extract(String s,JsonObject a){String pattern=text(a,"pattern");if(pattern.isEmpty())throw new IllegalArgumentException("regex_extract 缺少 pattern");Matcher m=compile(pattern,text(a,"flags")).matcher(s);StringBuilder out=new StringBuilder();int n=0;while(m.find()&&n<100000){out.append(m.groupCount()>0?m.group(1):m.group()).append('\n');n++;}return out.toString();}
    private static String chunk(String s,int size){size=Math.max(200,Math.min(size,100000));StringBuilder out=new StringBuilder();int part=1;for(int start=0;start<s.length();){int end=Math.min(s.length(),start+size);if(end<s.length()){int nl=s.lastIndexOf('\n',end);if(nl>start+size/2)end=nl+1;}out.append("--- 第 ").append(part++).append(" 段 ---\n").append(s,start,end);if(end==start)break;start=end;if(start<s.length()&&out.charAt(out.length()-1)!='\n')out.append('\n');}return out.toString();}
    private static Pattern compile(String p,String flags){int f=0;if(flags.toLowerCase(Locale.ROOT).contains("i"))f|=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;if(flags.toLowerCase(Locale.ROOT).contains("m"))f|=Pattern.MULTILINE;if(flags.toLowerCase(Locale.ROOT).contains("s"))f|=Pattern.DOTALL;return Pattern.compile(p,f);}
    private Path requireFile(String raw){if(raw==null||raw.trim().isEmpty())return null;Path p=resolve(raw);if(!PathsGuard.inside(workspace.workDir(),p)||!Files.isRegularFile(p))return null;return p;}
    private Path requireOutput(JsonObject args,String action){String raw=text(args,"outputPath");if(raw.isEmpty())return null;Path p=resolve(raw);Path in=resolve(text(args,"path"));if(!PathsGuard.inside(workspace.workDir(),p)||p.equals(in))return null;return p;}
    private void checkpoint(Path out,String reason)throws Exception{if(checkpoints!=null)checkpoints.create(out,reason);}
    private Path resolve(String raw){return workspace.cwd().resolve(raw).normalize().toAbsolutePath();}
    private static boolean isWrite(String a){String s=a.toLowerCase(Locale.ROOT);return "normalize".equals(s)||"dedupe".equals(s)||"regex_extract".equals(s)||"replace".equals(s)||"chunk".equals(s)||"merge".equals(s);}
    private static List<String> parsePaths(String s){List<String> out=new ArrayList<String>();for(String x:s.split("[;\\r\\n]+"))if(!x.trim().isEmpty())out.add(x.trim());return out;}
    private static String sample(Set<String>s){StringBuilder b=new StringBuilder();int n=0;for(String x:s){if(n++>=20){b.append("...\n");break;}b.append(x).append('\n');}return b.toString();}
    private static String join(Iterable<String> xs,String sep){StringBuilder b=new StringBuilder();for(String x:xs){if(b.length()>0)b.append(sep);b.append(x);}return b.toString();}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
}
