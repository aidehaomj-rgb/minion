package com.minion.gui;

import com.minion.core.agent.RetryProgress;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.WorkspaceConfig;
import com.minion.gui.chat.ChatView;
import com.minion.gui.dialog.ConfirmSheet;
import com.minion.gui.dialog.NewProjectDialog;
import com.minion.gui.dialog.SettingsDialog;
import com.minion.gui.dialog.WorkspaceFormDialog;
import com.minion.gui.input.InputView;
import com.minion.gui.session.AutoScrollPolicy;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import com.minion.gui.sidebar.SessionListView;
import com.minion.gui.sidebar.ProjectSidebarView;
import com.minion.gui.sidebar.WorkspaceListView;
import com.minion.gui.theme.Theme;
import com.minion.core.tools.dev.ProjectTool;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.CacheHint;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 主窗口：自绘标题栏、可拖动侧栏与独立报告工作区。 */
public class MainWindow {

    private final Stage stage;
    private final SessionManager manager;
    /** §9.3 双击 Esc 检测：上次 Esc 时间戳（400ms 内两次=打开 Rewind 选择器） */
    private long lastEscAt;
    private final TabPane tabs = new TabPane();
    private BorderPane root;
    private boolean synchronizingTabs;
    private SessionListView sessionList;
    private ProjectSidebarView projectSidebar;
    private AutomationCenter automationCenter;
    private ChatView chatView;
    private ScrollPane chatScroll;
    private final AutoScrollPolicy policy = new AutoScrollPolicy();
    /** 按项目内会话句柄缓存，避免不同项目复制得到的同 ID 会话误绑。
     *  LRU 上限 8 个：每视图常驻最多 200 段 TextArea 节点，无上限时长开多会话内存只增不减；
     *  逐出最久未访问的视图（当前展示的视图不逐出），逐出前解绑直通监听，重开时从事件缓冲全量重建。 */
    private final Map<SessionHandle, ChatView> viewCache =
            new LinkedHashMap<SessionHandle, ChatView>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<SessionHandle, ChatView> eldest) {
                    if (size() <= 8 || eldest.getValue() == chatView) return false;
                    eldest.getValue().bind(false);
                    return true;
                }
            };
    /** 无活动会话时的工作台起始页；会话视图仍独立缓存。 */
    private final StackPane placeholder = new StackPane();
    private InputView inputView;
    /** 运行状态指示器（输入框上方；仅当前会话运行或压缩时显示） */
    private RunningIndicator runningIndicator;
    /** 用户浏览历史消息时显示，点击立即回到最新对话并恢复流式跟随。 */
    private Button jumpLatestButton;
    /** 对话与生成报告共用的右侧工作区。 */
    private SplitPane workspaceSplit;
    private VBox conversationColumn;
    /** Part E 对话框上方常驻任务条（ui.taskStrip.enabled=false 时为 null，布局与现状一致） */
    private TaskStrip taskStrip;
    private ArtifactPreviewPane artifactPreview;
    private com.minion.gui.artifact.LocalFileLinks fileLinks;
    private SessionHandle previewOwner;
    private double workspaceDivider=Double.NaN;
    private boolean workspaceFocused;
    private Button reportButton;
    private MenuButton modelButton;
    /** H2 对话级权限选择器（默认/自动审批/完全授权；会话内存态，不落盘） */
    private MenuButton permissionButton;
    private HBox tabsBar; // 右侧顶部页签栏（无会话时整行隐藏）
    /** 已打开会话 id 集合：页签存在性的唯一权威（页签 ⇔ openedIds 含句柄）；切工作空间保留 */
    private final Set<SessionHandle> openedIds = new HashSet<SessionHandle>();

    public MainWindow(Stage stage, SessionManager manager) {
        this.stage = stage;
        this.manager = manager;
    }

    public void show() {
        stage.setTitle("minion");
        com.minion.gui.icon.AppIcons.install(stage);
        stage.initStyle(StageStyle.UNDECORATED); // 需求 1：隐藏系统标题栏
        stage.setMinWidth(960);
        stage.setMinHeight(640);

        // 初始尺寸钳制到屏幕可视范围：无边框窗口没有系统窗口管理器的「缩到屏内」钳制，
        // 而窗口按场景首选尺寸打开（侧栏两个 ListView 各 ~400px，总高常超 900px），
        // 小屏上会把顶部标题栏和底部输入框顶出屏幕；故首次显示前显式设尺寸
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(1280, vb.getWidth() - 40));
        stage.setHeight(Math.min(760, vb.getHeight() - 40));
        stage.setMinWidth(Math.min(960, vb.getWidth() - 40));
        stage.setMinHeight(Math.min(640, vb.getHeight() - 40));

        // 根容器：AnchorPane 承载内容 + 8 个缩放区域（ResizeHelper 覆盖在边缘）
        AnchorPane frame = new AnchorPane();
        root = new BorderPane();
        root.getStyleClass().addAll("root", "minion-workbench");
        Theme.apply(root, MinionApp.config().uiTheme());
        AnchorPane.setTopAnchor(root, 0.0);
        AnchorPane.setBottomAnchor(root, 0.0);
        AnchorPane.setLeftAnchor(root, 0.0);
        AnchorPane.setRightAnchor(root, 0.0);
        frame.getChildren().add(root);

        // 自绘标题栏：应用名 | 模型标签 | 留白 | ⚙ | 窗口按钮
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        tabs.getStyleClass().add("conversation-tabs");
        tabs.setMinHeight(42);tabs.setPrefHeight(42);tabs.setMaxHeight(42);tabs.setMinWidth(0);
        // 需求：标题栏页签点击激活对应会话（userData 存会话句柄，避免不同项目重复ID误绑）；跨工作空间的页签点击先自动切回该空间
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv == null || synchronizingTabs) return;
            if (!(nv.getUserData() instanceof SessionHandle) || !tabs.getTabs().contains(nv)) return;
            SessionHandle h=(SessionHandle)nv.getUserData();
            manager.openSessionAcrossProjects(h,null);

        });
        root.setTop(new TitleBar(stage, this::confirmClose));

        // 侧栏可以拖动；初始宽度见下方 SidebarSplit。
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("panel");
        sidebar.setMinWidth(200);
        sessionList = new SessionListView(manager,
                h -> {
                    removeTab(h);
                    openedIds.remove(h); // 删除会话：打开标记一并清除
                    viewCache.remove(h); // 删除会话：缓存视图一并释放
                    if (chatView != null && chatView.handle() == h) clearChatPane();
                    refreshSidebar();
                });
        VBox.setVgrow(sessionList, Priority.ALWAYS);
        Button newSession = new Button("＋ 新建会话");
        newSession.getStyleClass().add("btn-ghost");
        newSession.setMaxWidth(Double.MAX_VALUE);
        newSession.setOnAction(e -> onNewSession());
        VBox sessionBox = new VBox(6);
        sessionBox.getChildren().addAll(newSession, sessionList);
        VBox.setVgrow(sessionBox, Priority.ALWAYS);
        final WorkspaceListView wsList = new WorkspaceListView(manager);
        VBox.setVgrow(wsList, Priority.ALWAYS);
        Button newProject = new Button("＋ 新建项目");
        newProject.getStyleClass().add("btn-ghost");
        newProject.setMaxWidth(Double.MAX_VALUE);
        newProject.setOnAction(e -> onNewProject(wsList));
        Button newWs = new Button("打开已有项目");
        newWs.getStyleClass().add("btn-ghost");
        newWs.setMaxWidth(Double.MAX_VALUE);
        newWs.setOnAction(e -> onNewWorkspace(wsList));
        VBox wsBox = new VBox(6);
        wsBox.getChildren().addAll(newProject, newWs, wsList);
        VBox.setVgrow(wsBox, Priority.ALWAYS);
        // VBox(8) 间距自然分隔上下两区，不加分隔线
        VBox legacySidebar=new VBox(8,sessionBox,wsBox);
        projectSidebar=new ProjectSidebarView(manager,legacySidebar,h->{
            removeTab(h);openedIds.remove(h);viewCache.remove(h);
            if(chatView!=null&&chatView.handle()==h)clearChatPane();refreshSidebar();
        },wsList::editWorkspace,name->{
            if(!name.equals(manager.workspaces().currentName()))manager.switchWorkspace(name);
            onNewSession();
        },()->onNewProject(wsList),()->onNewWorkspace(wsList),()->{sessionList.refresh();wsList.refresh();},()->{
            if(automationCenter==null)automationCenter=new AutomationCenter(manager,stage);
            automationCenter.show();
        },this::openSettings);
        VBox.setVgrow(projectSidebar,Priority.ALWAYS);sidebar.getChildren().setAll(projectSidebar);
        // 需求：会话/工作空间高度固定黄金比例 0.618:0.382（VBox 无权重 API，监听高度动态设 prefHeight 实现）
        sidebar.heightProperty().addListener((obs, ov, nv) -> {
            double h = Math.max(0, nv.doubleValue() - sidebar.getSpacing()); // 扣除两区间距
            double sessionH = h * 0.618;
            sessionBox.setPrefHeight(sessionH);
            wsBox.setPrefHeight(h - sessionH);
        });

        VBox right = new VBox();
        right.getStyleClass().add("panel-dark");
        chatScroll = new ScrollPane();
        chatScroll.getStyleClass().add("chat-scroll");
        // JavaFX 8 Modena 会在 ScrollPane 获得焦点后沿整个聊天视口绘制浅色 focus ring。
        // 外层 workspaceSplit 的样式无法覆盖它，因此聊天滚动区本身也必须退出焦点遍历。
        chatScroll.setFocusTraversable(false);
        chatScroll.setFitToWidth(true);
        chatScroll.setFitToHeight(true);
        chatScroll.setPrefHeight(200); // 固定 pref：否则 prefHeight 随消息内容增长，挤压右侧 VBox 把页签行压扁（探针验证）
        configureWelcome();
        chatScroll.setContent(placeholder); // 激活会话后换 ChatView
        setupAutoScroll();
        WheelScrollAccelerator.attach(chatScroll); // 滚轮加速：每格固定像素滚动（替换 JavaFX 8 默认慢速比例滚动）
        inputView = new InputView(manager, MinionApp.config());
        // 运行状态指示器：独占输入框上方的一行，不覆盖流式输出。
        runningIndicator = new RunningIndicator();
        inputView.setStatusNode(runningIndicator);
        // §10.4 两层状态栏：footer 初始化 + 2s 定时刷新（权限模式|上下文|压缩剩余 + 自定义 statusLine）
        inputView.initStatusBar();
        javafx.animation.Timeline statusTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2), e -> refreshStatusBar()));
        statusTimer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        statusTimer.play();
        jumpLatestButton = new Button("↓ 最新对话");
        jumpLatestButton.getStyleClass().add("btn-jump-latest");
        jumpLatestButton.setTooltip(new Tooltip("直达对话底部并继续跟随新消息"));
        jumpLatestButton.setVisible(false);
        jumpLatestButton.setManaged(false);
        jumpLatestButton.setOnAction(e -> jumpToLatest());
        StackPane chatHost = new StackPane(chatScroll, jumpLatestButton);
        // JavaFX 8 无 StackPane.setFillWidth/Height（12+ 才有）：用 max 钳制防默认 fill 拉伸铺满
        runningIndicator.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(jumpLatestButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(jumpLatestButton, new Insets(0, 18, 14, 0));
        jumpLatestButton.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        workspaceSplit = new SplitPane();
        workspaceSplit.getStyleClass().add("workspace-split");
        workspaceSplit.setFocusTraversable(false); // 禁止 JavaFX 8 默认焦点环给整个对话区描亮边
        // Part E：对话框上方常驻任务条（纯展示层；默认开，ui.taskStrip.enabled=false 时不创建，布局与现状逐像素一致）
        if (Boolean.parseBoolean(MinionApp.config().get("ui.taskStrip.enabled", "true"))) {
            taskStrip = new TaskStrip(manager, MinionApp.config(), () -> {
                SessionHandle h = manager.currentSession();
                if (h != null) new TaskDashboard(stage, manager, h).show();
            });
            conversationColumn = new VBox(chatHost, taskStrip, inputView);
        } else {
            conversationColumn = new VBox(chatHost, inputView);
        }
        conversationColumn.getStyleClass().add("conversation-column");
        conversationColumn.setMinWidth(250);
        VBox.setVgrow(chatHost, Priority.ALWAYS);
        workspaceSplit.getItems().add(conversationColumn);
        fileLinks=new com.minion.gui.artifact.LocalFileLinks(this::fileLinkBase,()->stage,
                path->openArtifactPreview(path.toString()),this::fileLinkError);
        artifactPreview = new ArtifactPreviewPane(()->{
            SessionHandle h=manager.currentSession();if(h!=null)h.suppressArtifactAutoOpen=true;
            closeArtifactPreview();
        },fileLinks);
        artifactPreview.setSelectionListener(path->{SessionHandle h=previewOwner;if(h!=null&&manager.currentSession()==h&&!h.deleted)h.selectedArtifactPath=path.toString();});
        artifactPreview.setFocusAction(this::focusArtifactWorkspace);
        if (Boolean.parseBoolean(MinionApp.config().get("ui.workspaceScan.enabled", "true"))) {
            artifactPreview.setWorkspaceScanner(new ArtifactPreviewPane.WorkspaceScanner() {
                @Override public java.util.List<java.nio.file.Path> scan() throws Exception {
                    SessionHandle h = manager.currentSession();
                    if (h == null || h.loop == null) return java.util.Collections.emptyList();
                    java.util.List<String> roots = new java.util.ArrayList<String>();
                    roots.add(h.loop.workspace().workDir());
                    roots.addAll(h.loop.workspace().extraAllowedDirs());
                    java.util.List<java.nio.file.Path> out = new java.util.ArrayList<java.nio.file.Path>();
                    java.util.Set<java.nio.file.Path> seen = new java.util.HashSet<java.nio.file.Path>();
                    for (String root : roots) {
                        if (root == null || root.trim().isEmpty()) continue;
                        try {
                            for (java.nio.file.Path p : com.minion.gui.artifact.WorkspaceFilesPane.scanDeliverables(java.nio.file.Paths.get(root)))
                                if (seen.add(p.toAbsolutePath().normalize())) out.add(p);
                        } catch (java.io.IOException unreadable) { /* 单个根目录不可读不影响其余 */ }
                    }
                    return out;
                }
                @Override public void adopt(java.nio.file.Path path) {
                    SessionHandle h = manager.currentSession();
                    if (h != null && h.loop != null) h.loop.adoptScannedArtifact(path);
                }
            });
        }
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN,e->{artifactPreview.close();fileLinks.close();if(taskStrip!=null)taskStrip.stop();});
        VBox.setVgrow(workspaceSplit, Priority.ALWAYS);
        // 页签栏（右侧顶部，下带 1px 分隔线；页签为空时整行隐藏）
        reportButton = new Button("工作区");
        reportButton.getStyleClass().add("workspace-top-action");
        reportButton.setGraphic(outlineIcon("M3 4h14v12H3z M11 4v12"));
        reportButton.setTooltip(new Tooltip("在右侧预览本会话的交付文件与引用文件"));
        reportButton.setOnAction(e -> {
            SessionHandle h = manager.currentSession();
            if (!workspaceSplit.getItems().contains(artifactPreview))
                openArtifactPreview(h,h==null?null:h.selectedArtifactPath==null?com.minion.gui.artifact.ArtifactSelection.preferred(h.session):h.selectedArtifactPath);
            else {if(h!=null)h.suppressArtifactAutoOpen=true;closeArtifactPreview();}
        });
        permissionButton = new MenuButton();
        permissionButton.getStyleClass().add("btn-ghost");
        permissionButton.getStyleClass().add("model-picker");
        permissionButton.setMaxWidth(150);
        permissionButton.setMinWidth(0);
        permissionButton.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        permissionButton.setTooltip(new Tooltip("对话级权限：仅本对话生效、不落盘、重启恢复默认。\n自动审批/完全授权后本对话主/子Agent不再弹确认；deny 规则仍拒绝。"));
        refreshPermissionMenu();
        inputView.setPermissionSelector(permissionButton);
        modelButton = new MenuButton();
        modelButton.getStyleClass().add("btn-ghost");
        modelButton.getStyleClass().add("model-picker");
        modelButton.setMaxWidth(225);
        modelButton.setMinWidth(0);
        modelButton.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        modelButton.setTooltip(new Tooltip("仅切换当前会话的模型；运行中从下次请求生效"));
        refreshModelMenu();
        inputView.setModelSelector(modelButton);
        Button taskButton=new Button("任务");
        taskButton.getStyleClass().add("workspace-top-action");
        taskButton.setGraphic(outlineIcon("M6 3h9v14H5V6z M7 8h6 M7 12h6"));
        taskButton.setTooltip(new Tooltip("主任务断点、子Agent、队列、上下文预算和旁支提问"));
        taskButton.setOnAction(e -> { SessionHandle h=manager.currentSession(); if(h!=null) new TaskDashboard(stage,manager,h).show(); });
        tabsBar = new HBox(5, tabs, taskButton, reportButton);
        tabsBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(tabs, Priority.ALWAYS);
        tabsBar.getStyleClass().add("tabs-bar");
        tabs.getTabs().addListener((ListChangeListener<Tab>) c -> {
            boolean empty = tabs.getTabs().isEmpty();
            tabsBar.setVisible(!empty);
            tabsBar.setManaged(!empty);
        });
        // 启动时无页签（懒加载）；注册监听后立即同步一次初始可见性
        tabsBar.setVisible(!tabs.getTabs().isEmpty());
        tabsBar.setManaged(!tabs.getTabs().isEmpty());
        right.getChildren().setAll(tabsBar, workspaceSplit);

        // 右侧面板外包 StackPane：ConfirmSheet 遮罩与卡片挂其顶层（遮罩范围即右侧，不越分隔线）
        StackPane rightStack = new StackPane(right);
        ConfirmSheet.setHost(rightStack);
        // 工具提问弹窗显示期间隐藏运行状态指示器（防"等待用户操作"被误判为卡死），关闭后恢复
        ConfirmSheet.setVisibilityListener(showing -> Platform.runLater(() -> {
            if (showing) runningIndicator.suspend(); else runningIndicator.resume();
        }));

        // 外层侧栏分隔条与右侧报告分隔条互不影响。
        SplitPane center = SidebarSplit.create(sidebar, rightStack);
        sidebar.maxWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(180, Math.min(420, center.getWidth() - 606)), center.widthProperty()));
        root.setCenter(center);

        // 注册 manager 监听（Tab 维护；内容与 Task 5 一致，含 clearChatPane）
        manager.addListener(new SessionManager.Listener() {
            @Override public void onSessionQueueChanged(SessionHandle h) {
                inputView.onQueueChanged(h);
            }
            @Override public void onSessionTitleChanged(SessionHandle h) {
                Platform.runLater(() -> {
                    updateTab(h);
                    refreshSidebar(); // 重命名后侧栏列表同步新标题（refresh 仅重建 cell，不触发回调，无通知循环）
                });
            }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) {
                if(running)h.suppressArtifactAutoOpen=false;
                Platform.runLater(() -> {
                    updateTab(h);
                    refreshSidebar();
                });
                if (inputView != null) inputView.onRunningChanged(h, running);
                // 仅当前激活会话反映到指示器（chatView 字段 FX 线程读写，统一 runLater 访问）
                Platform.runLater(() -> {
                    if (chatView != null && chatView.handle() == h) {
                        runningIndicator.setRunning(running);
                        runningIndicator.setCompressing(false); // 运行结束复位压缩态
                    }
                });
            }
            @Override public void onSessionViewed(SessionHandle h) {
                Platform.runLater(() -> {
                    updateTab(h);
                    refreshSidebar();
                });
            }
            @Override public void onExternalWaitChanged(SessionHandle h, String jobId) {
                Platform.runLater(()->{if(chatView!=null&&chatView.handle()==h)runningIndicator.setExternalWait(jobId!=null);});
            }
            @Override public void onCompressingChanged(SessionHandle h, boolean compressing) {
                if (inputView != null) inputView.setCompressing(h, compressing);
                Platform.runLater(() -> {
                    if (chatView != null && chatView.handle() == h) { // 仅当前激活会话
                        runningIndicator.setCompressing(compressing);
                    }
                });
            }
            @Override public void onRetryProgress(SessionHandle h, RetryProgress p) {
                Platform.runLater(() -> {
                    if (chatView != null && chatView.handle() == h) { // 仅当前激活会话
                        runningIndicator.setRetryProgress(p);
                    }
                });
            }
            @Override public void onContextStatsChanged(SessionHandle h, int used, int max) {
                if (inputView != null) inputView.onContextStats(h, used, max);
            }
            @Override public void onArtifactCreated(SessionHandle h, String path) {
                Platform.runLater(() -> {
                    if (chatView != null && com.minion.gui.artifact.ArtifactSelection.sameOwner(chatView.handle(),manager.currentSession(),h)&&!h.deleted) {
                        updateReportButton(h);
                        artifactPreview.setArtifacts(com.minion.gui.artifact.ArtifactSelection.ordered(h.session));
                        com.minion.core.agent.GeneratedArtifact item=com.minion.gui.artifact.ArtifactSelection.find(h.session,path);
                        String preferred=com.minion.gui.artifact.ArtifactSelection.preferred(h.session);
                        if(!h.suppressArtifactAutoOpen
                                &&com.minion.gui.artifact.ArtifactSelection.presentable(item)
                                &&preferred!=null&&preferred.equals(path)
                                &&artifactPreview.followsNewArtifacts())
                            openArtifactPreview(h,path,true);
                    }
                });
            }
            @Override public void onSessionAskChanged(SessionHandle h, boolean asking, String question) {
                if (inputView != null) inputView.onAskChanged(h, asking, question);
                // AskUserQuestion 挂起等待回答期间会话 running 仍为 true（AgentLoop 阻塞等待），
                // 齿轮指示器会误显示"正在加载"；挂起时隐藏（保留运行态），回答完成/中断后恢复（同 ConfirmSheet 先例）
                Platform.runLater(() -> {
                    if (chatView != null && chatView.handle() == h) { // 仅当前激活会话
                        if (asking) runningIndicator.suspend();
                        else runningIndicator.resume();
                    }
                });
            }
            @Override public void onSessionActivated(SessionHandle h) {
                Platform.runLater(() -> {
                    if(h.deleted || manager.currentSession()!=h)return;
                    if(projectSidebar!=null)projectSidebar.reveal(h);
                    synchronizingTabs=true;
                    try {
                        if (!openedIds.contains(h)) {
                            openedIds.add(h);
                            addTab(h);
                        } else {
                            updateTab(h);
                        }
                        selectTab(h);
                    } finally {
                        synchronizingTabs=false;
                    }
                    refreshModelMenu();
                    closeArtifactPreview(); // 预览属于原会话；新会话可通过“成果”再次打开
                    artifactPreview.clear();
                    updateReportButton(h);
                    if (chatView != null) chatView.rememberVvalue(chatScroll.getVvalue()); // 切走前记滚动位置
                    ChatView cached = viewCache.get(h);
                    if (cached != null) {
                        // 缓存命中：不重建消息区，只增量重放上次绑定后的新事件（毫秒级），恢复滚动位置
                        chatView = cached;
                        chatView.bind(true);
                        chatScroll.setContent(chatView);
                        chatScroll.setVvalue(chatView.savedVvalue());
                        if (inputView != null) inputView.bindSession(h);
                        refreshSidebar(); // 激活即刷新该会话相对时间（不停留切换前旧值）
                        runningIndicator.setRunning(h.running);
                    runningIndicator.setExternalWait(h.externalJob!=null); // 切会话：按新会话运行态刷新指示器
                        runningIndicator.setCompressing(false); // 切会话复位压缩态（压缩事件按会话隔离）
                        return;
                    }
                    chatView = ChatView.forSession(h);
                    chatView.setFileLinkHandler(href->{if(manager.currentSession()==h)fileLinks.open(sessionFileLink(h,href));},
                            href->fileLinks.menu(sessionFileLink(h,href),path->openArtifactPreview(h,path.toString())));
                    chatView.setOpenArtifactArchive(() -> {
                        if(manager.currentSession()!=h || h.deleted) return;
                        openArtifactPreview(h, null);
                        artifactPreview.showArtifactIndex();
                    });
                    viewCache.put(h, chatView);
                    chatView.setScrollBottomRequest(() -> {
                        policy.forceFollow();
                        Platform.runLater(() -> chatScroll.setVvalue(1.0)); // 布局完成后置底
                    });
                    // 截断保活补偿：头部段被删 → 内容变矮，vvalue（归一化）不变时视口绝对位置下移。
                    // 双 runLater 等布局跑完再读高度（layoutBounds 是布局缓存，删除后未布局仍返回旧值）；
                    // 贴底（v=1）时公式自然收敛 1.0，无需分支。
                    chatView.setTrimListener(removedH -> Platform.runLater(() -> Platform.runLater(() -> {
                        Node content = chatScroll.getContent();
                        double viewport = chatScroll.getViewportBounds().getHeight();
                        double hNow = content != null ? content.getLayoutBounds().getHeight() : 0;
                        double hOld = hNow + removedH;              // 截断前内容高度
                        double scrollableOld = hOld - viewport;     // 截断前可滚动行程
                        double scrollableNew = hNow - viewport;     // 截断后可滚动行程
                        if (scrollableOld <= 0 || scrollableNew <= 0) return;
                        double v = chatScroll.getVvalue();
                        double v2 = v * scrollableOld / scrollableNew; // 视口顶部绝对位置守恒
                        chatScroll.setVvalue(Math.max(0.0, Math.min(1.0, v2)));
                    })));
                    chatView.bind(true);
                    chatScroll.setContent(chatView);
                    if (inputView != null) inputView.bindSession(h);
                    refreshSidebar(); // 激活即刷新该会话相对时间（不停留切换前旧值）
                    runningIndicator.setRunning(h.running);
                    runningIndicator.setExternalWait(h.externalJob!=null); // 切会话：按新会话运行态刷新指示器
                    runningIndicator.setCompressing(false); // 切会话复位压缩态（压缩事件按会话隔离）
                });
            }
            @Override public void onWorkspaceChanged() {
                Platform.runLater(() -> {
                    clearChatPane();
                    viewCache.clear(); // 工作空间切换：不跨空间残留缓存视图；openedIds 保留（页签与空间无关）
                    wsList.refresh();
                    refreshSidebar();
                    refreshModelMenu();
                });
            }
            @Override public void onSessionDeleted(SessionHandle h) {
                Platform.runLater(() -> {
                    removeTab(h);
                    openedIds.remove(h);
                    viewCache.remove(h); // 死页签清理（幂等：与 SessionListView.onDeleted 回调共存）
                    if (chatView != null && chatView.handle() == h) clearChatPane();
                });
            }
            @Override public void onError(String message) {
                Platform.runLater(() -> {
                    if (chatView != null) chatView.appendSystemLine(message);
                    System.err.println("[minion] " + message);
                });
            }
        });

        Scene scene = new Scene(frame);
        scene.getStylesheets().addAll(Theme.STYLESHEET, Theme.WORKBENCH);
        stage.setScene(scene);

        // §10.1 快捷键注册表驱动（keybindings.json 可覆盖；切片 34）：
        // Ctrl+T/B 任务与后台面板、Ctrl+O 转录、Alt+T 思考开关、Shift+Tab 权限模式循环、
        // Alt+P 模型选择（提示经 /model）、Ctrl+R 历史搜索（提示经 /resume）；Esc 序列独立处理
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            String action = manager.keyBindings().match(e.isControlDown(), e.isAltDown(),
                    e.isShiftDown(), e.getCode().getName().toLowerCase());
            if (action != null) {
                SessionHandle h = manager.currentSession();
                if (h == null) return;
                e.consume();
                if ("task_panel".equals(action) || "background_panel".equals(action)) {
                    new TaskDashboard(stage, manager, h).show();
                } else if ("transcript_toggle".equals(action)) {
                    manager.dispatchCommand(h, "/verbose");
                } else if ("thinking_toggle".equals(action)) {
                    manager.toggleThinking();
                } else if ("permission_mode_cycle".equals(action)) {
                    h.controller.onSystem("权限模式循环: " + manager.cyclePermissionMode());
                } else if ("model_select".equals(action)) {
                    manager.dispatchCommand(h, "/model");
                } else if ("history_search".equals(action)) {
                    manager.dispatchCommand(h, "/resume");
                }
                return;
            }
            // §9.3 双击 Esc（400ms 内两次）= Rewind 选择器
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                long now = System.currentTimeMillis();
                if (now - lastEscAt < 400) {
                    e.consume();
                    SessionHandle h = manager.currentSession();
                    if (h != null) com.minion.gui.dialog.RewindSelector.show(stage, manager, h);
                    lastEscAt = 0;
                } else {
                    lastEscAt = now;
                }
            }
        });

        // 系统关闭事件（Alt+F4/任务栏关闭）与自绘 ✕ 共用 confirmClose
        stage.setOnCloseRequest(e -> {
            e.consume(); // 统一走 confirmClose（stage.close() 不触发 onCloseRequest，须自行 close）
            confirmClose();
        });

        ResizeHelper.attach(stage, frame); // 无边框窗口边缘/四角缩放

        stage.show();
        // FX8 lays out an undecorated window at its actual scene width after show().
        // Set the initial pixel width once; resizing and user drags remain independent.
        Platform.runLater(() -> {
            double width = center.getWidth();
            if (width > 0) center.setDividerPositions(SidebarSplit.defaultSidebarWidth(width) / width);
        });
        WindowsTaskbar.install(stage);
    }

    private void configureWelcome() {
        placeholder.getStyleClass().add("workbench-welcome");
        StackPane mark = new StackPane(outlineIcon("M10 1l2.5 6.5L19 10l-6.5 2.5L10 19l-2.5-6.5L1 10l6.5-2.5z"));
        mark.getStyleClass().add("welcome-mark");
        mark.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Label eyebrow = new Label("MINION 工作台");
        eyebrow.getStyleClass().add("welcome-eyebrow");
        Label title = new Label("今天想处理什么？");
        title.getStyleClass().add("welcome-title");
        Label subtitle = new Label("在下方输入任务或添加文件，也可以从左侧继续已有对话。");
        subtitle.getStyleClass().add("welcome-description");
        subtitle.setWrapText(true);
        subtitle.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Button start = new Button("＋ 新建对话");
        start.getStyleClass().add("welcome-start");
        start.setOnAction(e -> onNewSession());
        VBox copy = new VBox(10, mark, eyebrow, title, subtitle, start);
        copy.setAlignment(Pos.CENTER);
        copy.setMaxWidth(410);
        placeholder.getChildren().setAll(copy);
        // 垂直居中：ScrollPane fitToHeight 对作为内容的 StackPane 不稳定生效（内容按 pref 高度
        // 顶对齐，欢迎块偏上）；直接绑定视口高度，StackPane 拉满后在几何中心摆放内容
        placeholder.minHeightProperty().bind(chatScroll.heightProperty());
    }

    private static javafx.scene.shape.SVGPath outlineIcon(String data) {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent(data);
        icon.getStyleClass().add("workbench-outline-icon");
        return icon;
    }

    private void refreshModelMenu() {
        if (modelButton == null) return;
        SessionHandle current = manager.currentSession();
        ModelConfig selected = manager.modelForSession(current);
        String selectedName = selected == null ? "" : selected.displayName;
        modelButton.setDisable(current == null);
        modelButton.setText(selectedName);
        modelButton.setAccessibleText("当前模型：" + selectedName);
        modelButton.getItems().clear();
        for (ModelConfig model : manager.models().list()) {
            final String name = model.displayName;
            MenuItem item = new MenuItem((name.equals(selectedName) ? "✓  " : "    ") + name);
            item.setOnAction(e -> {
                manager.selectModelForSession(manager.currentSession(), name);
                refreshModelMenu();
            });
            modelButton.getItems().add(item);
        }
        refreshPermissionMenu();   // 会话切换/模型刷新同点同步权限选择器显示
    }

    /** H2 对话级权限选择器：默认（逐次确认）/自动审批/完全授权；选择即写入会话态 ConfirmGate，
     *  主/子agent共用同一门，本对话两者都不再弹确认（deny 规则仍拒绝）。 */
    private void refreshPermissionMenu() {
        if (permissionButton == null) return;
        SessionHandle current = manager.currentSession();
        String choice = manager.conversationPermission();
        permissionButton.setDisable(current == null);
        permissionButton.setText("权限: " + permissionLabel(choice));
        permissionButton.setAccessibleText("对话权限：" + permissionLabel(choice));
        permissionButton.getItems().clear();
        String[][] options = {
                {"default", "默认（逐次确认）"},
                {"auto", "自动审批"},
                {"full", "完全授权"},
        };
        for (String[] option : options) {
            final String value = option[0];
            MenuItem item = new MenuItem((value.equals(choice) ? "✓  " : "    ") + option[1]);
            item.setOnAction(e -> {
                SessionHandle h = manager.currentSession();
                String feedback = manager.setConversationPermission(value);
                if (h != null && h.controller != null) h.controller.onSystem(feedback);
                refreshPermissionMenu();
            });
            permissionButton.getItems().add(item);
        }
    }

    private static String permissionLabel(String choice) {
        if ("auto".equals(choice)) return "自动审批";
        if ("full".equals(choice)) return "完全授权";
        return "默认";
    }

    /** 左侧栏底部设置入口；关闭后刷新输入区模型菜单。 */
    private void openSettings() {
        SettingsDialog.show(stage, manager.models(), manager, MinionApp.config(),
                manager.mcpManager(), manager.plugins());
        refreshModelMenu();
    }

    /**
     * 关闭确认（自绘 ✕ 按钮与 stage.setOnCloseRequest 共用）：
     * 无运行中会话直接退出；有则弹确认再退。stage.close() 不触发 onCloseRequest，须自行调用。
     * stage.close() 后补 Platform.exit()：即使有残留非模态窗口（如 JDK 版本警告），
     * 也强制 JavaFX 平台退出——否则 FX 线程不退，StatusDot 等 Timeline 动画持续刷新占 CPU。
     */
    /**
     * §10.4 两层状态栏刷新（2s Timeline）：权限模式 | 上下文占用 | 压缩剩余 |
     * 任务计数 | 自定义 statusLine（settings statusLine:{type:command,command}，
     * §10.9 信任复核+5s 超时+首行渲染）。
     */
    private void refreshStatusBar() {
        // §10.4 状态栏默认隐藏（statusbar.enabled=true 开启，或配置了自定义 statusLine 命令）
        boolean enabled = Boolean.parseBoolean(MinionApp.config().get("statusbar.enabled", "false"));
        String statusCommand = MinionApp.config().get("statusLine", "");
        boolean hasCustom = statusCommand != null && statusCommand.contains("\"command\"");
        if (!enabled && !hasCustom) {
            inputView.updateStatusBar(null);
            return;
        }
        try {
            SessionHandle h = manager.currentSession();
            StringBuilder sb = new StringBuilder();
            if (enabled && h != null && h.isLoaded()) {
                String mode = MinionApp.config().permissionMode();
                sb.append("[").append(mode).append("]");
                com.minion.core.context.ContextManager cm = h.loop.contextManager();
                if (cm != null) {
                    int used = com.minion.core.context.TokenCounter.estimateMessages(h.session.messages);
                    int max = cm.maxTokens();
                    int pct = max > 0 ? used * 100 / max : 0;
                    sb.append(" ctx ").append(pct).append("%");
                    int compactAt = cm.autoCompressAtTokens();
                    if (compactAt > 0 && used < compactAt) {
                        sb.append(" compact余").append((compactAt - used) * 100 / compactAt).append("%");
                    }
                }
                int pendingTodos = 0;
                synchronized (h.session.todos) {
                    for (com.minion.core.agent.TodoList.TodoItem t : h.session.todos.items)
                        if (!t.done) pendingTodos++;
                }
                if (pendingTodos > 0) sb.append(" 待办").append(pendingTodos);
            }
            // 自定义 statusLine（异步：不阻塞 FX 线程——后台线程执行命令，结果 runLater 更新）
            final String base = sb.toString();
            if (hasCustom) {
                com.google.gson.JsonObject spec = com.google.gson.JsonParser.parseString(statusCommand)
                        .getAsJsonObject();
                String command = spec.has("command") ? spec.get("command").getAsString() : null;
                if (command != null && !command.trim().isEmpty()) {
                    final String cmd = command.trim();
                    final SessionHandle handle = h;
                    manager.background(() -> {
                        try {
                            com.google.gson.JsonObject payload = com.minion.core.ui.statusline.StatusLineRunner
                                    .payload(handle == null ? "" : handle.session.id,
                                            MinionApp.config().get("model.name", ""),
                                            handle == null ? "" : manager.currentWorkspaceDir(),
                                            "", 0, MinionApp.config().get("output.style", "default"), "");
                            String custom = com.minion.core.ui.statusline.StatusLineRunner.run(
                                    cmd, payload,
                                    new com.minion.core.security.TrustGate(
                                            com.minion.core.security.ProjectTrustStore.load(
                                                    com.minion.core.config.Config.jarDir()),
                                            MinionApp.config().trustStoreEnabled(),
                                            com.minion.core.security.TrustGate.DENY),
                                    manager.currentWorkspaceDir());
                            final String result = custom == null ? "" : custom;
                            javafx.application.Platform.runLater(() ->
                                    inputView.updateStatusBar(
                                            (base + " " + result).trim()));
                        } catch (Exception ignored) {
                            javafx.application.Platform.runLater(() ->
                                    inputView.updateStatusBar(base));
                        }
                    });
                    return;   // 异步路径，等 runLater 更新
                }
            }
            inputView.updateStatusBar(base.isEmpty() ? null : base.toString());
        } catch (Exception ignored) {   // 状态栏失败不影响主功能
        }
    }

    private void confirmClose() {
        if (!manager.hasRunning()) {
            manager.shutdown();
            stage.close();
            Platform.exit();
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "仍有会话正在运行，确认退出？", ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);                 // 去掉左边的"确认"文字（header 行移除 → 弹窗高度减一行）
        a.getDialogPane().setGraphic(null);    // 去掉叹号圆圈图标
        a.getDialogPane().getStyleClass().add("dialog-exit"); // 正文居中
        Theme.style(a);
        a.setTitle("退出确认");
        a.showAndWait();
        if (a.getResult() == ButtonType.OK) {
            manager.shutdown();
            stage.close();
            Platform.exit();
        }
    }

    /** 清空右侧面板：解绑事件流、回空白占位、解绑输入区（删除会话/切换工作空间后调用）。
     *  视图内容保留在缓存中（下次激活增量重放），不 clear 销毁——销毁由 viewCache 清理（删除/切空间）连带 GC。 */
    private void clearChatPane() {
        if (chatView != null) chatView.bind(false); // 分离监听器（EventList 缓冲保留，视图缓存保留）
        chatView = null;
        chatScroll.setContent(placeholder);
        if (inputView != null) inputView.bindSession(null); // current=null → 下次发送自动建会话
        runningIndicator.setRunning(false); // 无活动会话：指示器隐藏并停动画
        runningIndicator.setCompressing(false);
        updateReportButton(null);
        closeArtifactPreview();
        refreshModelMenu();
    }

    private void updateReportButton(SessionHandle h) {
        if (reportButton == null) return;
        int count=h==null?0:com.minion.gui.artifact.ArtifactSelection.ordered(h.session).size();
        reportButton.setText(count==0?"工作区":"工作区 · "+count);
    }

    private void openArtifactPreview(String path) {
        openArtifactPreview(manager.currentSession(),path);
    }
    private void openArtifactPreview(SessionHandle owner,String path) {
        openArtifactPreview(owner,path,false);
    }
    private void openArtifactPreview(SessionHandle owner,String path,boolean updated) {
        if(owner!=null&&(owner.deleted||chatView==null||!com.minion.gui.artifact.ArtifactSelection.sameOwner(chatView.handle(),manager.currentSession(),owner)))return;
        if(owner==null&&path!=null)return;
        previewOwner=owner;
        artifactPreview.setLinkMenus(href->fileLinks.menu(href,p->openArtifactPreview(owner,p.toString())));
        if (!workspaceSplit.getItems().contains(artifactPreview)) {
            workspaceSplit.getItems().add(artifactPreview);
            restoreWorkspaceDivider();
            reportButton.getStyleClass().add("workspace-top-active");
        }
        SessionHandle h=owner==null?manager.currentSession():owner;
        artifactPreview.setArtifacts(h==null?java.util.Collections.emptyList():com.minion.gui.artifact.ArtifactSelection.ordered(h.session));
        if(path!=null&&!path.trim().isEmpty()){
            if(updated)artifactPreview.openUpdated(path);else artifactPreview.open(path);
        }
    }

    private void closeArtifactPreview() {
        if (workspaceSplit != null && artifactPreview != null) {
            if(workspaceFocused)focusArtifactWorkspace(false);
            rememberWorkspaceDivider();
            artifactPreview.suspend();
            previewOwner=null;
            workspaceSplit.getItems().remove(artifactPreview);
            if(reportButton!=null)reportButton.getStyleClass().remove("workspace-top-active");
        }
    }

    private void rememberWorkspaceDivider(){
        if(workspaceSplit.getItems().size()!=2)return;
        double pos=workspaceSplit.getDividerPositions()[0];
        if(pos>=0.22&&pos<=0.80)workspaceDivider=pos;
    }

    private void restoreWorkspaceDivider() {
        workspaceSplit.setDividerPositions(Double.isNaN(workspaceDivider)
                ? SidebarSplit.defaultConversationShare(workspaceSplit.getWidth()) : workspaceDivider);
    }

    private void focusArtifactWorkspace(boolean expand){
        if(expand==workspaceFocused||!workspaceSplit.getItems().contains(artifactPreview))return;
        if(expand){rememberWorkspaceDivider();workspaceSplit.getItems().remove(conversationColumn);}
        else if(!workspaceSplit.getItems().contains(conversationColumn)){
            workspaceSplit.getItems().add(0,conversationColumn);restoreWorkspaceDivider();
        }
        workspaceFocused=expand;artifactPreview.setFocusedMode(expand);
    }

    private java.nio.file.Path fileLinkBase() {
        SessionHandle h=manager.currentSession();
        return fileLinkBase(h);
    }
    private java.nio.file.Path fileLinkBase(SessionHandle h) {
        if(h!=null&&h.session!=null) {
            String path=h.session.cwd==null||h.session.cwd.trim().isEmpty()?h.session.workDir:h.session.cwd;
            if(path!=null)return java.nio.file.Paths.get(path).toAbsolutePath().normalize();
        }
        return java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
    private String sessionFileLink(SessionHandle h,String href) {
        try{return com.minion.gui.artifact.LocalFileLinks.resolve(href,fileLinkBase(h)).toUri().toASCIIString();}
        catch(Exception invalidOrWeb){return href;}
    }
    private void fileLinkError(String message) {
        Alert alert=new Alert(Alert.AlertType.INFORMATION,message,ButtonType.OK);
        alert.setTitle("文件操作");alert.setHeaderText(null);alert.initOwner(stage);Theme.style(alert);alert.show();
    }

    /** 自动滚动节流：流式增长触发的 setVvalue(1.0) 合并为每 150ms 至多一次。
     *  背景：JavaFX 8 ScrollPaneSkin 硬编码视口缓存，每次滚动都全量重绘离屏纹理上传 GPU，
     *  聊天内容大（长会话）且 es2 管线在 4K/老驱动下时，高频滚动会堵死渲染队列导致界面假死。
     *  节流显著降低重绘频率；最后一次请求以 ≤150ms 延迟保证执行（pending 标志防丢尾）。 */
    private final PauseTransition scrollThrottle = new PauseTransition(Duration.millis(150));
    private boolean scrollPending = false;

    private void requestAutoScroll() {
        if (scrollPending) return;
        scrollPending = true;
        scrollThrottle.setOnFinished(e -> {
            scrollPending = false;
            if (policy.shouldFollow()) chatScroll.setVvalue(1.0); // 完成后再确认仍贴底
        });
        scrollThrottle.playFromStart();
    }

    private void jumpToLatest() {
        policy.forceFollow();
        chatScroll.setVvalue(1.0);
        updateJumpLatestVisibility();
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    private void updateJumpLatestVisibility() {
        if (jumpLatestButton == null) return;
        boolean show = chatView != null && policy.shouldShowJumpButton();
        jumpLatestButton.setVisible(show);
        jumpLatestButton.setManaged(show);
    }

    /** 需求：消息区自动滚动——贴底时随新内容滚到底，离开底部即暂停，拖回底部恢复。
     *  vmax 恒 1.0 不可用（vvalue 为归一化比例），内容增长改监听内容节点高度变化；
     *  会话切换时内容节点被替换，须随 contentProperty 重挂监听 */
    private void setupAutoScroll() {
        // 按实际管线分流：软件渲染缓存视口，硬件管线关闭离屏纹理缓存。
        chatScroll.skinProperty().addListener((obs, ov, nv) -> {
            if (nv != null) Platform.runLater(this::applyViewportCache);
        });
        javafx.beans.value.ChangeListener<javafx.geometry.Bounds> contentGrew = (obs, o, n) -> {
            if (policy.shouldFollow()) requestAutoScroll();
            updateJumpLatestVisibility();
        };
        chatScroll.vvalueProperty().addListener((obs, ov, nv) -> {
            policy.sync(nv.doubleValue(), eps());
            updateJumpLatestVisibility();
        });
        chatScroll.contentProperty().addListener((obs, ov, nv) -> {
            if (ov != null) ov.layoutBoundsProperty().removeListener(contentGrew);
            if (nv != null) nv.layoutBoundsProperty().addListener(contentGrew);
        });
    }

    /** 软件管线启用视口缓存，避免流式更新反复全量绘制。 */
    private void applyViewportCache() {
        Node viewport = chatScroll.lookup(".viewport");
        if (viewport == null) return;
        boolean software = RenderPipeline.isSoftware();
        viewport.setCache(software);
        if (software) viewport.setCacheHint(CacheHint.SPEED);
    }

    private double eps() {
        double viewport = chatScroll.getViewportBounds().getHeight();
        double content = chatScroll.getContent() != null
                ? chatScroll.getContent().getLayoutBounds().getHeight() : 0;
        double scrollable = content - viewport;
        return scrollable <= 0 ? 1.0 : 0.5 * viewport / scrollable;
    }

    private void refreshSidebar(){
        if(projectSidebar!=null){projectSidebar.refresh();if(projectSidebar.legacyMode())sessionList.refresh();}
        else if(sessionList!=null)sessionList.refresh();
    }
    private void onNewSession() {
        SessionHandle h = manager.createSession(null); // titlePending，无页签
        if (h == null) return; // 终审修复：当前空间删除中（后台 awaitTermination ≤5s）被点击，createSession 返回 null，忽略即可
        refreshSidebar(); // createSession 无 Listener 通知，UI 层自行刷新
        manager.activateSession(h);
        // 会话激活后，onSessionActivated 回调在 show() 注册的监听器中绑定消息区与输入区
    }

    /** 从零创建项目脚手架，注册为空间并立即进入首个会话。 */
    private void onNewProject(WorkspaceListView wsList) {
        java.util.List<String> existing = new java.util.ArrayList<String>();
        for (WorkspaceConfig w : manager.workspaces().list()) existing.add(w.workSpaceName);
        NewProjectDialog d = new NewProjectDialog(existing);
        Optional<NewProjectDialog.Value> result = d.showAndWait();
        if (!result.isPresent()) return;
        NewProjectDialog.Value v = result.get();
        try {
            ProjectTool.createAt(v.projectDir, v.template);
            java.nio.file.Path skills = v.projectDir.resolve("skills");
            java.nio.file.Files.createDirectories(skills);
            java.nio.file.Path projectMd = v.projectDir.resolve("project.md");
            String md = "# " + v.name + "\n\n"
                    + "## 项目目标\n\n请在首次对话中补充本项目要解决的问题、输入数据和期望产物。\n\n"
                    + "## 技术基线\n\n- Windows 7\n- Python 3.7.6 / Java 8\n- 模板：" + v.template + "\n";
            java.nio.file.Files.write(projectMd, md.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!manager.addWorkspace(v.name, v.projectDir.toString(), projectMd.toString(), skills.toString())) {
                throw new IllegalStateException("工作空间名称非法或已存在");
            }
            manager.switchWorkspace(v.name);
            wsList.refresh();
            onNewSession();
        } catch (Exception ex) {
            Alert a = new Alert(Alert.AlertType.ERROR, "创建项目失败：" + ex.getMessage(), ButtonType.OK);
            Theme.style(a); a.setTitle("新建项目失败"); a.setHeaderText(null); a.showAndWait();
        }
    }

    /** 打开已有项目弹窗（登记为工作空间，不创建或修改项目文件）。 */
    private void onNewWorkspace(WorkspaceListView wsList) {
        java.util.List<String> existing = new java.util.ArrayList<String>();
        for (WorkspaceConfig w : manager.workspaces().list()) existing.add(w.workSpaceName);
        WorkspaceFormDialog d = new WorkspaceFormDialog("打开已有项目",
                "将已有项目文件夹加入 Agent", null, existing);
        Optional<WorkspaceConfig> r = d.showAndWait();
        if (!r.isPresent()) return;
        WorkspaceConfig v = r.get();
        if (!manager.addWorkspace(v.workSpaceName, v.workDir, v.projectMd, v.projectSkillsDir)) {
            Alert a = new Alert(Alert.AlertType.ERROR,
                    "名称非法或已存在；或路径不合法：项目路径须为已存在文件夹、"
                            + "主说明文件须为已存在文件、技能路径须为已存在文件夹"
                            + "（后两项留空视为合法）", ButtonType.OK);
            Theme.style(a);
            a.setTitle("新建失败");
            a.showAndWait();
        }
        wsList.refresh();refreshSidebar();
    }

    private static final int TAB_TITLE_MAX = 16; // 页签标题截取长度（过长撑宽页签栏致标题栏错乱）

    /** 页签标题：超 16 字符截断加省略号（完整标题挂 Tooltip，信息不丢失） */
    static String tabTitle(String title) {
        if (title != null && title.length() > TAB_TITLE_MAX) {
            return title.substring(0, TAB_TITLE_MAX) + "…";
        }
        return title;
    }

    private void updateTab(SessionHandle h) {
        for (Tab t : tabs.getTabs()) {
            if (h == t.getUserData()) {
                String title = h.title == null ? "(新会话)" : h.title;
                t.setText(tabTitle(title));
                t.setTooltip(new Tooltip(h.workspaceName+" / "+title));
                updateTabStatus(t, h.activityState());
                return;
            }
        }
        if (openedIds.contains(h)) addTab(h); // 补建页签（无标题会话标题生成后；已关闭页签的不复活——openedIds 已移除）
    }

    private void addTab(SessionHandle h) {
        String title = h.title == null ? "(新会话)" : h.title;
        Tab t = new Tab(tabTitle(title));
        t.setUserData(h);
        t.setTooltip(new Tooltip(h.workspaceName+" / "+title));
        updateTabStatus(t, h.activityState());
        t.setClosable(true);
        t.setOnCloseRequest(e -> {
            e.consume(); // 不触发 TabPane 默认移除，由本流程显式移除
            if (h.running) {
                // 运行中：确认才关（关闭 = 中断运行，会话不删除）
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "会话「" + (h.title == null ? "(新会话)" : h.title) + "」正在运行，关闭将中断运行，确认关闭？",
                        ButtonType.OK, ButtonType.CANCEL);
                a.setHeaderText(null);                 // 去掉左边的"确认"文字
                a.getDialogPane().setGraphic(null);    // 去掉叹号圆圈图标
                a.getDialogPane().getStyleClass().add("dialog-exit"); // 正文居中
                Theme.style(a);
                a.setTitle("关闭会话");
                a.showAndWait();
                if (a.getResult() != ButtonType.OK) return; // 取消：页签保留、运行继续
                manager.stop(h); // 中断运行（会话不删除，保留在列表）
            }
            // 关闭流程：卸载视图缓存（再次从左侧打开时重新加载）+ 移除页签
            openedIds.remove(h);
            viewCache.remove(h);
            StatusDot.stopPulseIn(t.getGraphic());
            tabs.getTabs().remove(t);
            if (chatView != null && chatView.handle() == h) {
                manager.deactivateSession(h); // 幂等守卫不再拦截再次激活
                clearChatPane();
            }
        });
        tabs.getTabs().add(t);
        tabs.getSelectionModel().select(t);
    }

    /** 按句柄移除页签，删除一项目的会话不影响其他项目的同 ID 页签。 */
    private void removeTab(SessionHandle h) {
        for (Tab t : tabs.getTabs()) {
            if (h == t.getUserData()) {
                StatusDot.stopPulseIn(t.getGraphic());
                tabs.getTabs().remove(t);
                return;
            }
        }
    }

    private void selectTab(SessionHandle h) {
        for (Tab t : tabs.getTabs()) {
            if (h == t.getUserData()) {
                tabs.getSelectionModel().select(t);
                return;
            }
        }
    }

    static void updateTabStatus(Tab tab, SessionHandle.ActivityState state) {
        // 与左侧列表共用状态：运行黄灯、回合结束绿灯，查看后隐藏。
        // 替换旧节点前停止呼吸动画，避免 Timeline 持有已卸载的页签节点。
        StatusDot.stopPulseIn(tab.getGraphic());
        tab.setGraphic(StatusDot.create(state));
    }
}
