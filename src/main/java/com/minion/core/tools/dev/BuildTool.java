package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;

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
import java.util.stream.Stream;

/** 自动识别 Python/Maven/npm 项目并执行常用测试构建。 */
public final class BuildTool implements Tool {
    private final Workspace workspace; private final PythonRuntime python; private final ProcessTool processes;
    public BuildTool(Workspace workspace,PythonRuntime python){this(workspace,python,null);}
    public BuildTool(Workspace workspace,PythonRuntime python,ProcessTool processes){this.workspace=workspace;this.python=python;this.processes=processes;}
    @Override public String name(){return "Build";}
    @Override public String description(){return "开发构建：detect/test/build/artifacts/command；识别 Maven、Python/PyInstaller、npm，命令执行需确认";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("测试与构建",new String[]{"action","path","command","timeoutSeconds","port"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return "test".equals(a)||"build".equals(a)||"command".equals(a)||"serve".equals(a);}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        Path root=path(text(args,"path"));ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),root);if(guard!=null)return guard;if(!Files.isDirectory(root))return ToolResult.error("目录不存在: "+root);
        String a=text(args,"action").toLowerCase(Locale.ROOT),kind=detect(root);
        if("detect".equals(a))return ToolResult.success("项目类型: "+kind+"\n目录: "+root+"\n建议: "+suggest(kind));
        if("artifacts".equals(a))return artifacts(root);
        if("serve".equals(a))return serve(root,kind,integer(args,"port",8000));
        int timeout=Math.max(5,Math.min(integer(args,"timeoutSeconds",300),1800));
        if("command".equals(a)){String command=text(args,"command");if(command.trim().isEmpty())return ToolResult.error("command 缺少命令");return runShell(root,command,timeout);}
        if("test".equals(a))return run(root,testCommand(kind),timeout);
        if("build".equals(a))return run(root,buildCommand(kind),timeout);
        return ToolResult.error("未知 action: "+a+"（支持 detect/test/build/artifacts/serve/command）");
    }
    private ToolResult serve(Path root,String kind,int port)throws Exception{if(processes==null)return ToolResult.error("本会话未启用后台进程管理");port=Math.max(1024,Math.min(port,65535));String command;if("web".equals(kind))command=quote(python.require().toString())+" -m http.server "+port;else if("python".equals(kind)||"pyinstaller".equals(kind))command=quote(python.require().toString())+" "+(Files.exists(root.resolve("app.py"))?"app.py":"main.py");else if("npm".equals(kind))command="npm.cmd run dev";else return ToolResult.error("该项目无法自动启动服务，请用 Process start");JsonObject p=new JsonObject();p.addProperty("action","start");p.addProperty("command",command);p.addProperty("cwd",workspace.cwd().relativize(root).toString());return processes.execute(p);}
    private ToolResult run(Path root,List<String> command,int timeout)throws Exception{if(command==null)return ToolResult.error("无法为该项目自动生成命令，请使用 action=command");if(command.size()==1&&"PYTHON_COMPILE".equals(command.get(0)))command=Arrays.asList(python.require().toString(),"-m","compileall","-q",".");if(command.size()==1&&"PYINSTALLER".equals(command.get(0)))command=Arrays.asList(python.require().toString(),"-m","PyInstaller","--noconfirm","--onefile","main.py");return runDirect(root,command,timeout);}
    private ToolResult runShell(Path root,String command,int timeout)throws Exception{return runDirect(root,isWindows()?Arrays.asList("cmd.exe","/d","/s","/c",command):Arrays.asList("sh","-lc",command),timeout);}
    private ToolResult runDirect(Path root,List<String> command,int timeout)throws Exception{
        Process p=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();StringBuilder out=new StringBuilder();Thread reader=new Thread(()->{try(BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=br.readLine())!=null)if(out.length()<100000)out.append(line).append('\n');}catch(Exception ignored){}});reader.setDaemon(true);reader.start();boolean done=p.waitFor(timeout,TimeUnit.SECONDS);if(!done){p.destroy();return ToolResult.error("构建超时，已终止\n"+out);}reader.join(2000);String s=out.toString().trim();if(s.isEmpty())s="完成";return p.exitValue()==0?ToolResult.success(s):ToolResult.error("退出码 "+p.exitValue()+"\n"+s);
    }
    private ToolResult artifacts(Path root)throws Exception{List<String> hits=new ArrayList<String>();try(Stream<Path>s=Files.walk(root,5)){s.filter(Files::isRegularFile).filter(p->{String q=p.toString().toLowerCase(Locale.ROOT);return q.contains("\\target\\")||q.contains("\\dist\\")||q.contains("\\build\\")||q.endsWith(".exe")||q.endsWith(".jar");}).limit(300).forEach(p->hits.add(root.relativize(p).toString()+"  "+size(p)));}return ToolResult.success(hits.isEmpty()?"未发现构建产物":String.join("\n",hits));}
    private static long size(Path p){try{return Files.size(p);}catch(Exception e){return -1;}}
    private static String detect(Path r){if(Files.exists(r.resolve("pom.xml")))return "maven";if(Files.exists(r.resolve("package.json")))return "npm";if(Files.exists(r.resolve("build.cmd"))&&Files.exists(r.resolve("main.py")))return "pyinstaller";if(Files.exists(r.resolve("requirements.txt"))||Files.exists(r.resolve("main.py"))||Files.exists(r.resolve("app.py")))return "python";if(Files.exists(r.resolve("index.html")))return "web";return "unknown";}
    private static String suggest(String k){if("maven".equals(k))return "mvn test / mvn package";if("npm".equals(k))return "npm test / npm run build";if("pyinstaller".equals(k))return "python -m PyInstaller --onefile main.py";if("python".equals(k))return "python -m compileall .";if("web".equals(k))return "使用 Process 启动 python -m http.server";return "使用 action=command 指定命令";}
    private static List<String> testCommand(String k){if("maven".equals(k))return Arrays.asList("mvn.cmd","test");if("npm".equals(k))return Arrays.asList("npm.cmd","test");if("python".equals(k)||"pyinstaller".equals(k))return Arrays.asList("PYTHON_COMPILE");return null;}
    private static List<String> buildCommand(String k){if("maven".equals(k))return Arrays.asList("mvn.cmd","-DskipTests","package");if("npm".equals(k))return Arrays.asList("npm.cmd","run","build");if("pyinstaller".equals(k))return Arrays.asList("PYINSTALLER");if("python".equals(k))return Arrays.asList("PYTHON_COMPILE");return null;}
    private Path path(String raw){return raw.trim().isEmpty()?workspace.cwd():workspace.cwd().resolve(raw).normalize().toAbsolutePath();}
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
    private static String quote(String s){return s.indexOf(' ')>=0?'"'+s+'"':s;}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
}
