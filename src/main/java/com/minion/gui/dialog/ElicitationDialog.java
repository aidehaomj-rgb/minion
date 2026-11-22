package com.minion.gui.dialog;

import com.minion.core.mcp.McpElicitation;
import com.minion.gui.theme.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * v2.4 §3.7 P1：MCP elicitation JavaFX 表单对话框（server 发起的用户输入请求）。
 * 限制字段数（≤8 由 McpElicitation.parseFields 保证）、单字段标签≤60、输入≤500
 * 由 McpElicitation.validate 保证；password 类型掩码渲染（明文不回显但值照常传回
 * server，不落日志）；超时/取消回 decline。模态阻塞（showAndWait）。
 */
public final class ElicitationDialog {

    private ElicitationDialog() { }

    /**
     * 显示表单并等待用户提交；返回 accept（含 content）或 decline。
     * fields 已由 McpElicitation.parseFields 验证（≤8、标签≤60）。
     */
    public static McpElicitation.Response show(Stage owner, String serverName,
            String message, McpElicitation.Field[] fields) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("MCP 输入请求 — " + serverName);
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);

        VBox root = new VBox(10);
        root.setPadding(new Insets(14));
        root.getStyleClass().add("minion-workbench");

        Label msg = new Label(message == null || message.isEmpty()
                ? "服务器需要你提供以下信息：" : message);
        msg.setWrapText(true);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        final TextField[] inputs = new TextField[fields.length];
        for (int i = 0; i < fields.length; i++) {
            McpElicitation.Field f = fields[i];
            Label label = new Label(f.label + (f.required ? " *" : ""));
            if (f.placeholder != null && !f.placeholder.isEmpty()) {
                label.setTooltip(new javafx.scene.control.Tooltip(f.placeholder));
            }
            inputs[i] = "password".equals(f.type) ? new PasswordField() : new TextField();
            inputs[i].setPromptText(f.placeholder == null ? "" : f.placeholder);
            inputs[i].setPrefColumnCount(Math.min(30, Math.max(15, f.label.length() + 10)));
            form.add(label, 0, i);
            form.add(inputs[i], 1, i);
        }

        final McpElicitation.Response[] result = new McpElicitation.Response[1];
        Button submit = new Button("提交");
        submit.getStyleClass().add("btn-approve");
        submit.setDefaultButton(true);
        submit.setOnAction(e -> {
            com.google.gson.JsonObject content = new com.google.gson.JsonObject();
            for (int i = 0; i < fields.length; i++)
                content.addProperty(fields[i].name, inputs[i].getText());
            // 校验（必填/长度）
            com.google.gson.JsonObject validated = McpElicitation.validate(fields, content);
            if (validated == null) {
                msg.setText("必填项不能为空且单值不超过 500 字符，请修正后重试。");
                return;
            }
            result[0] = McpElicitation.Response.accept(validated);
            stage.close();
        });
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("btn-ghost");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> {
            result[0] = McpElicitation.Response.decline();
            stage.close();
        });
        // 关窗（✕）也回 decline
        stage.setOnCloseRequest(e -> {
            if (result[0] == null) result[0] = McpElicitation.Response.decline();
        });

        HBox buttons = new HBox(8, cancel, submit);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().addAll(msg, form, buttons);

        Theme.apply(root, Theme.activeId());
        Scene scene = new Scene(root, 480, 200 + fields.length * 36);
        scene.getStylesheets().addAll(Theme.STYLESHEET, Theme.WORKBENCH);
        stage.setScene(scene);
        if (fields.length > 0) inputs[0].requestFocus();
        stage.showAndWait();
        return result[0] == null ? McpElicitation.Response.decline() : result[0];
    }
}
