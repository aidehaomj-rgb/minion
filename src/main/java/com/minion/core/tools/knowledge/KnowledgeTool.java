package com.minion.core.tools.knowledge;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.TextFiles;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.data.DocumentTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** 项目级轻量知识库：Markdown 落盘 + 中英文关键词/中文二元组检索，无云端与向量模型依赖。 */
public class KnowledgeTool implements Tool {
    private static final int MAX_NOTE_CHARS = 200_000;
    private final Workspace workspace;
    public KnowledgeTool(Workspace workspace) { this.workspace=workspace; }
    @Override public String name(){return "Knowledge";}
    @Override public String description(){return "项目本地知识库（.minion/knowledge）：add、import/sync 批量导入文件夹，search 带来源引用检索，list/read/delete；数据不离开内网";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("本地知识库工具",
            new String[]{"action","title","content","source","query","name","limit","path","recursive"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){return "delete".equalsIgnoreCase(text(args,"action"));}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        String action=text(args,"action").toLowerCase(Locale.ROOT); Path root=root(); Files.createDirectories(root);
        if("add".equals(action))return add(root,args);
        if("import".equals(action)||"sync".equals(action))return importPath(root,args);
        if("list".equals(action))return list(root);
        if("search".equals(action))return search(root,text(args,"query"),integer(args,"limit",10));
        if("read".equals(action))return read(root,text(args,"name"));
        if("delete".equals(action))return delete(root,text(args,"name"));
        return ToolResult.error("未知 action: "+action+"（支持 add/import/sync/search/list/read/delete）");
    }
    private ToolResult add(Path root,JsonObject args)throws Exception{
        String title=text(args,"title").trim(),content=text(args,"content"),source=text(args,"source").trim();
        if(title.isEmpty()||content.trim().isEmpty())return ToolResult.error("add 需要 title 和 content");
        if(content.length()>MAX_NOTE_CHARS)return ToolResult.error("单条知识超过 "+MAX_NOTE_CHARS+" 字符，请分段保存");
        String base=safeName(title), stamp=new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path file=root.resolve(base+"-"+stamp+".md"); int n=1;
        while(Files.exists(file))file=root.resolve(base+"-"+stamp+"-"+(n++)+".md");
        String body="# "+title+"\n\n"+(source.isEmpty()?"":"来源: "+source+"\n\n")+content.trim()+"\n";
        Files.write(file,body.getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("已加入知识库: "+root.relativize(file));
    }
    private ToolResult list(Path root)throws Exception{
        List<Path> files=files(root); if(files.isEmpty())return ToolResult.success("知识库为空");
        StringBuilder out=new StringBuilder("知识条目（").append(files.size()).append("）：");
        for(Path p:files)out.append('\n').append("- ").append(p.getFileName()).append(" (").append(Files.size(p)).append(" bytes)");
        return ToolResult.success(out.toString());
    }
    private ToolResult read(Path root,String name)throws Exception{
        Path p=safeResolve(root,name); if(p==null||!Files.isRegularFile(p))return ToolResult.error("知识条目不存在: "+name);
        return ToolResult.success(TextFiles.decode(Files.readAllBytes(p)).text);
    }
    private ToolResult delete(Path root,String name)throws Exception{
        Path p=safeResolve(root,name); if(p==null||!Files.isRegularFile(p))return ToolResult.error("知识条目不存在: "+name);
        Files.delete(p); return ToolResult.success("已删除知识条目: "+name);
    }
    private ToolResult search(Path root,String query,int limit)throws Exception{
        if(query==null||query.trim().isEmpty())return ToolResult.error("search 需要 query");
        limit=Math.max(1,Math.min(limit,30)); Set<String> terms=terms(query); List<Hit> hits=new ArrayList<Hit>();
        for(Path p:files(root)){
            String content=TextFiles.decode(Files.readAllBytes(p)).text, lower=content.toLowerCase(Locale.ROOT); int score=0,first=-1;
            for(String term:terms){int at=lower.indexOf(term);if(at>=0){score+=term.length()>=2?3:1;if(first<0||at<first)first=at;}}
            if(lower.contains(query.toLowerCase(Locale.ROOT))){score+=20;first=lower.indexOf(query.toLowerCase(Locale.ROOT));}
            if(score>0)hits.add(new Hit(p,score,snippet(content,first),sourceOf(content)));
        }
        Collections.sort(hits,new Comparator<Hit>(){@Override public int compare(Hit a,Hit b){return Integer.compare(b.score,a.score);}});
        if(hits.isEmpty())return ToolResult.success("未找到相关知识");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<Math.min(limit,hits.size());i++){Hit h=hits.get(i);if(out.length()>0)out.append("\n\n");out.append("[").append(h.path.getFileName()).append("] score=").append(h.score);if(!h.source.isEmpty())out.append(" | 来源: ").append(h.source);out.append('\n').append(h.snippet);}
        return ToolResult.success(out.toString());
    }
    private ToolResult importPath(Path root,JsonObject args)throws Exception{
        String raw=text(args,"path");if(raw.trim().isEmpty())return ToolResult.error("import/sync 需要 path");
        Path input=PathsGuard.resolve(workspace.cwd().toString(),raw).toAbsolutePath().normalize();ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),input);if(guard!=null)return guard;
        if(!Files.exists(input))return ToolResult.error("导入路径不存在: "+input);
        int max=Math.max(1,Math.min(integer(args,"limit",100),500));boolean recursive=!args.has("recursive")||Boolean.parseBoolean(text(args,"recursive"));
        List<Path> candidates=new ArrayList<Path>();
        if(Files.isRegularFile(input))candidates.add(input);else{
            try(Stream<Path>s=recursive?Files.walk(input):Files.list(input)){s.filter(Files::isRegularFile).limit(max).forEach(candidates::add);}
        }
        int added=0,updated=0,skipped=0,failed=0;StringBuilder errors=new StringBuilder();
        for(Path file:candidates){
            try{
                String content=DocumentTool.extractText(file);if(content.trim().isEmpty()){skipped++;continue;}
                String key=stableKey(input,file);Path dest=root.resolve("import-"+key+".md");long modified=Files.getLastModifiedTime(file).toMillis();
                String marker="导入时间戳: "+modified;
                if(Files.isRegularFile(dest)){String old=TextFiles.decode(Files.readAllBytes(dest)).text;if(old.contains(marker)){skipped++;continue;}updated++;}else added++;
                String title=file.getFileName().toString();String body="# "+title+"\n\n来源: "+file.toAbsolutePath()+"\n"+marker+"\n\n"+content.trim()+"\n";
                if(body.length()>MAX_NOTE_CHARS)body=body.substring(0,MAX_NOTE_CHARS)+"\n[导入内容已截断]\n";
                Files.write(dest,body.getBytes(StandardCharsets.UTF_8));
            }catch(Exception e){failed++;if(errors.length()<1000)errors.append("\n- ").append(file.getFileName()).append(": ").append(e.getMessage());}
        }
        return ToolResult.success("知识库导入完成：新增="+added+" 更新="+updated+" 跳过="+skipped+" 失败="+failed+errors);
    }
    private Path root(){return java.nio.file.Paths.get(workspace.workDir()).toAbsolutePath().normalize().resolve(".minion").resolve("knowledge");}
    private static List<Path> files(Path root)throws Exception{List<Path> out=new ArrayList<Path>();try(DirectoryStream<Path>d=Files.newDirectoryStream(root,"*.md")){for(Path p:d)if(Files.isRegularFile(p))out.add(p);}Collections.sort(out);return out;}
    private static Path safeResolve(Path root,String name){if(name==null||name.trim().isEmpty())return null;Path p=root.resolve(name).normalize();return p.startsWith(root)&&p.getParent().equals(root)?p:null;}
    private static String safeName(String s){String n=s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();if(n.length()>60)n=n.substring(0,60);return n.isEmpty()?"knowledge":n;}
    private static Set<String> terms(String q){String s=q.toLowerCase(Locale.ROOT).trim();Set<String> out=new LinkedHashSet<String>();for(String x:s.split("[^\\p{L}\\p{N}_]+"))if(!x.isEmpty())out.add(x);for(int i=0;i+1<s.length();i++){char a=s.charAt(i),b=s.charAt(i+1);if(isCjk(a)&&isCjk(b))out.add(s.substring(i,i+2));}return out;}
    private static boolean isCjk(char c){return c>=0x3400&&c<=0x9fff;}
    private static String snippet(String s,int at){if(at<0)at=0;int from=Math.max(0,at-120),to=Math.min(s.length(),at+380);return(from>0?"…":"")+s.substring(from,to).replace('\r',' ').replace('\n',' ')+(to<s.length()?"…":"");}
    private static String sourceOf(String content){for(String line:content.split("\\r?\\n")){if(line.startsWith("来源: "))return line.substring(4).trim();}return "";}
    private static String stableKey(Path base,Path file)throws Exception{String s=(Files.isDirectory(base)?base.relativize(file):file.getFileName()).toString().toLowerCase(Locale.ROOT);java.security.MessageDigest md=java.security.MessageDigest.getInstance("SHA-1");byte[] b=md.digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(int i=0;i<8;i++)out.append(String.format(Locale.ROOT,"%02x",b[i]&0xff));return out.toString();}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?Integer.parseInt(o.get(k).getAsString()):d;}catch(Exception e){return d;}}
    private static final class Hit{final Path path;final int score;final String snippet;final String source;Hit(Path p,int s,String n,String source){path=p;score=s;snippet=n;this.source=source;}}
}
