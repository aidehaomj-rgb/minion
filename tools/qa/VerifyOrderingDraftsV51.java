import com.minion.core.agent.Session;
import com.minion.core.config.*;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.*;
import com.minion.gui.*;
import com.minion.gui.chat.ChatView;
import com.minion.gui.input.*;
import com.minion.gui.session.*;
import com.minion.gui.sidebar.*;
import javafx.application.Platform;
import javafx.embed.swing.*;
import javafx.event.Event;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual MainWindow from the packaged JAR; fixture data only, no model requests or native OS mouse injection. */
public final class VerifyOrderingDraftsV51 {
    static Path output,fixture;static Stage stage;static MainWindow window;static SessionManager manager;
    static ProjectSidebarView sidebar;static ListView<ProjectTreeModel.Row> tree;static InputView composer;static TextArea input;static TabPane tabs;
    static int checks;static Map<Path,byte[]> original=new LinkedHashMap<Path,byte[]>();
    static Object field(Object o,String name)throws Exception {java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception {java.lang.reflect.Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static <T>T fx(Callable<T> c)throws Exception {FutureTask<T> f=new FutureTask<T>(c);Platform.runLater(f);return f.get(15,TimeUnit.SECONDS);}
    static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);checks++;}
    static void settle()throws Exception {fx(()->null);Thread.sleep(120);fx(()->null);}
    static void until(BooleanSupplier condition,String text)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){if(fx(()->condition.getAsBoolean())){checks++;return;}Thread.sleep(25);}throw new AssertionError(text);}
    static ProjectTreeModel.Row row(String project,String id){for(ProjectTreeModel.Row r:tree.getItems())if(r.workspace.equals(project)&&(id==null?r.kind==ProjectTreeModel.Kind.PROJECT:r.session!=null&&r.session.id.equals(id)))return r;throw new AssertionError(project+"/"+id);}
    static SessionHandle handle(String project,String id){return manager.findSession(project,id);}
    static void open(SessionHandle h)throws Exception {fx(()->{manager.openSessionAcrossProjects(h,null);return null;});until(()->{try{return manager.currentSession()==h&&field(composer,"current")==h&&((ChatView)field(window,"chatView")).handle()==h;}catch(Exception e){return false;}},"chat opens");settle();}
    static void menu(ProjectTreeModel.Row r,String text)throws Exception {ContextMenu menu=(ContextMenu)call(sidebar,"menu",new Class<?>[]{ProjectTreeModel.Row.class},r);for(MenuItem i:menu.getItems())if(text.equals(i.getText())){i.fire();return;}throw new AssertionError(text);}
    static List<?> chips()throws Exception {return (List<?>)field(composer,"chips");}
    static void snapshot(String name)throws Exception {fx(()->{stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null),null),"png",output.resolve(name+".png").toFile());return null;});}
    static void fixture(Path root,String project,String id)throws Exception {
        Session s=Session.create(root.toString(),"no-model-request");s.id=id;s.title=project+" · "+id;s.context150kMigrationVersion=1;s.messages.add(Message.user("合成历史记录："+project));
        Path dir=WorkspaceManager.sessionDirFor(fixture,project);new SessionStore(dir).save(s);Path file=dir.resolve(id+".json");original.put(file,Files.readAllBytes(file));
    }
    public static void main(String[] args)throws Exception {try{run(args);}catch(Throwable e){e.printStackTrace();Platform.exit();System.exit(1);}}
    static void run(String[] args)throws Exception {
        output=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(output);fixture=Files.createTempDirectory(output,"fixture-");
        Path root=Files.createDirectories(fixture.resolve("中文目录"));WorkspaceManager workspaces=WorkspaceManager.load(fixture);workspaces.update("default",root.toString(),"",null);
        workspaces.add("分析项目",root.toString(),"",null);workspaces.add("空项目",root.toString(),"",null);
        fixture(root,"default","same-id");fixture(root,"default","other-a");fixture(root,"分析项目","same-id");fixture(root,"分析项目","other-b");
        new SessionStore(WorkspaceManager.sessionDirFor(fixture,"分析项目")).saveOrder(Arrays.asList("same-id","other-b"));
        new JFXPanel();Platform.setImplicitExit(false);
        manager=new SessionManager(request->ConfirmUi.Decision.REJECT,Config.load(fixture),fixture,workspaces,ModelManager.load(fixture),Collections.emptyList(),null,null);
        try{
            fx(()->{java.lang.reflect.Field f=MinionApp.class.getDeclaredField("config");f.setAccessible(true);f.set(null,Config.load(fixture));stage=new Stage();stage.setX(-16000);stage.setY(0);
                window=new MainWindow(stage,manager);window.show();sidebar=(ProjectSidebarView)field(window,"projectSidebar");tree=(ListView<ProjectTreeModel.Row>)field(sidebar,"tree");
                composer=(InputView)field(window,"inputView");input=(TextArea)field(composer,"input");tabs=(TabPane)field(window,"tabs");return null;});settle();
            SessionHandle a=handle("default","same-id"),b=handle("分析项目","same-id");open(a);
            fx(()->{input.setText("甲项目的未发送内容 [粘贴块1]");input.selectRange(2,5);
                call(composer,"addChip",new Class<?>[]{InputChip.class},InputChip.pasteChip("甲独立的长文本","[粘贴块1]"));
                call(composer,"addChip",new Class<?>[]{InputChip.class},InputChip.attachmentChip("甲附件正文","甲.txt"));return null;});
            open(b);fx(()->{check(input.getText().isEmpty()&&chips().isEmpty(),"same-ID other project starts empty");input.undo();check(input.getText().isEmpty(),"undo cannot bring A into B");input.setText("乙项目的独立草稿");return null;});
            open(a);fx(()->{check(input.getText().equals("甲项目的未发送内容 [粘贴块1]"),"A draft restored after cross-project null binding");check(chips().size()==2,"A attachments restored");check(input.getAnchor()==2&&input.getCaretPosition()==5,"selection restored");return null;});
            snapshot("draft-a-restored");
            fx(()->{menu(row("分析项目","other-b"),"上移");return null;});settle();
            fx(()->{check(manager.sessions("分析项目",false).get(0).id.equals("other-b"),"menu sorts inactive project's conversation");check(manager.currentSession()==a&&input.getText().startsWith("甲项目"),"sorting does not switch or overwrite input");
                tree.getSelectionModel().select(row("分析项目","same-id"));Event.fireEvent(tree,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.UP,false,false,true,false));return null;});settle();
            fx(()->{check(manager.sessions("分析项目",false).get(0)==b,"Alt+Up shares reorder behavior");menu(row("空项目",null),"上移");return null;});settle();
            fx(()->{check(manager.workspaces().list().get(1).workSpaceName.equals("空项目"),"project menu reorders project block");check(manager.currentSession()==a,"project ordering does not navigate");
                int handlers=0;for(javafx.scene.Node node:tree.lookupAll(".list-cell"))if(node instanceof ListCell){ListCell<?> c=(ListCell<?>)node;if(c.getItem() instanceof ProjectTreeModel.Row){check(c.getOnDragDetected()!=null&&c.getOnDragOver()!=null&&c.getOnDragDropped()!=null&&c.getOnDragDone()!=null,"virtualized row has drag handlers");handlers++;}}
                check(handlers>=3,"drag handlers tested on rendered rows");return null;});
            open(b);fx(()->{check(input.getText().equals("乙项目的独立草稿"),"B draft retained through ordering");
                Tab t=tabs.getTabs().stream().filter(tab->tab.getUserData()==b).findFirst().get();t.getOnCloseRequest().handle(new Event(Tab.TAB_CLOSE_REQUEST_EVENT));return null;});settle();open(b);
            fx(()->{check(input.getText().equals("乙项目的独立草稿"),"closed/reopened tab retains draft in same process");call(composer,"clearComposer",new Class<?>[0]);return null;});open(a);open(b);
            fx(()->{check(input.getText().isEmpty()&&chips().isEmpty(),"cleared draft never resurrects");return null;});
            for(Map.Entry<Path,byte[]> e:original.entrySet())check(Arrays.equals(e.getValue(),Files.readAllBytes(e.getKey())),"drafts and sorting do not rewrite original session snapshots");
            snapshot("project-ordering");
            System.out.println("PASS V51 native FX8 JAR: "+checks+" assertions; real MainWindow cross-project drafts, undo isolation, attachments, close/reopen, keyboard/menu ordering and DnD handler wiring. Fixture-only; no model call; no OS mouse injection.");
        }finally{fx(()->{if(stage!=null)stage.hide();return null;});if(composer!=null)((ExecutorService)field(composer,"filePool")).shutdownNow();manager.shutdown();Platform.exit();}
        System.exit(0);
    }
}
