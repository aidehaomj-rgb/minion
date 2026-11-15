package com.minion.gui;

import com.google.gson.*;
import com.minion.gui.session.*;
import com.minion.gui.theme.Theme;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/** No execution integration here: approval is exclusively a human gesture. */
public final class SessionHooksPane extends VBox {
    private final TextArea preview=new TextArea();
    private final Label status=new Label();
    private String hash;
    private boolean busy;
    public SessionHooksPane(Window owner,SessionManager manager,SessionHandle handle) {
        super(8);preview.setEditable(false);preview.setWrapText(true);
        Button read=new Button("读取完整配置"),approve=new Button("人工批准此 hash（仅本会话）"),disable=new Button("停用本会话 Hooks");approve.setDisable(true);
        Runnable load=()->{if(busy||handle.deleted)return;busy=true;hash=null;approve.setDisable(true);
            manager.background(()->{String value;try{value=handle.loop.hooks().configurationPreview();}catch(Exception e){value="读取失败: "+e.getMessage();}final String out=value;
                Platform.runLater(()->{busy=false;if(handle.deleted)return;preview.setText(out);try{hash=HooksPreviewHash.parse(out);JsonObject config=JsonParser.parseString(out).getAsJsonObject();preview.setText("路径: "+config.get("path").getAsString()+"\nSHA-256: "+hash+"\n\n完整配置 / 命令:\n"+(config.get("config").isJsonPrimitive()?config.get("config").getAsString():new GsonBuilder().setPrettyPrinting().create().toJson(config.get("config"))));approve.setDisable(false);}catch(Exception e){status.setText(e.getMessage());}});});};
        read.setOnAction(e->load.run());
        approve.setOnAction(e->{if(busy||hash==null||handle.deleted)return;final String expected=hash;
            Alert a=new Alert(Alert.AlertType.CONFIRMATION,"已检查下方完整命令及路径？批准 hash="+expected+"。仅本会话有效；配置变更须重新批准。",ButtonType.OK,ButtonType.CANCEL);a.initOwner(owner);Theme.style(a);
            if(a.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;
            busy=true;setDisable(true);manager.background(()->{String result;try{if(handle.deleted)throw new IllegalStateException("会话已删除");handle.loop.hooks().approve(expected);result=handle.loop.hooks().status().toString();}catch(Exception x){result="未批准: "+x.getMessage();}final String out=result;Platform.runLater(()->{busy=false;setDisable(false);hash=null;approve.setDisable(true);status.setText(out);});});});
        disable.setOnAction(e->{if(busy||handle.deleted)return;busy=true;setDisable(true);manager.background(()->{String result;try{if(handle.deleted)throw new IllegalStateException("会话已删除");handle.loop.hooks().disable();result=handle.loop.hooks().status().toString();}catch(Exception x){result="停用失败: "+x.getMessage();}final String out=result;Platform.runLater(()->{busy=false;setDisable(false);hash=null;approve.setDisable(true);status.setText(out);});});});
        getChildren().addAll(new Label("项目 .minion/hooks.json 默认关闭；没有自动批准。先读取完整命令与 hash，再人工确认。"),new HBox(8,read,approve,disable),preview,status);VBox.setVgrow(preview,Priority.ALWAYS);
    }
}
