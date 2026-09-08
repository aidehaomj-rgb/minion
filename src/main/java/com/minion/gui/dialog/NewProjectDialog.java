package com.minion.gui.dialog;

import com.minion.core.config.WorkspaceManager;
import com.minion.gui.theme.Theme;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** 从模板创建全新项目，并返回创建参数；实际写盘由 MainWindow 确认后执行。 */
public final class NewProjectDialog extends Dialog<NewProjectDialog.Value> {
    public static final class Value {
        public final String name, template;
        public final Path projectDir;
        Value(String name, String template, Path projectDir) {
            this.name=name; this.template=template; this.projectDir=projectDir;
        }
    }

    public NewProjectDialog(List<String> existingNames) {
        setTitle("新建项目");
        setHeaderText("创建项目文件并立即在 Agent 中打开");
        Theme.style(this);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setMinWidth(620);

        TextField name=new TextField(); name.setPromptText("例如：销售数据看板");
        TextField parent=new TextField(); parent.setPromptText("选择项目保存位置");
        Button browse=new Button("浏览…"); browse.getStyleClass().add("btn-ghost");
        browse.setOnAction(e->{DirectoryChooser dc=new DirectoryChooser();File cur=new File(parent.getText().trim());if(cur.isDirectory())dc.setInitialDirectory(cur);File f=dc.showDialog(getOwner());if(f!=null)parent.setText(f.getAbsolutePath());});
        HBox parentRow=new HBox(6,parent,browse); HBox.setHgrow(parent,Priority.ALWAYS);
        ComboBox<String> template=new ComboBox<String>();
        template.getItems().addAll("blank — 空白项目","python-data — Excel/数据分析","flask-dashboard — 内网数据看板","flask — Python 网页","web — 原生网页","pwa — 离线 App","sqlite — 数据库应用","pyinstaller — Windows EXE","java-maven — Java 8");
        template.getSelectionModel().select(0); template.setMaxWidth(Double.MAX_VALUE);
        GridPane grid=new GridPane();grid.setHgap(8);grid.setVgap(10);grid.setPadding(new Insets(10));
        grid.addRow(0,new Label("项目名称:"),name);grid.addRow(1,new Label("保存位置:"),parentRow);grid.addRow(2,new Label("起始模板:"),template);
        getDialogPane().setContent(grid);
        Button ok=(Button)getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(Bindings.createBooleanBinding(()->!WorkspaceManager.isValidName(name.getText().trim(),existingNames)||!new File(parent.getText().trim()).isDirectory(),name.textProperty(),parent.textProperty()));
        setResultConverter(bt->{if(bt!=ButtonType.OK)return null;String selected=template.getValue();String id=selected.substring(0,selected.indexOf(' '));Path dir=Paths.get(parent.getText().trim()).resolve(name.getText().trim()).toAbsolutePath().normalize();return new Value(name.getText().trim(),id,dir);});
    }
}
