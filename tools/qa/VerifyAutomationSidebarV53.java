import com.minion.core.agent.LocalJobs;
import com.minion.core.agent.ModelProbe;
import com.minion.core.config.*;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.*;
import com.minion.gui.session.*;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.*;

/** Actual application sidebar and automation window, rendered with a disabled fixture. */
public final class VerifyAutomationSidebarV53 {
    private static Object field(Object object,String name)throws Exception{
        return VerifyOrderingDraftsV51.field(object,name);
    }
    private static void snapshot(Stage stage,Path file)throws Exception{
        stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
        javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(stage.getScene().snapshot(null),null),"png",file.toFile());
    }
    public static void main(String[] args)throws Exception{
        Path out=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(out);
        Path fixture=Files.createTempDirectory(out,"fixture-");
        Path root=Files.createDirectories(fixture.resolve("资料分析"));
        WorkspaceManager workspaces=WorkspaceManager.load(fixture);
        workspaces.update("default",root.toString(),"",null);
        workspaces.add("资料分析",root.toString(),"",null);
        workspaces.add("临时工作",root.toString(),"",null);
        new JFXPanel();javafx.application.Platform.setImplicitExit(false);
        SessionManager manager=new SessionManager(request->ConfirmUi.Decision.REJECT,Config.load(fixture),fixture,
                workspaces,ModelManager.load(fixture),Collections.emptyList(),null,null);
        Stage[] stages=new Stage[2];
        try{
            VerifyOrderingDraftsV51.fx(()->{
                java.lang.reflect.Field cfg=MinionApp.class.getDeclaredField("config");cfg.setAccessible(true);cfg.set(null,Config.load(fixture));
                manager.switchWorkspace("资料分析");
                for(int i=1;i<=7;i++)manager.createSession("报告整理 "+i);
                SessionHandle h=manager.sessions().get(0);
                LocalJobs.Job job=new LocalJobs.Job();job.title="资料巡检";job.prompt="只读检查项目中的报告并总结变化。";
                manager.jobs(h).saveJob(null,job,ModelProbe.fingerprint(manager.modelForSession(h)));
                Stage main=new Stage();stages[0]=main;main.setX(-16000);main.setY(0);
                MainWindow window=new MainWindow(main,manager);window.show();
                VerifyOrderingDraftsV51.window=window;
                VerifyOrderingDraftsV51.check(!manager.jobs(h).snapshot().get(0).enabled,"fixture remains disabled");
                return null;
            });
            VerifyOrderingDraftsV51.settle();
            VerifyOrderingDraftsV51.fx(()->{
                MainWindow window=VerifyOrderingDraftsV51.window;
                Stage main=stages[0];snapshot(main,out.resolve("sidebar.png"));
                Button button=null,more=null;
                for(Node n:main.getScene().getRoot().lookupAll(".button"))if(n instanceof Button){
                    Button b=(Button)n;if("自动化".equals(b.getText()))button=b;
                    if(b.getText()!=null&&b.getText().startsWith("显示更多 · 5 / 7"))more=b;
                }
                VerifyOrderingDraftsV51.check(button!=null&&more!=null,"navigation and five-item paging");
                more.fire();button.fire();
                Object center=field(window,"automationCenter");Stage automation=(Stage)field(center,"stage");stages[1]=automation;
                automation.setX(-16000);automation.setY(0);
                ListView<?> jobs=(ListView<?>)field(center,"list");
                VerifyOrderingDraftsV51.check(jobs.getItems().size()==1,"existing ledger visible");
                jobs.getSelectionModel().select(0);
                TextArea detail=(TextArea)field(center,"details");
                VerifyOrderingDraftsV51.check(detail.getText().contains("资料巡检")&&detail.getText().contains("停用"),"job details and status");
                VerifyOrderingDraftsV51.check(manager.currentSession()==null,"opening center does not navigate or dispatch");
                snapshot(automation,out.resolve("automation.png"));
                return null;
            });
            VerifyOrderingDraftsV51.settle();
            VerifyOrderingDraftsV51.fx(()->{
                Object sidebar=field(VerifyOrderingDraftsV51.window,"projectSidebar");
                ListView<?> rows=(ListView<?>)field(sidebar,"tree");
                long count=rows.getItems().stream().filter(r->r instanceof com.minion.gui.sidebar.ProjectTreeModel.Row
                        &&((com.minion.gui.sidebar.ProjectTreeModel.Row)r).session!=null).count();
                VerifyOrderingDraftsV51.check(count==7,"show more expands only the selected project");
                ((SplitPane)stages[0].getScene().lookup("#sidebar-split")).setDividerPositions(.14);
                return null;
            });
            VerifyOrderingDraftsV51.settle();
            VerifyOrderingDraftsV51.fx(()->{snapshot(stages[0],out.resolve("sidebar-narrow.png"));return null;});
            if(args.length>1&&"--collapse".equals(args[1])){
                VerifyOrderingDraftsV51.fx(()->{
                    for(Node n:stages[0].getScene().getRoot().lookupAll(".button"))
                        if(n instanceof Button&&"收起至 5 条".equals(((Button)n).getText())){
                            ((Button)n).fire();return null;
                        }
                    throw new AssertionError("missing collapse button after expansion");
                });
                VerifyOrderingDraftsV51.settle();
                VerifyOrderingDraftsV51.fx(()->{
                    Object sidebar=field(VerifyOrderingDraftsV51.window,"projectSidebar");
                    ListView<?> rows=(ListView<?>)field(sidebar,"tree");
                    long count=rows.getItems().stream().filter(r->r instanceof com.minion.gui.sidebar.ProjectTreeModel.Row
                            &&((com.minion.gui.sidebar.ProjectTreeModel.Row)r).session!=null).count();
                    VerifyOrderingDraftsV51.check(count==5,"collapse restores five conversations");
                    VerifyOrderingDraftsV51.check(manager.currentSession()==null,"collapse does not navigate");
                    return null;
                });
            }
            System.out.println("PASS V53 actual JavaFX JAR: "+VerifyOrderingDraftsV51.checks+" assertions; screenshots; no model requests.");
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
        finally{VerifyOrderingDraftsV51.fx(()->{for(Stage s:stages)if(s!=null)s.hide();return null;});manager.shutdown();javafx.application.Platform.exit();}
        System.exit(0);
    }
}
