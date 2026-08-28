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
    @Override public String description(){return "Git 工作台：status/diff/log/branches/review；stage/unstage/commit/branch/checkout/pull/merge/push 需确认";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("Git 版本控制",
            new String[]{"action","paths","message","name","remote","branch","cached","limit"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return !("status".equals(a)||"diff".equals(a)||"log".equals(a)||"branches".equals(a)||"review".equals(a));}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        if(!Files.isDirectory(workspace.cwd().resolve(".git"))&&!isInsideGit())return ToolResult.error("当前工作区不是 Git 仓库");
        String a=text(args,"action").toLowerCase(Locale.ROOT);List<String> cmd=new ArrayList<String>();cmd.add("git");
        if("status".equals(a)){cmd.addAll(Arrays.asList("status","--short","--branch"));}
        else if("diff".equals(a)){cmd.add("diff");if(bool(args,"cached"))cmd.add("--cached");cmd.add("--");cmd.addAll(paths(args));}
        else if("log".equals(a)){cmd.addAll(Arrays.asList("log","--oneline","--decorate","--graph","-n",String.valueOf(Math.max(1,Math.min(integer(args,"limit",30),200)))));}
        else if("branches".equals(a)){cmd.addAll(Arrays.asList("branch","-a","-vv"));}
        else if("review".equals(a)){return review(args);}
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
        return check.ok&&diff.ok?ToolResult.success(body):ToolResult.error(body);
    }
    private ToolResult run(List<String> cmd,int timeout)throws Exception{
        ProcessBuilder pb=new ProcessBuilder(cmd);pb.directory(workspace.cwd().toFile());pb.redirectErrorStream(true);pb.environment().put("LC_ALL","C.UTF-8");
        Process p=pb.start();StringBuilder out=new StringBuilder();Thread t=new Thread(()->{try{BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8));String line;while((line=br.readLine())!=null){synchronized(out){if(out.length()<MAX_OUTPUT)out.append(line).append('\n');}}br.close();}catch(Exception ignored){}});t.setDaemon(true);t.start();
        boolean done=p.waitFor(timeout,TimeUnit.SECONDS);if(!done){p.destroy();return ToolResult.error("Git 操作超时，已终止");}t.join(2000);String s=out.toString().trim();if(s.isEmpty())s="完成";return p.exitValue()==0?ToolResult.success(s):ToolResult.error("Git 退出码 "+p.exitValue()+"\n"+s);
    }
    private boolean isInsideGit(){try{return run(Arrays.asList("git","rev-parse","--is-inside-work-tree"),10).ok;}catch(Exception e){return false;}}
    private static void appendRemoteBranch(List<String> cmd,JsonObject args){String r=text(args,"remote"),b=text(args,"branch");if(!r.isEmpty())cmd.add(r);if(!b.isEmpty())cmd.add(b);}
    private static List<String> paths(JsonObject args){List<String> out=new ArrayList<String>();String raw=text(args,"paths");if(raw.trim().isEmpty())return out;try{JsonArray a=new Gson().fromJson(raw,JsonArray.class);for(JsonElement e:a)out.add(e.getAsString());}catch(Exception e){for(String s:raw.split("[,\\n]"))if(!s.trim().isEmpty())out.add(s.trim());}return out;}
    private static boolean validRef(String s){return s!=null&&s.matches("[A-Za-z0-9._/-]+")&&!s.contains("..")&&!s.startsWith("/")&&!s.endsWith("/");}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
    private static boolean bool(JsonObject o,String k){return o.has(k)&&o.get(k).getAsBoolean();}
}
