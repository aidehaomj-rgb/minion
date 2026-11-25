package com.minion.gui;

import com.minion.core.agent.GeneratedArtifact;
import com.minion.gui.artifact.ArtifactSelection;
import com.minion.gui.artifact.DocumentPreviewLoader;
import com.minion.gui.artifact.ArtifactDocumentNodes;
import com.minion.gui.artifact.ArtifactThumbnailService;
import com.minion.gui.artifact.LocalFileLinks;
import com.minion.gui.artifact.OfficePreviewConverter;
import com.minion.gui.artifact.PagedPdfPreview;
import com.minion.gui.artifact.PagedSlideViewer;
import com.minion.gui.artifact.PptxCardViewer;
import com.minion.gui.artifact.WorkbookPreviewView;
import com.minion.gui.artifact.ZoomableImageView;
import com.minion.gui.chat.MarkdownRenderer;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Offline, bounded, asynchronous artifact workspace. Never executes HTML/macros or modifies source files. */
final class ArtifactPreviewPane extends VBox implements AutoCloseable {
    interface Loader { DocumentPreviewLoader.Result load(Path path)throws Exception; }
    /** F4 手动工作区扫描：scan 列出交付文件，adopt 走 late 展示登记通道（MainWindow 注入）。 */
    interface WorkspaceScanner { java.util.List<Path> scan()throws Exception; void adopt(Path path); }
    private WorkspaceScanner scanner;
    private final Button scan=new Button("扫描工作区文件");
    private static final int MAX_TABS=8;
    private final Label fileTitle=new Label();
    private final Hyperlink pathLink=new Hyperlink();
    private final Label status=new Label();
    private final Label notice=new Label();
    private final MenuButton files=new MenuButton("成果");
    private final CheckMenuItem follow=new CheckMenuItem("跟随本会话的新成果");
    private final ToggleButton rendered=new ToggleButton("阅读"),original=new ToggleButton("原版"),source=new ToggleButton("文本");
    private final MenuButton outline=new MenuButton("目录");
    private final MenuButton more=new MenuButton("⋯");
    private final MenuItem find=new MenuItem("查找文本…");
    private final Menu historyMenu=new Menu("历史版本");
    private final TabPane tabs=new TabPane();
    private final VBox body=new VBox(13);
    private final ScrollPane scroll=new ScrollPane(body);
    private final TextArea textView=new TextArea();
    private final TextField search=new TextField();
    private final Label searchStatus=new Label();
    private final HBox findBar=new HBox(6);
    private final WebView htmlView=new WebView();
    private final StackPane pagedHost=new StackPane();
    private final StackPane workbookHost=new StackPane();
    private final StackPane viewport = new StackPane(scroll,textView,htmlView,pagedHost,workbookHost);
    private VBox documentView;
    private final VBox overviewView;
    private final FlowPane overviewGrid=new FlowPane(10,10);
    private final FlowPane gallery=new FlowPane(6,6);
    private final Map<Path,Tab> openTabs=new LinkedHashMap<Path,Tab>();
    private final Map<Path,ViewState> viewStates=new HashMap<Path,ViewState>();
    private final Map<Path,GeneratedArtifact> artifacts=new LinkedHashMap<Path,GeneratedArtifact>();
    private final DoubleBinding textWidth=Bindings.createDoubleBinding(
            ()->Math.max(160,scroll.getViewportBounds().getWidth()-body.getPadding().getLeft()-body.getPadding().getRight()),
            scroll.viewportBoundsProperty(),body.paddingProperty());
    private final LocalFileLinks links;
    private final Loader loader;
    private final ThreadPoolExecutor reader=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(8),r->{
        Thread t=new Thread(r,"minion-document-preview");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ThreadPoolExecutor conversion=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(8),r->{
        Thread t=new Thread(r,"minion-office-conversion");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.DiscardOldestPolicy());
    /** F3：同 key 待执行任务合并（cancel 旧 Future 再入队），替代盲目 DiscardOldest 静默丢任务 */
    private final ConcurrentHashMap<String,Future<?>> poolTasks=new ConcurrentHashMap<String,Future<?>>();
    static final int MAX_LOAD_RETRY=3;
    private static final long RETRY_DELAY_MS=500;
    private Future<?> pending;
    private long generation;
    private long pagedGeneration;
    private boolean updating;
    private boolean restoring;
    private boolean active;
    private boolean closed;
    private Path current;
    private DocumentPreviewLoader.Result loaded;
    private Path convertedPdf;
    private String conversionEngine;
    private boolean conversionAttempted;
    private String extractedText="";
    private boolean textExtractionAttempted;
    private boolean textExtractionLoading;
    private Node pagedNode;
    private Node workbookNode;
    private List<MarkdownRenderer.Block> blocks=Collections.emptyList();
    private Consumer<Path> selectionListener=p->{};
    private Consumer<Boolean> focusAction=b->{};
    private final ToggleButton focus=new ToggleButton("⤢");
    private java.util.function.Function<String,ContextMenu> linkMenus;

    ArtifactPreviewPane(Runnable closeAction,LocalFileLinks links){this(closeAction,links,DocumentPreviewLoader::load);}
    ArtifactPreviewPane(Runnable closeAction,LocalFileLinks links,Loader loader) {
        this.links=links;this.loader=loader;this.linkMenus=links::menu;
        setMinWidth(300);setMinHeight(180);getStyleClass().add("artifact-workspace");
        gallery.getStyleClass().add("artifact-gallery");
        fileTitle.getStyleClass().add("artifact-file-title");fileTitle.setMinWidth(0);
        fileTitle.setMaxWidth(Double.MAX_VALUE);fileTitle.setTextOverrun(OverrunStyle.ELLIPSIS);
        pathLink.getStyleClass().add("artifact-path");pathLink.setWrapText(false);pathLink.setMinWidth(0);pathLink.setMaxWidth(Double.MAX_VALUE);
        pathLink.visibleProperty().bind(pathLink.textProperty().isNotEmpty().and(pathLink.textProperty().isNotEqualTo(fileTitle.textProperty())));
        pathLink.managedProperty().bind(pathLink.visibleProperty());
        pathLink.setOnAction(e->{if(current!=null)links.open(current.toUri().toString());});
        fileTitle.contextMenuProperty().bind(pathLink.contextMenuProperty());
        status.getStyleClass().add("artifact-meta");status.setMinWidth(0);status.setWrapText(true);
        notice.getStyleClass().add("artifact-notice");notice.setWrapText(true);
        notice.visibleProperty().bind(notice.textProperty().isNotEmpty());notice.managedProperty().bind(notice.visibleProperty());
        files.setId("artifact-list");files.setTooltip(new Tooltip("按类型查找本会话的成果"));
        files.showingProperty().addListener((o,a,b)->{if(b)updateArtifactMenu();});updateArtifactMenu();
        follow.setSelected(true);
        ToggleGroup modes=new ToggleGroup();rendered.setToggleGroup(modes);original.setToggleGroup(modes);source.setToggleGroup(modes);rendered.setSelected(true);
        modes.selectedToggleProperty().addListener((o,a,b)->{if(b==null){if(a!=null)a.setSelected(true);return;}if(!restoring)render();});
        Button open=new Button("打开文件");open.setId("artifact-open");open.setOnAction(e->{if(current!=null)links.open(current.toUri().toString());});
        scan.setId("artifact-scan");scan.setTooltip(new Tooltip("扫描工作区中尚未登记的交付文件（仅手动触发，不自动扫描）"));
        scan.setOnAction(e->runWorkspaceScan());scan.setVisible(false);scan.setManaged(false);
        MenuItem refresh=new MenuItem("刷新预览");refresh.setOnAction(e->{rememberView();reload();});
        MenuItem location=new MenuItem("打开文件位置");location.setId("artifact-reveal");location.setOnAction(e->{if(current!=null)links.reveal(current.toUri().toString());});
        MenuItem copy=new MenuItem("复制预览文本");copy.setOnAction(e->{if(loaded!=null){ClipboardContent c=new ClipboardContent();c.putString(previewText());Clipboard.getSystemClipboard().setContent(c);}});
        MenuItem copyPath=new MenuItem("复制完整路径");copyPath.setOnAction(e->{if(current!=null){ClipboardContent c=new ClipboardContent();c.putString(current.toString());Clipboard.getSystemClipboard().setContent(c);}});
        MenuItem save=new MenuItem("另存为…");save.setOnAction(e->{if(current!=null)links.saveAs(current);});
        more.setAccessibleText("更多文件操作");more.setTooltip(new Tooltip("刷新、打开文件位置、复制或另存为"));
        more.getItems().addAll(find,refresh,location,copy,copyPath,save,new SeparatorMenuItem(),historyMenu,follow);
        more.showingProperty().addListener((o,a,b)->{if(b){updateHistoryMenu();refresh.setDisable(current==null);location.setDisable(current==null);copyPath.setDisable(current==null);
            copy.setDisable(loaded==null||previewText().isEmpty());save.setDisable(current==null);}});
        focus.getStyleClass().add("artifact-icon-button");focus.setAccessibleText("放大或还原右侧工作区");focus.setTooltip(new Tooltip("放大工作区 / 还原分栏"));
        focus.setOnAction(e->focusAction.accept(focus.isSelected()));
        Button close=new Button("×");close.getStyleClass().add("artifact-icon-button");close.setAccessibleText("关闭右侧工作区");
        close.setTooltip(new Tooltip("关闭右侧工作区"));close.setOnAction(e->closeAction.run());
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        HBox heading=new HBox(6,files,spacer,focus,close);heading.setAlignment(Pos.CENTER_LEFT);heading.getStyleClass().add("artifact-workspace-bar");
        tabs.setId("artifact-tabs");tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        tabs.getStyleClass().add("artifact-tabs");
        tabs.setMinHeight(39);tabs.setPrefHeight(39);tabs.setMaxHeight(39);
        tabs.visibleProperty().bind(Bindings.isNotEmpty(tabs.getTabs()));tabs.managedProperty().bind(tabs.visibleProperty());
        tabs.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{
                if(!updating&&b!=null){follow.setSelected(false);activate((Path)b.getUserData());}
        });
        FlowPane actions=new FlowPane(6,5,rendered,original,source,outline,open,scan,more);actions.getStyleClass().add("artifact-actions");
        actions.visibleProperty().bind(fileTitle.textProperty().isNotEmpty());
        actions.managedProperty().bind(actions.visibleProperty());
        outline.visibleProperty().bind(outline.disableProperty().not());
        outline.managedProperty().bind(outline.visibleProperty());
        find.setOnAction(e->{if(findBar.isVisible())setFindVisible(false);else if(loaded!=null&&!loaded.sourceText.isEmpty()){
            source.setSelected(true);setFindVisible(true);search.requestFocus();
        }});
        search.setPromptText("查找文本");
        search.setMinWidth(0);HBox.setHgrow(search,Priority.ALWAYS);
        search.setOnAction(e->findNext());
        search.textProperty().addListener((o,a,b)->searchStatus.setText(""));
        search.setOnKeyPressed(e->{if(e.getCode()==javafx.scene.input.KeyCode.ESCAPE){setFindVisible(false);textView.requestFocus();e.consume();}});
        Button next=new Button("下一个");next.setOnAction(e->findNext());
        searchStatus.getStyleClass().add("artifact-meta");
        findBar.getStyleClass().add("artifact-find");findBar.setAlignment(Pos.CENTER_LEFT);findBar.getChildren().addAll(search,next,searchStatus);
        setFindVisible(false);
        VBox fileHeader=new VBox(5,fileTitle,status,pathLink,actions,gallery,findBar,notice);fileHeader.getStyleClass().add("artifact-file-header");
        fileHeader.visibleProperty().bind(fileTitle.textProperty().isNotEmpty());
        fileHeader.managedProperty().bind(fileHeader.visibleProperty());
        body.setPadding(new Insets(19,20,28,20));body.setFillWidth(true);body.setMinWidth(0);
        body.getStyleClass().add("artifact-document");
        body.prefWidthProperty().bind(Bindings.createDoubleBinding(()->Math.max(200,scroll.getViewportBounds().getWidth()),scroll.viewportBoundsProperty()));
        scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);scroll.setFocusTraversable(false);
        scroll.getStyleClass().add("artifact-reader");scroll.setMinHeight(0);
        textView.setId("artifact-source");textView.setEditable(false);textView.setWrapText(true);
        textView.getStyleClass().add("artifact-source");textView.setMinSize(0,0);textView.setMaxSize(Double.MAX_VALUE,Double.MAX_VALUE);
        textView.setVisible(false);textView.setManaged(false);viewport.setMinSize(0,0);VBox.setVgrow(viewport,Priority.ALWAYS);
        htmlView.getStyleClass().add("artifact-html");htmlView.setVisible(false);htmlView.setManaged(false);
        htmlView.setContextMenuEnabled(false);htmlView.getEngine().setJavaScriptEnabled(false);
        htmlView.getEngine().setCreatePopupHandler(features->null);
        htmlView.getEngine().locationProperty().addListener((o,a,b)->{
            if(b!=null&&(b.startsWith("http:")||b.startsWith("https:")||b.startsWith("file:"))){
                htmlView.getEngine().getLoadWorker().cancel();
                notice.setText("HTML 外链导航已被阻止；离线预览只显示本地静态内容。");
            }
        });
        documentView=new VBox(tabs,fileHeader,viewport);
        documentView.setMinSize(0,0);
        VBox.setVgrow(documentView,Priority.ALWAYS);
        overviewGrid.setPrefWrapLength(540);
        ScrollPane overviewScroll=new ScrollPane(overviewGrid);
        overviewScroll.setFitToWidth(true);overviewScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        overviewScroll.getStyleClass().add("artifact-overview-scroll");
        overviewView=new VBox(overviewScroll);overviewView.getStyleClass().add("artifact-overview");
        VBox.setVgrow(overviewScroll,Priority.ALWAYS);
        StackPane content=new StackPane(documentView,overviewView);content.setMinSize(0,0);VBox.setVgrow(content,Priority.ALWAYS);
        getChildren().addAll(heading,content);
        open.disableProperty().bind(fileTitle.textProperty().isEmpty());
        resetDocument();
        rebuildOverview();showOverview(true);
    }
    private void updateArtifactMenu(){
        files.getItems().clear();
        for(Map.Entry<Path,GeneratedArtifact> row:artifacts.entrySet()){
            Path path=row.getKey();
            files.getItems().add(fileMenuRow(path,shortTitle(path.getFileName().toString(),54),
                    ()->{files.hide();follow.setSelected(false);open(path.toString());}));
        }
        if(files.getItems().isEmpty()){MenuItem empty=new MenuItem("暂无成果");empty.setDisable(true);files.getItems().add(empty);}
    }
    private void showOverview(boolean show){
        if(overviewView==null||documentView==null)return;
        overviewView.setVisible(show);overviewView.setManaged(show);
        documentView.setVisible(!show);documentView.setManaged(!show);
    }
    private void rebuildOverview(){
        overviewGrid.getChildren().clear();
        if(artifacts.isEmpty()){
            Label empty=hint("暂无成果");
            empty.getStyleClass().add("artifact-overview-empty");
            overviewGrid.getChildren().add(empty);return;
        }
        int thumbnails=0;
        for(Map.Entry<Path,GeneratedArtifact> row:artifacts.entrySet()){
            boolean coverArtifact=isImage(row.getKey())||isPdfOrPresentation(row.getKey());
            boolean thumbnail=coverArtifact&&safePreviewSize(row.getKey())&&thumbnails++<24;
            overviewGrid.getChildren().add(overviewCard(row.getKey(),row.getValue(),thumbnail));
        }
    }
    private Node overviewCard(Path path,GeneratedArtifact item,boolean thumbnail){
        VBox card=new VBox(7);
        card.getStyleClass().add("artifact-card");card.setPrefWidth(170);
        StackPane visual=new StackPane();visual.getStyleClass().add("artifact-card-visual");
        visual.setPrefSize(154,76);visual.setMinHeight(76);visual.setMaxHeight(76);
        if(thumbnail) {
            ImageView image=new ImageView();
            image.setFitWidth(154);image.setFitHeight(76);image.setPreserveRatio(true);image.setSmooth(true);
            visual.getChildren().add(image);
            if(isImage(path)) {
                image.setImage(new Image(path.toUri().toString(),154,76,true,true,true));
            } else {
                Label pending=new Label(typeLabel(path));pending.getStyleClass().add("artifact-card-type");
                visual.getChildren().add(pending);
                ArtifactThumbnailService.load(path,fxImage->{
                    image.setImage(fxImage);
                    visual.getChildren().remove(pending);
                });
            }
        }else{
            Label type=new Label(typeLabel(path));type.getStyleClass().add("artifact-card-type");
            visual.getChildren().add(type);
        }
        Label name=new Label(item.title==null||item.title.trim().isEmpty()?path.getFileName().toString():item.title);
        name.setWrapText(false);name.setTextOverrun(OverrunStyle.ELLIPSIS);name.setMaxWidth(Double.MAX_VALUE);
        name.getStyleClass().add("artifact-card-name");
        Label meta=new Label(typeLabel(path)+" · "+new java.text.SimpleDateFormat("MM-dd HH:mm")
                .format(new Date(item.updatedAt<=0?System.currentTimeMillis():item.updatedAt)));
        meta.getStyleClass().add("artifact-card-meta");
        card.getChildren().addAll(visual,name,meta);
        Button button=new Button();button.setGraphic(card);button.setMaxWidth(Double.MAX_VALUE);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);button.getStyleClass().add("artifact-card-button");
        button.setTooltip(new Tooltip(path.toString()));button.setOnAction(e->{follow.setSelected(false);open(path.toString());});
        return button;
    }
    private void updateGallery(){
        gallery.getChildren().clear();
        boolean show=current!=null&&isImage(current)&&artifacts.size()>1;
        gallery.setVisible(show);gallery.setManaged(show);
        if(!show)return;
        int count=0;
        for(Map.Entry<Path,GeneratedArtifact> row:artifacts.entrySet()){
            Path path=row.getKey();if(!isImage(path)||!safePreviewSize(path)||count++>=12)continue;
            ImageView image=new ImageView(new Image(path.toUri().toString(),60,44,true,true,true));
            image.setFitWidth(60);image.setFitHeight(44);image.setPreserveRatio(true);
            Button button=new Button();button.setGraphic(image);button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.getStyleClass().add("artifact-gallery-item");button.setTooltip(new Tooltip(path.getFileName().toString()));
            if(path.equals(current))button.getStyleClass().add("selected");
            button.setOnAction(e->{follow.setSelected(false);open(path.toString());});
            gallery.getChildren().add(button);
        }
    }
    private void updateHistoryMenu(){
        historyMenu.getItems().clear();
        GeneratedArtifact item=current==null?null:artifacts.get(current);
        int count=item==null||item.history==null?0:item.history.size();
        historyMenu.setText(count==0?"历史版本":"历史版本 ("+count+")");
        historyMenu.setDisable(count==0);
        if(count==0)return;
        for(int i=item.history.size()-1;i>=0;i--){
            GeneratedArtifact.Version version=item.history.get(i);
            if(version==null||version.snapshotPath==null)continue;
            String label="v"+(version.revision==null?"?":version.revision)+" · "
                    +new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date(version.updatedAt))
                    +" · "+sizeText(version.size);
            MenuItem row=new MenuItem(label);
            row.setOnAction(e->open(version.snapshotPath));
            historyMenu.getItems().add(row);
        }
    }
    private static String typeLabel(Path path){
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        if(name.matches(".*\\.(png|jpg|jpeg|gif|bmp)$"))return "图片";
        if(name.endsWith(".docx")||name.endsWith(".doc"))return "WORD";
        if(name.endsWith(".pptx")||name.endsWith(".ppt"))return "PPT";
        if(name.endsWith(".xlsx")||name.endsWith(".xls")||name.endsWith(".csv"))return "表格";
        if(name.endsWith(".md")||name.endsWith(".markdown"))return "MD";
        if(name.endsWith(".pdf"))return "PDF";
        int dot=name.lastIndexOf('.');return dot<0?"文档":name.substring(dot+1).toUpperCase(Locale.ROOT);
    }
    private static boolean isImage(Path path){
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.matches(".*\\.(png|jpg|jpeg|gif|bmp)$");
    }
    private static boolean isPdfOrPresentation(Path path){
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf")||name.endsWith(".pptx")||name.endsWith(".ppt")
                ||name.endsWith(".ppsx")||name.endsWith(".pps");
    }
    private static boolean safePreviewSize(Path path){
        long limit=isImage(path)?DocumentPreviewLoader.MAX_DIRECT_IMAGE_BYTES
                :DocumentPreviewLoader.MAX_DOCX_BYTES;
        try{return Files.isRegularFile(path)&&Files.size(path)<=limit;}
        catch(Exception ignored){return false;}
    }
    private static String shortNotice(DocumentPreviewLoader.Result result){
        if(result.truncated)return "预览已截取";
        if(result.markdown.isEmpty()&&result.images.isEmpty())return result.notice;
        if(htmlFormat(result.format))return "离线静态预览";
        if("pdf".equals(result.format))return "全页分页预览";
        if("pptx".equals(result.format)&&result.previewPdf!=null)return "全页幻灯片预览";
        return "";
    }
    private CustomMenuItem fileMenuRow(Path path,String name,Runnable select) {
        HBox row=new HBox(8);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("artifact-menu-row");
        Label label=new Label(name);label.setMinWidth(0);label.setTooltip(new Tooltip(path.toString()));
        HBox.setHgrow(label,Priority.ALWAYS);label.setMaxWidth(Double.MAX_VALUE);
        Button options=new Button("⋯");options.setAccessibleText("文件操作");options.getStyleClass().add("artifact-icon-button");
        Runnable showMenu=()->{
            files.hide();
            ContextMenu context=linkMenus.apply(path.toUri().toString());
            if(context!=null)context.show(files,javafx.geometry.Side.BOTTOM,0,0);
        };
        options.setOnAction(e->showMenu.run());row.setOnContextMenuRequested(e->{showMenu.run();e.consume();});
        // CustomMenuItem's selection event is also used by keyboard.
        CustomMenuItem menu=new CustomMenuItem(row,true);menu.setOnAction(e->select.run());
        label.setOnMouseClicked(e->{if(e.getButton()==javafx.scene.input.MouseButton.PRIMARY){select.run();e.consume();}});
        row.getChildren().addAll(label,options);return menu;
    }
    void setSelectionListener(Consumer<Path> listener){selectionListener=listener==null?p->{}:listener;}
    void setLinkMenus(java.util.function.Function<String,ContextMenu> factory){linkMenus=factory==null?links::menu:factory;}
    void setFocusAction(Consumer<Boolean> action){focusAction=action;}
    /** F4：注入扫描器才显示按钮（配置关 = 不注入 = 零行为变化）。 */
    void setWorkspaceScanner(WorkspaceScanner value){
        scanner=value;scan.setVisible(value!=null);scan.setManaged(value!=null);
    }
    private void runWorkspaceScan(){
        if(scanner==null)return;
        final WorkspaceScanner active=scanner;
        notice.setText("正在扫描工作区文件…");
        submitMerged(reader,"workspace-scan",()->{
            java.util.List<Path> found;
            try{found=active.scan();}
            catch(Exception e){Platform.runLater(()->notice.setText("扫描失败："+(e.getMessage()==null?e.toString():e.getMessage())));return;}
            Platform.runLater(()->{notice.setText("");showScanResults(unregistered(found));});
        });
    }
    /** 过滤掉已在登记表的文件（纯逻辑，便于测试）。 */
    java.util.List<Path> unregistered(java.util.List<Path> scanned){
        java.util.List<Path> out=new ArrayList<Path>();
        if(scanned==null)return out;
        for(Path p:scanned){
            try{Path n=p.toAbsolutePath().normalize();if(!artifacts.containsKey(n))out.add(n);}
            catch(RuntimeException invalid){/* Old receipts can contain invalid paths. */}
        }
        return out;
    }
    private void showScanResults(java.util.List<Path> files){
        Dialog<Void> dialog=new Dialog<Void>();
        dialog.setTitle("扫描工作区文件");
        dialog.setHeaderText(files.isEmpty()?"没有未登记的交付文件"
                :"发现 "+files.size()+" 个未登记文件（登记仅进展示面，不影响任务判定）");
        ListView<Path> list=new ListView<Path>();
        list.getItems().setAll(files);
        list.setPlaceholder(new Label("工作区交付文件均已登记"));
        list.setCellFactory(v->new ListCell<Path>(){
            @Override protected void updateItem(Path p,boolean empty){
                super.updateItem(p,empty);
                if(empty||p==null){setGraphic(null);setText(null);return;}
                Label name=new Label(p.getFileName().toString());name.setMinWidth(0);
                HBox.setHgrow(name,Priority.ALWAYS);name.setMaxWidth(Double.MAX_VALUE);
                name.setTooltip(new Tooltip(p.toString()));
                Label badge=new Label("未登记");badge.getStyleClass().add("artifact-meta");
                Button adopt=new Button("登记并预览");
                adopt.setOnAction(e->{if(scanner!=null)scanner.adopt(p);dialog.close();open(p.toString());});
                HBox row=new HBox(8,name,badge,adopt);row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);setText(null);
            }
        });
        list.setPrefSize(460,300);
        dialog.getDialogPane().setContent(list);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }
    void showArtifactIndex(){if(files.getScene()!=null)files.show();}
    void setFocusedMode(boolean value){focus.setSelected(value);}
    void setArtifacts(List<GeneratedArtifact> entries) {
        artifacts.clear();
        if(entries!=null)for(GeneratedArtifact item:entries){
            if(!ArtifactSelection.presentable(item))continue;
            try{artifacts.putIfAbsent(Paths.get(item.path).toAbsolutePath().normalize(),item);}
            catch(RuntimeException invalid){/* Old receipts can contain invalid paths. */}
        }
        files.setText(artifacts.isEmpty()?"成果":"成果 · "+artifacts.size());
        updateArtifactMenu();
        rebuildOverview();updateGallery();updateHistoryMenu();
        if(current==null)showOverview(true);
        if(current!=null&&loaded!=null)updateMeta();
    }
    boolean followsNewArtifacts(){return follow.isSelected();}
    Path currentPath(){return current;}
    void openUpdated(String path){
        Path before=current;
        boolean same=false;
        try{same=active&&before!=null&&before.equals(Paths.get(path).toAbsolutePath().normalize());}
        catch(RuntimeException invalid){/* The normal open path reports malformed links. */}
        open(path);
        if(same){rememberView();reload();}
    }
    void open(String path) {
        try {
            if(path==null||path.trim().isEmpty())return;
            Path next=Paths.get(path).toAbsolutePath().normalize();
            Tab tab=openTabs.get(next);
            updating=true;
            try{
                if(tab==null){
                    if(openTabs.size()>=MAX_TABS){
                        Path remove=openTabs.keySet().stream().filter(p->!p.equals(current)).findFirst().orElse(null);
                        if(remove!=null){tabs.getTabs().remove(openTabs.remove(remove));viewStates.remove(remove);}
                    }
                    tab=new Tab(shortTitle(next.getFileName().toString(),25));
                    tab.setTooltip(new Tooltip(next.toString()));tab.setUserData(next);
                    tab.setOnClosed(e->{
                        openTabs.remove(next);viewStates.remove(next);
                        if(next.equals(current)){
                            Tab remaining=tabs.getSelectionModel().getSelectedItem();
                            if(remaining==null){suspend();current=null;resetDocument();status.setText("请选择一个成果");showOverview(true);}
                            else activate((Path)remaining.getUserData());
                        }
                    });
                    openTabs.put(next,tab);tabs.getTabs().add(tab);
                }
                tabs.getSelectionModel().select(tab);
            }
            finally {updating=false;}
            activate(next);
        }
        catch(RuntimeException invalid){showError("文件路径无效："+invalid.getMessage());}
    }
    private void activate(Path next){
        if(active&&Objects.equals(next,current))return;
        rememberView();current=next;active=true;
        showOverview(false);updateGallery();updateHistoryMenu();
        ViewState state=viewStates.get(next);restoring=true;
        try{if(state!=null&&state.source)source.setSelected(true);else rendered.setSelected(true);}
        finally{restoring=false;}
        selectionListener.accept(current);reload();
    }
    private void rememberView(){
        if(current!=null&&loaded!=null&&openTabs.containsKey(current))
            viewStates.put(current,new ViewState(source.isSelected(),scroll.getVvalue(),textView.getScrollTop()));
    }
    void clear(){
        suspend();current=null;updating=true;try{tabs.getTabs().clear();}finally{updating=false;}
        openTabs.clear();viewStates.clear();setArtifacts(null);updateArtifactMenu();resetDocument();follow.setSelected(true);
        showOverview(true);
    }
    private void resetDocument(){
        loaded=null;blocks=Collections.emptyList();convertedPdf=null;conversionEngine=null;conversionAttempted=false;
        extractedText="";textExtractionAttempted=false;textExtractionLoading=false;
        clearPagedNode();fileTitle.setText("");pathLink.setText("");
        pathLink.setTooltip(null);pathLink.setContextMenu(null);textView.clear();outline.getItems().clear();
        notice.setText("");status.setText("");showSource(false);setFindVisible(false);search.clear();
        body.getChildren().setAll(hint("从上方「成果」选择文件进行预览。"));
        outline.setDisable(true);setModeAvailability(false);hideViewportModes();scroll.setVisible(true);scroll.setManaged(true);scroll.setVvalue(0);
    }
    private void reload() {
        final Path path=current;final long request=++generation;if(pending!=null)pending.cancel(true);
        if(path==null){resetDocument();return;}
        resetDocument();scroll.setVvalue(0);
        fileTitle.setText(path.getFileName().toString());fileTitle.setTooltip(new Tooltip(path.getFileName().toString()));
        pathLink.setText(path.toString());
        pathLink.setContextMenu(linkMenus.apply(path.toUri().toString()));
        pathLink.setTooltip(new Tooltip(path.toString()+"\n点击打开；右键定位文件或复制路径"));
        fileTitle.setTooltip(new Tooltip(path.toString()+"\n右键打开、定位或复制路径"));
        notice.setText("");status.setText("正在读取本地文件…");
        body.getChildren().setAll(hint("正在载入预览…"));
        pending=submitMerged(reader,path.toString(),()->loadAndApply(request,path,0));
    }
    /** F3：加载主体（首次 attempt=0；重试递增）。generation 守卫逐字保留。 */
    private void loadAndApply(long request,Path path,int attempt){
        try {
            DocumentPreviewLoader.Result result=loader.load(path);List<MarkdownRenderer.Block> parsed=richFormat(result.format)?MarkdownRenderer.parse(result.markdown):Collections.emptyList();
            OfficePreviewConverter.Conversion cachedConversion = (result.previewPdf==null&&result.sourcePath!=null
                    &&officeSource(result.format)) ? OfficePreviewConverter.findCached(path) : null;
            long size=Files.size(path),modified=Files.getLastModifiedTime(path).toMillis();
            Platform.runLater(()->{
                if(request!=generation||!path.equals(current))return;
                loaded=result;blocks=parsed;loadedSize=size;loadedModified=modified;
                if(cachedConversion!=null){
                    convertedPdf=cachedConversion.pdf;
                    conversionEngine=cachedConversion.engine;
                    conversionAttempted=true;
                }
                updateMeta();
                notice.setText(shortNotice(result));
                render();
                ViewState state=viewStates.get(path);
                if(state!=null)Platform.runLater(()->{if(request==generation&&path.equals(current)){
                    scroll.setVvalue(state.scroll);textView.setScrollTop(state.textScroll);
                }});
                if(result.previewPdf==null&&convertedPdf==null&&result.sourcePath!=null
                        &&officeSource(result.format))scheduleOfficeConversion(request,path);
            });
        }catch(Exception e){Platform.runLater(()->{
            if(request!=generation||!path.equals(current))return;
            if(attempt<MAX_LOAD_RETRY&&retryableFailure(e)){
                com.minion.core.diagnostics.DiagnosticLog.info("preview-retry",
                        "path="+path+" attempt="+(attempt+1)+" err="+e.getClass().getSimpleName());
                notice.setText("文件写入中，正在重试…（"+(attempt+1)+"/"+MAX_LOAD_RETRY+"）");
                body.getChildren().setAll(hint("文件可能仍在写入，正在自动重试…"));
                scheduleRetry(request,path,attempt+1);
            }else showLoadError(path,e);
        });}
    }
    /** F3：reader 池内有界重试；重试前大小两次采样稳定（写盘完成启发式），不稳定则继续等。 */
    private void scheduleRetry(long request,Path path,int attempt){
        submitMerged(reader,path.toString(),()->{
            try{
                Thread.sleep(RETRY_DELAY_MS);
                for(int i=0;i<4&&!sizeStable(path);i++)Thread.sleep(RETRY_DELAY_MS);
            }catch(InterruptedException stop){Thread.currentThread().interrupt();return;}
            loadAndApply(request,path,attempt);
        });
    }
    static boolean sizeStable(Path path)throws InterruptedException{
        try{long first=Files.size(path);Thread.sleep(200);return first==Files.size(path);}
        catch(java.io.IOException unreadable){return true;}
    }
    /** 权限/安全类失败不重试；其余（ZipException/文件锁/NotFound 竞态）视为可重试。 */
    static boolean retryableFailure(Throwable e){
        for(Throwable t=e;t!=null;t=t.getCause())
            if(t instanceof AccessDeniedException||t instanceof SecurityException)return false;
        return true;
    }
    /** F3：同 key 合并 + 队列满丢弃不再静默（记 preview-drop + 当前卡片可见提示）。 */
    private Future<?> submitMerged(ThreadPoolExecutor pool,String key,Runnable task){
        String kind=pool==reader?"reader":"conversion";
        poolTasks.values().removeIf(Future::isDone);
        Future<?> old=poolTasks.get(kind+":"+key);
        if(old!=null)old.cancel(false);
        if(pool.getQueue().remainingCapacity()==0){
            com.minion.core.diagnostics.DiagnosticLog.warn("preview-drop","pool="+kind+" key="+key);
            if(pool==reader)Platform.runLater(()->{
                if(current!=null&&current.toString().equals(key))notice.setText("预览被跳过，点击重试");
            });
        }
        Future<?> future=pool.submit(task);
        poolTasks.put(kind+":"+key,future);
        return future;
    }
    /** F3：3 次仍败/不可重试 → 可操作错误卡（重试重置一次性标记 + 用关联应用打开）。 */
    private void showLoadError(Path path,Exception e){
        loaded=null;blocks=Collections.emptyList();status.setText("预览不可用");notice.setText("");showSource(false);
        Label message=hint("无法预览："+(e.getMessage()==null?e.toString():e.getMessage())+"。仍可打开原文件或定位文件位置。");
        Button retry=new Button("重试");retry.setId("artifact-retry");
        retry.setOnAction(ev->{conversionAttempted=false;textExtractionAttempted=false;reload();});
        Button open=new Button("用关联应用打开");open.setOnAction(ev->links.open(path.toUri().toString()));
        body.getChildren().setAll(message,new HBox(8,retry,open));
    }
    private long loadedSize,loadedModified;
    private static boolean officeSource(String format){
        return "docx".equals(format)||"pptx".equals(format)||"doc".equals(format)||"ppt".equals(format);
    }
    private void scheduleOfficeConversion(long request,Path path){
        if(conversionAttempted)return;
        conversionAttempted=true;
        if(loaded!=null&&("pptx".equals(loaded.format)||"ppt".equals(loaded.format))){
            notice.setText("正在后台渲染高保真幻灯片，完成后自动切换…");
        }
        submitMerged(conversion,"office:"+path,()->{
            OfficePreviewConverter.Conversion result=OfficePreviewConverter.convert(path);
            Platform.runLater(()->{
                if(request!=generation||!path.equals(current))return;
                if(result==null){
                    if(loaded!=null&&"pptx".equals(loaded.format))
                        notice.setText("当前为离线卡片预览（安装 LibreOffice / PowerPoint / WPS 后自动显示原版排版）");
                    return;
                }
                convertedPdf=result.pdf;conversionEngine=result.engine;
                original.setDisable(false);
                if(loaded!=null&&("pptx".equals(loaded.format)||"ppt".equals(loaded.format))) notice.setText("");
                if(rendered.isSelected())render();
            });
        });
    }
    private String previewText(){
        if(loaded==null)return "";
        return loaded.sourceText==null||loaded.sourceText.isEmpty()
                ?(extractedText==null?"":extractedText):loaded.sourceText;
    }
    private void schedulePdfText(){
        Path pdf=loaded==null?null:(loaded.previewPdf!=null?loaded.previewPdf:convertedPdf);
        if(pdf==null||textExtractionAttempted||textExtractionLoading)return;
        textExtractionAttempted=true;textExtractionLoading=true;
        final long request=generation;final Path path=current;final Path previewPdf=pdf;
        submitMerged(conversion,"text:"+previewPdf,()->{
            String text="";
            try(PagedPdfPreview preview=new PagedPdfPreview(previewPdf)){
                text=preview.fullText(DocumentPreviewLoader.MAX_MARKDOWN_CHARS);
            }catch(Exception ignored){
            }
            final String value=text;
            Platform.runLater(()->{
                if(request!=generation||!path.equals(current))return;
                extractedText=value;textExtractionLoading=false;
                setModeAvailability(true);
                if(source.isSelected())render();
            });
        });
    }
    private void updateMeta(){
        GeneratedArtifact artifact=artifacts.get(current);
        status.setText((artifact==null?"文件":"成果")+"  ·  "+
                (loaded.format.isEmpty()?"未知格式":loaded.format.toUpperCase(Locale.ROOT))+"  ·  "
                +new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date(loadedModified)));
    }
    private void showSource(boolean on) {
        hideViewportModes();
        textView.setManaged(on);textView.setVisible(on);
        scroll.setManaged(!on);scroll.setVisible(!on);
    }
    private void hideViewportModes(){
        for(Node node:Arrays.asList(scroll,textView,htmlView,pagedHost,workbookHost)){
            node.setManaged(false);node.setVisible(false);
        }
    }
    private void showPaged(Node node){
        hideViewportModes();
        pagedHost.getChildren().setAll(node);
        pagedHost.setManaged(true);pagedHost.setVisible(true);
    }
    private void showWorkbook(Node node){
        hideViewportModes();
        workbookHost.getChildren().setAll(node);
        workbookHost.setManaged(true);workbookHost.setVisible(true);
    }
    private void clearPagedNode(){
        pagedGeneration++;
        if(pagedNode instanceof AutoCloseable)try{((AutoCloseable)pagedNode).close();}catch(Exception ignored){}
        pagedNode=null;pagedHost.getChildren().clear();
        workbookNode=null;workbookHost.getChildren().clear();
    }
    private void openPagedAsync(Path pdf){
        final long request=++pagedGeneration;
        final Path path=current;
        Label loading=hint("正在打开分页预览…");
        loading.getStyleClass().add("artifact-preview-loading");
        showPaged(loading);
        submitMerged(reader,"paged:"+pdf,()->{
            PagedPdfPreview backend=null;
            try{
                backend=new PagedPdfPreview(pdf);
                final PagedPdfPreview ready=backend;
                Platform.runLater(()->{
                    if(closed||request!=pagedGeneration||!Objects.equals(path,current)){
                        ready.close();
                        return;
                    }
                    try{
                        PagedSlideViewer viewer=new PagedSlideViewer(ready);
                        pagedNode=viewer;showPaged(viewer);
                    }catch(RuntimeException unavailable){
                        ready.close();
                        notice.setText("分页预览不可用："+unavailable.getMessage()+"，已回退文字预览。");
                        body.getChildren().setAll(hint("分页预览不可用，请切换“文本”或打开原件。"));
                        scroll.setManaged(true);scroll.setVisible(true);
                    }
                });
            }catch(Exception unavailable){
                if(backend!=null)backend.close();
                Platform.runLater(()->{
                    if(closed||request!=pagedGeneration||!Objects.equals(path,current))return;
                    notice.setText("分页预览不可用："+unavailable.getMessage()+"，已回退文字预览。");
                    body.getChildren().setAll(hint("分页预览不可用，请切换“文本”或打开原件。"));
                    scroll.setManaged(true);scroll.setVisible(true);
                });
            }
        });
    }
    private void setFindVisible(boolean visible){findBar.setManaged(visible);findBar.setVisible(visible);if(!visible)searchStatus.setText("");}
    private void findNext(){
        if(loaded==null||search.getText().isEmpty())return;
        String text=textView.getText(),query=search.getText();
        int offset=indexOfIgnoreCase(text,query,textView.getSelection().getEnd());
        if(offset<0)offset=indexOfIgnoreCase(text,query,0);
        if(offset<0){searchStatus.setText("无匹配");return;}
        searchStatus.setText("");textView.selectRange(offset,offset+query.length());
    }
    private static int indexOfIgnoreCase(String text,String query,int start){
        for(int i=start;i+query.length()<=text.length();i++)if(text.regionMatches(true,i,query,0,query.length()))return i;
        return -1;
    }
    private void setModeAvailability(boolean available){
        String text=previewText();
        Path effectivePdf=loaded==null?null:(loaded.previewPdf!=null?loaded.previewPdf:convertedPdf);
        boolean canExtract=effectivePdf!=null&&!textExtractionAttempted;
        source.setDisable(!available||loaded==null||text.isEmpty()&&!canExtract);
        find.setDisable(source.isDisabled());
        rendered.setDisable(!available);
        boolean layoutMode=loaded!=null&&"docx".equals(loaded.format);
        original.setVisible(layoutMode);
        original.setManaged(layoutMode);
        original.setDisable(!available||!layoutMode||convertedPdf==null);
        if(loaded!=null){
            if("pdf".equals(loaded.format)||"pptx".equals(loaded.format))rendered.setText("幻灯片");
            else if("xlsx".equals(loaded.format))rendered.setText("表格");
            else if(isImage(current))rendered.setText("图片");
            else rendered.setText("阅读");
            source.setText(("pptx".equals(loaded.format)||"docx".equals(loaded.format))?"大纲":"文本");
        }
    }
    private void render() {
        if(loaded==null)return;
        clearPagedNode();body.getChildren().clear();outline.getItems().clear();outline.setDisable(true);
        setModeAvailability(!loaded.markdown.isEmpty()||!loaded.images.isEmpty()
                ||!loaded.sourceText.isEmpty()||!loaded.sheets.isEmpty()
                ||!loaded.slides.isEmpty()||loaded.previewPdf!=null||convertedPdf!=null);
        hideViewportModes();
        if(htmlFormat(loaded.format)&&!source.isSelected()){
            renderHtml();return;
        }
        if(!loaded.sheets.isEmpty()&&!source.isSelected()){
            WorkbookPreviewView view=new WorkbookPreviewView(loaded.sheets);
            workbookNode=view;showWorkbook(view);return;
        }
        if(isImage(current)&&!source.isSelected()){
            try{
                Path path=Paths.get(current.toUri());
                if(Files.size(path)<=DocumentPreviewLoader.MAX_DIRECT_IMAGE_BYTES){
                    Image image=new Image(path.toUri().toString(),2400,2400,true,true,true);
                    if(!image.isError()){Node view=new ZoomableImageView(image);pagedNode=view;showPaged(view);return;}
                }
            }catch(Exception unavailable){body.getChildren().add(hint("图片无法缩放预览，请打开原件。"));}
        }
        Path effectivePdf=loaded.previewPdf!=null?loaded.previewPdf:convertedPdf;
        if(source.isSelected()&&effectivePdf!=null&&previewText().isEmpty()){
            if(!textExtractionAttempted)schedulePdfText();
            textView.setText("");
            showSource(true);
            if(textExtractionAttempted&&!textExtractionLoading){
                notice.setText("该文档未提取到可复制文字，可能是扫描件或没有文字层；分页视图仍可正常阅读。");
                status.setText("无可提取文字");
            }else{
                status.setText("正在后台提取文档全文…");
            }
            return;
        }
        if(!previewText().isEmpty()&&(source.isSelected()||!richFormat(loaded.format)&&loaded.images.isEmpty())) {
            if(!source.isSelected()){restoring=true;try{source.setSelected(true);}finally{restoring=false;}}
            if(!previewText().equals(textView.getText())){textView.setText(previewText());textView.positionCaret(0);}
            showSource(true);
        } else {
            setFindVisible(false);
            Path pdf=loaded.previewPdf!=null?loaded.previewPdf:convertedPdf;
            boolean showPdf=pdf!=null&&(loaded.previewPdf!=null||original.isSelected()
                    ||"pptx".equals(loaded.format));
            if(showPdf){
                openPagedAsync(pdf);
                return;
            }
            // PPTX 优先走幻灯片卡片（无转换器也能看到内容），不走 original 死路
            if("pptx".equals(loaded.format)&&!loaded.slides.isEmpty()){
                PptxCardViewer viewer=new PptxCardViewer(loaded.slides);
                pagedNode=viewer;showPaged(viewer);return;
            }
            if(original.isSelected()){
                body.getChildren().add(hint("原版排版需要本机 Word/WPS/LibreOffice；当前未检测到可用转换器。"));
                scroll.setManaged(true);scroll.setVisible(true);return;
            }
            scroll.setManaged(true);scroll.setVisible(true);
            int count=0;
            for(MarkdownRenderer.Block block:blocks) {
                if(count++>=300){body.getChildren().add(hint("显示前300个内容块，完整内容可切换“文本”或打开原文件。"));break;}
                Node node=ArtifactDocumentNodes.create(boundedTable(block),textWidth,this::openDocumentLink,href->linkMenus.apply(documentLink(href)));body.getChildren().add(node);
                if(block.type==MarkdownRenderer.Block.Type.TABLE&&(block.rows.size()>80||block.rows.stream().anyMatch(row->row.cells.size()>15)))
                    body.getChildren().add(hint("此表格预览最多80行、15列；完整表格请切换“文本”或打开原文件。"));
                if(block.type==MarkdownRenderer.Block.Type.HEADING&&outline.getItems().size()<60) {
                    MenuItem item=new MenuItem(shortTitle(block.text,50));item.setOnAction(e->{double available=body.getHeight()-scroll.getViewportBounds().getHeight();if(available>0)scroll.setVvalue(Math.max(0,Math.min(1,(node.getBoundsInParent().getMinY()-14)/available)));});outline.getItems().add(item);
                }
            }
            int imageCount=0;FlowPane imageGrid=new FlowPane(10,10);imageGrid.setMinWidth(0);
            for(DocumentPreviewLoader.EmbeddedImage embedded:loaded.images) {
                if(imageCount++>=8)break;
                try {
                    Image image=new Image(new ByteArrayInputStream(embedded.bytes),1000,1000,true,true);if(image.isError())continue;
                    ImageView view=new ImageView(image);view.setPreserveRatio(true);
                    double cap=loaded.images.size()==1?Math.max(520,textWidth.get()):310;
                    view.fitWidthProperty().bind(Bindings.min(textWidth,cap));
                    VBox item=new VBox(4,view);
                    if(!"本地图片预览".equals(embedded.name))item.getChildren().add(0,hint(embedded.name));
                    else item.getChildren().add(0,hint("图片"));
                    imageGrid.getChildren().add(item);
                }catch(RuntimeException unavailable){body.getChildren().add(hint("此附图无法预览，请打开原文件查看。"));}
            }
            if(!imageGrid.getChildren().isEmpty())body.getChildren().add(imageGrid);
        }
        outline.setDisable(outline.getItems().isEmpty());
        if(!textView.isVisible()&&body.getChildren().isEmpty())body.getChildren().add(hint("文档没有可显示的正文；请打开原文件查看。"));
    }
    private void renderHtml(){
        htmlView.getEngine().loadContent(safeHtml(loaded.sourceText),"text/html");
        htmlView.setVisible(true);htmlView.setManaged(true);
        scroll.setVisible(false);scroll.setManaged(false);
        textView.setVisible(false);textView.setManaged(false);
        body.getChildren().setAll(hint("HTML 离线静态预览；脚本、外链资源、表单和弹窗不会执行。"));
    }
    static String safeHtml(String source){
        String value=source==null?"":source;
        value=value.replaceAll("(?is)<!--.*?-->","")
                .replaceAll("(?is)<script\\b.*?</script>","")
                .replaceAll("(?is)<iframe\\b.*?</iframe>","")
                .replaceAll("(?is)<object\\b.*?</object>","")
                .replaceAll("(?is)<embed\\b[^>]*>","")
                .replaceAll("(?is)<link\\b[^>]*>","")
                .replaceAll("(?is)<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[^>]*>","")
                .replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)","")
                .replaceAll("(?i)\\s+(src|href|action|formaction)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)","")
                .replaceAll("(?is)@import\\s+[^;]+;","")
                .replaceAll("(?is)url\\s*\\([^)]*\\)","none");
        return value;
    }
    private static MarkdownRenderer.Block boundedTable(MarkdownRenderer.Block b) {
        if(b.type!=MarkdownRenderer.Block.Type.TABLE)return b;
        MarkdownRenderer.Block out=new MarkdownRenderer.Block(b.type);int rows=0;
        for(MarkdownRenderer.TableRowData row:b.rows) {
            if(rows++>=80)break;
            if(row.cells.size()<=15)out.rows.add(row);
            else {MarkdownRenderer.TableRowData copy=new MarkdownRenderer.TableRowData(row.header);copy.cells.addAll(row.cells.subList(0,15));copy.cellSpans.addAll(row.cellSpans.subList(0,Math.min(15,row.cellSpans.size())));out.rows.add(copy);}
        }
        return out;
    }
    private static Label hint(String text){Label l=new Label(text);l.setWrapText(true);l.setMinWidth(0);l.getStyleClass().add("artifact-hint");return l;}
    private static String shortTitle(String text,int cap){
        return text.length()>cap?text.substring(0,Character.isHighSurrogate(text.charAt(cap-1))?cap-1:cap)+"…":text;
    }
    private static boolean richFormat(String format){return "md".equals(format)||"markdown".equals(format)
            ||"docx".equals(format)||"pptx".equals(format);}
    private static boolean htmlFormat(String format){return "html".equals(format)||"htm".equals(format);}
    private String documentLink(String href) {
        try {return LocalFileLinks.resolve(href,current==null?Paths.get(System.getProperty("user.dir")):current.getParent()).toUri().toASCIIString();}
        catch(Exception invalidOrWeb){return href;}
    }
    private void openDocumentLink(String href) {
        if(href!=null&&href.startsWith("#")){notice.setText("文档内部跳转请使用上方“目录”。");return;}
        links.open(documentLink(href));
    }
    private void showError(String error){loaded=null;blocks=Collections.emptyList();status.setText("预览不可用");notice.setText("");showSource(false);body.getChildren().setAll(hint(error));}
    private static String sizeText(long bytes){return bytes>=1024*1024?String.format(Locale.ROOT,"%.1f MiB",bytes/1048576.0):bytes>=1024?String.format(Locale.ROOT,"%.1f KiB",bytes/1024.0):bytes+" B";}
    void suspend(){rememberView();active=false;generation++;if(pending!=null)pending.cancel(true);}
    private static final class ViewState{
        final boolean source;final double scroll,textScroll;
        ViewState(boolean source,double scroll,double textScroll){this.source=source;this.scroll=scroll;this.textScroll=textScroll;}
    }
    @Override public void close(){
        closed=true;
        suspend();
        clearPagedNode();
        reader.shutdownNow();
        conversion.shutdownNow();
    }
}
