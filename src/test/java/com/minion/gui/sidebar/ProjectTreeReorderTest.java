package com.minion.gui.sidebar;

import com.minion.core.agent.Session;
import com.minion.core.config.*;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.session.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class ProjectTreeReorderTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    Path app;WorkspaceManager workspaces;SessionManager manager;Map<Path,byte[]> original=new LinkedHashMap<Path,byte[]>();
    @Before public void setup()throws Exception {
        app=temp.newFolder("app").toPath();Path project=temp.newFolder("项目目录").toPath();workspaces=WorkspaceManager.load(app);
        assertTrue(workspaces.update("default",project.toString(),"",null));assertTrue(workspaces.add("项目甲",project.toString(),"",null));assertTrue(workspaces.add("项目乙",project.toString(),"",null));
        for(String name:Arrays.asList("default","项目甲","项目乙")){
            SessionStore store=new SessionStore(WorkspaceManager.sessionDirFor(app,name));
            for(String id:Arrays.asList("u1","u2","u3","p1","p2","archived")){
                Session s=Session.create(project.toString(),"fixture");s.id=id;s.title=id;s.pinned=id.startsWith("p");s.archived=id.equals("archived");s.messages.add(Message.user(name+" 原始要求 "+id));
                store.save(s);Path snapshot=WorkspaceManager.sessionDirFor(app,name).resolve(id+".json");original.put(snapshot,Files.readAllBytes(snapshot));
            }
            store.saveOrder(Arrays.asList("p1","p2","u1","u2","u3","archived"));
        }
        manager=newManager();
    }
    SessionManager newManager(){return new SessionManager(request->ConfirmUi.Decision.REJECT,Config.load(app),app,WorkspaceManager.load(app),ModelManager.load(app),Collections.emptyList(),null,null);}
    @After public void close(){if(manager!=null)manager.shutdown();}
    ProjectTreeModel.Row row(String project,String id){
        for(ProjectTreeModel.Row row:ProjectTreeModel.rows(manager.workspaces().list(),name->manager.sessions(name,true),Collections.emptySet(),Collections.singletonMap(project,100),"",true))
            if(row.workspace.equals(project)&&(id==null?row.kind==ProjectTreeModel.Kind.PROJECT:row.session!=null&&row.session.id.equals(id)))return row;
        throw new AssertionError(project+"/"+id);
    }
    List<String> ids(String project){List<String> ids=new ArrayList<String>();for(SessionHandle h:manager.sessions(project,false))ids.add(h.id);return ids;}
    List<String> projects(){List<String> names=new ArrayList<String>();for(WorkspaceConfig w:manager.workspaces().list())names.add(w.workSpaceName);return names;}
    void unchanged()throws Exception {for(Map.Entry<Path,byte[]> e:original.entrySet())assertArrayEquals(e.getKey().toString(),e.getValue(),Files.readAllBytes(e.getKey()));}
    @Test public void projectHeaderReorderUsesFullProjectOrderAndSurvivesRestart()throws Exception {
        assertTrue(ProjectTreeReorder.move(manager,row("项目乙",null),row("default",null),true));
        assertEquals(Arrays.asList("项目乙","default","项目甲"),projects());
        assertTrue(ProjectTreeReorder.move(manager,row("项目乙",null),row("项目甲",null),false));
        assertEquals(Arrays.asList("default","项目甲","项目乙"),projects());
        assertTrue(ProjectTreeReorder.move(manager,row("项目乙",null),row("default",null),false));
        assertEquals(Arrays.asList("default","项目乙","项目甲"),projects());
        unchanged();manager.shutdown();manager=newManager();assertEquals(Arrays.asList("default","项目乙","项目甲"),projects());assertNull(manager.currentSession());
    }
    @Test public void projectCanDropAfterAnotherExpandedProjectByItsConversationRow()throws Exception {
        assertTrue(ProjectTreeReorder.move(manager,row("default",null),row("项目甲","u1"),false));
        assertEquals(Arrays.asList("项目甲","default","项目乙"),projects());
        assertEquals(Arrays.asList("p1","p2","u1","u2","u3"),ids("项目甲"));
        assertNull(manager.currentSession());unchanged();
    }
    @Test public void sessionsInInactiveProjectReorderWithoutActivationOrLoadingAndKeepArchives()throws Exception {
        byte[] workspace=Files.readAllBytes(app.resolve("workspace.json"));
        assertTrue(ProjectTreeReorder.move(manager,row("项目甲","u3"),row("项目甲","u1"),true));
        assertEquals(Arrays.asList("p1","p2","u3","u1","u2"),ids("项目甲"));
        assertEquals(Arrays.asList("p1","p2","u1","u2","u3"),ids("default"));
        assertEquals("default",manager.workspaces().currentName());assertNull(manager.currentSession());
        for(SessionHandle h:manager.sessions("项目甲",true))assertFalse(h.isLoaded());assertArrayEquals(workspace,Files.readAllBytes(app.resolve("workspace.json")));unchanged();
        manager.shutdown();manager=newManager();assertEquals(Arrays.asList("p1","p2","u3","u1","u2"),ids("项目甲"));assertEquals(6,manager.sessions("项目甲",true).size());
    }
    @Test public void crossProjectSameIdPinnedBoundaryArchiveAndForeignKindsCannotMove()throws Exception {
        ProjectTreeModel.Row a=row("default","u1"),b=row("项目甲","u1");
        assertFalse(ProjectTreeReorder.move(manager,a,b,true));assertFalse(ProjectTreeReorder.move(manager,a,row("default","p1"),true));
        assertFalse(ProjectTreeReorder.move(manager,a,row("default","archived"),true));assertFalse(ProjectTreeReorder.move(manager,a,row("default",null),true));
        assertFalse(ProjectTreeReorder.move(manager,row("default",null),a,true));assertFalse(ProjectTreeReorder.move(manager,a,a,true));
        a.session.deleted=true;assertFalse(ProjectTreeReorder.canMove(a,row("default","u2")));a.session.deleted=false;unchanged();
    }
    @Test public void adjacentReorderAndPinnedReorderShareSamePersistenceRules()throws Exception {
        assertFalse(ProjectTreeReorder.moveAdjacent(manager,row("default",null),-1));
        assertTrue(ProjectTreeReorder.moveAdjacent(manager,row("项目乙",null),-1));assertEquals(Arrays.asList("default","项目乙","项目甲"),projects());
        assertTrue(ProjectTreeReorder.moveAdjacent(manager,row("项目甲","p2"),-1));assertEquals(Arrays.asList("p2","p1","u1","u2","u3"),ids("项目甲"));
        assertFalse(ProjectTreeReorder.moveAdjacent(manager,row("项目甲","u1"),-1));
        assertTrue(ProjectTreeReorder.moveAdjacent(manager,row("项目甲","u3"),-1));assertEquals(Arrays.asList("p2","p1","u1","u3","u2"),ids("项目甲"));unchanged();
    }
    @Test public void failedSessionOrderWriteDoesNotChangeVisibleOrder()throws Exception {
        Path blocker=WorkspaceManager.sessionDirFor(app,"项目甲").resolve("session-order.txt.tmp");Files.createDirectory(blocker);Files.write(blocker.resolve("keep"),new byte[]{1});
        assertFalse(ProjectTreeReorder.move(manager,row("项目甲","u3"),row("项目甲","u1"),true));assertEquals(Arrays.asList("p1","p2","u1","u2","u3"),ids("项目甲"));unchanged();
    }
    @Test public void failedProjectOrderWriteRollsBackInMemoryOrder()throws Exception {
        Path blocker=app.resolve("workspace.json.tmp");Files.createDirectory(blocker);Files.write(blocker.resolve("keep"),new byte[]{1});
        assertFalse(ProjectTreeReorder.move(manager,row("项目乙",null),row("default",null),true));assertEquals(Arrays.asList("default","项目甲","项目乙"),projects());unchanged();
    }
}
