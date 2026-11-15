import com.minion.core.config.*;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.*;
import com.minion.gui.session.*;
import com.minion.gui.sidebar.ProjectTreeModel;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.*;

/** Drives the real ListView's mouse event route in an isolated window. No OS mouse injection. */
public final class VerifyReplySidebarV54 {
    private static Stage stage;
    private static SessionManager manager;
    private static ListView<?> tree;
    private static Path fixture;
    private static void check(boolean condition,String text){VerifyOrderingDraftsV51.check(condition,text);}
    private static ListCell<?> cell(String project,String id){
        for(Node n:tree.lookupAll(".list-cell"))if(n instanceof ListCell){
            ListCell<?> cell=(ListCell<?>)n;
            if(!(cell.getItem() instanceof ProjectTreeModel.Row)||cell.isEmpty()||!cell.isVisible())continue;
            ProjectTreeModel.Row row=(ProjectTreeModel.Row)cell.getItem();
            if(project.equals(row.workspace)&&(id==null?row.kind==ProjectTreeModel.Kind.PROJECT:row.session!=null&&id.equals(row.session.id)))return cell;
        }
        throw new AssertionError(project+"/"+id);
    }
    private static void mouse(ListCell<?> origin,ListCell<?> destination,EventType<MouseEvent> type,double vertical){
        Bounds bounds=destination.localToScene(destination.getLayoutBounds());
        double x=bounds.getMinX()+24,y=bounds.getMinY()+bounds.getHeight()*vertical;
        Event.fireEvent(origin,new MouseEvent(type,x,y,x,y,MouseButton.PRIMARY,1,false,false,false,false,
                type!=MouseEvent.MOUSE_RELEASED,false,false,false,false,false,new PickResult(origin,x,y)));
    }
    private static void drag(ListCell<?> origin,ListCell<?> target,double vertical){
        mouse(origin,origin,MouseEvent.MOUSE_PRESSED,.5);
        mouse(origin,target,MouseEvent.MOUSE_DRAGGED,vertical);
        mouse(origin,target,MouseEvent.MOUSE_RELEASED,vertical);
    }
    private static List<String> projectOrder(WorkspaceManager ws){
        List<String> names=new ArrayList<String>();for(WorkspaceConfig w:ws.list())names.add(w.workSpaceName);return names;
    }
    public static void main(String[] args)throws Exception{
        Path out=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(out);
        fixture=Files.createTempDirectory(out,"fixture-");
        Path dir=Files.createDirectories(fixture.resolve("项目数据"));
        WorkspaceManager ws=WorkspaceManager.load(fixture);ws.update("default",dir.toString(),"",null);
        ws.add("项目甲",dir.toString(),"",null);ws.add("项目乙",dir.toString(),"",null);
        new JFXPanel();Platform.setImplicitExit(false);
        try{
            VerifyOrderingDraftsV51.fx(()->{
                Config config=Config.load(fixture);
                manager=new SessionManager(request->ConfirmUi.Decision.REJECT,config,fixture,ws,ModelManager.load(fixture),Collections.emptyList(),null,null);
                java.lang.reflect.Field f=MinionApp.class.getDeclaredField("config");f.setAccessible(true);f.set(null,config);
                manager.switchWorkspace("项目甲");manager.createSession("甲1");manager.createSession("甲2");
                manager.switchWorkspace("项目乙");manager.createSession("乙1");manager.createSession("乙2");
                stage=new Stage();stage.setX(-16000);stage.setY(0);
                MainWindow window=new MainWindow(stage,manager);window.show();
                tree=(ListView<?>)VerifyOrderingDraftsV51.field(VerifyOrderingDraftsV51.field(window,"projectSidebar"),"tree");
                return null;
            });
            VerifyOrderingDraftsV51.settle();
            VerifyOrderingDraftsV51.fx(()->{
                stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
                ListCell<?> a=cell("default",null),b=cell("项目甲",manager.sessions("项目甲",false).get(0).id);
                for(ListCell<?> c:new ListCell<?>[]{a,b})for(MenuItem item:c.getContextMenu().getItems())
                    check(!"上移".equals(item.getText())&&!"下移".equals(item.getText()),"no menu sorting");
                mouse(a,a,MouseEvent.MOUSE_PRESSED,.5);
                mouse(a,b,MouseEvent.MOUSE_DRAGGED,.55);
                check(tree.lookup(".project-drop-after")!=null,"project drag has insertion marker on expanded group");
                stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
                javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(stage.getScene().snapshot(null),null),"png",out.resolve("project-drag.png").toFile());
                mouse(a,b,MouseEvent.MOUSE_RELEASED,.55);
                check(projectOrder(ws).equals(Arrays.asList("项目甲","default","项目乙")),"drop on child moves whole project");
                check(projectOrder(WorkspaceManager.load(fixture)).equals(projectOrder(ws)),"project drag persisted");
                check(manager.currentSession()==null&&"项目乙".equals(ws.currentName()),"sorting does not navigate");
                return null;
            });
            VerifyOrderingDraftsV51.settle();
            VerifyOrderingDraftsV51.fx(()->{
                List<SessionHandle> before=manager.sessions("项目甲",false);
                drag(cell("项目甲",before.get(1).id),cell("项目甲",before.get(0).id),.2);
                check(manager.sessions("项目甲",false).get(0)==before.get(1),"conversation drag still sorts");
                return null;
            });
            VerifyOrderingDraftsV51.settle();
            VerifyOrderingDraftsV51.fx(()->{
                List<SessionHandle> before=manager.sessions("项目甲",false);
                drag(cell("项目甲",before.get(0).id),cell("项目乙",manager.sessions("项目乙",false).get(0).id),.2);
                check(manager.sessions("项目甲",false).equals(before),"cross-project conversation drag rejected");
                return null;
            });
            System.out.println("PASS V54 JavaFX mouse event integration: "+VerifyOrderingDraftsV51.checks+" assertions; no model calls.");
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
        finally{VerifyOrderingDraftsV51.fx(()->{if(stage!=null)stage.hide();return null;});if(manager!=null)manager.shutdown();Platform.exit();}
        System.exit(0);
    }
}
