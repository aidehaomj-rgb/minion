package com.minion.gui.session;
import com.minion.core.config.*;
import com.minion.core.storage.SessionStore;
import com.minion.gui.sidebar.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
public class ProjectSidebarLegacyCompatibilityTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private void put(Path p,String value)throws Exception{Files.createDirectories(p.getParent());Files.write(p,value.getBytes(StandardCharsets.UTF_8));}
    @Test public void originalDataBytesIdsOrderAndOldReaderSurviveProjectTreeBrowsing()throws Exception {
        Path app=temp.newFolder("installed").toPath(),a=temp.newFolder("中文项目甲").toPath(),b=temp.newFolder("项目乙").toPath();
        WorkspaceManager wm=WorkspaceManager.load(app);assertTrue(wm.update("default",a.toString(),"",null));assertTrue(wm.add("项目乙",b.toString(),"",null));assertTrue(wm.add("同目录另一项目",a.toString(),"",null));
        List<Path> protectedFiles=new ArrayList<Path>();protectedFiles.add(app.resolve("workspace.json"));
        for(String project:Arrays.asList("default","项目乙","同目录另一项目")){
            Path dir=WorkspaceManager.sessionDirFor(app,project);String path=project.equals("项目乙")?b.toString():a.toString();
            com.minion.core.agent.Session seed=com.minion.core.agent.Session.create(path,"old-intranet-model");seed.id="same-id";
            seed.messages.add(com.minion.core.llm.Message.user("最初的原始要求不要改动"));new SessionStore(dir).save(seed);
            for(String id:Arrays.asList("same-id","pinned-old","archived-old")){
                String raw="{\"id\":\""+id+"\",\"createdAt\":\"2026-08-01T09:00:00\",\"title\":\"旧对话-"+id+"\",\"workDir\":"+new com.google.gson.Gson().toJson(path)+",\"modelName\":\"old-intranet-model\",\"pinned\":"+id.equals("pinned-old")+",\"archived\":"+id.equals("archived-old")+",\"messages\":[{\"role\":\"USER\",\"content\":\"最初的原始要求不要改动\"}],\"legacyCustomField\":\"keep\"}";
                if(id.equals("same-id"))raw=raw.replace("\"legacyCustomField\"","\"journalSequence\":1,\"legacyCustomField\"");
                Path file=dir.resolve(id+".json");put(file,raw);protectedFiles.add(file);
            }
            new SessionStore(dir).saveOrder(Arrays.asList("same-id","pinned-old","archived-old"));protectedFiles.add(dir.resolve("session-order.txt"));
            Path archive=dir.resolve(".history/same-id/original.json");put(archive,"{\"original\":\"旧归档不迁移\"}");protectedFiles.add(archive);
            Path event=dir.resolve(".events/same-id/0000000000000001.json");assertTrue(Files.isRegularFile(event));protectedFiles.add(event);
        }
        Map<Path,byte[]> before=new LinkedHashMap<Path,byte[]>();for(Path p:protectedFiles)before.put(p,Files.readAllBytes(p));
        SessionManager manager=new SessionManager(message->ConfirmUi.Decision.REJECT,Config.load(app),app,wm,ModelManager.load(app),Collections.emptyList(),null,null);
        try{
            String current=wm.currentName();List<SessionHandle> original=manager.sessions();assertEquals("pinned-old",original.get(0).id);
            for(int i=0;i<4;i++){
                List<ProjectTreeModel.Row> rows=ProjectTreeModel.rows(wm.list(),name->manager.sessions(name,true),i%2==0?Collections.emptySet():Collections.singleton("default"),Collections.emptyMap(),i%2==0?"":"旧对话",true);
                assertFalse(rows.isEmpty());assertEquals(current,wm.currentName());assertNull(manager.currentSession());
                for(WorkspaceConfig w:wm.list())for(SessionHandle h:manager.sessions(w.workSpaceName,true)){assertFalse(h.isLoaded());assertNull(h.loop);assertNull(h.llm);}
            }
            SidebarPreferences prefs=new SidebarPreferences(manager.sidebarPreferencesPath());prefs.load();SidebarPreferences.State state=new SidebarPreferences.State();state.legacy=true;prefs.save(state,prefs.changed());
        }finally{manager.shutdown();}
        for(Map.Entry<Path,byte[]> e:before.entrySet())assertArrayEquals(e.getKey().toString(),e.getValue(),Files.readAllBytes(e.getKey()));
        // Existing SessionStore still reads the original format, not a converted/new database.
        assertEquals("最初的原始要求不要改动",new SessionStore(WorkspaceManager.sessionDirFor(app,"项目乙")).load("same-id").messages.get(0).content);
    }
}
