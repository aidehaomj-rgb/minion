package com.minion.gui.chat;
import com.minion.gui.session.*;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.*;
import javafx.scene.control.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ChatViewArtifactLinksTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static void fx(Runnable r)throws Exception{new JFXPanel();CompletableFuture<Void> done=new CompletableFuture<Void>();Platform.runLater(()->{try{r.run();done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});done.get(10,TimeUnit.SECONDS);}
    private static List<Hyperlink> links(Node node){List<Hyperlink> all=new ArrayList<Hyperlink>();if(node instanceof Hyperlink)all.add((Hyperlink)node);if(node instanceof Parent)for(Node child:((Parent)node).getChildrenUnmodifiable())all.addAll(links(child));return all;}
    @Test public void realFileEventBecomesClickableCardWithRevealMenuAndDeduplicatesUpdates()throws Exception {
        Path file=temp.newFile("中文 报告.docx").toPath();EventList events=new EventList();ChatView[] view={null};List<String> opened=new ArrayList<String>();
        fx(()->{view[0]=new ChatView(events,null);view[0].setFileLinkHandler(opened::add,href->{MenuItem reveal=new MenuItem("打开文件位置");return new ContextMenu(reveal);});view[0].bind(true);events.add(new EventList.Ev(EventList.Kind.ARTIFACT,file.toString(),null));events.add(new EventList.Ev(EventList.Kind.ARTIFACT,file.toString(),null));});
        try{fx(()->{List<Hyperlink> found=links(view[0]);assertEquals(1,found.size());found.get(0).fireEvent(new javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,0,0,10,10,false,null));assertEquals("打开文件位置",found.get(0).getContextMenu().getItems().get(0).getText());found.get(0).fire();assertEquals(file,Paths.get(URI.create(opened.get(0))));});}
        finally{fx(()->view[0].bind(false));}
    }
    @Test public void replyFinalizationPublishesClickableMarkdownLinkWithoutNewUserMessage()throws Exception {
        Path file=temp.newFile("result.md").toPath();EventList events=new EventList();ChatView[] view={null};List<String> opened=new ArrayList<String>();
        fx(()->{view[0]=new ChatView(events,null);view[0].setFileLinkHandler(opened::add,h->new ContextMenu());view[0].bind(true);events.add(new EventList.Ev(EventList.Kind.CONTENT,"[查看结果]("+file.toUri().toASCIIString()+")",null));events.add(new EventList.Ev(EventList.Kind.RESPONSE_END,null,null));});
        try{fx(()->{List<Hyperlink> found=links(view[0]);assertEquals(1,found.size());found.get(0).fire();assertEquals(file,Paths.get(URI.create(opened.get(0))));});}
        finally{fx(()->view[0].bind(false));}
    }
}
