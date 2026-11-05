package com.minion.gui;

import com.minion.core.agent.ModelProbe;
import com.minion.core.config.*;
import com.minion.core.llm.*;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.session.*;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 独立运行的真实 JavaFX8 控件检查，不显示窗口、不联网，不在无桌面 CI 自动启动。 */
public final class ReliabilityModelCapabilitiesUiSmoke {
    private static SessionManager manager;private static SessionHandle handle;
    private static ReliabilityPanel panel;private static TextArea output;private static ModelCapabilityStore store;
    private static Path work;
    private static void fx(Runnable action)throws Exception {
        FutureTask<Void> task=new FutureTask<Void>(action,null);Platform.runLater(task);task.get(15,TimeUnit.SECONDS);
    }
    private static Button button(String text) {
        for(Node node:descendants(panel))if(node instanceof Button&&text.equals(((Button)node).getText()))return (Button)node;
        throw new AssertionError("缺少真实控件: "+text);
    }
    private static List<Node> descendants(Node root) {
        List<Node> result=new ArrayList<Node>();result.add(root);
        if(root instanceof TabPane)for(Tab tab:((TabPane)root).getTabs())if(tab.getContent()!=null)result.addAll(descendants(tab.getContent()));
        if(root instanceof Parent)for(Node child:((Parent)root).getChildrenUnmodifiable())result.addAll(descendants(child));
        return result;
    }
    private static void awaitOutput(String expected)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean[] done={false};
        while(System.nanoTime()<deadline) {
            fx(()->done[0]=output.getText().contains(expected));if(done[0])return;
            Thread.sleep(20); // 仅测试轮询异步 GUI 回调，不是产品固定等待。
        }
        throw new AssertionError("GUI输出未出现: "+expected);
    }
    public static void main(String[] args)throws Exception {
        new JFXPanel();int code=0;
        try {
            work=Files.createTempDirectory("minion-batch-e-ui-");
            fx(()->{
                try {
                    WorkspaceManager spaces=WorkspaceManager.load(work);spaces.current().workDir=work.toString();
                    manager=new SessionManager(r->ConfirmUi.Decision.REJECT,Config.load(work),work,
                            spaces,ModelManager.load(work),Collections.emptyList(),null,null);
                    handle=manager.createSession("能力档案GUI离线检查");
                    if(!Paths.get(handle.loop.workspace().workDir()).toRealPath().equals(work.toRealPath()))
                        throw new AssertionError("GUI测试工作空间必须隔离到临时目录");
                    panel=new ReliabilityPanel(new Stage(),manager,handle);new Scene(panel,1000,700);
                    panel.applyCss();panel.layout();
                    TabPane tabs=(TabPane)panel.getChildren().get(0);Tab probe=tabs.getTabs().get(3);tabs.getSelectionModel().select(probe);
                    List<Node> nodes=descendants(probe.getContent());
                    for(Node node:nodes)if(node instanceof TextArea&&!((TextArea)node).isEditable())output=(TextArea)node;
                    if(output==null)throw new AssertionError("缺少结果显示区");
                    if(button("手动模型自检").getOnAction()==null)throw new AssertionError("探测按钮未接事件");
                    store=new ModelCapabilityStore(Paths.get(handle.loop.workspace().workDir()).resolve(".minion/model-capabilities"));
                    if(Files.exists(Paths.get(handle.loop.workspace().workDir()).resolve(".minion/model-capabilities")))throw new AssertionError("初始化不应读取/生成能力档案");
                    button("查看当前能力档案").fire();
                } catch(Exception e){throw new RuntimeException(e);}
            });
            awaitOutput("UNKNOWN");
            ModelConfig model=manager.modelForSession(handle);String fp=ModelProbe.fingerprint(model);long now=System.currentTimeMillis();
            EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State> states=new EnumMap<ModelCapabilityProfile.Capability,ModelCapabilityProfile.State>(ModelCapabilityProfile.Capability.class);
            states.put(ModelCapabilityProfile.Capability.TOOLS,ModelCapabilityProfile.State.SUPPORTED);
            store.save(new ModelCapabilityProfile(fp,fp,now,now+100000,0,states));
            fx(()->button("查看当前能力档案").fire());awaitOutput("SUPPORTED");
            fx(()->button("作废能力档案").fire());awaitOutput("\"current\":false");
            if(store.load(fp).state(ModelCapabilityProfile.Capability.TOOLS,System.currentTimeMillis())!=ModelCapabilityProfile.State.UNKNOWN)throw new AssertionError("作废未持久化");
            fx(()->button("客户端耗时 / token来源").fire());awaitOutput("recentRequests");
            fx(()->button("清除本会话图片拒绝记录").fire());
            if(((DeepSeekClient)handle.llm).telemetry().attempts()!=0)throw new AssertionError("离线查看按钮不应发起请求");
            System.out.println("PASS JavaFX8: probe event wired; profile read/invalidate persisted; telemetry visible; no network requests; hidden 1000x700 layout");
        } catch(Throwable error){error.printStackTrace();code=1;}
        finally {if(manager!=null)manager.shutdown();Platform.exit();}
        System.exit(code);
    }
}
