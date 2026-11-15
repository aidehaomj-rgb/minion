import com.minion.core.agent.Session;
import com.minion.core.config.*;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.*;
import com.minion.gui.MainWindow;
import com.minion.gui.chat.ChatView;
import com.minion.gui.session.*;
import com.minion.gui.sidebar.*;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Native FX8 fixture-only acceptance. Never sends a message, runs a tool or touches installed user data. */
public final class VerifyProjectSidebarV50 {
    static Path output,fixture;static Manager manager;static MainWindow window;static Stage stage;
    static ProjectSidebarView sidebar;static ListView<ProjectTreeModel.Row> tree;static TextField search;static TabPane tabs;
    static int checks;static Map<Path,byte[]> original=new LinkedHashMap<Path,byte[]>();
    static final class Manager extends SessionManager {
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final CountDownLatch nextStarted=new CountDownLatch(1),nextRelease=new CountDownLatch(1);
        Manager(Path p,WorkspaceManager w){super(request->ConfirmUi.Decision.REJECT,Config.load(p),p,w,ModelManager.load(p),Collections.emptyList(),null,null);}
        @Override protected boolean prepareNavigation(SessionHandle h){
            if(h.id.equals("slow-old")){started.countDown();try{release.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
            if(h.id.equals("pending-next")){nextStarted.countDown();try{nextRelease.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
            return super.prepareNavigation(h);
        }
    }
    static Object field(Object o,String name)throws Exception {java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static <T>T fx(Callable<T> call)throws Exception {FutureTask<T> f=new FutureTask<T>(call);Platform.runLater(f);return f.get(20,TimeUnit.SECONDS);}
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;}
    static void until(BooleanSupplier condition,String message)throws Exception {
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<stop){if(fx(()->condition.getAsBoolean())){checks++;return;}Thread.sleep(35);}
        throw new AssertionError(message);
    }
    static void settle()throws Exception {fx(()->null);Thread.sleep(100);fx(()->null);}
    static SessionHandle handle(String project,String id){for(SessionHandle h:manager.sessions(project,true))if(id.equals(h.id))return h;throw new AssertionError(project+"/"+id);}
    static void save(Path p,String project,String id,String title,boolean archived)throws Exception {
        Session s=Session.create(p.resolve(project.equals("default")||project.equals("同目录独立")?"工作甲":"工作乙").toString(),"fixture-no-request");
        s.id=id;s.title=title;s.archived=archived;s.messages.add(Message.user("保留此项目的最初要求："+project));
        new SessionStore(WorkspaceManager.sessionDirFor(p,project)).save(s);
    }
    static CheckMenuItem option(String text){
        for(javafx.scene.Node n:sidebar.lookupAll(".menu-button"))if(n instanceof MenuButton)
            for(MenuItem m:((MenuButton)n).getItems())if(text.equals(m.getText()))return (CheckMenuItem)m;
        throw new AssertionError(text);
    }
    static void toggle(CheckMenuItem item){item.setSelected(!item.isSelected());item.fire();}
    static void enter(ProjectTreeModel.Row row){tree.getSelectionModel().select(row);tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ENTER,false,false,false,false));}
    static void snapshot(String name)throws Exception {fx(()->{stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null),null),"png",output.resolve(name+".png").toFile());return null;});}
    public static void main(String[] args)throws Exception {
        try{run(args);}catch(Throwable error){error.printStackTrace();Platform.exit();System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        output=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(output);fixture=Files.createTempDirectory(output,"fixture-");
        Files.createDirectories(fixture.resolve("工作甲"));Files.createDirectories(fixture.resolve("工作乙"));Path missing=Files.createDirectories(fixture.resolve("将离线的目录"));
        WorkspaceManager w=WorkspaceManager.load(fixture);check(w.update("default",fixture.resolve("工作甲").toString(),"",null),"default fixture");
        check(w.add("情报分析",fixture.resolve("工作乙").toString(),"",null),"second fixture");check(w.add("同目录独立",fixture.resolve("工作甲").toString(),"",null),"same-folder fixture");
        check(w.add("空项目",fixture.resolve("工作乙").toString(),"",null),"empty fixture");check(w.add("目录暂离",missing.toString(),"",null),"missing fixture");Files.delete(missing);
        for(int i=0;i<35;i++)save(fixture,"default",String.format("p%02d",i),"历史核对 "+i,false);
        save(fixture,"default","same-id","同名重复ID",false);save(fixture,"default","slow-old","模拟迟到旧导航",false);save(fixture,"default","archived","已归档对话",true);
        save(fixture,"default","pending-next","模拟程序页签同步竞争",false);
        save(fixture,"情报分析","same-id","同名重复ID",false);save(fixture,"情报分析","b2","报告整理",false);
        try(java.util.stream.Stream<Path> paths=Files.walk(fixture)){paths.filter(Files::isRegularFile).forEach(p->{try{original.put(p,Files.readAllBytes(p));}catch(Exception e){throw new RuntimeException(e);}});}
        new JFXPanel();Platform.setImplicitExit(false);manager=new Manager(fixture,w);
        try{
            fx(()->{java.lang.reflect.Field config=com.minion.gui.MinionApp.class.getDeclaredField("config");config.setAccessible(true);config.set(null,Config.load(fixture));
                stage=new Stage();stage.setX(-16000);stage.setY(20);window=new MainWindow(stage,manager);window.show();
                sidebar=(ProjectSidebarView)field(window,"projectSidebar");tree=(ListView<ProjectTreeModel.Row>)field(sidebar,"tree");search=(TextField)field(sidebar,"search");tabs=(TabPane)field(window,"tabs");return null;});
            until(()->{try{return (Boolean)field(sidebar,"ready");}catch(Exception e){throw new RuntimeException(e);}},"preferences ready");settle();
            fx(()->{
                check(!sidebar.legacyMode(),"new default tree");check(tree.getItems().stream().filter(r->r.kind==ProjectTreeModel.Kind.PROJECT).count()==5,"all old projects including empty/missing");
                check(tree.getItems().stream().anyMatch(r->r.kind==ProjectTreeModel.Kind.MORE),"30-row pagination");
                search.setText("同名重复ID");return null;
            });settle();
            fx(()->{check(tree.getItems().stream().filter(r->r.session!=null).count()==2,"two projects with same session ID");search.setText("");return null;});settle();
            fx(()->{enter(tree.getItems().stream().filter(r->r.workspace.equals("default")&&r.kind==ProjectTreeModel.Kind.PROJECT).findFirst().get());return null;});settle();
            fx(()->{check(manager.currentSession()==null&&w.currentName().equals("default"),"fold does not navigate");
                toggle(option("旧版双列表"));return null;});settle();snapshot("legacy-sidebar");
            fx(()->{check(sidebar.legacyMode(),"legacy fallback");toggle(option("旧版双列表"));return null;});settle();
            for(WorkspaceConfig project:w.list())for(SessionHandle h:manager.sessions(project.workSpaceName,true))check(!h.isLoaded()&&h.llm==null,"browse is metadata-only");
            for(Map.Entry<Path,byte[]> p:original.entrySet())check(Arrays.equals(p.getValue(),Files.readAllBytes(p.getKey())),"passive byte preservation "+p.getKey());
            SessionHandle slow=handle("default","slow-old"),b=handle("情报分析","same-id"),a=handle("default","same-id");
            fx(()->{manager.openSessionAcrossProjects(slow,null);return null;});check(manager.started.await(5,TimeUnit.SECONDS),"slow navigation started");
            fx(()->{manager.openSessionAcrossProjects(b,null);return null;});until(()->manager.currentSession()==b,"latest click opens while old is blocked");
            manager.release.countDown();until(slow::isLoaded,"slow preparation completes");settle();
            fx(()->{check(manager.currentSession()==b&&w.currentName().equals("情报分析"),"late completion cannot steal focus");
                check(((ChatView)field(window,"chatView")).handle()==b,"main chat bound to newest project");return null;});
            snapshot("project-conversations");
            fx(()->{((SplitPane)stage.getScene().lookup("#sidebar-split")).setDividerPositions(0.14);return null;});settle();
            fx(()->{javafx.scene.layout.FlowPane actions=(javafx.scene.layout.FlowPane)sidebar.lookup(".project-actions");
                check(sidebar.getWidth()<=205&&sidebar.getWidth()>=175,"narrow sidebar keeps adjustable width");
                for(javafx.scene.Node button:actions.getChildren())check(button.getBoundsInParent().getMaxX()<=actions.getWidth()+1,"toolbar wraps without overflow");
                for(javafx.scene.Node n:tree.lookupAll(".button"))if(n instanceof Button&&"＋".equals(((Button)n).getText()))check(n.getLayoutBounds().getWidth()+1>=((Button)n).prefWidth(-1),"project plus button remains legible");return null;});
            snapshot("project-sidebar-narrow");
            fx(()->{((SplitPane)stage.getScene().lookup("#sidebar-split")).setDividerPositions(0.31);return null;});settle();
            fx(()->{manager.openSessionAcrossProjects(a,null);return null;});until(()->manager.currentSession()==a&&tabs.getTabs().size()==2,"same-ID tabs remain distinct");
            fx(()->{check(tabs.getTabs().stream().anyMatch(t->t.getUserData()==a)&&tabs.getTabs().stream().anyMatch(t->t.getUserData()==b),"handle-qualified tabs");
                tabs.getSelectionModel().select(tabs.getTabs().stream().filter(t->t.getUserData()==b).findFirst().get());return null;});settle();
            fx(()->{check(manager.currentSession()==b&&((ChatView)field(window,"chatView")).handle()==b,"tab navigates to owning project");
                Tab tab=tabs.getTabs().stream().filter(t->t.getUserData()==b).findFirst().get();tab.getOnCloseRequest().handle(new Event(Tab.TAB_CLOSE_REQUEST_EVENT));return null;});settle();
            fx(()->{check(!b.deleted&&Files.isRegularFile(WorkspaceManager.sessionDirFor(fixture,"情报分析").resolve("same-id.json")),"close tab keeps old data");manager.openSessionAcrossProjects(b,null);return null;});settle();
            fx(()->{check(tabs.getTabs().size()==2&&manager.currentSession()==b,"closed tab reopens");
                toggle(option("显示归档对话（项目树）"));search.setText("已归档");return null;});settle();
            fx(()->{check(tree.getItems().stream().anyMatch(r->r.session!=null&&r.session.id.equals("archived")),"archives visible on request");
                search.setText("");manager.openSessionAcrossProjects(handle("default","p00"),null);return null;});until(()->manager.currentSession()==handle("default","p00"),"old page session opens");settle();
            fx(()->{check(tree.getItems().stream().anyMatch(r->r.session==manager.currentSession()),"reveal beyond page 30");
                SessionHandle other=handle("情报分析","b2");other.running=true;manager.openSessionAcrossProjects(b,null);return null;});settle();
            fx(()->{check(handle("情报分析","b2").running,"navigation never stops other tasks");handle("情报分析","b2").running=false;return null;});
            check(manager.sessions("同目录独立",true).isEmpty(),"same folder identities never merged");
            SessionHandle pending=handle("default","pending-next");
            fx(()->{manager.openSessionAcrossProjects(a,null);manager.openSessionAcrossProjects(pending,null);return null;});
            check(manager.nextStarted.await(5,TimeUnit.SECONDS),"next pending navigation starts");settle();manager.nextRelease.countDown();
            until(()->manager.currentSession()==pending,"programmatic tab selection cannot cancel newer pending navigation");settle();
            fx(()->{check(((ChatView)field(window,"chatView")).handle()==pending,"latest navigation remains displayed after tab synchronization");return null;});
            fx(()->{enter(tree.getItems().stream().filter(r->r.workspace.equals("同目录独立")&&r.kind==ProjectTreeModel.Kind.EMPTY).findFirst().get());return null;});settle();
            fx(()->{check(manager.currentSession()!=null&&manager.currentSession().workspaceName.equals("同目录独立"),"new chat belongs to explicitly chosen project");
                check(manager.sessions("同目录独立",true).size()==1&&manager.sessions("情报分析",true).size()==2,"new chat does not move or replace old chats");
                check(manager.currentSession().session.workDir.equals(fixture.resolve("工作甲").toString()),"new chat uses target project root");return null;});
            fx(()->{enter(tree.getItems().stream().filter(r->r.workspace.equals("目录暂离")&&r.kind==ProjectTreeModel.Kind.EMPTY).findFirst().get());return null;});settle();
            fx(()->{check(manager.currentSession().workspaceName.equals("同目录独立")&&manager.sessions("目录暂离",true).isEmpty(),"missing folder never silently creates a replacement project");return null;});
            System.out.println("PASS V50 native FX8: "+checks+" assertions; metadata-only browsing and byte preservation; latest-wins and tab-sync races; duplicate-ID tabs; close/reopen; archived; pagination; background state. No model request.");
        }finally{manager.release.countDown();manager.nextRelease.countDown();fx(()->{if(stage!=null)stage.hide();return null;});manager.shutdown();Platform.exit();}
        System.exit(0);
    }
}
