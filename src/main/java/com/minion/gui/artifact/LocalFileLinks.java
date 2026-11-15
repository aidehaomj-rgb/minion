package com.minion.gui.artifact;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Explicit file gestures only. No shell command concatenation, automatic execution or cloud dependency. */
public final class LocalFileLinks implements AutoCloseable {
    interface Backend { void open(Path path)throws Exception; void reveal(Path path)throws Exception; void browse(URI uri)throws Exception; }
    private static final Set<String> EXECUTABLE=new HashSet<String>(Arrays.asList(
            "exe","com","scr","msi","msp","bat","cmd","ps1","psm1","vbs","vbe","js","jse","wsf","wsh","hta","jar","lnk","url","reg","application",
            "py","pyw","sh","bash","zsh","rb","pl","r","lua","ahk","cpl","dll","chm","pif","msc","scf","sct","gadget","appx","appxbundle","msix","msixbundle","inf","diagcab","settingcontent-ms","search-ms","library-ms"));
    private final Supplier<Path> base;
    private final Supplier<Window> owner;
    private final Consumer<String> errors;
    private final Consumer<Path> preview;
    private final Backend backend;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,2,15,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(16),r->{
        Thread t=new Thread(r,"minion-file-action");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.AbortPolicy());
    public LocalFileLinks(Supplier<Path> base,Supplier<Window> owner,Consumer<Path> preview,Consumer<String> errors) {
        this(base,owner,preview,errors,new SystemBackend());
    }
    LocalFileLinks(Supplier<Path> base,Supplier<Window> owner,Consumer<Path> preview,Consumer<String> errors,Backend backend) {
        this.base=base;this.owner=owner;this.preview=preview;this.errors=errors;this.backend=backend;
    }
    public static Path resolve(String value,Path base)throws IOException {
        if(value==null||value.trim().isEmpty())throw new IOException("文件链接为空");
        String raw=value.trim();if(raw.startsWith("<")&&raw.endsWith(">"))raw=raw.substring(1,raw.length()-1);
        for(int i=0;i<raw.length();i++)if(Character.isISOControl(raw.charAt(i)))throw new IOException("文件链接包含无效字符");
        try {
            if(raw.regionMatches(true,0,"file:",0,5)) {
                raw=raw.replace('\\','/').replace(" ","%20");
                raw=raw.replaceFirst("(?i)^file://([a-z]):/","file:///$1:/").replaceFirst("(?i)^file:([a-z]):/","file:///$1:/");
                URI uri=new URI(raw);
                String authority=uri.getAuthority();
                if("localhost".equalsIgnoreCase(authority))authority=null;
                URI clean=new URI("file",authority,uri.getPath(),null,null);
                return Paths.get(clean).toAbsolutePath().normalize();
            }
            boolean drive=raw.matches("(?s)^[A-Za-z]:[\\\\/].*");
            if(!drive&&raw.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:.*"))throw new IOException("不是本机文件链接；请使用实际文件路径或 file:/// 链接");
            raw=raw.replaceFirst("#L[0-9]+(?:-L?[0-9]+)?$","");
            if(!drive&&!raw.startsWith("\\\\")&&raw.matches("(?is).*%[a-f0-9]{2}.*"))raw=URLDecoder.decode(raw.replace("+","%2B"),"UTF-8");
            Path path=Paths.get(raw);
            return (path.isAbsolute()?path:base.toAbsolutePath().resolve(path)).normalize();
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("文件链接无效："+e.getMessage(),e);}
    }
    public static boolean executable(Path path) {
        String name=path.getFileName()==null?"":path.getFileName().toString();int dot=name.lastIndexOf('.');
        return dot>=0&&EXECUTABLE.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));
    }
    public void open(String href) {
        try {
            if(href!=null&&href.matches("(?is)^https?://.*")){URI uri=new URI(href);submit(()->backend.browse(uri));return;}
            final Path path=resolve(href,base.get());
            submit(()->{requireFile(path);
            if(executable(path))throw new IOException("可执行文件不从聊天链接自动运行。请使用“打开文件位置”后自行核对运行："+path);
            backend.open(path);
            });
        }catch(Exception e){error(e);}
    }
    public void reveal(String href) {try{final Path path=resolve(href,base.get());submit(()->{if(!Files.exists(path))throw new IOException("文件不存在或已移动："+path);backend.reveal(path);});}catch(Exception e){error(e);} }
    public void preview(String href) {
        try {Path path=resolve(href,base.get());onFx(()->preview.accept(path));}catch(Exception e){error(e);}
    }
    public ContextMenu menu(String href) {
        return menu(href,preview);
    }
    public ContextMenu menu(String href,Consumer<Path> previewTarget) {
        ContextMenu menu=new ContextMenu();
        if(href!=null&&href.matches("(?is)^https?://.*")) {
            MenuItem web=new MenuItem("打开网页");web.setOnAction(e->open(href));menu.getItems().add(web);
            MenuItem copy=new MenuItem("复制链接");copy.setOnAction(e->copy(href));menu.getItems().add(copy);return menu;
        }
        Path path;
        try {path=resolve(href,base.get());}catch(Exception e){MenuItem invalid=new MenuItem("无效的本机文件链接");invalid.setDisable(true);menu.getItems().add(invalid);return menu;}
        String link=path.toUri().toASCIIString();
        MenuItem view=new MenuItem("在右侧预览");view.setId("file-preview");view.setOnAction(e->onFx(()->previewTarget.accept(path)));
        MenuItem open=new MenuItem("打开文件");open.setId("file-open");open.setDisable(executable(path));open.setOnAction(e->open(link));
        MenuItem reveal=new MenuItem("打开文件位置");reveal.setId("file-reveal");reveal.setOnAction(e->reveal(link));
        MenuItem pathCopy=new MenuItem("复制完整路径");pathCopy.setOnAction(e->copy(path.toString()));
        MenuItem linkCopy=new MenuItem("复制文件链接");linkCopy.setOnAction(e->copy(link));
        MenuItem fileCopy=new MenuItem("复制文件");fileCopy.setOnAction(e->{ClipboardContent c=new ClipboardContent();c.putFiles(Collections.singletonList(path.toFile()));Clipboard.getSystemClipboard().setContent(c);});
        MenuItem save=new MenuItem("另存为…");save.setId("file-save");save.setOnAction(e->saveAs(path));
        menu.getItems().addAll(view,open,reveal,new SeparatorMenuItem(),pathCopy,linkCopy,fileCopy,save);return menu;
    }
    public void saveAs(Path source) {
        FileChooser chooser=new FileChooser();chooser.setTitle("另存为（不修改原文件）");chooser.setInitialFileName(source.getFileName().toString());
        java.io.File selected=chooser.showSaveDialog(owner.get());if(selected==null)return;
        Path target=selected.toPath().toAbsolutePath().normalize();
        if(source.toAbsolutePath().normalize().equals(target)){errors.accept("已选择原文件，不需要复制。");return;}
        submit(()->{requireFile(source);Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);});
    }
    private static void requireFile(Path path)throws IOException {if(!Files.isRegularFile(path))throw new IOException("文件不存在、不是普通文件或已移动："+path);}
    private void copy(String text){ClipboardContent c=new ClipboardContent();c.putString(text);Clipboard.getSystemClipboard().setContent(c);}
    private interface Job {void run()throws Exception;}
    private void submit(Job job) {try{io.execute(()->{try{job.run();}catch(Exception e){error(e);}});}catch(RejectedExecutionException e){error(new IOException("文件操作队列繁忙或窗口已关闭，请稍后重试"));}}
    private void error(Exception e){onFx(()->errors.accept(e.getMessage()==null?e.toString():e.getMessage()));}
    private static void onFx(Runnable r){if(Platform.isFxApplicationThread())r.run();else try{Platform.runLater(r);}catch(IllegalStateException notStarted){r.run();}}
    @Override public void close(){io.shutdownNow();}
    private static final class SystemBackend implements Backend {
        @Override public void open(Path path)throws Exception {if(!Desktop.isDesktopSupported())throw new IOException("系统未提供文件关联，请在文件位置手动打开");Desktop.getDesktop().open(path.toFile());}
        @Override public void browse(URI uri)throws Exception {if(!Desktop.isDesktopSupported())throw new IOException("系统未提供默认浏览器");Desktop.getDesktop().browse(uri);}
        @Override public void reveal(Path path)throws Exception {
            if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))new ProcessBuilder("explorer.exe","/select,",path.toString()).start();
            else {Path parent=Files.isDirectory(path)?path:path.getParent();if(parent==null)throw new IOException("文件没有父目录");Desktop.getDesktop().open(parent.toFile());}
        }
    }
}
