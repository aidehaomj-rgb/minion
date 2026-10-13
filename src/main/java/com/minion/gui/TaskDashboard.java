package com.minion.gui;

import com.minion.core.agent.TaskState;
import com.minion.core.config.ModelConfig;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.util.*;

/** An explicitly scoped, non-modal view; polling never traverses live LLM messages. */
public final class TaskDashboard {
    private final SessionManager manager;
    private final SessionHandle handle;
    private final Stage stage=new Stage();
    private final TextArea task=area(), budget=area();
    private final Label feedback=new Label();
    private final TableView<TaskState.Child> agents=new TableView<TaskState.Child>();
    private final ListView<String> queue=new ListView<String>();
    private final Timeline timer;
    private List<SessionHandle.QueuedMessage> queued=Collections.emptyList();

    public TaskDashboard(Stage owner,SessionManager manager,SessionHandle handle) {
        this.manager=manager; this.handle=handle;
        stage.initOwner(owner); stage.setTitle("任务面板 — "+(handle.title==null?handle.id:handle.title));
        TabPane tabs=new TabPane(tab("主任务与断点",taskPane()),tab("子 Agent",agentsPane()),
                tab("上下文预算",contextPane()),tab("待执行队列",queuePane()),tab("旁支提问",sidePane()));
        VBox root=new VBox(10,tabs,feedback); root.setPadding(new Insets(12)); VBox.setVgrow(tabs,Priority.ALWAYS);
        Scene scene=new Scene(root,1000,700); scene.getStylesheets().add(Theme.STYLESHEET); stage.setScene(scene);
        timer=new Timeline(new KeyFrame(Duration.seconds(1),e -> refresh())); timer.setCycleCount(Timeline.INDEFINITE);
        stage.setOnHidden(e -> timer.stop()); refresh();
    }
    public void show() { stage.show(); timer.play(); }
    private static TextArea area() { TextArea a=new TextArea(); a.setEditable(false); a.setWrapText(true); return a; }
    private static Tab tab(String title,javafx.scene.Node body) { Tab t=new Tab(title,body); t.setClosable(false); return t; }
    private Button button(String text,Runnable action) { Button b=new Button(text); b.setOnAction(e -> {
        try { action.run(); } catch(Exception ex) { feedback.setText(ex.getMessage()); }
    }); return b; }
    private VBox taskPane() {
        Button verify=button("核验断点文件",() -> {
            feedback.setText("正在核验文件哈希…");
            manager.background(() -> {
                String result=handle.loop.authorizedCheckpointStatus();
                Platform.runLater(() -> {
                    feedback.setText("核验完成；哈希一致不代表业务结论正确");
                    if(!stage.isShowing()) return;
                    Alert a=new Alert(Alert.AlertType.INFORMATION); a.initOwner(stage); Theme.style(a); a.setHeaderText("断点文件核验");
                    TextArea output=area(); output.setText(result); output.setPrefSize(700,400); a.getDialogPane().setContent(output); a.show();
                });
            });
        });
        VBox body=new VBox(10,new Label("暂停保留记录；恢复时核对断点，不重新派发整个任务。"),
                new HBox(8,button("暂停主任务",() -> manager.stop(handle)),button("继续主任务",() -> {
                    if(handle.running) { feedback.setText("任务正在运行"); return; }
                    manager.send(handle,"继续未完成的任务");
                }),verify),task); VBox.setVgrow(task,Priority.ALWAYS); return body;
    }
    private void column(String label,java.util.function.Function<TaskState.Child,String> value,int width) {
        TableColumn<TaskState.Child,String> c=new TableColumn<TaskState.Child,String>(label);
        c.setCellValueFactory(v -> new ReadOnlyStringWrapper(value.apply(v.getValue()))); c.setPrefWidth(width); agents.getColumns().add(c);
    }
    private TaskState.Child selected() { TaskState.Child c=agents.getSelectionModel().getSelectedItem(); if(c==null) throw new IllegalArgumentException("请先选择子Agent"); return c; }
    private VBox agentsPane() {
        column("ID",c -> c.id.substring(0,Math.min(8,c.id.length())),75);
        column("分工",c -> c.assignment,200); column("状态",c -> c.status,160);
        column("模式 / 模型",c -> c.mode+" / "+c.model,155);
        column("上下文",c -> c.contextTokens+" / "+c.maxContextTokens,115);
        column("累计 in / out",c -> c.inputTokens+" / "+c.outputTokens+(c.usageEstimated?" (估算)":""),150);
        ComboBox<String> model=models(true); model.setValue(handle.session.subAgentModelName==null?"跟随主模型":handle.session.subAgentModelName);
        ComboBox<String> mode=new ComboBox<String>(FXCollections.observableArrayList("worker","explore")); mode.setValue(handle.session.subAgentMode);
        TextField tools=new TextField(handle.session.subAgentTools); tools.setPromptText("白名单，逗号分隔；留空=模式默认工具"); HBox.setHgrow(tools,Priority.ALWAYS);
        Button save=button("保存默认分工设置",() -> {
            manager.configureAgentDefaults(handle,"跟随主模型".equals(model.getValue())?null:model.getValue(),mode.getValue(),tools.getText());
            feedback.setText("只影响本会话之后创建的子Agent；已有子Agent保留原模型和权限");
        });
        Button review=button("登记已核对",() -> {
            if(handle.running) throw new IllegalArgumentException("请等待主任务结束后手动登记核对");
            TaskState.Child c=selected(); TextInputDialog dialog=new TextInputDialog(); dialog.initOwner(stage); Theme.style(dialog);
            dialog.setHeaderText("请先检查文件和结果，填写实际核对的证据；这不是自动正确性认证");
            dialog.showAndWait().filter(s -> !s.trim().isEmpty()).ifPresent(note -> {
                if(handle.session.taskState.reviewChild(c.id)) { handle.session.taskState.recordEvidence("user","child-review",true,note,null); manager.saveSettings(handle); feedback.setText("已登记人工核对"); }
                else feedback.setText("只能核对已返回的子Agent");
            });
        });
        Button record=button("查看记录摘要",() -> {
            TaskState.Child c=selected(); Alert dialog=new Alert(Alert.AlertType.INFORMATION);
            dialog.initOwner(stage); Theme.style(dialog); dialog.setTitle("子Agent记录"); dialog.setHeaderText(c.assignment);
            TextArea text=area(); text.setText("ID: "+c.id+"\n完整JSON记录: "+c.transcript+"\n\n"+c.result); text.setPrefSize(650,350);
            dialog.getDialogPane().setContent(text); dialog.show();
        });
        VBox body=new VBox(10,new Label("explore=只读探索；worker=允许获批工具。恢复只补缺项；重试开启新一轮并归档旧记录。"),
                new HBox(8,model,mode,tools,save),agents,new HBox(8,
                button("停止",() -> feedback.setText(manager.controlSubAgent(handle,selected().id,"stop"))),
                button("恢复",() -> feedback.setText(manager.controlSubAgent(handle,selected().id,"resume"))),
                button("重试",() -> {
                    TaskState.Child c=selected(); Alert a=new Alert(Alert.AlertType.CONFIRMATION,"重试会从分工重新开始，可能重复操作。确认已核对现有文件？",ButtonType.OK,ButtonType.CANCEL);
                    a.initOwner(stage); Theme.style(a); if(a.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK) feedback.setText(manager.controlSubAgent(handle,c.id,"retry"));
                }),review,record)); VBox.setVgrow(agents,Priority.ALWAYS); return body;
    }
    private ComboBox<String> models(boolean inherit) {
        ComboBox<String> result=new ComboBox<String>(); if(inherit) result.getItems().add("跟随主模型");
        for(ModelConfig m:manager.models().list()) result.getItems().add(m.displayName);
        result.setValue(inherit?"跟随主模型":manager.modelForSession(handle).displayName); return result;
    }
    private VBox contextPane() {
        ComboBox<String> profile=new ComboBox<String>(FXCollections.observableArrayList("balanced","compact")); profile.setValue(handle.session.contextProfile);
        TextField window=new TextField(String.valueOf(handle.session.preferredContextLimit)); window.setPrefWidth(140);
        Button save=button("保存",() -> {
            manager.configureContext(handle,profile.getValue(),Integer.parseInt(window.getText().trim()));
            feedback.setText("设置已保存；预算在下一次模型请求时刷新，服务端实际限制仍需实测");
        });
        Label modelCompression=new Label("自动压缩比例按模型配置：设置 → 模型 → 修改 → 自动压缩阈值 (%)。\n这里的安全窗口0=自动；旧会话受实测窗口限制时，确认服务端已调整再重置。/compact仍可手动使用。");
        modelCompression.setWrapText(true);
        VBox body=new VBox(10,new Label("balanced=均衡，compact=节省工具输出/旧思考。安全窗口0=自动；不能超过模型配置窗口。"),modelCompression,
                new HBox(8,new Label("请求策略"),profile,new Label("本会话安全窗口"),window,save,button("重置学到的窗口",() -> {
                    Alert a=new Alert(Alert.AlertType.CONFIRMATION,"只有确认服务端窗口已增加时才重置，以免再次超限。继续？",ButtonType.OK,ButtonType.CANCEL);
                    a.initOwner(stage); Theme.style(a); if(a.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK) { manager.resetObservedContext(handle); feedback.setText("已清除会话实测安全窗口，将按本会话设置重新判断"); }
                })),budget);
        VBox.setVgrow(budget,Priority.ALWAYS); return body;
    }
    private VBox queuePane() {
        VBox body=new VBox(10,new Label("等待主任务成功完成后顺序作为新任务发送；失败/暂停时保留，不自动串到旧任务。"),queue,
                button("取消所选",() -> {
                    int index=queue.getSelectionModel().getSelectedIndex(); if(index<0) throw new IllegalArgumentException("请选择任务");
                    manager.cancelQueuedTask(handle,queued.get(index));
                })); VBox.setVgrow(queue,Priority.ALWAYS); return body;
    }
    private VBox sidePane() {
        TextArea question=new TextArea(); question.setWrapText(true); question.setPrefRowCount(3); question.setPromptText("仅问答，不执行工具，不改变主任务");
        TextArea answer=area(); ComboBox<String> model=models(false); ListView<SessionManager.SideQuestion> history=new ListView<SessionManager.SideQuestion>(); history.setPrefHeight(110);
        history.setCellFactory(list -> new ListCell<SessionManager.SideQuestion>() { protected void updateItem(SessionManager.SideQuestion q,boolean empty) { super.updateItem(q,empty); setText(empty||q==null?null:q.question); } });
        Runnable reload=() -> manager.background(() -> { List<SessionManager.SideQuestion> items=manager.sideQuestionHistory(handle); Platform.runLater(() -> history.setItems(FXCollections.observableArrayList(items))); });
        history.getSelectionModel().selectedItemProperty().addListener((obs,old,q) -> { if(q!=null) answer.setText(q.answer+"\n\n记录: "+q.record); });
        Button ask=new Button("单独提问"); ask.setOnAction(e -> {
            try { ask.setDisable(true); answer.setText("旁支模型正在回答，不影响主任务…");
                manager.askSideQuestion(handle,question.getText(),model.getValue(),q -> Platform.runLater(() -> { answer.setText(q.answer+"\n\n记录: "+q.record); ask.setDisable(false); reload.run(); }));
            } catch(Exception ex) { ask.setDisable(false); feedback.setText(ex.getMessage()); }
        });
        reload.run(); VBox body=new VBox(10,new Label("只携带最多6000字符的任务背景；没有文件访问，也不注入主任务历史。回答单独落盘。"),
                question,new HBox(8,model,ask,button("刷新提问历史",reload)),answer,history); VBox.setVgrow(answer,Priority.ALWAYS); return body;
    }
    private static void setIfChanged(TextArea area,String value) { if(!area.getText().equals(value)) { double scroll=area.getScrollTop(); area.setText(value); area.setScrollTop(scroll); } }
    private void refresh() {
        if(handle.deleted) { stage.close(); return; }
        setIfChanged(task,"执行状态: "+(handle.running?"运行中":"空闲/暂停")+handle.session.taskState.render(null));
        setIfChanged(budget,handle.loop.contextBudget());
        String selected=agents.getSelectionModel().getSelectedItem()==null?null:agents.getSelectionModel().getSelectedItem().id;
        agents.setItems(FXCollections.observableArrayList(handle.session.taskState.childSnapshot()));
        if(selected!=null) for(TaskState.Child c:agents.getItems()) if(selected.equals(c.id)) agents.getSelectionModel().select(c);
        List<SessionHandle.QueuedMessage> latest=handle.queuedSnapshot();
        if(!sameQueue(latest,queued)) { queued=latest; List<String> lines=new ArrayList<String>(); for(SessionHandle.QueuedMessage q:queued) lines.add((q.guidance?"[待引导/新任务] ":"")+q.text); queue.setItems(FXCollections.observableArrayList(lines)); }
    }
    private static boolean sameQueue(List<SessionHandle.QueuedMessage> a,List<SessionHandle.QueuedMessage> b) {
        if(a.size()!=b.size()) return false; for(int i=0;i<a.size();i++) if(a.get(i).createdAt!=b.get(i).createdAt||!a.get(i).text.equals(b.get(i).text)) return false; return true;
    }
}
