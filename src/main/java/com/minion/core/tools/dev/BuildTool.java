package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.python.PythonRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** 自动识别 Python/Maven/npm/Gradle 项目并执行常用测试构建。 */
public final class BuildTool implements Tool {
    private final Workspace workspace; private final PythonRuntime python; private final ProcessTool processes;
    public BuildTool(Workspace workspace,PythonRuntime python){this(workspace,python,null);}
    public BuildTool(Workspace workspace,PythonRuntime python,ProcessTool processes){this.workspace=workspace;this.python=python;this.processes=processes;}
    @Override public String name(){return "Build";}
    @Override public String description(){return "开发验证：detect/test/build/syntax_check/verify/artifacts/command/serve；执行需确认，JSON minion.validation.v1 含真实测试计数、完整日志路径和源码摘要。test 不用 compileall 冒充；verify 需 reportPath；构建/语法/未知测试数不证明功能正确。不安装依赖；优先 Maven/Gradle wrapper，npm 实际 scripts。Android 需要匹配的 JDK/SDK/离线缓存";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("测试与构建；verify 使用本项目 reportPath，syntax_check 仅语法",new String[]{"action","path","command","timeoutSeconds","port","reportPath"},new String[]{"action"});}
    @Override public boolean isHighRisk(JsonObject args){String a=text(args,"action").toLowerCase(Locale.ROOT);return "test".equals(a)||"build".equals(a)||"syntax_check".equals(a)||"command".equals(a)||"serve".equals(a);}
    @Override public ToolResult execute(JsonObject args)throws Exception{
        Path root=path(text(args,"path"));ToolResult guard=PathsGuard.errorIfOutside(workspace.workDir(),root);if(guard!=null)return guard;if(!Files.isDirectory(root))return ToolResult.error("目录不存在: "+root);
        String a=text(args,"action").toLowerCase(Locale.ROOT);
        if("detect".equals(a)){String kind=ValidationDiscovery.detect(root);return ToolResult.success("项目类型: "+kind+"\n目录: "+root+"\n建议: "+suggest(kind));}
        if("artifacts".equals(a))return artifacts(root);
        if("serve".equals(a))return serve(root,ValidationDiscovery.detect(root),integer(args,"port",8000));
        if("verify".equals(a)){
            String raw=text(args,"reportPath");if(raw.trim().isEmpty())return ToolResult.error("verify 缺少 reportPath");
            Path reportPath=root.resolve(raw).normalize().toAbsolutePath();
            ToolResult reportGuard=PathsGuard.errorIfOutside(workspace.workDir(),reportPath);if(reportGuard!=null)return reportGuard;
            boolean current=ValidationRunner.verifyReport(root,reportPath);
            JsonObject verified=new JsonObject();verified.addProperty("type","minion.validation.verify.v1");verified.addProperty("reportPath",reportPath.toString());verified.addProperty("current",current);
            verified.addProperty("meaning","仅核对源码/日志版本，不能将失败、未运行、未知计数或其他任务报告升级为通过");
            return current?ToolResult.success(verified.toString()):ToolResult.error(verified.toString());
        }
        int timeout=Math.max(1,Math.min(integer(args,"timeoutSeconds",300),1800));
        if("command".equals(a)||"test".equals(a)||"build".equals(a)||"syntax_check".equals(a)){
            JsonObject report=new ValidationRunner(python).run(root,a,text(args,"command"),timeout);
            String status=report.get("status").getAsString();
            boolean ok="passed".equals(status)||("command".equals(a)&&"command_succeeded".equals(status));
            return ok?ToolResult.success(ValidationRunner.serialize(report)):ToolResult.error(ValidationRunner.serialize(report));
        }
        return ToolResult.error("未知 action: "+a+"（支持 detect/test/build/syntax_check/verify/artifacts/serve/command）");
    }
    private ToolResult serve(Path root,String kind,int port)throws Exception{if(processes==null)return ToolResult.error("本会话未启用后台进程管理");port=Math.max(1024,Math.min(port,65535));String command;if("web".equals(kind))command=quote(python.require().toString())+" -m http.server "+port;else if("python".equals(kind)||"pyinstaller".equals(kind))command=quote(python.require().toString())+" "+(Files.exists(root.resolve("app.py"))?"app.py":"main.py");else if("npm".equals(kind))command="npm.cmd run dev";else return ToolResult.error("该项目无法自动启动服务，请用 Process start");JsonObject p=new JsonObject();p.addProperty("action","start");p.addProperty("command",command);p.addProperty("cwd",workspace.cwd().relativize(root).toString());return processes.execute(p);}
    private ToolResult artifacts(Path root)throws Exception{List<String> hits=new ArrayList<String>();try(Stream<Path>s=Files.walk(root,5)){s.filter(Files::isRegularFile).filter(p->{String q=p.toString().toLowerCase(Locale.ROOT);return q.contains("\\target\\")||q.contains("\\dist\\")||q.contains("\\build\\")||q.endsWith(".exe")||q.endsWith(".jar");}).limit(300).forEach(p->hits.add(root.relativize(p).toString()+"  "+size(p)));}return ToolResult.success(hits.isEmpty()?"未发现构建产物":String.join("\n",hits));}
    private static long size(Path p){try{return Files.size(p);}catch(Exception e){return -1;}}
    private static String suggest(String k){if("maven".equals(k))return "优先项目 mvnw wrapper，执行离线 test/package；需要已有依赖缓存";if("android".equals(k))return "gradlew.bat :app:testDebugUnitTest / :app:assembleDebug；先确认 JDK/Android SDK/离线 Gradle 缓存匹配，Win7 不保证新版工具链";if("gradle".equals(k))return "项目 wrapper 离线 test/build；不调用全局 Gradle";if("npm".equals(k))return "仅读取 package.json 实际 scripts.test/scripts.build；不自动安装";if("pyinstaller".equals(k))return "test 执行真实测试；build 使用已有 PyInstaller；syntax_check 仅语法";if("python".equals(k))return "test 使用已安装 pytest 或 unittest；syntax_check 独立 compileall，不代表测试通过；.minion/validation.json 可显式配置";if("web".equals(k))return "静态网页无自动构建；Process 预览后仍需实际功能验证";return "配置 .minion/validation.json 或 action=command；退出 0 不证明功能正确";}
    private Path path(String raw){return raw.trim().isEmpty()?workspace.cwd():workspace.cwd().resolve(raw).normalize().toAbsolutePath();}
    private static String quote(String s){return s.indexOf(' ')>=0?'"'+s+'"':s;}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
}
