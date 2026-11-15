package com.minion.gui;

import com.minion.core.agent.GeneratedArtifact;
import com.minion.core.agent.SteeringReceipt;
import com.minion.gui.chat.ChatView;
import com.minion.gui.session.SessionController;
import com.minion.gui.artifact.LocalFileLinks;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Owned QA files/window only. Captures the actual native JavaFX DOCX/Markdown preview, no external apps. */
public class VerifyArtifactPreviewV48 extends Application {
    private static Path root;private static Throwable failed;
    private ArtifactPreviewPane pane;private LocalFileLinks links;private Stage stage;
    private static void entry(ZipOutputStream zip,String name,String data)throws Exception {zip.putNextEntry(new ZipEntry(name));zip.write(data.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static void createFiles()throws Exception {
        Files.createDirectories(root);Path doc=root.resolve("分析报告.docx");
        String md="# 科室数据分析报告\n\n本次交付包含 **Word报告** 与可核对的 Markdown 结果。\n\n## 文件交付\n\n[打开 Word 分析报告](<"+doc.toUri().toASCIIString()+">)\n\n## 核心指标\n\n| 项目 | 本期 | 环比 |\n|---|---:|---:|\n| 已完成任务 | 38 | +12% |\n| 数据核验通过 | 37 | +8% |\n\n## 下一步\n\n- 核对原始数据和例外记录\n- 右键文件链接可打开文件位置\n- 结果文件保留在本机，不上传云端\n";
        Files.write(root.resolve("报告摘要.md"),md.getBytes(StandardCharsets.UTF_8));
        String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(doc))) {
            entry(z,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(z,"word/styles.xml","<w:styles xmlns:w=\""+w+"\"><w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/></w:style></w:styles>");
            entry(z,"word/document.xml","<w:document xmlns:w=\""+w+"\"><w:body><w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>科室数据分析 · Word成果预览</w:t></w:r></w:p><w:p><w:r><w:t>这是一份真实的DOCX文件，由离线Java读取，不调用Office或Python。</w:t></w:r></w:p><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>本期结果：</w:t></w:r><w:r><w:t>38项任务，37项通过核验；1项待人工复核。</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>指标</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>结果</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>已完成任务</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>38</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>内容预览不等于Word分页排版；上方“打开文件”可查看原版。</w:t></w:r></w:p></w:body></w:document>");
        }
    }
    @Override public void start(Stage stage) {
        this.stage=stage;
        try {
            links=new LocalFileLinks(()->root,()->stage,p->{},m->{throw new AssertionError(m);});pane=new ArtifactPreviewPane(()->{},links);
            List<GeneratedArtifact> files=new ArrayList<GeneratedArtifact>();
            for(String name:new String[]{"报告摘要.md","分析报告.docx"}){GeneratedArtifact a=new GeneratedArtifact();a.path=root.resolve(name).toString();a.title=name;a.kind="deliverable";files.add(a);}
            pane.setArtifacts(files);
            SessionController controller=new SessionController();ChatView chat=new ChatView(controller.eventList(),null);
            chat.setFileLinkHandler(links::open,links::menu);chat.bind(true);
            SteeringReceipt first=new SteeringReceipt();first.id="demo-read";first.text="只分析最近一个月的数据，先核对再生成报告。";first.state="unread";first.updatedAt=1;controller.onSteeringReceipt(first);
            SteeringReceipt read=first.copy();read.state="read";read.updatedAt=2;read.requestId="demo-local-request";controller.onSteeringReceipt(read);
            SteeringReceipt next=new SteeringReceipt();next.id="demo-unread";next.text="再补充异常记录的说明。";next.state="unread";next.updatedAt=3;controller.onSteeringReceipt(next);
            controller.onContent("已生成本地报告，可直接查看右侧内容；点击文件链接打开原版。\n\n[Word报告](<"+root.resolve("分析报告.docx").toUri().toASCIIString()+">)");controller.onResponseEnd();
            controller.onArtifactCreated(root.resolve("分析报告.docx").toString());
            Label demo=new Label("开发验收示例：模拟回执，不连接模型");demo.setWrapText(true);demo.setStyle("-fx-text-fill:#c9b581;-fx-padding:12;");
            ScrollPane chatScroll=new ScrollPane(chat);chatScroll.setFitToWidth(true);VBox.setVgrow(chatScroll,Priority.ALWAYS);
            VBox conversation=new VBox(demo,chatScroll);conversation.setMinWidth(300);
            SplitPane container=new SplitPane(conversation,pane);container.getStyleClass().add("workspace-split");container.setDividerPositions(0.35);container.setStyle("-fx-background-color:#121314;");
            Scene scene=new Scene(container,1120,820);scene.getStylesheets().add(getClass().getResource("/theme/theme.css").toExternalForm());
            stage.setScene(scene);stage.setTitle("MINION V48 artifact preview QA");stage.show();pane.open(root.resolve("分析报告.docx").toString());captureWhenReady("docx-preview.png",0,()->{pane.open(root.resolve("报告摘要.md").toString());captureWhenReady("markdown-preview.png",0,this::finish);});
        }catch(Throwable e){failed=e;e.printStackTrace();finish();}
    }
    private void captureWhenReady(String name,int attempt,Runnable next) {
        PauseTransition pause=new PauseTransition(Duration.millis(250));pause.setOnFinished(e->{try {
            java.lang.reflect.Field f=ArtifactPreviewPane.class.getDeclaredField("loaded");f.setAccessible(true);
            if(f.get(pane)==null){if(attempt>40)throw new AssertionError("Preview not ready: "+name);captureWhenReady(name,attempt+1,next);return;}
            stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
            ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null),null),"png",root.resolve(name).toFile());next.run();
        }catch(Throwable bad){failed=bad;bad.printStackTrace();finish();}});pause.play();
    }
    private void finish(){if(pane!=null)pane.close();if(links!=null)links.close();stage.close();Platform.exit();}
    public static void main(String[] args)throws Exception {root=Paths.get(args[0]).toAbsolutePath();createFiles();launch(args);if(failed!=null)System.exit(1);System.out.println("PASS native JavaFX DOCX/Markdown rendered snapshots; no real files/apps opened");}
}
