package com.minion.gui.dialog;
import com.minion.gui.session.*;
import com.minion.gui.theme.Theme;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.event.ActionEvent;

/** 只改仍在队列里的条目；保存不触发模型请求。 */
public final class QueueEditor {
 public static void show(Window owner,SessionManager manager,SessionHandle handle,SessionHandle.QueuedMessage entry) {
  if(entry==null)return;
  Dialog<Void> dialog=new Dialog<Void>();if(owner!=null)dialog.initOwner(owner);dialog.setTitle("编辑待执行/待引导信息");Theme.style(dialog);
  TextArea text=new TextArea(entry.text);text.setWrapText(true);text.setPrefSize(650,280);
  Label error=new Label();error.setWrapText(true);
  dialog.getDialogPane().setContent(new VBox(10,new Label("只保存队列文字；附件保留，不立即发送。已经开始的任务不能回改。"),text,error));
  ButtonType save=new ButtonType("保存",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
  dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION,e->{
   try{manager.editQueuedTask(handle,entry,text.getText());}catch(Exception ex){error.setText(ex.getMessage());e.consume();}
  });dialog.showAndWait();
 }
}
