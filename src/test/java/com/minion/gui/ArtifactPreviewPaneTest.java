package com.minion.gui;

import com.minion.core.agent.GeneratedArtifact;
import com.minion.gui.artifact.DocumentPreviewLoader;
import com.minion.gui.artifact.LocalFileLinks;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.TextFlow;
import javafx.scene.text.Text;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ArtifactPreviewPaneTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static <T>T field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(owner);}
    private static void fx(Runnable r)throws Exception {new JFXPanel();CompletableFuture<Void> done=new CompletableFuture<Void>();Platform.runLater(()->{try{r.run();done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});done.get(10,TimeUnit.SECONDS);}
    private static void await(Callable<Boolean> check)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(check.call())return;Thread.sleep(20);}fail("preview did not settle");}
    @Test public void workspaceListsOnlyUserFacingDeliverables()throws Exception{
        Path root=temp.newFolder().toPath(),markdown=root.resolve("报告.md"),word=root.resolve("附件.docx"),
                record=root.resolve("任务执行记录.md"),reference=root.resolve("输入.pdf"),code=root.resolve("main.py");
        Files.write(markdown,"# 结果".getBytes(StandardCharsets.UTF_8));Files.write(word,new byte[]{1});
        Files.write(record,"# 执行过程".getBytes(StandardCharsets.UTF_8));Files.write(reference,new byte[]{1});
        Files.write(code,"print('x')".getBytes(StandardCharsets.UTF_8));
        GeneratedArtifact md=new GeneratedArtifact();md.path=markdown.toString();md.kind=GeneratedArtifact.DELIVERABLE;
        GeneratedArtifact docx=new GeneratedArtifact();docx.path=word.toString();docx.kind=GeneratedArtifact.DELIVERABLE;
        GeneratedArtifact hidden=new GeneratedArtifact();hidden.path=record.toString();hidden.kind=GeneratedArtifact.EXECUTION_RECORD;
        GeneratedArtifact ref=new GeneratedArtifact();ref.path=reference.toString();ref.kind=GeneratedArtifact.REFERENCE;
        GeneratedArtifact py=new GeneratedArtifact();py.path=code.toString();py.kind=GeneratedArtifact.DELIVERABLE;
        ArtifactPreviewPane[] pane={null};LocalFileLinks[] links={null};
        try{
            fx(()->{links[0]=new LocalFileLinks(()->root,()->null,p->{},m->{});pane[0]=new ArtifactPreviewPane(()->{},links[0]);
                pane[0].setArtifacts(Arrays.asList(md,docx,hidden,ref,py));});
            fx(()->{try{
                MenuButton results=field(pane[0],"files");assertEquals("成果 · 3",results.getText());
                assertEquals(3,results.getItems().size());
                assertTrue(results.getItems().stream().noneMatch(Menu.class::isInstance));
                java.util.Map<?,?> visible=field(pane[0],"artifacts");assertEquals(3,visible.size());
                assertFalse(visible.values().stream().anyMatch(item->GeneratedArtifact.EXECUTION_RECORD.equals(((GeneratedArtifact)item).kind)));
                assertFalse(visible.keySet().stream().anyMatch(path->path.toString().endsWith(".py")));
                javafx.scene.layout.FlowPane overview=field(pane[0],"overviewGrid");assertEquals(3,overview.getChildren().size());
                VBox overviewView=field(pane[0],"overviewView");assertTrue(overviewView.isVisible());
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(pane[0]!=null)pane[0].close();if(links[0]!=null)links[0].close();});}
    }
    @Test public void storedPreviousRevisionAppearsInHistoryMenu()throws Exception{
        Path root=temp.newFolder().toPath(),current=root.resolve("报告.md"),previous=root.resolve("旧版.md");
        Files.write(current,"# 当前版".getBytes(StandardCharsets.UTF_8));Files.write(previous,"# 旧版".getBytes(StandardCharsets.UTF_8));
        GeneratedArtifact item=new GeneratedArtifact();item.path=current.toString();item.kind=GeneratedArtifact.DELIVERABLE;
        GeneratedArtifact.Version version=new GeneratedArtifact.Version();version.snapshotPath=previous.toString();
        version.revision=1L;version.updatedAt=System.currentTimeMillis()-1000;version.size=Files.size(previous);item.history.add(version);
        ArtifactPreviewPane[] pane={null};LocalFileLinks[] links={null};
        try{
            fx(()->{links[0]=new LocalFileLinks(()->root,()->null,p->{},m->{});pane[0]=new ArtifactPreviewPane(()->{},links[0]);
                pane[0].setArtifacts(Arrays.asList(item));pane[0].open(current.toString());});
            fx(()->{try{java.lang.reflect.Method update=ArtifactPreviewPane.class.getDeclaredMethod("updateHistoryMenu");update.setAccessible(true);update.invoke(pane[0]);
                Menu history=field(pane[0],"historyMenu");assertEquals("历史版本 (1)",history.getText());assertEquals(1,history.getItems().size());
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(pane[0]!=null)pane[0].close();if(links[0]!=null)links[0].close();});}
    }
    @Test public void htmlPreviewSanitizerRemovesExecutionAndExternalResources(){
        String html="<html><style>@import url(https://bad/x.css);body{color:red}</style>"
                +"<script>alert(1)</script><img src=\"https://bad/x.png\" onerror=\"alert(2)\">"
                +"<a href=\"https://bad/\">link</a><p>离线内容</p></html>";
        String safe=ArtifactPreviewPane.safeHtml(html);
        assertFalse(safe.contains("<script"));assertFalse(safe.contains("onerror"));
        assertFalse(safe.contains("https://bad"));assertFalse(safe.contains("@import"));
        assertTrue(safe.contains("离线内容"));
    }
    @Test public void wordBoldPunctuationBeforeNumberIsRenderedNotLiteralMarkdown()throws Exception {
        Path file=temp.newFile("bold.docx").toPath();
        try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))){z.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));z.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>本期结果：</w:t></w:r><w:r><w:t>38项完成</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));z.closeEntry();}
        DocumentPreviewLoader.Result result=DocumentPreviewLoader.load(file);
        String plain=com.minion.gui.chat.MarkdownRenderer.toPlainText(result.markdown);
        assertTrue(plain.contains("本期结果："));assertTrue(plain.contains("38项完成"));assertFalse(plain.contains("**"));
        assertTrue(com.minion.gui.chat.MarkdownRenderer.parse(result.markdown).get(0).spans.stream().anyMatch(s->s.style.contains("bold")&&s.text.contains("本期结果")));
    }
    @Test public void wideTablePreviewRetainsTheLinksInVisibleColumns()throws Exception {
        com.minion.gui.chat.MarkdownRenderer.Block b=new com.minion.gui.chat.MarkdownRenderer.Block(com.minion.gui.chat.MarkdownRenderer.Block.Type.TABLE);
        com.minion.gui.chat.MarkdownRenderer.TableRowData row=new com.minion.gui.chat.MarkdownRenderer.TableRowData(true);
        for(int i=0;i<17;i++){row.cells.add("column"+i);row.cellSpans.add(java.util.Collections.singletonList(new com.minion.gui.chat.MarkdownRenderer.Span("打开","plain","file:///D:/column"+i+".md")));}b.rows.add(row);
        java.lang.reflect.Method method=ArtifactPreviewPane.class.getDeclaredMethod("boundedTable",com.minion.gui.chat.MarkdownRenderer.Block.class);method.setAccessible(true);
        com.minion.gui.chat.MarkdownRenderer.Block clipped=(com.minion.gui.chat.MarkdownRenderer.Block)method.invoke(null,b);
        assertEquals(15,clipped.rows.get(0).cells.size());assertEquals(15,clipped.rows.get(0).cellSpans.size());assertEquals("file:///D:/column0.md",clipped.rows.get(0).cellSpans.get(0).get(0).href);
    }
    @Test public void markdownLoadsOffFxThreadAndHasNativeOpenAndRevealMenu()throws Exception {
        Path root=temp.newFolder().toPath(),file=root.resolve("中文 报告.md");Files.write(file,"# 报告标题\n\n|项目|结果|\n|---|---|\n|测试|完成|".getBytes(StandardCharsets.UTF_8));
        ArtifactPreviewPane[] pane={null};LocalFileLinks[] links={null};
        try {
            fx(()->{links[0]=new LocalFileLinks(()->root,()->null,p->{},message->{});pane[0]=new ArtifactPreviewPane(()->{},links[0],path->{assertFalse(Platform.isFxApplicationThread());return DocumentPreviewLoader.load(path);});pane[0].open(file.toString());});
            await(()->{boolean[] ready={false};fx(()->{try{ready[0]=!((Label)field(pane[0],"status")).getText().contains("正在");}catch(Exception e){throw new RuntimeException(e);}});return ready[0];});
            fx(()->{try{Hyperlink path=field(pane[0],"pathLink");assertEquals(file.toString(),path.getText());assertTrue(path.getContextMenu().getItems().stream().anyMatch(i->"打开文件位置".equals(i.getText())));VBox body=field(pane[0],"body");assertTrue(body.getChildren().stream().anyMatch(n->n instanceof Label&&"报告标题".equals(((Label)n).getText())));}catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(pane[0]!=null)pane[0].close();if(links[0]!=null)links[0].close();});}
    }
    @Test public void oldSlowReadCannotLeakIntoNewSelectionAndClearCancelsIt()throws Exception {
        Path root=temp.newFolder().toPath(),a=root.resolve("a.md"),b=root.resolve("b.md");Files.write(a,"# STALE".getBytes("UTF-8"));Files.write(b,"# CURRENT".getBytes("UTF-8"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ArtifactPreviewPane[] pane={null};LocalFileLinks[] links={null};
        try {
            fx(()->{links[0]=new LocalFileLinks(()->root,()->null,p->{},m->{});pane[0]=new ArtifactPreviewPane(()->{},links[0],path->{if(path.equals(a)){entered.countDown();boolean waiting=true;while(waiting)try{release.await();waiting=false;}catch(InterruptedException ignored){}}return DocumentPreviewLoader.load(path);});pane[0].open(a.toString());});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            fx(()->{pane[0].clear();pane[0].open(b.toString());assertEquals(b,pane[0].currentPath());});release.countDown();
            await(()->{boolean[] ready={false};fx(()->{try{VBox body=field(pane[0],"body");ready[0]=body.getChildren().stream().anyMatch(n->n instanceof Label&&"CURRENT".equals(((Label)n).getText()));}catch(Exception e){throw new RuntimeException(e);}});return ready[0];});
            fx(()->{try{VBox body=field(pane[0],"body");assertFalse(body.getChildren().stream().anyMatch(n->n instanceof Label&&"STALE".equals(((Label)n).getText())));}catch(Exception e){throw new RuntimeException(e);}});
        }finally{release.countDown();fx(()->{if(pane[0]!=null)pane[0].close();if(links[0]!=null)links[0].close();});}
    }
    @Test public void missingFileHasExplicitFallbackRatherThanBinaryGarbage()throws Exception {
        Path root=temp.newFolder().toPath();ArtifactPreviewPane[] pane={null};LocalFileLinks[] links={null};
        try {
            fx(()->{links[0]=new LocalFileLinks(()->root,()->null,p->{},m->{});pane[0]=new ArtifactPreviewPane(()->{},links[0]);pane[0].open(root.resolve("missing.docx").toString());});
            await(()->{boolean[] ready={false};fx(()->{try{ready[0]="预览不可用".equals(((Label)field(pane[0],"status")).getText());}catch(Exception e){throw new RuntimeException(e);}});return ready[0];});
        }finally{fx(()->{if(pane[0]!=null)pane[0].close();if(links[0]!=null)links[0].close();});}
    }
    @Test public void chineseReportUsesActualViewportWidthAtNarrowAndWideSizes()throws Exception{
        Path root=temp.newFolder().toPath(),file=root.resolve("长中文执行记录.md");
        String paragraph=String.join("",java.util.Collections.nCopies(26,"这是一段连续中文正文，用来核对报告阅读宽度和换行。"));
        String table="\n\n| 项目 | 内容 |\n|---|---|\n|正文|"+paragraph.substring(0,165)+"|\n|尾行|完整|";
        Files.write(file,("# 报告\n\n"+paragraph+table).getBytes(StandardCharsets.UTF_8));
        ArtifactPreviewPane[] pane={null};LocalFileLinks[] links={null};VBox host=new VBox();
        try{
            fx(()->{links[0]=new LocalFileLinks(()->root,()->null,p->{},m->{});pane[0]=new ArtifactPreviewPane(()->{},links[0]);
                host.getChildren().add(pane[0]);VBox.setVgrow(pane[0],javafx.scene.layout.Priority.ALWAYS);
                Scene scene=new Scene(host,500,600);scene.getStylesheets().add(getClass().getResource("/theme/theme.css").toExternalForm());
                pane[0].open(file.toString());});
            await(()->{boolean[] ready={false};fx(()->{try{ready[0]=field(pane[0],"loaded")!=null;}catch(Exception e){throw new RuntimeException(e);}});return ready[0];});
            fx(()->{try{
                VBox body=field(pane[0],"body");ScrollPane scroll=field(pane[0],"scroll");
                for(int width:new int[]{310,650,360}){
                    host.resize(width,600);for(int pass=0;pass<6;pass++){host.applyCss();host.requestLayout();host.layout();}
                    TextFlow prose=(TextFlow)body.getChildren().stream().filter(n->n instanceof TextFlow).findFirst().get();
                    assertTrue("Viewport "+scroll.getViewportBounds(),scroll.getViewportBounds().getWidth()>width-35);
                    assertTrue("Chinese text wrapped vertically: "+prose.getWidth(),prose.getWidth()>width-65);
                    double height=prose.getHeight();
                    assertTrue("Paragraph unexpectedly tall at "+width+": "+height,height<1550);
                    assertEquals(ScrollPane.ScrollBarPolicy.NEVER,scroll.getHbarPolicy());
                    ScrollPane gridPane=(ScrollPane)body.getChildren().stream().filter(n->n instanceof ScrollPane).findFirst().get();
                    assertTrue("Table rows clipped: "+gridPane.getViewportBounds().getHeight()+" / "+gridPane.getContent().getLayoutBounds().getHeight(),
                            gridPane.getViewportBounds().getHeight()+2>=gridPane.getContent().getLayoutBounds().getHeight());
                }
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(pane[0]!=null)pane[0].close();if(links[0]!=null)links[0].close();});}
    }
    @Test public void tabsRestoreSourceModeAndSeparateFileContent()throws Exception{
        Path root=temp.newFolder().toPath(),first=root.resolve("first.md"),second=root.resolve("second.md");
        Files.write(first,"# 第一份".getBytes(StandardCharsets.UTF_8));Files.write(second,"# 第二份".getBytes(StandardCharsets.UTF_8));
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,file->{},m->{});p[0]=new ArtifactPreviewPane(()->{},l[0]);p[0].open(first.toString());});
            await(()->ready(p[0]));
            fx(()->{try{((ToggleButton)field(p[0],"source")).setSelected(true);p[0].open(second.toString());}catch(Exception e){throw new RuntimeException(e);}});
            await(()->ready(p[0]));
            fx(()->{try{TabPane tabs=field(p[0],"tabs");assertEquals(2,tabs.getTabs().size());
                assertFalse(((ToggleButton)field(p[0],"source")).isSelected());tabs.getSelectionModel().select(0);}catch(Exception e){throw new RuntimeException(e);}});
            await(()->ready(p[0]));
            fx(()->{try{
                assertEquals(first,p[0].currentPath());assertTrue(((ToggleButton)field(p[0],"source")).isSelected());
                assertEquals("# 第一份",((TextArea)field(p[0],"textView")).getText());assertFalse(p[0].followsNewArtifacts());
                ((javafx.scene.control.MenuItem)field(p[0],"find")).fire();
                javafx.scene.control.TextField search=field(p[0],"search");search.setText("第一");
                search.getOnAction().handle(new javafx.event.ActionEvent());
                assertEquals("第一",((TextArea)field(p[0],"textView")).getSelectedText());
                p[0].clear();assertEquals(0,((TabPane)field(p[0],"tabs")).getTabs().size());
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }
    @Test public void samePathArtifactEventReloadsWithoutDuplicatingTabs()throws Exception{
        Path root=temp.newFolder().toPath(),file=root.resolve("output.md");
        Files.write(file,"# 第一版".getBytes(StandardCharsets.UTF_8));
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});p[0]=new ArtifactPreviewPane(()->{},l[0]);p[0].open(file.toString());});
            await(()->ready(p[0]));
            Files.write(file,"# 第二版".getBytes(StandardCharsets.UTF_8));
            fx(()->p[0].openUpdated(file.toString()));await(()->ready(p[0]));
            fx(()->{try{
                assertEquals(1,((TabPane)field(p[0],"tabs")).getTabs().size());
                assertEquals("# 第二版",((DocumentPreviewLoader.Result)field(p[0],"loaded")).sourceText);
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }
    private static boolean ready(ArtifactPreviewPane pane)throws Exception{
        boolean[] ready={false};fx(()->{try{ready[0]=field(pane,"loaded")!=null;}catch(Exception e){throw new RuntimeException(e);}});return ready[0];
    }
}
