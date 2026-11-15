package com.minion.gui.session;

import com.minion.core.agent.Session;
import com.minion.core.config.*;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Explicit metadata actions may write only their owning project; viewing is covered separately. */
public class ProjectSidebarOperationsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private Path app;private WorkspaceManager workspaces;private SessionManager manager;
    @Before public void setup()throws Exception {
        app=temp.newFolder("app").toPath();Path root=temp.newFolder("旧项目目录").toPath();
        workspaces=WorkspaceManager.load(app);assertTrue(workspaces.update("default",root.toString(),"",null));
        assertTrue(workspaces.add("另一项目",root.toString(),"",null));
        for(String name:Arrays.asList("default","另一项目")){
            Session s=Session.create(root.toString(),"fixture-no-request");s.id="same-id";s.title="原始对话";s.messages.add(Message.user("本项目原始要求："+name));
            SessionStore store=new SessionStore(WorkspaceManager.sessionDirFor(app,name));store.save(s);store.saveOrder(Collections.singletonList(s.id));
        }
        manager=new SessionManager(request->ConfirmUi.Decision.REJECT,Config.load(app),app,workspaces,ModelManager.load(app),Collections.emptyList(),null,null);
    }
    @After public void close(){if(manager!=null)manager.shutdown();}
    private SessionHandle other(){return manager.sessions("另一项目",true).get(0);}
    private Path snapshot(String name){return WorkspaceManager.sessionDirFor(app,name).resolve("same-id.json");}
    @Test public void sameDirectoryAndDuplicateIdCannotShareAutomationLedger()throws Exception {
        try{manager.jobs(other());fail("ambiguous automation owner");}
        catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("同 ID"));}
        assertFalse(other().isLoaded());
        assertFalse(Files.exists(Paths.get(workspaces.get("另一项目").workDir,".minion","reliability","same-id","jobs.json")));
    }
    @Test public void archiveRenameAndPinTargetOnlyTheOwningProjectWithoutNavigation()throws Exception {
        byte[] original=Files.readAllBytes(snapshot("default")),config=Files.readAllBytes(app.resolve("workspace.json"));
        Path order=WorkspaceManager.sessionDirFor(app,"另一项目").resolve("session-order.txt");byte[] originalOrder=Files.readAllBytes(order);
        SessionHandle b=other();manager.setArchived(b,true);
        assertTrue(b.isLoaded());assertTrue(b.session.archived);assertTrue(manager.sessions("另一项目",false).isEmpty());
        assertEquals(1,manager.sessions("default",false).size());assertArrayEquals(originalOrder,Files.readAllBytes(order));
        manager.setArchived(b,false);manager.renameSession(b,"新标题");manager.setPinned(b,true);
        Session stored=new SessionStore(WorkspaceManager.sessionDirFor(app,"另一项目")).load("same-id");
        assertFalse(stored.archived);assertTrue(stored.pinned);assertEquals("新标题",stored.title);
        assertEquals("本项目原始要求：另一项目",stored.messages.get(0).content);
        assertArrayEquals(original,Files.readAllBytes(snapshot("default")));assertArrayEquals(config,Files.readAllBytes(app.resolve("workspace.json")));
        assertNull(manager.currentSession());assertEquals("default",workspaces.currentName());
    }
    @Test public void runningConversationCannotBeArchivedOrHydratedByArchiveAction()throws Exception {
        SessionHandle b=other();byte[] before=Files.readAllBytes(snapshot("另一项目"));b.running=true;
        try{manager.setArchived(b,true);assertFalse(b.isLoaded());assertFalse(b.session.archived);assertArrayEquals(before,Files.readAllBytes(snapshot("另一项目")));}
        finally{b.running=false;}
    }
    @Test public void explicitDeleteOfDuplicateIdLeavesOtherProjectsOriginalSnapshotAndEvent()throws Exception {
        SessionHandle b=other();Path event=WorkspaceManager.sessionDirFor(app,"default").resolve(".events/same-id/0000000000000001.json");
        byte[] before=Files.readAllBytes(snapshot("default")),evidence=Files.readAllBytes(event);manager.deleteSession(b);
        assertTrue(b.deleted);assertTrue(manager.sessions("另一项目",true).isEmpty());assertFalse(Files.exists(snapshot("另一项目")));
        assertArrayEquals(before,Files.readAllBytes(snapshot("default")));assertArrayEquals(evidence,Files.readAllBytes(event));assertFalse(manager.sessions().get(0).isLoaded());
    }
}
