package com.minion.core.tools.data;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.tools.*;
import com.minion.core.tools.knowledge.KnowledgeTool;
import com.minion.gui.input.AttachmentReader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.*;
import java.util.stream.Stream;
import java.util.zip.*;
import static org.junit.Assert.*;

public class GdWorkflowTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private Path root(){return tmp.getRoot().toPath();}
    private Workspace workspace(){return new Workspace(root().toString());}
    private Path nativeGd()throws Exception{
        // Synthetic proprietary-format placeholder: does NOT assert native Sursen decoding.
        Path file=root().resolve("书生公文 & !.gd");
        Files.write(file,new byte[]{19,0,23,124,5,10,17,28,1,0,41});
        return file;
    }
    private DocumentTool document(){return new DocumentTool(workspace(),null,null);}
    private static JsonObject args(String action,String... values){return PdfWorkflowTest.args(action,values);}

    @Test public void readsPdfByContentInAttachmentReadAndDocument()throws Exception{
        Path pdf=root().resolve("公文.GD");
        try(PDDocument doc=new PDDocument()){
            PDPage page=new PDPage();doc.addPage(page);
            try(PDPageContentStream stream=new PDPageContentStream(doc,page)){
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(15,700);
                stream.showText("NOTICE GD PDF CONTENT");stream.endText();
            }
            doc.save(pdf.toFile());
        }
        assertTrue(AttachmentReader.read(pdf).promptText.contains("NOTICE GD PDF CONTENT"));
        assertTrue(new ReadTool(workspace()).execute(args("","path",pdf.toString())).output.contains("NOTICE GD PDF CONTENT"));
        ToolResult text=document().execute(args("gd_read","path",pdf.toString()));
        assertTrue(text.output,text.ok);assertTrue(text.output.contains("NOTICE GD PDF CONTENT"));
        ToolResult visual=document().execute(args("gd_view","path",pdf.toString(),"startPage","1","dpi","72"));
        assertTrue(visual.output,visual.ok);assertNotNull(visual.images);
        assertTrue(DocumentTool.extractText(pdf).contains("NOTICE GD PDF CONTENT"));
    }

    @Test public void readsOfdByContentAndBatchIndexesMixedExtensions()throws Exception{
        Path dir=Files.createDirectory(root().resolve("公文"));
        Path gd=dir.resolve("公文.gd"), ofd=dir.resolve("另外一份.ofd");
        ofdZip(gd,"书生公文测试内容");ofdZip(ofd,"同目录第四季度要点");
        assertTrue(AttachmentReader.read(gd).promptText.contains("书生公文测试内容"));
        ToolResult read=document().execute(args("gd_read","path",gd.toString()));
        assertTrue(read.output,read.ok);assertTrue(read.output.contains("书生公文测试内容"));
        KnowledgeTool k=new KnowledgeTool(workspace());
        ToolResult batch=k.execute(args("import","path",dir.toString()));
        assertTrue(batch.output,batch.output.contains("新增=2"));
        assertTrue(k.execute(args("search","query","同目录第四季度要点")).output.contains("另外一份.ofd"));
        assertTrue(k.execute(args("search","query","书生公文测试内容")).output.contains("公文.gd"));
    }

    @Test public void nativeBinaryDoesNotFabricateTextAndExplicitExportKeepsProvenance()throws Exception{
        Path gd=nativeGd();
        byte[] original=Files.readAllBytes(gd);
        ToolResult blocked=document().execute(args("gd_read","path",gd.toString()));
        assertFalse(blocked.ok);assertTrue(blocked.output.contains("原生书生GD未直接解码"));
        try {AttachmentReader.read(gd);fail("must request native reader");}
        catch(GdSupport.ReaderRequired expected){assertTrue(expected.getMessage().contains("书生阅读器"));}
        Path copy=root().resolve("导出文本.txt");
        Files.write(copy,"书生导出文本（中文）".getBytes(Charset.forName("GBK")));
        ToolResult read=document().execute(args("gd_read","path",gd.toString(),"exportPath",copy.toString()));
        assertTrue(read.output,read.ok);assertTrue(read.output.contains("书生导出文本（中文）"));
        assertTrue(read.output.contains(gd.toString())&&read.output.contains(copy.toString())&&read.output.contains("电子签章已经核验"));
        assertTrue(GdSupport.exportPreview(gd,copy).contains("书生导出文本"));
        Path utf16=root().resolve("unicode.txt");Files.write(utf16,"UTF-16 正文".getBytes(StandardCharsets.UTF_16));
        assertTrue(GdSupport.exportPreview(gd,utf16).contains("UTF-16 正文"));
        assertArrayEquals(original,Files.readAllBytes(gd));
    }

    @Test public void rejectsRenamedBinaryAndUnauthorizedExport()throws Exception{
        Path gd=nativeGd(), fake=root().resolve("renamed.pdf");
        Files.copy(gd,fake);
        ToolResult rejected=document().execute(args("gd_read","path",gd.toString(),"exportPath",fake.toString()));
        assertFalse(rejected.ok);assertTrue(rejected.output.contains("实际格式与后缀不符"));
        Path isolated=Files.createDirectory(root().resolve("isolated")), local=Files.copy(gd,isolated.resolve("local.gd"));
        Path outside=root().resolve("outside.txt");Files.write(outside,"not allowed".getBytes(StandardCharsets.UTF_8));
        try{
            new DocumentTool(new Workspace(isolated.toString()),null,null).execute(args("gd_read","path",local.toString(),"exportPath",outside.toString()));
            fail("workspace permission");
        }catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("工作路径之外"));}
    }

    @Test public void configuredLocalCliConvertsCopiesAndKnowledgeSyncDoesNotReconvert()throws Exception{
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path gd=nativeGd();
        byte[] original=Files.readAllBytes(gd);
        Config config=Config.load(root());
        configure(config,"good");
        DocumentTool tool=new DocumentTool(workspace(),null,null,config,null);
        ToolResult text=tool.execute(args("gd_read","path",gd.toString()));
        assertTrue(text.output,text.ok);assertTrue(text.output.contains("离线转换测试正文"));
        assertTrue(text.output.contains("本地转换程序"));assertFalse(text.output.contains("export.txt"));
        KnowledgeTool knowledge=new KnowledgeTool(workspace(),config);
        assertTrue(knowledge.execute(args("import","path",gd.toString())).output.contains("新增=1"));
        configure(config,"fail");
        assertTrue(knowledge.execute(args("sync","path",gd.toString())).output.contains("跳过=1"));
        ToolResult rejected=tool.execute(args("gd_read","path",gd.toString()));
        assertFalse(rejected.ok);assertTrue(rejected.output.contains("退出码"));
        assertArrayEquals(original,Files.readAllBytes(gd));
        try(Stream<Path> paths=Files.list(root().resolve(".minion/tmp"))){assertEquals(0,paths.count());}
    }

    @Test public void malformedConfigDoesNotLaunchAnything()throws Exception{
        Path gd=nativeGd();
        Config config=Config.load(root());config.set("gd.converter.path","relative.bat");
        ToolResult rejected=new DocumentTool(workspace(),null,null,config,null).execute(args("gd_read","path",gd.toString()));
        assertFalse(rejected.ok);assertTrue(rejected.output.contains("配置无效"));
        assertFalse(Files.exists(root().resolve(".minion/tmp")));
    }

    private static void configure(Config config,String mode)throws Exception{
        config.set("gd.converter.path",Paths.get(System.getProperty("java.home"),"bin","java.exe").toString());
        config.set("gd.converter.format","txt");
        JsonArray a=new JsonArray();
        a.add("-cp");a.add(Paths.get("target/test-classes").toAbsolutePath().toString());a.add(ConverterFixture.class.getName());
        a.add(mode);a.add("{input}");a.add("{output}");
        config.set("gd.converter.args",a.toString());
    }
    public static final class ConverterFixture {
        public static void main(String[] argv)throws Exception{
            if("fail".equals(argv[0]))System.exit(3);
            Path source=Paths.get(argv[1]),dest=Paths.get(argv[2]);
            if(!source.getFileName().toString().equals("source.gd")||Files.size(source)<8)System.exit(4);
            Files.write(dest,"离线转换测试正文".getBytes(StandardCharsets.UTF_8));
        }
    }
    private static void ofdZip(Path file,String text)throws Exception{
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(file))){
            entry(z,"OFD.xml","<OFD><DocBody><DocRoot>D/Document.xml</DocRoot></DocBody></OFD>");
            entry(z,"D/Document.xml","<Document><Pages><Page ID=\"1\" BaseLoc=\"P.xml\"/></Pages></Document>");
            entry(z,"D/P.xml","<Page><Content><Layer><TextObject><TextCode>"+text+"</TextCode></TextObject></Layer></Content></Page>");
        }
    }
    private static void entry(ZipOutputStream z,String path,String body)throws Exception{
        z.putNextEntry(new ZipEntry(path));z.write(body.getBytes(StandardCharsets.UTF_8));z.closeEntry();
    }
}
