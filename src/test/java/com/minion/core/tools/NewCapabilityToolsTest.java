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

    private static JsonObject args(String key,String value){JsonObject o=new JsonObject();o.addProperty(key,value);return o;}
}
