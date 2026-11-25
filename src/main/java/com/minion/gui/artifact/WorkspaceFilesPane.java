package com.minion.gui.artifact;

import com.minion.gui.icon.IconFactory;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** On-demand single-directory view. It never recursively indexes a project or follows links out of it. */
public final class WorkspaceFilesPane extends VBox implements AutoCloseable {
    private static final int LIMIT=400;
    private static final Set<String> INTERNAL=new HashSet<>(Arrays.asList(".git",".minion",".session"));
    private final ListView<Row> rows=new ListView<>();
    private final Label location=new Label(), message=new Label();
    private final Button parent=new Button("上一级");
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(1),r->{
        Thread t=new Thread(r,"minion-workspace-files");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.DiscardOldestPolicy());
    private long revision;
    private Future<?> pending;
    private Path root, current;

    public WorkspaceFilesPane(Consumer<Path> open,Function<String,ContextMenu> menu){
        super(7);getStyleClass().add("workspace-files");
        location.getStyleClass().add("artifact-meta");location.setMinWidth(0);location.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(location,Priority.ALWAYS);
        Button refresh=new Button("刷新");refresh.setOnAction(e->load(current));
        HBox header=new HBox(6,parent,location,refresh);header.setAlignment(Pos.CENTER_LEFT);
        parent.setOnAction(e->{if(root!=null&&current!=null&&!root.equals(current))load(current.getParent());});
        rows.setMinSize(0,0);rows.setPlaceholder(new Label("本目录没有可预览的文件"));VBox.setVgrow(rows,Priority.ALWAYS);
        rows.setCellFactory(list->new ListCell<Row>(){
            @Override protected void updateItem(Row row,boolean empty){
                super.updateItem(row,empty);
                getStyleClass().removeAll("workspace-file-dir","workspace-file-item");
                if(empty||row==null){setText(null);setGraphic(null);setTooltip(null);setContextMenu(null);return;}
                SVGPath icon=row.directory?IconFactory.folder():IconFactory.fileDoc();
                IconFactory.size(icon,15);
                StackPane iconBox=new StackPane(icon);
                iconBox.setMinWidth(18);iconBox.setPrefWidth(18);
                setGraphic(iconBox);
                setGraphicTextGap(8);
                setText(row.path.getFileName().toString());
                getStyleClass().add(row.directory?"workspace-file-dir":"workspace-file-item");
                setTooltip(new Tooltip(row.path.toString()));
                setContextMenu(row.directory?null:menu.apply(row.path.toUri().toString()));
            }
        });
        Runnable select=()->{Row row=rows.getSelectionModel().getSelectedItem();if(row!=null){if(row.directory)load(row.path);else open.accept(row.path);}};
        rows.setOnMouseClicked(e->{if(e.getButton()==MouseButton.PRIMARY&&e.getClickCount()==2)select.run();});
        rows.setOnKeyPressed(e->{if(e.getCode()==javafx.scene.input.KeyCode.ENTER){select.run();e.consume();}});
        Button preview=new Button("打开所选项");preview.disableProperty().bind(rows.getSelectionModel().selectedItemProperty().isNull());
        preview.setOnAction(e->select.run());message.getStyleClass().add("artifact-meta");message.setWrapText(true);
        getChildren().addAll(header,rows,new HBox(8,preview,message));
    }
    public void showRoot(Path root){
        this.root=root==null?null:root.toAbsolutePath().normalize();
        load(this.root);
    }
    private void load(Path path){
        final long ticket=++revision;
        if(pending!=null)pending.cancel(true);
        rows.getItems().clear();current=path;
        Path base=root;
        if(path==null||base==null){location.setText("未选择项目");message.setText("请先打开一个项目。");parent.setDisable(true);return;}
        location.setText(base.equals(path)?"项目根目录":base.relativize(path).toString());
        location.setTooltip(new Tooltip(path.toString()));parent.setDisable(base.equals(path));message.setText("正在读取…");
        pending=io.submit(()->{
            try{
                Scan scan=list(base,path);
                Platform.runLater(()->{
                    if(ticket!=revision)return;
                    rows.getItems().setAll(scan.rows);
                    message.setText(scan.limited?"本目录只列前 "+LIMIT+" 项":"双击文件预览，右键打开或定位");
                });
            }catch(IOException|RuntimeException e){Platform.runLater(()->{if(ticket==revision)message.setText("无法读取此目录："+e.getMessage());});}
        });
    }
    /** F4 手动扫描：递归 ≤3 层、检查 ≤2000 项、收集 ≤400 个交付扩展名文件；
     *  排除 .git/.minion/.session、符号链接与越界路径（复用 list 的护栏）。仅用户显式点击调用。 */
    public static List<Path> scanDeliverables(Path base)throws IOException {
        Path realBase=base.toRealPath();
        List<Path> out=new ArrayList<>();
        scanDeliverables(realBase,realBase,0,new int[]{0},new boolean[]{false},out);
        out.sort(Comparator.comparing(p->p.getFileName().toString(),String.CASE_INSENSITIVE_ORDER));
        return out;
    }
    private static void scanDeliverables(Path realBase,Path dir,int depth,int[] checked,boolean[] limited,List<Path> out)throws IOException {
        if(depth>3||limited[0])return;
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir)){
            for(Path p:stream){
                if(Thread.currentThread().isInterrupted())throw new IOException("目录读取已取消");
                if(++checked[0]>2000||out.size()>=LIMIT){limited[0]=true;return;}
                String name=p.getFileName().toString().toLowerCase(Locale.ROOT);
                if(INTERNAL.contains(name))continue;
                try{
                    if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(realBase))continue;
                    if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))scanDeliverables(realBase,p,depth+1,checked,limited,out);
                    else if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&isDeliverable(name))out.add(p);
                }catch(NoSuchFileException|AccessDeniedException ignored){/* entries can change during a scan */}
            }
        }
    }
    private static boolean isDeliverable(String lowerName){
        for(String suffix:new String[]{".doc",".docx",".xls",".xlsx",".ppt",".pptx",".pdf",".md",".csv",".html",".htm",".png",".jpg",".jpeg"})
            if(lowerName.endsWith(suffix))return true;
        return false;
    }
    static Scan list(Path base,Path directory)throws IOException {
        Path realBase=base.toRealPath(), real=directory.toRealPath();
        if(!real.startsWith(realBase))throw new IOException("不能查看工作区之外的目录");
        List<Row> files=new ArrayList<>();boolean limited=false;
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(directory)){
            int checked=0;
            for(Path p:stream){
                if(Thread.currentThread().isInterrupted())throw new IOException("目录读取已取消");
                if(++checked>2000||files.size()>=LIMIT){limited=true;break;}
                if(INTERNAL.contains(p.getFileName().toString().toLowerCase(Locale.ROOT)))continue;
                try {
                    if(Files.isSymbolicLink(p)||!p.toRealPath().startsWith(realBase))continue;
                    boolean folder=Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS);
                    if(folder||Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))files.add(new Row(p,folder));
                } catch(NoSuchFileException|AccessDeniedException ignored){/* entries can change during a scan */}
            }
        }
        files.sort(Comparator.comparing((Row r)->!r.directory).thenComparing(r->r.path.getFileName().toString(),String.CASE_INSENSITIVE_ORDER));
        return new Scan(files,limited);
    }
    public void clear(){revision++;if(pending!=null)pending.cancel(true);root=null;current=null;rows.getItems().clear();}
    @Override public void close(){clear();io.shutdownNow();}
    static final class Row {
        final Path path;final boolean directory;
        Row(Path path,boolean directory){this.path=path;this.directory=directory;}
    }
    static final class Scan {
        final List<Row> rows;final boolean limited;
        Scan(List<Row> rows,boolean limited){this.rows=rows;this.limited=limited;}
    }
}
