package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.TextFiles;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 文件工作台只读能力：树、搜索、分段读取、元数据和哈希；写入继续走有检查点的 Write/Edit。 */
public final class FilesTool implements Tool {
    private final Workspace workspace;
    public FilesTool(Workspace workspace){this.workspace=workspace;}
    @Override public String name(){return "Files";}
    @Override public String description(){return "文件工作台：tree/search/read_chunk/summary/metadata/hash；大文件分段读取并缓存摘要，二进制拒绝全文读取";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("文件浏览与分段读取",
            new String[]{"action","path","query","depth","limit","offset","length","content"},new String[]{"action"});}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        String a=text(args,"action").toLowerCase(Locale.ROOT);Path p=resolve(text(args,"path"));ToolResult g=PathsGuard.errorIfOutside(workspace.workDir(),p);if(g!=null)return g;
        if("tree".equals(a))return tree(p,integer(args,"depth",4),integer(args,"limit",500));
        if("search".equals(a))return search(p,text(args,"query"),bool(args,"content"),integer(args,"limit",100));
        if("read_chunk".equals(a))return readChunk(p,longVal(args,"offset",0),integer(args,"length",64*1024));
        if("summary".equals(a))return summary(p);
        if("metadata".equals(a))return metadata(p);
        if("hash".equals(a))return hash(p);
        return ToolResult.error("未知 action: "+a+"（支持 tree/search/read_chunk/summary/metadata/hash）");
    }
    private ToolResult tree(Path root,int depth,int limit)throws Exception{
        if(!Files.isDirectory(root))return ToolResult.error("目录不存在: "+root);depth=Math.max(1,Math.min(depth,12));limit=Math.max(1,Math.min(limit,3000));List<Path> list=new ArrayList<Path>();
        try(Stream<Path>s=Files.walk(root,depth)){s.filter(p->!p.equals(root)).sorted().limit(limit).forEach(list::add);}StringBuilder sb=new StringBuilder(root.toString());for(Path p:list){int d=root.relativize(p).getNameCount();sb.append('\n');for(int i=1;i<d;i++)sb.append("  ");sb.append(Files.isDirectory(p)?"[D] ":"[F] ").append(p.getFileName());}return ToolResult.success(sb.toString());
    }
    private ToolResult search(Path root,String query,boolean content,int limit)throws Exception{
        if(query.trim().isEmpty())return ToolResult.error("search 缺少 query");if(!Files.exists(root))return ToolResult.error("路径不存在: "+root);limit=Math.max(1,Math.min(limit,1000));String q=query.toLowerCase(Locale.ROOT);List<String> hits=new ArrayList<String>();
        try(Stream<Path>s=Files.isDirectory(root)?Files.walk(root):Stream.of(root)){java.util.Iterator<Path>it=s.filter(Files::isRegularFile).iterator();while(it.hasNext()&&hits.size()<limit){Path f=it.next();String rel=java.nio.file.Paths.get(workspace.workDir()).toAbsolutePath().normalize().relativize(f.toAbsolutePath().normalize()).toString();if(rel.toLowerCase(Locale.ROOT).contains(q)){hits.add(rel);continue;}if(content&&Files.size(f)<=1024*1024){try{String text=TextFiles.decode(Files.readAllBytes(f)).text;int at=text.toLowerCase(Locale.ROOT).indexOf(q);if(at>=0)hits.add(rel+":"+lineOf(text,at));}catch(Exception ignored){}}}}
        return ToolResult.success(hits.isEmpty()?"未找到":String.join("\n",hits));
    }
    private ToolResult readChunk(Path p,long offset,int length)throws Exception{
        if(!Files.isRegularFile(p))return ToolResult.error("文件不存在: "+p);long size=Files.size(p);offset=Math.max(0,Math.min(offset,size));length=Math.max(1,Math.min(length,256*1024));byte[] all=Files.readAllBytes(p);int from=(int)Math.min(offset,all.length),to=Math.min(all.length,from+length);byte[] chunk=java.util.Arrays.copyOfRange(all,from,to);
        if(binary(chunk))return ToolResult.error("检测到二进制文件，拒绝按文本读取: "+p);
        String text=TextFiles.decode(chunk).text;return ToolResult.success("文件: "+p+"\n范围: "+from+"-"+to+" / "+size+"\n"+text);
    }
    private ToolResult summary(Path p)throws Exception{
        if(!Files.isRegularFile(p))return ToolResult.error("文件不存在: "+p);long size=Files.size(p),mtime=Files.getLastModifiedTime(p).toMillis();String key=p.toAbsolutePath()+"|"+size+"|"+mtime;String cacheName=digest(key.getBytes(StandardCharsets.UTF_8));Path cache=java.nio.file.Paths.get(workspace.workDir()).resolve(".minion/cache/file-summaries").resolve(cacheName+".txt");
        if(Files.isRegularFile(cache))return ToolResult.success(new String(Files.readAllBytes(cache),StandardCharsets.UTF_8)+"\n[缓存命中]");
        int take=(int)Math.min(32768,size);byte[]head=new byte[take],tail=new byte[(int)Math.min(32768,Math.max(0,size-take))];java.io.RandomAccessFile raf=new java.io.RandomAccessFile(p.toFile(),"r");try{raf.readFully(head);if(tail.length>0){raf.seek(size-tail.length);raf.readFully(tail);}}finally{raf.close();}if(binary(head))return ToolResult.error("检测到二进制文件，仅提供 metadata/hash");String first=TextFiles.decode(head).text,last=tail.length==0?"":TextFiles.decode(tail).text;String body="文件: "+p+"\n大小: "+size+" bytes\n修改时间: "+Files.getLastModifiedTime(p)+"\n--- 开头 ---\n"+first+(last.isEmpty()?"":"\n--- 结尾 ---\n"+last);Files.createDirectories(cache.getParent());Files.write(cache,body.getBytes(StandardCharsets.UTF_8));return ToolResult.success(body+"\n[摘要已缓存]");
    }
    private ToolResult metadata(Path p)throws Exception{return Files.exists(p)?ToolResult.success("路径: "+p+"\n类型: "+(Files.isDirectory(p)?"directory":"file")+"\n大小: "+(Files.isDirectory(p)?0:Files.size(p))+"\n修改时间: "+Files.getLastModifiedTime(p)+"\n可读: "+Files.isReadable(p)+"\n可写: "+Files.isWritable(p)+"\n符号链接: "+Files.isSymbolicLink(p)):ToolResult.error("路径不存在: "+p);}
    private ToolResult hash(Path p)throws Exception{if(!Files.isRegularFile(p))return ToolResult.error("文件不存在: "+p);MessageDigest md=MessageDigest.getInstance("SHA-256");java.io.InputStream in=Files.newInputStream(p);try{byte[]b=new byte[8192];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}finally{in.close();}StringBuilder sb=new StringBuilder();for(byte b:md.digest())sb.append(String.format(Locale.ROOT,"%02x",b&0xff));return ToolResult.success("SHA-256 "+sb+"  "+p);}
    private static String digest(byte[] data)throws Exception{byte[]bytes=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&0xff));return out.toString();}
    private Path resolve(String raw){return raw==null||raw.trim().isEmpty()?workspace.cwd():PathsGuard.resolve(workspace.cwd().toString(),raw).toAbsolutePath().normalize();}
    private static boolean binary(byte[]b){int bad=0;for(byte x:b){int v=x&0xff;if(v==0)return true;if(v<9||(v>13&&v<32))bad++;}return b.length>0&&bad>b.length/20;}
    private static int lineOf(String s,int at){int line=1;for(int i=0;i<at;i++)if(s.charAt(i)=='\n')line++;return line;}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
    private static long longVal(JsonObject o,String k,long d){try{return o.has(k)?o.get(k).getAsLong():d;}catch(Exception e){return d;}}
    private static boolean bool(JsonObject o,String k){return o.has(k)&&o.get(k).getAsBoolean();}
}
