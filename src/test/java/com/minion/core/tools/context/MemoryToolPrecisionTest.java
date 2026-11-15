package com.minion.core.tools.context;

import com.google.gson.*;
import com.minion.core.context.ProjectMemoryStore;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class MemoryToolPrecisionTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private JsonObject args(String action,String id){JsonObject a=new JsonObject();a.addProperty("action",action);if(id!=null)a.addProperty("id",id);return a;}
    private MemoryTool tool(){return new MemoryTool(new Workspace(tmp.getRoot().getPath()),null);}
    @Test public void newEntrypointsRemainRiskGatedAndDeleteAliasIsCompatible()throws Exception {
        MemoryTool tool=tool();ProjectMemoryStore s=new ProjectMemoryStore(tmp.getRoot().toPath());
        ProjectMemoryStore.Entry e=s.remember("project","人工可管理的记忆","用户确认");s.setVerified(e.id,true);
        for(String action:new String[]{"delete","disable","enable","configure"})assertTrue(tool.isHighRisk(args(action,e.id)));
        assertTrue(tool.execute(args("disable",e.id)).ok);assertTrue(s.verifiedIndex(6).isEmpty());
        assertTrue(tool.execute(args("enable",e.id)).ok);assertFalse(s.read(e.id).verified);
        assertTrue(tool.execute(args("delete",e.id)).ok);assertTrue(s.list().isEmpty());
        assertFalse(tool.isHighRisk(args("rebuild_index",null)));
    }
    @Test public void sourceFingerprintActuallyFiltersToolIndexAndSearchAndReadShowsState()throws Exception {
        Path p=tmp.getRoot().toPath().resolve("source.txt");Files.write(p,"old".getBytes(StandardCharsets.UTF_8));MemoryTool tool=tool();
        JsonObject a=args("remember",null);a.addProperty("type","decision");a.addProperty("content","接口按旧说明实施");a.addProperty("source","source.txt");
        assertTrue(tool.execute(a).ok);ProjectMemoryStore s=new ProjectMemoryStore(tmp.getRoot().toPath());String id=s.list().get(0).id;
        tool.execute(args("verify",id));Files.write(p,"new".getBytes(StandardCharsets.UTF_8));
        assertFalse(tool.execute(args("index",null)).output.contains("接口按旧说明实施"));
        a=args("search",null);a.addProperty("query","接口按旧说明实施");assertEquals("未找到",tool.execute(a).output);
        assertTrue(tool.execute(args("read",id)).output.contains("changed"));
    }
    @Test public void schemaMetadataAndSessionScopeAreWiredWithoutLoopSignatureChanges()throws Exception {
        Session session=Session.create(tmp.getRoot().getPath(),"fake");session.taskState.id="task-a";
        MemoryTool tool=new MemoryTool(new Workspace(tmp.getRoot().getPath()),null,null,"session",session);
        assertEquals("array",tool.schema().getAsJsonObject("properties").getAsJsonObject("supersedes").get("type").getAsString());
        JsonObject a=args("remember",null);a.addProperty("type","user");a.addProperty("content","当前模块采用中文输出");a.addProperty("source","用户限定");
        JsonObject c=new JsonObject();c.addProperty("task_id","task-a");c.addProperty("path_prefix","模块");a.add("conditions",c);
        JsonArray aliases=new JsonArray();aliases.add("中文标题");a.add("aliases",aliases);
        assertTrue(tool.execute(a).ok);String id=new ProjectMemoryStore(tmp.getRoot().toPath()).list().get(0).id;tool.execute(args("verify",id));
        a=args("search",null);a.addProperty("query","中文标题");a.addProperty("path","模块/说明.md");assertTrue(tool.execute(a).output.contains(id));
        a.addProperty("task","task-b");assertEquals("未找到",tool.execute(a).output);
    }
    @Test public void globalDisableConfigureAndDeleteStillRequireRealCurrentUser()throws Exception {
        Path app=tmp.newFolder("app").toPath();Session s=Session.create(tmp.getRoot().getPath(),"fake");
        MemoryTool tool=new MemoryTool(new Workspace(tmp.getRoot().getPath()),null,null,"s",s,app);
        ProjectMemoryStore g=ProjectMemoryStore.global(app);String id=g.remember("user","我喜欢简短输出","用户确认").id;
        for(String action:new String[]{"global_disable","global_enable","global_configure","global_delete"})assertFalse(tool.execute(args(action,id)).ok);
        Message user=Message.user("请删除这条全局记忆");user.userAuthored=true;s.messages.add(user);
        assertTrue(tool.execute(args("global_delete",id)).ok);assertTrue(g.list().isEmpty());
    }
    @Test public void legacyNotesAreLabeledAndNeverBypassExplicitScopeFilter()throws Exception {
        Path note=tmp.getRoot().toPath().resolve(".minion/memory/project.md");Files.createDirectories(note.getParent());
        Files.write(note,"旧版方案未经核验，不是当前决定".getBytes(StandardCharsets.UTF_8));MemoryTool tool=tool();
        JsonObject a=args("search",null);a.addProperty("query","旧版方案");assertTrue(tool.execute(a).output.contains("未经核验"));
        a.addProperty("task","new-task");assertEquals("未找到",tool.execute(a).output);
    }
}
