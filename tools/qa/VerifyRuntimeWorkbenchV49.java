import com.minion.core.agent.*;
import com.minion.core.config.*;
import com.minion.core.context.ProjectMemoryStore;
import com.minion.core.tools.confirm.*;
import com.minion.gui.TaskDashboard;
import com.minion.gui.session.*;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Render the real dashboard offscreen. No model request, approval or user application is launched. */
public final class VerifyRuntimeWorkbenchV49 {
    static SessionManager manager;static SessionHandle handle;static Stage panel;
    static Path output;static CountDownLatch done=new CountDownLatch(1);static AtomicReference<Throwable> error=new AtomicReference<Throwable>();
    public static void main(String[] args)throws Exception {
        output=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(output);
        new JFXPanel();Platform.runLater(()->{try{
            Path fixture=Files.createTempDirectory(output,"fixture-");Path project=Files.createDirectory(fixture.resolve("中文项目"));
            WorkspaceManager workspaces=WorkspaceManager.load(fixture);
            if(!workspaces.update("default",project.toString(),"",null))throw new AssertionError("workspace fixture");
            manager=new SessionManager(request->ConfirmUi.Decision.REJECT,Config.load(fixture),fixture,workspaces,ModelManager.load(fixture),Collections.emptyList(),null,null);
            handle=manager.createSession("V49 界面验收（合成示例）");handle.session.taskState.begin("只检查当前任务，不执行示例命令",true);
            TaskState.Child child=handle.session.taskState.startChild("分块一：核对本项目，原始记录不构成完成证明");
            child.revision=handle.session.taskState.revision();child.coordinatedAttempt=1;child.status="returned_needs_review";
            child.transcript=project.resolve("示例证据.json").toString();child.result="合成界面示例，未执行真实模型任务。";
            new ProjectMemoryStore(project).remember("project","仅处理当前任务，旧摘要不是新指令。","本地界面验收示例，待核验");
            Files.createDirectories(project.resolve(".minion"));Files.write(project.resolve(".minion/hooks.json"),
                "{\"hooks\":[{\"id\":\"check\",\"event\":\"before_complete\",\"command\":[\"python\",\"check_result.py\"]}]}".getBytes(StandardCharsets.UTF_8));
            TaskDashboard dashboard=new TaskDashboard(new Stage(),manager,handle);
            java.lang.reflect.Field f=TaskDashboard.class.getDeclaredField("stage");f.setAccessible(true);panel=(Stage)f.get(dashboard);
            panel.getScene().getRoot().applyCss();panel.getScene().getRoot().layout();
            TabPane tabs=(TabPane)panel.getScene().lookup(".tab-pane");if(tabs==null||tabs.getTabs().size()!=9)throw new AssertionError("Expected 9 tabs");
            capture(tabs,new String[]{"记忆解释","多Agent只读复核","Hooks"},0);
        }catch(Throwable t){finish(t);}});
        if(!done.await(45,TimeUnit.SECONDS))throw new AssertionError("offscreen FX timeout");
        if(error.get()!=null){error.get().printStackTrace();System.exit(1);}
        System.out.println("PASS V49 native JavaFX: 9 tabs; memory/review/hooks snapshots; approval stays disabled; no model call");
        Platform.exit();System.exit(0);
    }
    static void capture(TabPane tabs,String[] names,int index){
        try{
            if(index==names.length){if(handle.loop.hooks().status().get("enabled").getAsBoolean())throw new AssertionError("UI auto-approved hooks");finish(null);return;}
            Tab selected=null;for(Tab t:tabs.getTabs())if(names[index].equals(t.getText()))selected=t;
            if(selected==null)throw new AssertionError("missing tab "+names[index]);tabs.getSelectionModel().select(selected);
            PauseTransition pause=new PauseTransition(Duration.millis(400));pause.setOnFinished(e->{try{
                panel.getScene().getRoot().applyCss();panel.getScene().getRoot().layout();
                javafx.scene.Node body=tabs.getSelectionModel().getSelectedItem().getContent();
                if(index==0){ListView<?> list=(ListView<?>)body.lookup(".list-view");if(list==null||list.getItems().isEmpty())throw new AssertionError("memory not loaded");list.getSelectionModel().select(0);}
                else for(javafx.scene.Node n:body.lookupAll(".button"))if(n instanceof Button){Button b=(Button)n;
                    if(index==1&&b.getText().startsWith("刷新只读")||index==2&&b.getText().equals("读取完整配置"))b.fire();}
                PauseTransition loaded=new PauseTransition(Duration.millis(600));loaded.setOnFinished(event->{try{
                    TextArea area=(TextArea)body.lookup(".text-area");if(area==null||area.getText().isEmpty())throw new AssertionError("empty detail in "+names[index]);
                    if(index==2&&!area.getText().contains("SHA-256"))throw new AssertionError("hook preview hash missing");
                    panel.getScene().getRoot().applyCss();panel.getScene().getRoot().layout();
                    ImageIO.write(SwingFXUtils.fromFXImage(panel.getScene().snapshot(null),null),"png",output.resolve("workbench-"+index+".png").toFile());
                    capture(tabs,names,index+1);
                }catch(Throwable t){finish(t);}});loaded.play();
            }catch(Throwable t){finish(t);}});pause.play();
        }catch(Throwable t){finish(t);}
    }
    static void finish(Throwable t){if(t!=null)error.set(t);if(panel!=null)panel.close();if(manager!=null)manager.shutdown();done.countDown();}
}
