package com.minion.gui;

import com.minion.core.context.ProjectMemoryStore;
import com.minion.gui.session.*;
import com.minion.gui.theme.Theme;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import java.nio.file.Paths;

/** Project-local management; deliberately no global store or verification button. */
public final class ProjectMemoryPane extends VBox {
    private final SessionHandle handle;
    private final SessionManager manager;
    private final ProjectMemoryStore store;
    private final ListView<ProjectMemoryStore.Entry> list = new ListView<ProjectMemoryStore.Entry>();
    private final TextArea detail = new TextArea();
    private final Label state = new Label();
    private boolean busy;
    private Boolean lastAuto;
    private final Button autoToggle=new Button("自动候选：未知（读取中）");
    private static final String SCOPE_NOTE="仅按当前已知 scope 解释（任务/修订；path 未提供）。路径限定项显示 not_applicable 仅表示本次缺少匹配路径，不代表永久失效；列表状态也不是完整范围核验。";
    private void showState(String message){state.setText("自动候选最后确认状态："+(lastAuto==null?"未知":lastAuto?"开启":"关闭")+"；"+message);}

    public ProjectMemoryPane(Window owner, SessionManager manager, SessionHandle handle) {
        super(8); this.manager=manager; this.handle=handle;
        store=new ProjectMemoryStore(Paths.get(handle.loop.workspace().workDir()));
        detail.setEditable(false); detail.setWrapText(true);
        list.setCellFactory(v -> new ListCell<ProjectMemoryStore.Entry>() {
            protected void updateItem(ProjectMemoryStore.Entry e, boolean empty) {
                super.updateItem(e,empty); setText(empty||e==null?null:e.id+" ["+e.state+"] "+e.text);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o,a,e)->{if(e!=null) explain(e.id);});
        Button reload=new Button("刷新本项目"), edit=new Button("编辑（降为待核验）"), remove=new Button("删除到回收区"), toggle=new Button("切换所选启用/关闭"), auto=autoToggle;
        reload.setOnAction(e->reload());
        edit.setOnAction(ev->{ProjectMemoryStore.Entry e=selected();if(e==null)return;
            Dialog<ButtonType> d=new Dialog<ButtonType>();d.initOwner(owner);Theme.style(d);d.setTitle("编辑项目记忆（不会核验）");
            TextArea text=new TextArea(e.text);TextField source=new TextField(e.source);
            d.getDialogPane().setContent(new VBox(8,new Label("内容 / 来源；修改前版本保留审计"),text,source));
            d.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
            if(d.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK){String body=text.getText(),origin=source.getText();work(()->store.edit(e.id,body,origin));}
        });
        remove.setOnAction(ev->{ProjectMemoryStore.Entry e=selected();if(e==null)return;
            Alert a=new Alert(Alert.AlertType.CONFIRMATION,"删除本项目记忆 "+e.id+"？移至 memory/trash；不会恢复被替代旧决定。",ButtonType.OK,ButtonType.CANCEL);a.initOwner(owner);Theme.style(a);
            if(a.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK)work(()->store.forget(e.id));});
        toggle.setOnAction(ev->{ProjectMemoryStore.Entry e=selected();if(e!=null)work(()->store.setDisabled(e.id,!e.disabled));});
        auto.setOnAction(ev->work(()->{store.setAutoExtractEnabled(!store.autoExtractEnabled());return null;}));
        FlowPane controls=new FlowPane(8,8,reload,edit,remove,toggle,auto);
        Label scopeNotice=new Label("仅绑定本会话项目；启用/编辑不会重新核验。只解释现有资格，不更新验收。\n"+SCOPE_NOTE);
        scopeNotice.setWrapText(true);state.setWrapText(true);
        getChildren().addAll(scopeNotice,controls,state,list,detail);
        VBox.setVgrow(detail,Priority.ALWAYS);reload();
    }
    private ProjectMemoryStore.Entry selected(){return busy||handle.deleted?null:list.getSelectionModel().getSelectedItem();}
    private ProjectMemoryStore.Scope scope(){ProjectMemoryStore.Scope s=new ProjectMemoryStore.Scope();synchronized(handle.session.taskState){s.taskId=handle.session.taskState.id;s.revision=handle.session.taskState.revision();}return s;}
    private void explain(String id){final ProjectMemoryStore.Scope s=scope();manager.background(()->{String value;try{value=store.explain(id,s);}catch(Exception e){value=e.getMessage();}final String out=value;Platform.runLater(()->{ProjectMemoryStore.Entry e=list.getSelectionModel().getSelectedItem();if(!handle.deleted&&e!=null&&id.equals(e.id))detail.setText(SCOPE_NOTE+"\n\n"+out);});});}
    private void work(java.util.concurrent.Callable<?> action){if(busy||handle.deleted)return;busy=true;setDisable(true);showState("正在处理，开关尚未重新读取；请勿将最后确认状态视为操作结果");manager.background(()->{String error=null;try{if(handle.deleted)throw new IllegalStateException("会话已删除");action.call();}catch(Exception e){error=e.getMessage();}final String message=error;Platform.runLater(()->{busy=false;setDisable(false);if(message!=null)showState("未完成: "+message+"；当前开关需重新读取确认");else reload();});});}
    private void reload(){if(busy||handle.deleted)return;busy=true;setDisable(true);showState("正在读取本项目；以上为最后确认状态");manager.background(()->{try{final java.util.List<ProjectMemoryStore.Entry> entries=store.list();final boolean auto=store.autoExtractEnabled();Platform.runLater(()->{busy=false;setDisable(false);if(handle.deleted)return;lastAuto=auto;autoToggle.setText("自动候选："+(auto?"开启（点击关闭）":"关闭（点击开启）"));list.getItems().setAll(entries);detail.clear();showState("项目: "+handle.loop.workspace().workDir()+"；候选始终待核验");});}catch(Exception e){Platform.runLater(()->{busy=false;setDisable(false);showState("读取失败: "+e.getMessage()+"；当前状态未知，以上仅最后确认值");});}});}
}
