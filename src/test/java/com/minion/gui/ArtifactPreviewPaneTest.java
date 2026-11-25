package com.minion.gui;

import com.minion.core.agent.GeneratedArtifact;
import com.minion.gui.artifact.DocumentPreviewLoader;
import com.minion.gui.artifact.LocalFileLinks;
import com.minion.gui.artifact.PagedSlideViewer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
    @Test public void multiPagePdfOpensPagedViewerInsteadOfSinglePageImage()throws Exception{
        Path root=temp.newFolder().toPath(),file=root.resolve("pages.pdf");
        try(PDDocument document=new PDDocument()){
            document.addPage(new PDPage());document.addPage(new PDPage());document.addPage(new PDPage());
            document.save(file.toFile());
        }
        ArtifactPreviewPane[] pane={null};LocalFileLinks[] links={null};
        try{
            fx(()->{links[0]=new LocalFileLinks(()->root,()->null,p->{},m->{});pane[0]=new ArtifactPreviewPane(()->{},links[0]);pane[0].open(file.toString());});
            await(()->hasPagedViewer(pane[0]));
            fx(()->{try{
                javafx.scene.layout.StackPane host=field(pane[0],"pagedHost");
                assertEquals(1,host.getChildren().size());
                assertTrue(host.getChildren().get(0) instanceof PagedSlideViewer);
            }catch(Exception e){throw new RuntimeException(e);}});
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
    // ---- F3 预览健壮性（additive，旧用例上方逐字保留） ----

    private static byte[] docxBytes()throws Exception{
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(out)){
            z.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            z.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>完整内容</w:t></w:r></w:p></w:body></w:document>").getBytes("UTF-8"));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    /** 半写文件：首次 ZipException → 有界重试，写盘完成后预览成功（R4 复现） */
    @Test public void halfWrittenFileRetriesUntilWriteCompletes()throws Exception{
        Path root=temp.newFolder().toPath(),file=root.resolve("半写.docx");
        byte[] full=docxBytes();Files.write(file,Arrays.copyOf(full,full.length/2));
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});
                p[0]=new ArtifactPreviewPane(()->{},l[0],path->{calls.incrementAndGet();return DocumentPreviewLoader.load(path);});
                p[0].open(file.toString());});
            Thread.sleep(400);Files.write(file,full);
            await(()->ready(p[0]));
            fx(()->{try{
                assertEquals("docx",((DocumentPreviewLoader.Result)field(p[0],"loaded")).format);
                assertTrue("应至少重试一次",calls.get()>=2);
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }

    /** 3 次仍败 → 错误卡带「重试」「用关联应用打开」；手动重试重置一次性标记后可成功 */
    @Test public void exhaustedRetriesShowActionableErrorAndManualRetryWorks()throws Exception{
        Path root=temp.newFolder().toPath(),file=root.resolve("损坏.docx");Files.write(file,docxBytes());
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});
                p[0]=new ArtifactPreviewPane(()->{},l[0],path->{
                    if(calls.incrementAndGet()<=4)throw new java.util.zip.ZipException("模拟半写损坏");
                    return DocumentPreviewLoader.load(path);});
                p[0].open(file.toString());});
            await(()->{boolean[] err={false};fx(()->{try{err[0]="预览不可用".equals(((Label)field(p[0],"status")).getText());}catch(Exception e){throw new RuntimeException(e);}});return err[0];});
            assertEquals("初次+3 次重试",4,calls.get());
            fx(()->{try{
                VBox body=field(p[0],"body");
                java.util.List<javafx.scene.control.Button> buttons=new java.util.ArrayList<>();
                for(javafx.scene.Node n:body.getChildren())if(n instanceof javafx.scene.layout.HBox)
                    for(javafx.scene.Node c:((javafx.scene.layout.HBox)n).getChildren())
                        if(c instanceof javafx.scene.control.Button)buttons.add((javafx.scene.control.Button)c);
                assertTrue(buttons.stream().anyMatch(b->"重试".equals(b.getText())));
                assertTrue(buttons.stream().anyMatch(b->"用关联应用打开".equals(b.getText())));
                buttons.stream().filter(b->"重试".equals(b.getText())).findFirst().get().fire();
            }catch(Exception e){throw new RuntimeException(e);}});
            await(()->ready(p[0]));
            assertEquals(5,calls.get());
        }finally{fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }

    /** 权限类失败不重试：一次即出可操作错误 */
    @Test public void accessDeniedIsNotRetried()throws Exception{
        Path root=temp.newFolder().toPath(),file=root.resolve("权限.docx");Files.write(file,docxBytes());
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});
                p[0]=new ArtifactPreviewPane(()->{},l[0],path->{calls.incrementAndGet();throw new AccessDeniedException(path.toString());});
                p[0].open(file.toString());});
            await(()->{boolean[] err={false};fx(()->{try{err[0]="预览不可用".equals(((Label)field(p[0],"status")).getText());}catch(Exception e){throw new RuntimeException(e);}});return err[0];});
            assertEquals("权限错误零重试",1,calls.get());
        }finally{fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }

    /** 大小两次采样：稳定文件 true；持续追加中的文件 false（不提前读） */
    @Test public void sizeStableDetectsGrowingFile()throws Exception{
        Path f=temp.newFile("grow.bin").toPath();Files.write(f,new byte[]{1});
        assertTrue(ArtifactPreviewPane.sizeStable(f));
        Thread writer=new Thread(()->{for(int i=0;i<8;i++){try{Files.write(f,new byte[]{2},StandardOpenOption.APPEND);Thread.sleep(40);}catch(Exception e){return;}}});
        writer.start();
        assertFalse("写入中大小不稳定",ArtifactPreviewPane.sizeStable(f));
        writer.join();
        assertTrue(ArtifactPreviewPane.sizeStable(f));
    }

    /** 队列 1→8；同 path 合并：旧 Future 被 cancel，登记表同 key 仅一条 */
    @Test public void queueCapacityEightAndSamePathMergeCancelsOld()throws Exception{
        Path root=temp.newFolder().toPath(),a=root.resolve("a.md");Files.write(a,"# A".getBytes("UTF-8"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});
                p[0]=new ArtifactPreviewPane(()->{},l[0],path->{
                    entered.countDown();boolean waiting=true;
                    while(waiting)try{release.await();waiting=false;}catch(InterruptedException ignored){}
                    return DocumentPreviewLoader.load(path);});
                p[0].open(a.toString());});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<?>[] first={null};
            fx(()->{try{
                ThreadPoolExecutor reader=field(p[0],"reader");
                assertEquals(8,reader.getQueue().remainingCapacity());
                java.util.Map<String,Future<?>> tasks=field(p[0],"poolTasks");
                first[0]=tasks.get("reader:"+a);
                p[0].openUpdated(a.toString());
            }catch(Exception e){throw new RuntimeException(e);}});
            fx(()->{try{
                assertTrue("旧任务应被取消",first[0].isCancelled());
                java.util.Map<String,Future<?>> tasks=field(p[0],"poolTasks");
                assertEquals("同 key 仅一条",1,tasks.keySet().stream().filter(k->k.equals("reader:"+a)).count());
            }catch(Exception e){throw new RuntimeException(e);}});
            release.countDown();
            await(()->ready(p[0]));
        }finally{release.countDown();fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }

    /** generation 切走：旧路径失败后不再调度重试（守卫照旧） */
    @Test public void switchedAwayGenerationAbandonsRetry()throws Exception{
        Path root=temp.newFolder().toPath(),a=root.resolve("a.md"),b=root.resolve("b.md");
        Files.write(a,"# A".getBytes("UTF-8"));Files.write(b,"# B".getBytes("UTF-8"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger aCalls=new java.util.concurrent.atomic.AtomicInteger();
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});
                p[0]=new ArtifactPreviewPane(()->{},l[0],path->{
                    if(path.equals(a)){aCalls.incrementAndGet();entered.countDown();
                        boolean waiting=true;while(waiting)try{release.await();waiting=false;}catch(InterruptedException ignored){}
                        throw new java.util.zip.ZipException("切走后失败");}
                    return DocumentPreviewLoader.load(path);});
                p[0].open(a.toString());});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            fx(()->p[0].open(b.toString()));
            release.countDown();
            await(()->ready(p[0]));
            Thread.sleep(1200);
            assertEquals("切走后零重试",1,aCalls.get());
            fx(()->{try{assertEquals("# B",((DocumentPreviewLoader.Result)field(p[0],"loaded")).sourceText);}catch(Exception e){throw new RuntimeException(e);}});
        }finally{release.countDown();fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }

    /** 队列满丢弃不再静默：当前卡片出「预览被跳过，点击重试」提示 */
    @Test public void queueFullDropShowsVisibleNotice()throws Exception{
        Path root=temp.newFolder().toPath(),file=root.resolve("ok.md");Files.write(file,"# OK".getBytes("UTF-8"));
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});p[0]=new ArtifactPreviewPane(()->{},l[0]);p[0].open(file.toString());});
            await(()->ready(p[0]));
            ThreadPoolExecutor[] reader={null};
            fx(()->{try{reader[0]=field(p[0],"reader");}catch(Exception e){throw new RuntimeException(e);}});
            await(()->reader[0].getActiveCount()==0&&reader[0].getQueue().isEmpty());
            // 先确定性占住唯一工作线程，再填满 8 格队列（core=1 时 execute 先入队，直接连发是竞态）
            reader[0].submit(()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            for(int i=0;i<8;i++)reader[0].submit(()->{try{Thread.sleep(2000);}catch(InterruptedException ignored){}});
            assertEquals(0,reader[0].getQueue().remainingCapacity());
            fx(()->{try{
                java.lang.reflect.Method submit=ArtifactPreviewPane.class.getDeclaredMethod("submitMerged",ThreadPoolExecutor.class,String.class,Runnable.class);
                submit.setAccessible(true);
                submit.invoke(p[0],reader[0],file.toString(),(Runnable)()->{});
            }catch(Exception e){throw new RuntimeException(e);}});
            await(()->{boolean[] shown={false};fx(()->{try{shown[0]=((Label)field(p[0],"notice")).getText().contains("预览被跳过");}catch(Exception e){throw new RuntimeException(e);}});return shown[0];});
        }finally{release.countDown();fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }

    // ---- F4 手动扫描（additive，旧用例上方逐字保留） ----

    /** 未注入扫描器（配置关）按钮隐藏零行为；注入后可见；置空复隐 */
    @Test public void scanButtonHiddenUntilScannerInjected()throws Exception{
        Path root=temp.newFolder().toPath();
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});p[0]=new ArtifactPreviewPane(()->{},l[0]);});
            fx(()->{try{assertFalse(((javafx.scene.control.Button)field(p[0],"scan")).isVisible());}catch(Exception e){throw new RuntimeException(e);}});
            fx(()->{try{
                p[0].setWorkspaceScanner(new ArtifactPreviewPane.WorkspaceScanner(){
                    public java.util.List<Path> scan(){return java.util.Collections.emptyList();}
                    public void adopt(Path path){}
                });
                assertTrue(((javafx.scene.control.Button)field(p[0],"scan")).isVisible());
                p[0].setWorkspaceScanner(null);
                assertFalse(((javafx.scene.control.Button)field(p[0],"scan")).isVisible());
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }

    /** unregistered 过滤：已登记路径剔除，未登记保留（规范化比对） */
    @Test public void unregisteredFiltersRegisteredPaths()throws Exception{
        Path root=temp.newFolder().toPath(),a=root.resolve("已登记.docx"),b=root.resolve("未登记.docx");
        Files.write(a,new byte[]{1});Files.write(b,new byte[]{1});
        GeneratedArtifact item=new GeneratedArtifact();item.path=a.toString();item.kind=GeneratedArtifact.DELIVERABLE;
        ArtifactPreviewPane[] p={null};LocalFileLinks[] l={null};
        try{
            fx(()->{l[0]=new LocalFileLinks(()->root,()->null,f->{},m->{});p[0]=new ArtifactPreviewPane(()->{},l[0]);
                p[0].setArtifacts(Arrays.asList(item));});
            fx(()->{try{
                java.util.List<Path> left=p[0].unregistered(Arrays.asList(a,b));
                assertEquals(1,left.size());
                assertEquals(b.toAbsolutePath().normalize(),left.get(0));
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally{fx(()->{if(p[0]!=null)p[0].close();if(l[0]!=null)l[0].close();});}
    }

    private static boolean ready(ArtifactPreviewPane pane)throws Exception{
        boolean[] ready={false};fx(()->{try{ready[0]=field(pane,"loaded")!=null;}catch(Exception e){throw new RuntimeException(e);}});return ready[0];
    }
    private static boolean hasPagedViewer(ArtifactPreviewPane pane)throws Exception{
        boolean[] ready={false};fx(()->{try{
            javafx.scene.layout.StackPane host=field(pane,"pagedHost");
            ready[0]=host.getChildren().size()==1&&host.getChildren().get(0) instanceof PagedSlideViewer;
        }catch(Exception e){throw new RuntimeException(e);}});return ready[0];
    }
}
