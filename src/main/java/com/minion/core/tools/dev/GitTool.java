package com.minion.core.tools.dev;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 不经过 shell 的 Git 工作台工具，避免命令拼接；写操作统一走高危确认。 */
public final class GitTool implements Tool {
    private static final int MAX_OUTPUT=80_000;
    private final Workspace workspace;
    public GitTool(Workspace workspace){this.workspace=workspace;}
    @Override public String name(){return "Git";}
    @Override public String description(){return "Git 工作台：status/diff/log/branches/review/review_snapshot/worktree_list；review_snapshot返回staged+unstaged+untracked只读内容快照(JSON)，truncated时必须收窄paths，不代表语义审查通过；写操作需确认，worktree只用于代码仓库";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("Git 版本控制",
            new String[]{"action","paths","message","name","remote","branch","cached","limit","scope","taskId","stepId"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return !("status".equals(a)||"diff".equals(a)||"log".equals(a)||"branches".equals(a)||"review".equals(a)||"review_snapshot".equals(a)||"worktree_list".equals(a));}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        if(!Files.isDirectory(workspace.cwd().resolve(".git"))&&!isInsideGit())return ToolResult.error("当前工作区不是 Git 仓库");
        String a=text(args,"action").toLowerCase(Locale.ROOT);List<String> cmd=new ArrayList<String>();cmd.add("git");
        if("status".equals(a)){cmd.addAll(Arrays.asList("status","--short","--branch"));}
        else if("diff".equals(a)){cmd.add("diff");if(bool(args,"cached"))cmd.add("--cached");cmd.add("--");cmd.addAll(paths(args));}
        else if("log".equals(a)){cmd.addAll(Arrays.asList("log","--oneline","--decorate","--graph","-n",String.valueOf(Math.max(1,Math.min(integer(args,"limit",30),200)))));}
        else if("branches".equals(a)){cmd.addAll(Arrays.asList("branch","-a","-vv"));}
        else if("worktree_list".equals(a)){cmd.addAll(Arrays.asList("worktree","list","--porcelain"));}
        else if("worktree_create".equals(a)){
            String ref=text(args,"branch");if(!ref.isEmpty()&&!"HEAD".equals(ref))return ToolResult.error("隔离代码任务必须基于当前HEAD，不允许以其他ref洗掉当前基线");
            JsonObject scopes=new JsonObject();if(args.has("scope"))scopes.add("paths",args.get("scope"));
            try{return ToolResult.success(new Gson().toJson(new com.minion.core.agent.ChildTaskIsolation(java.nio.file.Paths.get(workspace.workDir())).create(text(args,"taskId"),text(args,"stepId"),text(args,"name"),paths(scopes))));}
            catch(InterruptedException e){Thread.currentThread().interrupt();return ToolResult.error("隔离创建中断，保留现场待核对");}catch(Exception e){return ToolResult.error("隔离创建失败: "+e.getMessage());}
        }
        else if("worktree_remove".equals(a)){
            try{new com.minion.core.agent.ChildTaskIsolation(java.nio.file.Paths.get(workspace.workDir())).remove(text(args,"name"));return ToolResult.success("已移除登记且干净的worktree: "+text(args,"name"));}
            catch(InterruptedException e){Thread.currentThread().interrupt();return ToolResult.error("隔离移除中断，需核对现场");}catch(Exception e){return ToolResult.error("隔离未清理/移除失败: "+e.getMessage());}
        }
        else if("review".equals(a)){return review(args);}
        else if("review_snapshot".equals(a)){return reviewSnapshot(args);}
        else if("stage".equals(a)){cmd.add("add");List<String> p=paths(args);if(p.isEmpty())cmd.add("-A");else{cmd.add("--");cmd.addAll(p);}}
        else if("unstage".equals(a)){cmd.addAll(Arrays.asList("reset","HEAD","--"));cmd.addAll(paths(args));}
        else if("commit".equals(a)){String m=text(args,"message");if(m.trim().isEmpty())return ToolResult.error("commit 缺少 message");cmd.addAll(Arrays.asList("commit","-m",m));}
        else if("branch".equals(a)){String n=text(args,"name");if(!validRef(n))return ToolResult.error("分支名非法");cmd.addAll(Arrays.asList("branch",n));}
        else if("checkout".equals(a)){String n=text(args,"name");if(!validRef(n))return ToolResult.error("分支名非法");cmd.addAll(Arrays.asList("checkout",n));}
        else if("pull".equals(a)){cmd.add("pull");appendRemoteBranch(cmd,args);}
        else if("merge".equals(a)){String n=text(args,"branch");if(!validRef(n))return ToolResult.error("merge 缺少合法 branch");cmd.addAll(Arrays.asList("merge","--no-edit",n));}
        else if("push".equals(a)){cmd.add("push");appendRemoteBranch(cmd,args);}
        else return ToolResult.error("未知 action: "+a);
        return run(cmd,120);
    }
    private ToolResult review(JsonObject args)throws Exception{
        ToolResult check=run(Arrays.asList("git","diff","--check"),30);ToolResult stat=run(Arrays.asList("git","diff","--stat"),30);ToolResult diff=run(Arrays.asList("git","diff","--"),60);
        String body="Diff 检查:\n"+check.output+"\n\n统计:\n"+stat.output+"\n\n变更:\n"+diff.output;
        return check.ok&&stat.ok&&diff.ok?ToolResult.success(body):ToolResult.error(body);
    }
    /** C05：只取真实内容，不把 diff --check 当语义审查。hash 精确绑定返回的 diffText。 */
    public ToolResult reviewSnapshot(JsonObject args) {
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            GitCommand.Result top=snapshotGit(workspace.cwd(),Arrays.asList("rev-parse","--show-toplevel"),deadline,8192);
            Path root=java.nio.file.Paths.get(top.text().trim()).toRealPath();
            if(!com.minion.core.tools.PathsGuard.inside(workspace.workDir(),root))return ToolResult.error("仓库根目录不在授权工作区内，请使用仓库根工作区");
            List<String> filters=new ArrayList<String>();
            for(String raw:paths(args)){
                String s=raw.replace('\\','/');Path p=java.nio.file.Paths.get(s);
                if(s.isEmpty()||p.isAbsolute()||s.contains(":")||s.contains("\u0000")||s.matches("(^|.*/)\\.\\.(/.*|$)"))return ToolResult.error("paths 只支持仓库相对字面路径，禁止越界/路径表达式");
                s=p.normalize().toString().replace('\\','/');filters.add(s.isEmpty()?".":s);
            }
            JsonObject first=collectSnapshot(root,filters,deadline);
            if(!first.get("truncated").getAsBoolean()) {
                JsonObject again=collectSnapshot(root,filters,deadline);
                if(!first.toString().equals(again.toString()))return ToolResult.error("Git内容在快照读取期间变化，请停止写入后重试（未提供稳定审查快照）");
            }
            return ToolResult.success(new Gson().toJson(first));
        }catch(InterruptedException e){Thread.currentThread().interrupt();return ToolResult.error("Git审查快照已中断");}
        catch(Exception e){return ToolResult.error("Git审查快照失败: "+e.getMessage());}
    }
    private static final int SNAPSHOT_BYTES=256*1024, SNAPSHOT_FILE_BYTES=128*1024, SNAPSHOT_FILES=512;
    private static JsonObject collectSnapshot(Path root,List<String> filters,long deadline)throws Exception {
        JsonObject result=new JsonObject();result.addProperty("type","minion.git-review.v1");
        JsonObject scope=new JsonObject();scope.addProperty("root",root.toString());JsonArray filterJson=new JsonArray();for(String f:filters)filterJson.add(f);scope.add("paths",filterJson);
        JsonArray included=new JsonArray();included.add("staged");included.add("unstaged");included.add("untracked");scope.add("includes",included);
        result.add("scope",scope);java.util.Map<String,java.util.Set<String>> names=new java.util.TreeMap<String,java.util.Set<String>>();
        boolean truncated=false;
        String[][] listings={{"staged","diff","--cached","--name-only","--no-renames","-z"},{"unstaged","diff","--name-only","--no-renames","-z"},{"untracked","ls-files","--others","--exclude-standard","-z"}};
        for(String[] listing:listings){
            List<String> command=new ArrayList<String>(Arrays.asList(listing).subList(1,listing.length));literalFilters(command,filters);
            GitCommand.Result listed=snapshotGit(root,command,deadline,1024*1024);
            if(listed.truncated){truncated=true;continue;} // NUL 列表截断不能解析半条路径。
            for(String name:listed.text().split("\u0000")){
                if(name.isEmpty()||excludedSnapshotPath(name))continue;
                Path p=root.resolve(name).normalize();if(!p.startsWith(root)||!com.minion.core.tools.PathsGuard.inside(root.toString(),p))throw new java.io.IOException("快照路径越界/符号链接: "+name);
                if(!names.containsKey(name)){if(names.size()>=SNAPSHOT_FILES){truncated=true;continue;}names.put(name,new java.util.TreeSet<String>());}
                names.get(name).add(listing[0]);
            }
        }
        StringBuilder diff=new StringBuilder();JsonArray files=new JsonArray();
        java.util.Set<String> binary=new java.util.HashSet<String>();
        List<String> selected=new ArrayList<String>(names.keySet());
        if(!selected.isEmpty())for(boolean cached:new boolean[]{true,false}){
            List<String> stats=new ArrayList<String>(Arrays.asList("diff","--no-ext-diff","--no-textconv","--numstat","--no-renames","-z"));if(cached)stats.add("--cached");literalFilters(stats,selected);
            GitCommand.Result stat=snapshotGit(root,stats,deadline,1024*1024);if(stat.truncated)truncated=true;
            else for(String line:stat.text().split("\u0000"))if(line.startsWith("-\t-\t"))binary.add(line.substring(4));
            List<String> command=new ArrayList<String>(Arrays.asList("diff","--no-ext-diff","--no-textconv","--no-color","--no-renames","--ignore-submodules=all","--unified=3"));if(cached)command.add("--cached");literalFilters(command,selected);
            GitCommand.Result body=snapshotGit(root,command,deadline,SNAPSHOT_BYTES);
            truncated|=body.truncated;truncated|=appendSnapshot(diff,"\n### "+(cached?"staged":"unstaged")+"\n"+body.text());
        }
        for(java.util.Map.Entry<String,java.util.Set<String>> entry:names.entrySet()){
            JsonObject file=new JsonObject();String name=entry.getKey();file.addProperty("path",name);JsonArray status=new JsonArray();for(String s:entry.getValue())status.add(s);file.add("status",status);
            boolean isBinary=binary.contains(name);boolean omitted=false;
            if(entry.getValue().contains("untracked")){
                Path path=root.resolve(name);
                if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                    omitted=true;truncated=true;appendSnapshot(diff,"\n### untracked "+name+"\n[非普通文件/符号链接，不读取]\n");
                }else if(Files.size(path)>SNAPSHOT_FILE_BYTES){
                    omitted=true;truncated=true;appendSnapshot(diff,"\n### untracked "+name+"\n[文件过大，不读取；请收窄路径或分段审查]\n");
                }else{
                    byte[] bytes=readSnapshotFile(path,SNAPSHOT_FILE_BYTES);
                    String content=null;try{
                        for(byte b:bytes)if(b==0){isBinary=true;break;}
                        if(!isBinary)content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    }catch(java.nio.charset.CharacterCodingException ex){isBinary=true;}
                    truncated|=appendSnapshot(diff,"\n### untracked "+name+"\n"+(isBinary?"[binary/非UTF-8：不读取文本内容]\n":content+"\n"));
                }
            }
            file.addProperty("binary",isBinary);file.addProperty("omitted",omitted);files.add(file);
        }
        String text=diff.toString();result.add("files",files);result.addProperty("diffText",text);result.addProperty("diffHash",snapshotHash(text));result.addProperty("truncated",truncated);
        result.addProperty("guidance",truncated?"快照不完整；必须收窄paths，不得据此宣称完整语义审查通过":"仅为内容快照；另需独立只读语义审查，报告文件/行号/风险/证据；binary未审查");return result;
    }
    private static byte[] readSnapshotFile(Path path,int limit)throws Exception{
        try(java.io.InputStream in=Files.newInputStream(path);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new java.io.IOException("文件在读取时增大，请收窄后重试: "+path);out.write(b,0,n);}return out.toByteArray();
        }
    }
    private static GitCommand.Result snapshotGit(Path root,List<String> args,long deadline,int limit)throws Exception{
        long millis=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());GitCommand.Result r=GitCommand.run(root,args,Math.max(1,Math.min(millis,10000)),limit);
        if(millis<=0||r.exitCode!=0)throw new java.io.IOException("Git快照读取失败/超时: "+r.text()+r.stderr);return r;
    }
    private static void literalFilters(List<String> command,List<String> filters){command.add("--");for(String f:filters)command.add(":(top,literal)"+f);}
    private static boolean excludedSnapshotPath(String name){for(String part:name.replace('\\','/').split("/"))if(Arrays.asList(".git",".minion","node_modules","target","dist","build","out",".gradle",".idea",".next","coverage").contains(part.toLowerCase(Locale.ROOT)))return true;return false;}
    private static boolean appendSnapshot(StringBuilder out,String value){
        int available=SNAPSHOT_BYTES-out.toString().getBytes(StandardCharsets.UTF_8).length;
        if(value.getBytes(StandardCharsets.UTF_8).length<=available){out.append(value);return false;}
        int low=0,high=Math.min(value.length(),Math.max(0,available));while(low<high){int mid=low+(high-low+1)/2;if(value.substring(0,mid).getBytes(StandardCharsets.UTF_8).length<=available)low=mid;else high=mid-1;}int end=low;
        if(end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;out.append(value,0,end);return true;
    }
    private static String snapshotHash(String text)throws Exception{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b&255));return out.toString();}
    private Path worktreeTarget(JsonObject args)throws Exception{
        String name=text(args,"name");if(!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))throw new IllegalArgumentException("worktree名称仅支持1-64位英数字/短横线/下划线");
        Path root=workspace.cwd().resolve(".minion").resolve("git-worktrees").toAbsolutePath().normalize();
        Path target=root.resolve(name).normalize();if(!com.minion.core.tools.PathsGuard.inside(workspace.workDir(),target)||!target.startsWith(root))throw new IllegalArgumentException("worktree路径越界");return target;
    }
    private ToolResult run(List<String> cmd,int timeout)throws Exception{
        try {
            GitCommand.Result result=GitCommand.run(workspace.cwd(),cmd.subList(1,cmd.size()),timeout*1000L,MAX_OUTPUT);
            String s=(result.text()+result.stderr).trim();if(s.isEmpty())s="完成";
            if(result.truncated)s+="\n[Git输出已截断，请收窄范围]";
            return result.exitCode==0?ToolResult.success(s):ToolResult.error("Git 退出码 "+result.exitCode+"\n"+s);
        } catch(InterruptedException e){Thread.currentThread().interrupt();return ToolResult.error("Git 操作已中断，需核对副作用");}
        catch(java.io.IOException e){return ToolResult.error(e.getMessage());}
    }
    private boolean isInsideGit(){try{return run(Arrays.asList("git","rev-parse","--is-inside-work-tree"),10).ok;}catch(Exception e){return false;}}
    private static void appendRemoteBranch(List<String> cmd,JsonObject args){String r=text(args,"remote"),b=text(args,"branch");if(!r.isEmpty())cmd.add(r);if(!b.isEmpty())cmd.add(b);}
    private static List<String> paths(JsonObject args){List<String> out=new ArrayList<String>();if(args.has("paths")&&args.get("paths").isJsonArray()){for(JsonElement e:args.getAsJsonArray("paths"))out.add(e.getAsString());return out;}String raw=text(args,"paths");if(raw.trim().isEmpty())return out;try{JsonArray a=new Gson().fromJson(raw,JsonArray.class);for(JsonElement e:a)out.add(e.getAsString());}catch(Exception e){for(String s:raw.split("[,\\n]"))if(!s.trim().isEmpty())out.add(s.trim());}return out;}
    private static boolean validRef(String s){return s!=null&&s.matches("[A-Za-z0-9._/-]+")&&!s.contains("..")&&!s.startsWith("/")&&!s.endsWith("/");}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
    private static boolean bool(JsonObject o,String k){return o.has(k)&&o.get(k).getAsBoolean();}
}
