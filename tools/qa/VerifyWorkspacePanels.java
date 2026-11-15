package com.minion.gui;

import com.minion.core.agent.GeneratedArtifact;
import com.minion.core.config.*;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.session.*;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.Node;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Test-only native FX8 screen captures, with generated fixtures and no model request. */
public final class VerifyWorkspacePanels {
    static Stage stage;
    static MainWindow window;
    static SessionManager manager;
    static SessionHandle opened;
    static ArtifactPreviewPane pane;
    static Path output, fixture, report;
    static Object field(Object target,String name)throws Exception{
        java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static <T>T fx(Callable<T> task)throws Exception{
        FutureTask<T> call=new FutureTask<>(task);Platform.runLater(call);return call.get(15,TimeUnit.SECONDS);
    }
    static void waitFor(Callable<Boolean> done)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<end){if(fx(done))return;Thread.sleep(40);}
        throw new AssertionError("UI did not settle");
    }
    static GeneratedArtifact artifact(Path p,String kind){
        GeneratedArtifact a=new GeneratedArtifact();a.path=p.toString();a.kind=kind;a.title=p.getFileName().toString();return a;
    }
    static void snapshot(String name)throws Exception{fx(()->{
        capture(name,stage.getScene());return null;
    });}
    static void capture(String name, javafx.scene.Scene scene)throws Exception {
        Parent root=scene.getRoot();root.applyCss();root.layout();
        ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null),null),"png",output.resolve(name+".png").toFile());
    }
    static Stage windowWith(String title) {
        for (Iterator<javafx.stage.Window> it=javafx.stage.Window.impl_getWindows();it.hasNext();) {
            javafx.stage.Window w=it.next();
            if(w instanceof Stage && ((Stage)w).getTitle()!=null && ((Stage)w).getTitle().startsWith(title)) return (Stage)w;
        }
        return null;
    }
    static void checkSecondaryWindows() throws Exception {
        fx(()->{
            Button automation=(Button)stage.getScene().getRoot().lookupAll(".sidebar-navigation .sidebar-nav").stream()
                    .filter(n->n instanceof Button && "自动化".equals(((Button)n).getText())).findFirst().get();
            automation.fire();return null;
        });
        waitFor(()->windowWith("自动化")!=null);
        fx(()->{Stage w=windowWith("自动化");capture("automation",w.getScene());w.hide();return null;});
        fx(()->{
            for(Node n:stage.getScene().getRoot().lookupAll(".workspace-top-action"))
                if(n instanceof Button && "任务".equals(((Button)n).getText())) ((Button)n).fire();
            return null;
        });
        waitFor(()->windowWith("任务面板")!=null);
        fx(()->{
            Stage w=windowWith("任务面板");
            javafx.scene.control.TabPane tabs=(javafx.scene.control.TabPane)w.getScene().lookup(".tab-pane");
            int i=0;
            for(javafx.scene.control.Tab tab:tabs.getTabs()) {
                tabs.getSelectionModel().select(tab);
                capture("task-" + (++i),w.getScene());
                if(tab.getContent().getBoundsInParent().getHeight()<120)
                    throw new AssertionError("task page collapsed: "+tab.getText());
            }
            w.hide();return null;
        });
        FutureTask<Void> settings = new FutureTask<>(()->{
            Button open=(Button)stage.getScene().lookup("#sidebar-settings");
            open.fire();return null;
        });
        Platform.runLater(settings);
        waitFor(()->windowWith("设置")!=null);
        fx(()->{
            Stage w=windowWith("设置");
            javafx.scene.control.ListView<?> nav=(javafx.scene.control.ListView<?>)w.getScene().lookup("#settings-nav");
            try {
                for(int i=0;i<nav.getItems().size();i++){
                    nav.getSelectionModel().select(i);capture("settings-"+(i+1),w.getScene());
                }
            } finally {
                javafx.scene.control.DialogPane dialog=(javafx.scene.control.DialogPane)w.getScene().lookup(".settings-workbench");
                ((Button)dialog.lookupButton(javafx.scene.control.ButtonType.CLOSE)).fire();
            }
            return null;
        });
        settings.get(10,TimeUnit.SECONDS);
    }
    static void assertComposerInConversation() {
        Parent root=stage.getScene().getRoot();root.applyCss();root.layout();
        Node composer=root.lookup(".input-frame");
        Node workspace=root.lookup(".artifact-workspace");
        if(composer==null||workspace==null)throw new AssertionError("missing split content");
        Bounds input=composer.localToScene(composer.getBoundsInLocal());
        Bounds reportBounds=workspace.localToScene(workspace.getBoundsInLocal());
        if(input.getWidth()<220||input.getMaxX()>reportBounds.getMinX())
            throw new AssertionError("composer overlaps the file workspace: "+input);
        assertComposerRespectsColumn(root);
    }
    static void assertComposerRespectsColumn(Parent root) {
        Node column=root.lookup(".conversation-column");
        Node composer=root.lookup(".input-frame");
        Bounds columnBounds=column.localToScene(column.getBoundsInLocal());
        Bounds input=composer.localToScene(composer.getBoundsInLocal());
        double available=columnBounds.getWidth()-40;
        double expectedWidth=Math.min(available,Math.min(960,Math.max(500,available*.618)));
        double leftGap=input.getMinX()-columnBounds.getMinX();
        double rightGap=columnBounds.getMaxX()-input.getMaxX();
        if(Math.abs(input.getWidth()-expectedWidth)>2 || Math.abs(leftGap-rightGap)>2)
            throw new AssertionError("composer should be centered and proportionate in its own column: "+input+", column "+columnBounds);
    }
    static void assertReadableWideLayout() {
        Parent root=stage.getScene().getRoot();root.applyCss();root.layout();
        Node column=root.lookup(".conversation-column");
        Node user=root.lookup(".transcript-user");
        assertComposerRespectsColumn(root);
        if(user.getLayoutBounds().getWidth()<1400 || column.getLayoutBounds().getWidth()-user.getLayoutBounds().getWidth()>52)
            throw new AssertionError("transcript unexpectedly became narrow: "+user.getLayoutBounds());
        javafx.scene.control.TextArea editor=(javafx.scene.control.TextArea)root.lookup(".input-textarea");
        javafx.scene.control.TextArea reply=(javafx.scene.control.TextArea)root.lookup(".transcript-reply .msg-textarea");
        javafx.scene.control.Label sidebar=(javafx.scene.control.Label)root.lookup(".sidebar-row-label");
        if(editor.getFont().getSize()<18||reply.getFont().getSize()<18||sidebar.getFont().getSize()<15)
            throw new AssertionError("workbench fonts fell below readable defaults");
        if(root.lookupAll(".artifact-message-link").size()!=2 || root.lookup(".message-link-items").isManaged())
            throw new AssertionError("file notifications or multi-link reply expanded into the transcript");
        Button historical=(Button)root.lookup("#artifact-archive-button");
        if (!historical.isVisible() || !historical.getText().endsWith("20"))
            throw new AssertionError("historical artifacts were not summarized");
    }
    public static void main(String[] args)throws Exception{
        output=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(output);fixture=Files.createTempDirectory(output,"fixture-");
        final String theme=args.length>1?args[1]:"graphite";
        Path project=Files.createDirectory(fixture.resolve("项目文件"));
        Files.createDirectory(project.resolve("原始资料"));
        report=project.resolve("月度分析报告.md");Path run=project.resolve("任务执行记录.md");
        String prose="本次对项目资料进行了逐项核对，并按所属章节整合可追溯的证据。交付内容已整理为可直接查看的报告，后续可从项目文件区打开原始资料。";
        Files.write(report,("# 月度分析报告\n\n"+prose+prose+"\n\n## 核心成果\n\n| 事项 | 处理结果 |\n|---|---|\n| 数据检查 | 已汇总需要优先回看的一组异常情况 |\n| 文档归档 | 已形成审阅稿 |\n\n## 下一步\n\n- 核对异常项\n- 与原件交叉复核").getBytes(StandardCharsets.UTF_8));
        Files.write(run,"# 任务执行记录\n\n测试记录，不是交付物。".getBytes(StandardCharsets.UTF_8));
        WorkspaceManager workspaces=WorkspaceManager.load(fixture);
        workspaces.update("default",project.toString(),"",null);workspaces.add("调研与资料",project.toString(),"",null);
        new JFXPanel();Platform.setImplicitExit(false);
        try{
            fx(()->{
                Config config=Config.load(fixture);java.lang.reflect.Field f=MinionApp.class.getDeclaredField("config");f.setAccessible(true);f.set(null,config);
                config.set("ui.theme",theme);
                manager=new SessionManager(request->ConfirmUi.Decision.REJECT,config,fixture,workspaces,
                        ModelManager.load(fixture),Collections.emptyList(),
                        new com.minion.core.tools.plugin.ToolPluginManager(com.minion.core.tools.plugin.ToolStore.load(fixture)),
                        new com.minion.core.mcp.McpManager(com.minion.core.mcp.McpStore.load(fixture)));
                manager.createSession("历史资料复核");manager.createSession("异常情况筛选");opened=manager.createSession("月度材料整理");
                stage=new Stage();stage.setX(-16000);stage.setY(10);window=new MainWindow(stage,manager);window.show();
                stage.setWidth(1230);stage.setHeight(760);
                return null;
            });
            snapshot("welcome");
            fx(()->{assertComposerRespectsColumn(stage.getScene().getRoot());return null;});
            fx(()->{manager.openSessionAcrossProjects(opened,null);return null;});
            waitFor(()->field(window,"chatView")!=null);
            fx(()->{
                opened.controller.onUserMessage("请把本月资料整理成报告，按核心成果和下一步分节，并附上需要复核的原始文件。");
                JsonObject argsJson=new JsonObject();argsJson.addProperty("path","原始资料/材料清单.md");
                opened.controller.onToolCall("Read",argsJson);
                opened.controller.onToolResult("Read",ToolResult.success("已读取材料清单。"));
                opened.controller.onContent("已完成资料整理。可在右侧打开月度分析报告，切换阅读或文本，也可以通过目录跳转。\n\n"
                        +"另见 [月度分析报告]("+report.toUri().toASCIIString()+") 和 [任务执行记录]("+run.toUri().toASCIIString()+")。");
                opened.controller.onResponseEnd();
                opened.controller.eventList().add(new EventList.Ev(EventList.Kind.ARTIFACT,report.toString(),null));
                java.util.List<GeneratedArtifact> historical = new java.util.ArrayList<>();
                for (int i=0;i<20;i++) historical.add(artifact(project.resolve("旧报告"+i+".md"),"deliverable"));
                historical.add(artifact(run,"execution_record"));
                opened.session.artifacts=historical;
                opened.controller.replayArtifacts(historical);
                return null;
            });
            snapshot("conversation");
            if(args.length>2&&"theme-only".equals(args[2])){
                checkSecondaryWindows();
                FutureTask<Void> openSettings=new FutureTask<>(()->{
                    ((Button)stage.getScene().lookup("#sidebar-settings")).fire();return null;
                });
                Platform.runLater(openSettings);
                waitFor(()->windowWith("设置")!=null);
                fx(()->{
                    Stage settings=windowWith("设置");
                    capture("settings-"+theme,settings.getScene());
                    @SuppressWarnings("unchecked")
                    javafx.scene.control.ComboBox<com.minion.gui.theme.Theme.Variant> picker=
                            (javafx.scene.control.ComboBox<com.minion.gui.theme.Theme.Variant>)settings.getScene().lookup("#ui-theme-picker");
                    if(picker==null||picker.getValue()==null||!theme.equals(picker.getValue().id))
                        throw new AssertionError("theme picker did not reflect "+theme);
                    String next="light".equals(theme)?"obsidian":"light";
                    picker.setValue(com.minion.gui.theme.Theme.variant(next));
                    Node mainRoot=stage.getScene().getRoot().lookup(".minion-workbench");
                    if(mainRoot==null||!mainRoot.getStyleClass().contains("theme-"+next))
                        throw new AssertionError("theme change was not applied live");
                    capture("settings-switched-"+next,settings.getScene());
                    settings.hide();
                    return null;
                });
                System.out.println("PASS theme-only FX8 render and live picker: "+theme);
                return;
            }
            fx(()->{
                Parent root=stage.getScene().getRoot();
                assertComposerRespectsColumn(root);
                double width=root.lookup(".project-sidebar").getLayoutBounds().getWidth();
                if(width<239||width>249)throw new AssertionError("unexpected default project rail width: "+width);
                return null;
            });
            fx(()->{stage.setWidth(2200);stage.setHeight(900);return null;});
            waitFor(()->stage.getScene().getWidth()>=2190);
            snapshot("widescreen");
            fx(()->{assertReadableWideLayout();stage.setWidth(1230);stage.setHeight(760);return null;});
            waitFor(()->stage.getScene().getWidth()<=1231);
            fx(()->{
                ((Button)stage.getScene().lookup("#artifact-archive-button")).fire();
                pane=(ArtifactPreviewPane)field(window,"artifactPreview");
                return null;
            });
            waitFor(()->{
                javafx.scene.control.MenuButton index=(javafx.scene.control.MenuButton)field(pane,"files");
                return index.isShowing() && index.getItems().stream().anyMatch(i ->
                        i instanceof javafx.scene.control.Menu && ((javafx.scene.control.Menu)i).getItems().size()==20);
            });
            fx(()->{
                assertComposerInConversation();
                double width=pane.getLayoutBounds().getWidth();
                if(width<380||width>410)throw new AssertionError("unexpected default report width: "+width);
                ((javafx.scene.control.MenuButton)field(pane,"files")).hide();
                ((Button)field(window,"reportButton")).fire();
                opened.session.artifacts=null;
                return null;
            });
            fx(()->{
                ((Button)field(window,"reportButton")).fire();
                pane=(ArtifactPreviewPane)field(window,"artifactPreview");
                pane.setWorkspaceRoot(project);
                pane.setArtifacts(Arrays.asList(artifact(report,"deliverable"),artifact(run,"execution_record")));
                pane.open(report.toString());
                return null;
            });
            waitFor(()->field(pane,"loaded")!=null);snapshot("workbench");
            fx(()->{
                assertComposerInConversation();
                javafx.scene.control.Hyperlink displayedPath=(javafx.scene.control.Hyperlink)field(pane,"pathLink");
                if(!displayedPath.getText().equals(report.getFileName().toString())||
                   !displayedPath.getTooltip().getText().contains(report.toString()))
                    throw new AssertionError("project-relative display must preserve the actual full path");
                if(displayedPath.isManaged()||((javafx.scene.control.Label)field(pane,"fileTitle")).getContextMenu()==null)
                    throw new AssertionError("redundant path shown or title has no file menu");
                ((javafx.scene.control.TextArea)stage.getScene().lookup(".input-textarea")).setText("未发送的本地草稿");
                javafx.scene.layout.VBox body=(javafx.scene.layout.VBox)field(pane,"body");
                TextFlow paragraph=(TextFlow)body.getChildren().stream().filter(TextFlow.class::isInstance).findFirst().get();
                if(paragraph.getWidth()<220||paragraph.getHeight()>350)throw new AssertionError("viewport collapsed: "+paragraph.getWidth());
                javafx.scene.text.Text proseSpan=(javafx.scene.text.Text)paragraph.getChildren().get(0);
                if(proseSpan.getFont().getSize()<17)throw new AssertionError("report prose too small");
                javafx.scene.control.TabPane tabs=(javafx.scene.control.TabPane)field(pane,"tabs");
                if(tabs.getHeight()<28||tabs.getTabs().size()!=1)throw new AssertionError("execution record or tab strip leaked into workspace");
                ((SplitPane)field(window,"workspaceSplit")).setDividerPositions(.51);
                stage.getScene().getRoot().layout();
                ((ToggleButton)field(pane,"focus")).fire();return null;
            });snapshot("expanded");
            fx(()->{
                if(stage.getScene().lookup(".input-frame")!=null)throw new AssertionError("composer remains in preview focus mode");
                ((ToggleButton)field(pane,"focus")).fire();
                double restored=((SplitPane)field(window,"workspaceSplit")).getDividerPositions()[0];
                if(Math.abs(restored-.51)>.015)throw new AssertionError("lost manual preview divider: "+restored);
                stage.setWidth(960);stage.setHeight(640);
                return null;
            });
            waitFor(()->stage.getScene().getWidth()<=961);
            fx(()->{
                javafx.scene.control.TextArea input=(javafx.scene.control.TextArea)stage.getScene().lookup(".input-textarea");
                if(input==null||!input.getText().equals("未发送的本地草稿"))throw new AssertionError("preview focus discarded draft");
                input.clear();return null;
            });
            snapshot("narrow");
            fx(()->{
                ((ToggleButton)field(pane,"source")).setSelected(true);
                return null;
            });
            snapshot("source");
            fx(()->{
                javafx.scene.control.TextArea text=(javafx.scene.control.TextArea)field(pane,"textView");
                if(!text.isVisible()||!text.getText().contains("月度分析报告")||text.getFont().getSize()<17)
                    throw new AssertionError("plain text preview unreadable");
                ((javafx.scene.control.MenuItem)field(pane,"find")).fire();
                javafx.scene.control.TextField search=(javafx.scene.control.TextField)field(pane,"search");
                search.setText("核心成果");
                search.fireEvent(new javafx.event.ActionEvent());
                if(!"核心成果".equals(text.getSelectedText()))throw new AssertionError("search in preview failed");
                return null;
            });
            snapshot("source-search");
            fx(()->{assertComposerInConversation();((Button)field(pane,"browseFiles")).fire();return null;});
            waitFor(()->pane.lookup(".workspace-files .list-cell:filled")!=null);snapshot("project-files");
            checkSecondaryWindows();
            System.out.println("PASS native FX8 workspace, bounded composer and full transcript, wide/narrow layout, archive, files, preview/search, task/automation/settings pages.");
        } finally{
            if(stage!=null)fx(()->{manager.shutdown();stage.hide();return null;});Platform.exit();
        }
    }
}
