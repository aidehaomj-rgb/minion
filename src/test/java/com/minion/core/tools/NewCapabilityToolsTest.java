package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.Session;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.config.Config;
import com.minion.core.security.SecretStore;
import com.minion.core.tools.context.ContextTool;
import com.minion.core.tools.context.MemoryTool;
import com.minion.core.tools.dev.BuildTool;
import com.minion.core.tools.dev.FilesTool;
import com.minion.core.tools.dev.ProjectTool;
import com.minion.core.tools.python.PythonRuntime;
import com.minion.core.tools.security.PermissionTool;
import com.minion.core.tools.skills.SkillAdminTool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class NewCapabilityToolsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void secretStoreRoundTripNeverNeedsPlaintextFile()throws Exception{
        Path root=temp.newFolder("secret").toPath();SecretStore store=new SecretStore(root);store.set("db.main","password-123");
        assertEquals("password-123",store.get("db.main"));assertFalse(new String(Files.readAllBytes(store.file()),StandardCharsets.UTF_8).contains("password-123"));
        assertTrue(store.delete("db.main"));assertNull(store.get("db.main"));
    }

    @Test public void memoryPersistsAndSearches()throws Exception{
        Path root=temp.newFolder("memory").toPath();Workspace ws=new Workspace(root.toString());MemoryTool tool=new MemoryTool(ws,new CheckpointStore(root,root.resolve("cp")));
        JsonObject update=args("action","update");update.addProperty("content","客户编码使用 UTF-8");assertTrue(tool.execute(update).ok);
        JsonObject search=args("action","search");search.addProperty("query","UTF-8");assertTrue(tool.execute(search).output.contains("客户编码"));
    }

    @Test public void projectTemplateAndBuildDetectionWork()throws Exception{
        Path root=temp.newFolder("project").toPath();Workspace ws=new Workspace(root.toString());ProjectTool project=new ProjectTool(ws);JsonObject create=args("action","create");create.addProperty("template","web");create.addProperty("path","demo");assertTrue(project.execute(create).ok);assertTrue(Files.exists(root.resolve("demo/index.html")));
        BuildTool build=new BuildTool(ws,new PythonRuntime(""));JsonObject detect=args("action","detect");detect.addProperty("path","demo");assertTrue(build.execute(detect).output.contains("web"));
    }

    @Test public void projectCreatesPwaAndOfflineDashboardTemplates()throws Exception{
        Path root=temp.newFolder("app-templates").toPath();ProjectTool project=new ProjectTool(new Workspace(root.toString()));
        JsonObject pwa=args("action","create");pwa.addProperty("template","pwa");pwa.addProperty("path","mobile");assertTrue(project.execute(pwa).ok);assertTrue(Files.exists(root.resolve("mobile/manifest.json")));assertTrue(Files.exists(root.resolve("mobile/sw.js")));
        JsonObject dashboard=args("action","create");dashboard.addProperty("template","flask-dashboard");dashboard.addProperty("path","dashboard");assertTrue(project.execute(dashboard).ok);assertTrue(Files.exists(root.resolve("dashboard/app.py")));assertTrue(Files.exists(root.resolve("dashboard/static/app.js")));
    }

    @Test public void projectCreateAtSupportsBlankProject()throws Exception{
        Path target=temp.newFolder("blank-parent").toPath().resolve("new-project");
        assertEquals(0,ProjectTool.createAt(target,"blank"));
        assertTrue(Files.isDirectory(target));
    }

    @Test public void fileSummaryCreatesCache()throws Exception{
        Path root=temp.newFolder("files").toPath();Files.write(root.resolve("big.txt"),"开头\n内容\n结尾".getBytes(StandardCharsets.UTF_8));FilesTool tool=new FilesTool(new Workspace(root.toString()));JsonObject summary=args("action","summary");summary.addProperty("path","big.txt");assertTrue(tool.execute(summary).output.contains("摘要已缓存"));assertTrue(tool.execute(summary).output.contains("缓存命中"));
    }

    @Test public void contextAndPermissionPoliciesAreVisible()throws Exception{
        Path root=temp.newFolder("config").toPath();Config config=Config.load(root);Session session=Session.create(root.toString(),"qwen");session.messages.add(com.minion.core.llm.Message.user("你好"));ContextTool context=new ContextTool(session,1000,config);assertTrue(context.execute(args("action","status")).output.contains("估算 token"));
        PermissionTool permission=new PermissionTool(config);JsonObject set=args("action","set");set.addProperty("mode","read-only");assertTrue(permission.execute(set).ok);assertEquals("read-only",config.permissionMode());
    }

    @Test public void skillAdminCreatesAndValidatesSkill()throws Exception{
        Path root=temp.newFolder("skills-work").toPath(),skills=temp.newFolder("skills").toPath();SkillAdminTool tool=new SkillAdminTool(skills.toString(),new Workspace(root.toString()));JsonObject create=args("action","create");create.addProperty("name","excel-review");create.addProperty("description","复核表格");create.addProperty("content","先检查列名，再输出结论。");assertTrue(tool.execute(create).ok);JsonObject validate=args("action","validate");validate.addProperty("name","excel-review");assertTrue(tool.execute(validate).output.contains("有效"));
    }

    @Test public void skillAdminCapturesWorkflowWithReusableAssets()throws Exception{
        Path root=temp.newFolder("capture-work").toPath(),skills=temp.newFolder("capture-skills").toPath();
        Files.write(root.resolve("analyse.py"),"import pandas as pd\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("query.sql"),"select 1;\n".getBytes(StandardCharsets.UTF_8));
        SkillAdminTool tool=new SkillAdminTool(skills.toString(),new Workspace(root.toString()));
        JsonObject capture=args("action","capture");capture.addProperty("name","monthly-analysis");
        capture.addProperty("description","月度 Excel 数据分析与复核");
        capture.addProperty("content","## 适用场景\n月度分析\n## 步骤\n运行 assets/analyse.py\n## 校验\n核对合计");
        capture.addProperty("resourcePaths","analyse.py;query.sql");
        ToolResult result=tool.execute(capture);
        assertTrue(result.output,result.ok);assertTrue(result.output.contains("2 个资源文件"));
        assertTrue(Files.exists(skills.resolve("monthly-analysis/assets/analyse.py")));
        assertTrue(Files.exists(skills.resolve("monthly-analysis/assets/query.sql")));
    }

    @Test public void skillAdminImportCopiesWholeSkillDirectory()throws Exception{
        Path root=temp.newFolder("import-work").toPath(),source=root.resolve("portable-skill"),skills=temp.newFolder("import-skills").toPath();
        Files.createDirectories(source.resolve("assets"));
        Files.write(source.resolve("SKILL.md"),"---\nname: portable-skill\ndescription: portable\n---\nsteps".getBytes(StandardCharsets.UTF_8));
        Files.write(source.resolve("assets/template.csv"),"a,b\n".getBytes(StandardCharsets.UTF_8));
        SkillAdminTool tool=new SkillAdminTool(skills.toString(),new Workspace(root.toString()));
        JsonObject in=args("action","import");in.addProperty("path","portable-skill");
        assertTrue(tool.execute(in).ok);
        assertTrue(Files.exists(skills.resolve("portable-skill/assets/template.csv")));
    }

    private static JsonObject args(String key,String value){JsonObject o=new JsonObject();o.addProperty(key,value);return o;}
}
