package com.minion.gui.sidebar;

import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspacePaths;
import com.minion.gui.StatusDot;
import com.minion.gui.icon.IconFactory;
import com.minion.gui.session.*;
import com.minion.gui.theme.Theme;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.geometry.Pos;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Virtualized project/conversation projection. Expansion never changes execution scope or disk data. */
public final class ProjectSidebarView extends VBox {
    private final SessionManager manager;
    private final Node legacy;
    private final Runnable refreshLegacy;
    private final Consumer<SessionHandle> onDeleted;
    private final Consumer<String> editProject,createConversation;
    private final ListView<ProjectTreeModel.Row> tree=new ListView<ProjectTreeModel.Row>();
    private final TextField search=new TextField();
    private final Label status=new Label();
    private final VBox projectBody=new VBox(6);
    private final SidebarPreferences preferences;
    private SidebarPreferences.State state=new SidebarPreferences.State();
    private boolean ready,dirty;
    private final Map<String,Integer> limits=new HashMap<String,Integer>();
    private final AtomicBoolean refreshQueued=new AtomicBoolean();
    private SessionHandle reveal;
    private ProjectTreeModel.Row pressedRow;
    private double pressX,pressY;
    private ProjectTreeModel.Row draggedRow;
    private RowCell dropMarker;
    private long ignoreClickUntil;
    public ProjectSidebarView(SessionManager manager,Node legacy,Consumer<SessionHandle> onDeleted,
                              Consumer<String> editProject,Consumer<String> createConversation,
                              Runnable newProject,Runnable openProject,Runnable refreshLegacy,Runnable openAutomation,
                              Runnable openSettings){
        super(8);this.manager=manager;this.legacy=legacy;this.onDeleted=onDeleted;this.refreshLegacy=refreshLegacy;
        this.editProject=editProject;this.createConversation=createConversation;
        preferences=new SidebarPreferences(manager.sidebarPreferencesPath());getStyleClass().add("project-sidebar");setMinWidth(0);projectBody.setMinWidth(0);tree.setMinWidth(0);
        Button create=nav("新建对话",glyph("M12 5v14M5 12h14"),()->newConversation(selectedWorkspace()));
        create.getStyleClass().add("sidebar-primary");
        create.setTooltip(new Tooltip("在选中的项目下新建；未选择时使用当前项目"));
        Button automation=nav("自动化",glyph("M5 6h14v13H5zM5 10h14M8 4v4M16 4v4M12 12v3h3"),openAutomation);
        VBox navigation=new VBox(5,create,automation);navigation.getStyleClass().add("sidebar-navigation");
        getChildren().add(navigation);
        Label heading=new Label("项目与对话");heading.getStyleClass().add("project-heading");
        heading.setMinWidth(0);
        MenuButton options=new MenuButton("⋯");CheckMenuItem old=new CheckMenuItem("旧版双列表"),archives=new CheckMenuItem("显示归档对话（项目树）");
        old.setOnAction(e->{state.legacy=old.isSelected();applyMode();changed();});
        archives.setOnAction(e->{state.includeArchived=archives.isSelected();refresh();changed();});
        MenuItem expand=new MenuItem("展开全部项目"),collapse=new MenuItem("折叠全部项目");
        expand.setOnAction(e->{state.collapsed.clear();refresh();changed();});
        collapse.setOnAction(e->{for(WorkspaceConfig w:manager.workspaces().list())state.collapsed.add(w.workSpaceName);refresh();changed();});
        MenuItem add=new MenuItem("新建项目"),open=new MenuItem("打开已有项目");
        add.setOnAction(e->newProject.run());open.setOnAction(e->openProject.run());
        options.getItems().addAll(add,open,new SeparatorMenuItem(),expand,collapse,archives,old);
        options.getStyleClass().add("sidebar-icon-button");options.setAccessibleText("项目和视图选项");
        options.setTooltip(new Tooltip("新建、打开项目和调整侧栏视图"));
        Button addProject=new Button();addProject.setGraphic(glyph("M4 7h6l2 2h8v10H4zM12 11v6M9 14h6"));
        addProject.getStyleClass().add("sidebar-icon-button");addProject.setAccessibleText("新建项目");
        addProject.setTooltip(new Tooltip("新建项目"));addProject.setOnAction(e->newProject.run());
        Button find=new Button();find.setGraphic(glyph("M14.5 10a4.5 4.5 0 1 1-9 0a4.5 4.5 0 1 1 9 0M13.3 13.3L19 19"));
        find.getStyleClass().add("sidebar-icon-button");find.setAccessibleText("搜索对话");find.setTooltip(new Tooltip("搜索项目与对话"));
        find.visibleProperty().bind(old.selectedProperty().not());find.managedProperty().bind(find.visibleProperty());
        search.setVisible(false);search.setManaged(false);
        find.setOnAction(e->{boolean show=!search.isVisible();search.setVisible(show);search.setManaged(show);
            if(show)search.requestFocus();else search.clear();});
        search.setOnKeyPressed(e->{if(e.getCode()==KeyCode.ESCAPE&&search.isVisible()){find.fire();e.consume();}});
        Region gap=new Region();HBox.setHgrow(gap,Priority.ALWAYS);
        HBox header=new HBox(5,heading,gap,find,addProject,options);
        header.setAlignment(Pos.CENTER_LEFT);header.getStyleClass().add("sidebar-section");getChildren().add(header);
        search.setPromptText("搜索项目、路径或对话标题");search.textProperty().addListener((o,a,b)->refresh());
        tree.setCellFactory(v->new RowCell());Label empty=new Label("没有匹配的项目或对话");
        empty.getStyleClass().add("sidebar-empty");empty.setWrapText(true);tree.setPlaceholder(empty);VBox.setVgrow(tree,Priority.ALWAYS);
        tree.setTooltip(new Tooltip("按住项目标题上下拖动，调整整组顺序。对话只能在同项目、同置顶分组内拖动。"));
        tree.addEventFilter(MouseEvent.MOUSE_PRESSED,this::pressForSort);
        tree.addEventFilter(MouseEvent.MOUSE_DRAGGED,this::dragForSort);
        tree.addEventFilter(MouseEvent.MOUSE_RELEASED,this::releaseSort);
        tree.setOnMouseClicked(e->{if(e.getButton()!=MouseButton.PRIMARY||buttonTarget(e.getTarget())||draggedRow!=null||System.nanoTime()<ignoreClickUntil)return;
            ProjectTreeModel.Row row=tree.getSelectionModel().getSelectedItem();
            if(row!=null&&cellTarget(e.getTarget()))activate(row);});
        tree.addEventFilter(KeyEvent.KEY_PRESSED,e->{
            ProjectTreeModel.Row row=tree.getSelectionModel().getSelectedItem();
            if(e.getCode()==KeyCode.ENTER){if(row!=null)activate(row);e.consume();}
        });
        projectBody.getChildren().addAll(search,tree);
        SVGPath settingsIcon=IconFactory.settings();
        IconFactory.size(settingsIcon,16);
        settingsIcon.getStyleClass().add("sidebar-settings-icon");
        Button settings=nav("设置",settingsIcon,openSettings);
        settings.setId("sidebar-settings");
        settings.setTooltip(new Tooltip("外观、模型、工具和应用设置"));
        VBox footer=new VBox(settings);footer.getStyleClass().add("sidebar-footer");
        getChildren().addAll(projectBody,legacy,status,footer);
        VBox.setVgrow(projectBody,Priority.ALWAYS);VBox.setVgrow(legacy,Priority.ALWAYS);status.setWrapText(true);status.getStyleClass().add("project-hint");
        status.visibleProperty().bind(status.textProperty().isNotEmpty());status.managedProperty().bind(status.visibleProperty());
        applyMode();refresh();
        manager.background(()->{SidebarPreferences.State loaded=preferences.load();Platform.runLater(()->{
            ready=true;if(!dirty){state=loaded;old.setSelected(state.legacy);archives.setSelected(state.includeArchived);applyMode();refresh();}
            else changed();if(!preferences.writable())status.setText("侧栏偏好不可读取；当前窗口仍可使用，旧项目和对话不受影响。");
        });});
    }
    private static Button nav(String text,Node icon,Runnable action){
        Button button=new Button(text,icon);button.getStyleClass().add("sidebar-nav");
        button.setAlignment(Pos.CENTER_LEFT);button.setMaxWidth(Double.MAX_VALUE);button.setOnAction(e->action.run());
        return button;
    }
    private static SVGPath glyph(String content){
        SVGPath icon=new SVGPath();icon.setContent(content);icon.getStyleClass().add("sidebar-glyph");
        return icon;
    }
    public boolean legacyMode(){return state.legacy;}
    private void applyMode(){if(state.legacy)refreshLegacy.run();legacy.setVisible(state.legacy);legacy.setManaged(state.legacy);projectBody.setVisible(!state.legacy);projectBody.setManaged(!state.legacy);}
    private void changed(){dirty=true;if(!ready)return;long version=preferences.changed();SidebarPreferences.State copy=state.copy();
        manager.background(()->{try{preferences.save(copy,version);}catch(Exception e){Platform.runLater(()->status.setText("界面偏好未保存；旧对话未改动: "+e.getMessage()));}});}
    public void refresh(){if(!refreshQueued.compareAndSet(false,true))return;Platform.runLater(()->{refreshQueued.set(false);if(pressedRow==null)rebuild();});}
    public void reveal(SessionHandle h){reveal=h;state.collapsed.remove(h.workspaceName);refresh();}
    private void rebuild(){
        ProjectTreeModel.Row selected=tree.getSelectionModel().getSelectedItem();String key=selected==null?null:selected.key;
        if(reveal!=null){
            List<SessionHandle> peers=manager.sessions(reveal.workspaceName,state.includeArchived);
            int index=peers.indexOf(reveal);
            if(index>=0)limits.put(reveal.workspaceName,Math.max(limits.containsKey(reveal.workspaceName)?limits.get(reveal.workspaceName):ProjectTreeModel.PAGE_SIZE,index+1));
        }
        List<ProjectTreeModel.Row> rows=ProjectTreeModel.rows(manager.workspaces().list(),name->manager.sessions(name,true),state.collapsed,limits,search.getText(),state.includeArchived);
        tree.getItems().setAll(rows);int focus=-1;
        for(int i=0;i<rows.size();i++){ProjectTreeModel.Row row=rows.get(i);
            if(reveal!=null&&row.session==reveal){focus=i;break;}if(reveal==null&&row.key.equals(key))focus=i;}
        if(focus>=0){tree.getSelectionModel().select(focus);if(reveal!=null)tree.scrollTo(focus);}
        reveal=null;
    }
    private String selectedWorkspace(){ProjectTreeModel.Row row=tree.getSelectionModel().getSelectedItem();return state.legacy||row==null?manager.workspaces().currentName():row.workspace;}
    private void newConversation(String workspace){
        WorkspaceConfig w=manager.workspaces().get(workspace);
        if(w==null){status.setText("请先打开或新建项目");return;}
        if(!WorkspacePaths.isExistingDir(w.workDir,null)){status.setText("项目目录不可用，旧对话仍保留；请在项目设置中核对路径。");return;}
        createConversation.accept(workspace);refresh();
    }
    private void activate(ProjectTreeModel.Row row){
        if(row.kind==ProjectTreeModel.Kind.PROJECT){
            if(!state.collapsed.add(row.workspace))state.collapsed.remove(row.workspace);changed();refresh();
        }else if(row.kind==ProjectTreeModel.Kind.SESSION)manager.openSessionAcrossProjects(row.session,status::setText);
        else if(row.kind==ProjectTreeModel.Kind.MORE){limits.put(row.workspace,Math.min(10000,row.shown+ProjectTreeModel.PAGE_SIZE));refresh();}
        else if(row.kind==ProjectTreeModel.Kind.LESS){limits.remove(row.workspace);refresh();}
        else newConversation(row.workspace);
    }
    private void metadataAction(SessionHandle h,Runnable action){
        if(h.deleted)return;
        if(h.isLoaded()){action.run();refresh();return;}
        status.setText("正在读取所选对话…");manager.background(()->{boolean loaded=manager.ensureLoaded(h);Platform.runLater(()->{
            if(loaded&&!h.deleted){action.run();refresh();status.setText("");}else status.setText("无法读取，原记录保留");
        });});
    }
    private ContextMenu menu(ProjectTreeModel.Row row){
        ContextMenu m=new ContextMenu();
        if(row.kind==ProjectTreeModel.Kind.PROJECT){
            MenuItem create=new MenuItem("在此项目新建对话"),edit=new MenuItem("项目设置"),copy=new MenuItem("复制项目路径");
            create.setOnAction(e->newConversation(row.workspace));edit.setOnAction(e->{editProject.accept(row.workspace);refresh();});
            copy.setOnAction(e->{ClipboardContent c=new ClipboardContent();c.putString(row.path==null?"":row.path);Clipboard.getSystemClipboard().setContent(c);});
            m.getItems().addAll(create,edit,copy);return m;
        }
        if(row.session==null)return null;SessionHandle h=row.session;
        MenuItem open=new MenuItem("打开对话"),pin=new MenuItem(h.session.pinned?"取消置顶":"置顶"),rename=new MenuItem("重命名"),archive=new MenuItem(h.session.archived?"取消归档":"归档（保留记录）"),remove=new MenuItem("删除对话…");
        open.setOnAction(e->manager.openSessionAcrossProjects(h,status::setText));
        pin.setOnAction(e->metadataAction(h,()->manager.setPinned(h,!h.session.pinned)));
        rename.setOnAction(e->{TextInputDialog d=new TextInputDialog(h.title);d.setTitle("重命名对话");d.setHeaderText(row.workspace+" / 对话标题");Theme.style(d);if(getScene()!=null)d.initOwner(getScene().getWindow());
            d.showAndWait().ifPresent(title->{if(!title.trim().isEmpty())metadataAction(h,()->manager.renameSession(h,title.trim()));});});
        archive.setDisable(h.running);archive.setOnAction(e->metadataAction(h,()->manager.setArchived(h,!h.session.archived)));
        remove.setOnAction(e->{Alert a=new Alert(Alert.AlertType.CONFIRMATION,"删除「"+row.workspace+" / "+(h.title==null?h.id:h.title)+"」？这会删除此对话的记录，不会删除项目文件。",ButtonType.OK,ButtonType.CANCEL);
            a.setHeaderText("确认删除对话");Theme.style(a);if(getScene()!=null)a.initOwner(getScene().getWindow());
            if(a.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK){manager.deleteSession(h);onDeleted.accept(h);refresh();}});
        m.getItems().addAll(open,pin,rename,archive,new SeparatorMenuItem(),remove);return m;
    }
    private void moved(boolean success){
        status.setText(success?"顺序已保存；对话所属项目不变":"未移动：已到分组边界，或不支持此排序位置");
        if(success){refresh();refreshLegacy.run();}
    }
    private void pressForSort(MouseEvent event){
        pressedRow=null;draggedRow=null;markDrop(null,false);
        if(event.getButton()!=MouseButton.PRIMARY||buttonTarget(event.getTarget()))return;
        for(Node n=event.getTarget() instanceof Node?(Node)event.getTarget():null;n!=null&&n!=tree;n=n.getParent()){
            if(!(n instanceof RowCell))continue;
            ProjectTreeModel.Row row=((RowCell)n).getItem();
            if(row!=null&&(row.kind==ProjectTreeModel.Kind.PROJECT
                    ||row.session!=null&&!row.session.deleted&&!row.session.session.archived)){
                pressedRow=row;pressX=event.getSceneX();pressY=event.getSceneY();
            }
            break;
        }
    }
    private void dragForSort(MouseEvent event){
        if(pressedRow==null||!event.isPrimaryButtonDown())return;
        if(draggedRow==null){
            if(Math.abs(event.getSceneY()-pressY)<5&&Math.abs(event.getSceneX()-pressX)<5)return;
            draggedRow=pressedRow;
        }
        RowCell target=dropTarget(event);
        markDrop(target==null?null:groupMarker(target,before(target,event)),target!=null&&before(target,event));
        event.consume();
    }
    private void releaseSort(MouseEvent event){
        if(event.getButton()!=MouseButton.PRIMARY)return;
        if(draggedRow!=null){
            RowCell target=dropTarget(event);
            if(target!=null)moved(ProjectTreeReorder.move(manager,draggedRow,target.getItem(),before(target,event)));
            ignoreClickUntil=System.nanoTime()+250000000L;
            event.consume();
        }
        boolean refreshNeeded=pressedRow!=null;
        pressedRow=null;draggedRow=null;markDrop(null,false);
        if(refreshNeeded)refresh();
    }
    private RowCell dropTarget(MouseEvent event){
        double x=event.getSceneX(),y=event.getSceneY();
        Node viewport=tree.lookup(".virtual-flow");
        if(viewport==null)viewport=tree;
        if(!viewport.localToScene(viewport.getLayoutBounds()).contains(x,y))return null;
        for(Node n:tree.lookupAll(".list-cell"))if(n instanceof RowCell){
            RowCell cell=(RowCell)n;
            if(!cell.isEmpty()&&cell.isVisible()&&cell.localToScene(cell.getLayoutBounds()).contains(x,y))
                return ProjectTreeReorder.canMove(draggedRow,cell.getItem())?cell:null;
        }
        return null;
    }
    private boolean before(RowCell cell,MouseEvent event){
        if(draggedRow.kind==ProjectTreeModel.Kind.PROJECT&&cell.getItem().kind!=ProjectTreeModel.Kind.PROJECT)return false;
        return cell.sceneToLocal(event.getSceneX(),event.getSceneY()).getY()<cell.getHeight()/2;
    }
    private RowCell groupMarker(RowCell target,boolean before){
        if(draggedRow.kind!=ProjectTreeModel.Kind.PROJECT||before)return target;
        RowCell last=target;
        javafx.geometry.Bounds visible=tree.localToScene(tree.getLayoutBounds());
        for(Node n:tree.lookupAll(".list-cell"))if(n instanceof RowCell){
            RowCell cell=(RowCell)n;
            if(!cell.isEmpty()&&cell.getItem()!=null&&cell.getIndex()>last.getIndex()
                    &&target.getItem().workspace.equals(cell.getItem().workspace)
                    &&visible.intersects(cell.localToScene(cell.getLayoutBounds())))last=cell;
        }
        return last;
    }
    private void markDrop(RowCell cell,boolean before){
        if(dropMarker!=null)dropMarker.getStyleClass().removeAll("project-drop-before","project-drop-after");
        dropMarker=cell;
        if(cell!=null)cell.getStyleClass().add(before?"project-drop-before":"project-drop-after");
    }
    private static long lastTime(SessionHandle h){
        if(!h.isLoaded())return h.listMessageTs;
        try{java.util.List<com.minion.core.llm.Message> messages=h.session.messages;
            for(int i=messages.size()-1,n=0;i>=0&&n<32;i--,n++){
                com.minion.core.llm.Message m=messages.get(i);if(m.role!=com.minion.core.llm.Message.Role.TOOL)return m.ts;
            }
        }catch(IndexOutOfBoundsException ignored){/* A concurrent append/reset will be reflected by the next lifecycle refresh. */}
        return h.listMessageTs;
    }
    private static boolean buttonTarget(Object target){for(Node n=target instanceof Node?(Node)target:null;n!=null;n=n.getParent())if(n instanceof ButtonBase)return true;return false;}
    private static boolean cellTarget(Object target){for(Node n=target instanceof Node?(Node)target:null;n!=null;n=n.getParent())if(n instanceof ListCell)return !((ListCell<?>)n).isEmpty();return false;}
    private final class RowCell extends ListCell<ProjectTreeModel.Row>{
        @Override protected void updateItem(ProjectTreeModel.Row row,boolean empty){
            super.updateItem(row,empty);StatusDot.stopPulseIn(getGraphic());setText(null);setContextMenu(null);setTooltip(null);
            getStyleClass().removeAll("project-row","conversation-row","project-drop-before","project-drop-after","active-conversation");if(empty||row==null){setGraphic(null);return;}
            HBox line=new HBox(6);line.setAlignment(Pos.CENTER_LEFT);
            line.maxWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                    ()->Math.max(0,getWidth()-getInsets().getLeft()-getInsets().getRight()-5),widthProperty(),insetsProperty()));
            Label name=new Label();name.getStyleClass().add("sidebar-row-label");name.setMinWidth(0);name.setTextOverrun(OverrunStyle.ELLIPSIS);HBox.setHgrow(name,Priority.ALWAYS);name.setMaxWidth(Double.MAX_VALUE);
            if(row.kind==ProjectTreeModel.Kind.PROJECT){
                getStyleClass().add("project-row");name.setText(ProjectTreeModel.displayName(row.workspace));
                SVGPath arrow=row.expanded?IconFactory.chevronDown():IconFactory.chevronRight();IconFactory.size(arrow,12);
                arrow.getStyleClass().add("sidebar-chevron");
                SVGPath folder=glyph("M4 7h6l2 2h8v10H4z");folder.getStyleClass().add("sidebar-folder");
                Label count=new Label(Integer.toString(row.total));count.getStyleClass().add("sidebar-count");
                count.visibleProperty().bind(widthProperty().greaterThanOrEqualTo(190));
                count.managedProperty().bind(count.visibleProperty());
                Button add=new Button();add.setGraphic(glyph("M12 6v12M6 12h12"));add.setMinWidth(Region.USE_PREF_SIZE);
                add.getStyleClass().addAll("sidebar-icon-button","sidebar-row-add");add.setAccessibleText("在此项目新建对话");
                add.setTooltip(new Tooltip("在「"+row.workspace+"」新建对话"));add.setOnAction(e->newConversation(row.workspace));
                line.getChildren().addAll(arrow,folder,name,count,add);
            }else if(row.session!=null){
                getStyleClass().add("conversation-row");SessionHandle h=row.session;
                if(manager.currentSession()==h)getStyleClass().add("active-conversation");
                name.setText((h.session.pinned?"★ ":"")+(h.session.archived?"[归档] ":"")+(h.title==null||h.title.isEmpty()?"新对话":h.title));
                Label time=new Label(TimeFormatter.format(lastTime(h),System.currentTimeMillis()));time.getStyleClass().add("cell-time");
                time.visibleProperty().bind(widthProperty().greaterThanOrEqualTo(245));time.managedProperty().bind(time.visibleProperty());
                line.getChildren().addAll(StatusDot.create(h.activityState()),name,time);
            }else if(row.kind==ProjectTreeModel.Kind.MORE||row.kind==ProjectTreeModel.Kind.LESS){
                Button page=new Button(row.kind==ProjectTreeModel.Kind.MORE?"显示更多 · "+row.shown+" / "+row.total:"收起至 5 条");
                page.getStyleClass().add("sidebar-more");page.setOnAction(e->activate(row));line.getChildren().add(page);
            }else{name.setText("暂无对话");name.getStyleClass().add("sidebar-count");line.getChildren().add(name);}
            setTooltip(new Tooltip(row.workspace+"\n"+(row.path==null?"未配置目录":row.path)+(row.session==null?"":"\n"+name.getText())
                    +(row.kind==ProjectTreeModel.Kind.PROJECT?"\n按住项目标题上下拖动排序":"")));
            setGraphic(line);setContextMenu(menu(row));
        }
    }
}
