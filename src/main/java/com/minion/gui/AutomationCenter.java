package com.minion.gui;

import com.minion.core.agent.LocalJobs;
import com.minion.core.agent.ModelProbe;
import com.minion.core.config.WorkspaceConfig;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Offline automation manager over the existing per-conversation LocalJobs ledger. */
public final class AutomationCenter {
    private final SessionManager manager;
    private final Stage stage=new Stage();
    private final ComboBox<String> projects=new ComboBox<String>();
    private final ComboBox<SessionHandle> sessions=new ComboBox<SessionHandle>();
    private final ListView<LocalJobs.Job> list=new ListView<LocalJobs.Job>();
    private final TextArea details=new TextArea();
    private final Label notice=new Label();
    private final Button add=new Button("新建自动化"),edit=new Button("编辑"),
            toggle=new Button("启用"),remove=new Button("删除");
    private final Timeline clock;
    private LocalJobs ledger;
    private String rendered;

    public AutomationCenter(SessionManager manager,Window owner){
        this.manager=manager;
        if(owner!=null)stage.initOwner(owner);
        stage.setTitle("自动化 · Minion");
        Label heading=new Label("自动化");heading.getStyleClass().add("automation-heading");
        Label intro=new Label("定时运行或监听目录变动。仅在 Minion 打开时调度，不补跑关机期间的任务。");
        intro.getStyleClass().add("automation-muted");intro.setWrapText(true);
        projects.setPromptText("选择项目");sessions.setPromptText("选择绑定对话");
        sessions.setConverter(new StringConverter<SessionHandle>(){
            @Override public String toString(SessionHandle h){
                if(h==null)return "";
                return (h.session.archived?"[归档] ":"")+(h.title==null||h.title.isEmpty()?"新对话":h.title)+" · "+h.id.substring(0,Math.min(8,h.id.length()));
            }
            @Override public SessionHandle fromString(String s){return null;}
        });
        projects.setMaxWidth(Double.MAX_VALUE);sessions.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sessions,Priority.ALWAYS);
        Button refresh=new Button("刷新");
        HBox scope=new HBox(8,projects,sessions,refresh);scope.setAlignment(Pos.CENTER_LEFT);
        list.setPlaceholder(new Label("该对话没有自动化任务"));
        list.setCellFactory(v->new ListCell<LocalJobs.Job>(){
            @Override protected void updateItem(LocalJobs.Job j,boolean empty){
                super.updateItem(j,empty);setGraphic(null);setText(null);
                if(empty||j==null)return;
                Label title=new Label(title(j));title.getStyleClass().add("automation-card-title");
                title.setMaxWidth(215);
                Label meta=new Label(schedule(j)+"  ·  "+status(j));meta.getStyleClass().add("automation-muted");
                meta.setWrapText(true);meta.setMaxWidth(215);setGraphic(new VBox(4,title,meta));
            }
        });
        details.setEditable(false);details.setWrapText(true);details.setPromptText("选择一项自动化查看详情");
        SplitPane content=new SplitPane(list,details);content.setDividerPositions(.38);
        VBox.setVgrow(content,Priority.ALWAYS);
        notice.getStyleClass().add("automation-muted");notice.setWrapText(true);
        HBox actions=new HBox(8,add,edit,toggle,remove);
        add.getStyleClass().add("automation-primary");
        VBox pane=new VBox(12,heading,intro,scope,content,actions,notice);pane.getStyleClass().addAll("automation-center","minion-workbench");
        Theme.apply(pane, Theme.activeId());
        Scene scene=new Scene(pane,730,520);scene.getStylesheets().addAll(Theme.STYLESHEET,Theme.WORKBENCH);stage.setScene(scene);
        stage.setMinWidth(630);stage.setMinHeight(420);
        projects.valueProperty().addListener((o,a,b)->loadSessions(null));
        sessions.valueProperty().addListener((o,a,b)->loadLedger());
        list.getSelectionModel().selectedItemProperty().addListener((o,a,b)->showDetails());
        refresh.setOnAction(e->loadProjects(sessions.getValue()));
        add.setOnAction(e->editor(null));edit.setOnAction(e->editor(selected()));
        toggle.setOnAction(e->toggle());remove.setOnAction(e->remove());
        clock=new Timeline(new KeyFrame(Duration.seconds(4),e->refreshRows()));
        clock.setCycleCount(Timeline.INDEFINITE);
        stage.setOnHidden(e->clock.stop());
        showDetails();
    }
    public void show(){
        if(!stage.isShowing()){loadProjects(manager.currentSession());stage.show();clock.play();}
        stage.toFront();
    }
    private void loadProjects(SessionHandle preferred){
        String project=preferred!=null?preferred.workspaceName:projects.getValue();
        if(project==null)project=manager.workspaces().currentName();
        List<String> names=new ArrayList<String>();
        for(WorkspaceConfig w:manager.workspaces().list())names.add(w.workSpaceName);
        projects.getItems().setAll(names);
        projects.setValue(names.contains(project)?project:names.isEmpty()?null:names.get(0));
        loadSessions(preferred);
    }
    private void loadSessions(SessionHandle preferred){
        String project=projects.getValue();
        sessions.getItems().setAll(project==null?java.util.Collections.emptyList():manager.sessions(project,true));
        sessions.setValue(sessions.getItems().contains(preferred)?preferred:sessions.getItems().isEmpty()?null:sessions.getItems().get(0));
        loadLedger();
    }
    private void loadLedger(){
        ledger=null;rendered=null;
        SessionHandle h=sessions.getValue();
        notice.setText(h==null?"请先创建一个对话，再将自动化绑定到它。":"新增或编辑后默认停用。启用前请核对提示词和只读工具。");
        try{if(h!=null)ledger=manager.jobs(h);}
        catch(Exception e){notice.setText("读取失败："+e.getMessage());}
        refreshRows();
    }
    private void refreshRows(){
        if(sessions.getValue()!=null&&sessions.getValue().deleted){ledger=null;notice.setText("绑定对话已删除，请刷新选择。");}
        String now=ledger==null?"":ledger.json();
        if(now.equals(rendered))return;
        rendered=now;
        String id=selected()==null?null:selected().id;
        list.getItems().setAll(ledger==null?java.util.Collections.emptyList():ledger.snapshot());
        for(LocalJobs.Job j:list.getItems())if(j.id.equals(id)){list.getSelectionModel().select(j);break;}
        showDetails();
    }
    private LocalJobs.Job selected(){return list.getSelectionModel().getSelectedItem();}
    private void showDetails(){
        LocalJobs.Job j=selected();
        boolean active=j!=null&&"dispatched".equals(j.status);
        add.setDisable(ledger==null);edit.setDisable(j==null||active);
        remove.setDisable(j==null||active);toggle.setDisable(j==null||active&&!j.enabled);
        toggle.setText(j!=null&&j.enabled?"暂停":"启用");
        details.setText(j==null?"":title(j)+"\n"+schedule(j)+"\n状态："+status(j)
                +"\n最近触发："+(j.lastRun<=0?"尚未触发":new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(j.lastRun)))
                +"\n工具："+String.join("、",j.allowedTools)+"\n\n"+j.prompt);
    }
    private void toggle(){
        SessionHandle h=sessions.getValue();LocalJobs.Job j=selected();
        if(h==null||j==null)return;
        boolean enable=!j.enabled;
        if(enable&&!confirm("启用「"+title(j)+"」？","将使用所选对话当前模型和指定只读工具执行，不会立即补跑。"))return;
        try{
            manager.jobs(h).setEnabled(j.id,enable,ModelProbe.fingerprint(manager.modelForSession(h)));
            notice.setText(enable?"已启用，等待下一次触发。":"已暂停后续触发；正在执行的轮次不会被强制终止。");
            refreshRows();
        }catch(Exception e){notice.setText("操作失败："+e.getMessage());}
    }
    private void remove(){
        SessionHandle h=sessions.getValue();LocalJobs.Job j=selected();
        if(h==null||j==null||!confirm("删除「"+title(j)+"」？","仅删除这条自动化规则，保留项目及对话历史。"))return;
        try{manager.jobs(h).deleteJob(j.id);notice.setText("已删除规则。");refreshRows();}
        catch(Exception e){notice.setText("删除失败："+e.getMessage());}
    }
    private boolean confirm(String title,String message){
        Alert d=new Alert(Alert.AlertType.CONFIRMATION,message,ButtonType.OK,ButtonType.CANCEL);
        d.setTitle("自动化");d.setHeaderText(title);d.initOwner(stage);Theme.style(d);
        return d.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;
    }
    private void editor(LocalJobs.Job original){
        SessionHandle owner=sessions.getValue();
        if(owner==null||ledger==null)return;
        LocalJobs.Job initial=original==null?new LocalJobs.Job():original;
        Dialog<ButtonType> dialog=new Dialog<ButtonType>();dialog.initOwner(stage);
        dialog.setTitle(original==null?"新建自动化":"编辑自动化");Theme.style(dialog);
        dialog.getDialogPane().getStyleClass().add("automation-center");
        dialog.getDialogPane().setPrefWidth(560);
        ButtonType save=new ButtonType("保存为停用",ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
        TextField name=new TextField(initial.title==null?"":initial.title);name.setPromptText("可选，例如：每日资料巡检");
        ComboBox<String> kind=new ComboBox<String>();kind.getItems().addAll("每隔一段时间","目录文件变化");
        kind.getSelectionModel().select("file".equals(initial.kind)?1:0);
        TextField minutes=new TextField(Integer.toString(initial.intervalMinutes));
        TextField directory=new TextField(initial.watchDir);directory.setPromptText("项目内相对路径或已授权的目录");
        VBox timer=new VBox(4,new Label("间隔（分钟）"),minutes);
        VBox file=new VBox(4,new Label("监听目录（仅第一层，稳定 30 秒后触发）"),directory);
        Runnable visibility=()->{boolean watching=kind.getSelectionModel().getSelectedIndex()==1;
            file.setVisible(watching);file.setManaged(watching);timer.setVisible(!watching);timer.setManaged(!watching);};
        kind.valueProperty().addListener((o,a,b)->visibility.run());visibility.run();
        TextArea prompt=new TextArea(initial.prompt);prompt.setWrapText(true);prompt.setPrefRowCount(5);
        FlowPane tools=new FlowPane(9,6);
        List<CheckBox> checks=new ArrayList<CheckBox>();
        for(String tool:new String[]{"Read","ViewImage","Glob","Grep","Knowledge","Document","Excel"}){
            CheckBox c=new CheckBox(tool);c.setSelected(initial.allowedTools.contains(tool));checks.add(c);tools.getChildren().add(c);
        }
        Label error=new Label();error.setWrapText(true);error.getStyleClass().add("automation-error");
        dialog.getDialogPane().setContent(new VBox(7,new Label("任务名称"),name,new Label("触发方式"),kind,timer,file,
                new Label("要执行的指令"),prompt,new Label("允许的工具（仅只读动作）"),tools,error));
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION,e->{
            try{
                LocalJobs.Job form=new LocalJobs.Job();form.title=name.getText();form.prompt=prompt.getText();
                form.kind=kind.getSelectionModel().getSelectedIndex()==1?"file":"timer";
                form.intervalMinutes="timer".equals(form.kind)?Integer.parseInt(minutes.getText().trim()):60;
                form.watchDir=directory.getText();form.allowedTools.clear();
                for(CheckBox c:checks)if(c.isSelected())form.allowedTools.add(c.getText());
                manager.jobs(owner).saveJob(original==null?null:original.id,form,ModelProbe.fingerprint(manager.modelForSession(owner)));
                notice.setText("已保存为停用。检查无误后可单独启用。");refreshRows();
            }catch(Exception ex){error.setText(ex instanceof NumberFormatException?"间隔须为正整数分钟":ex.getMessage());e.consume();}
        });
        dialog.showAndWait();
    }
    private static String title(LocalJobs.Job j){
        if(j.title!=null&&!j.title.trim().isEmpty())return j.title;
        String text=j.prompt.replaceAll("\\s+"," ").trim();return text.length()>32?text.substring(0,32)+"…":text;
    }
    private static String schedule(LocalJobs.Job j){return "file".equals(j.kind)?"目录变化 · "+j.watchDir:"每 "+j.intervalMinutes+" 分钟";}
    private static String status(LocalJobs.Job j){
        if("dispatched".equals(j.status))return j.enabled?"运行中":"运行中（后续已暂停）";
        if(j.status!=null&&j.status.startsWith("paused:"))return "已暂停 · "+j.status.substring(7).trim();
        if(!j.enabled)return "停用";
        return "completed".equals(j.status)?"上次完成":"等待触发";
    }
}
