package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.agent.ToolResourceScope;
import com.minion.core.config.Config;
import com.minion.core.tools.ReadTool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.knowledge.KnowledgeTool;
import com.minion.gui.input.AttachmentReader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Assume;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class OfdWorkflowTest {
    private static final String NS = "http://www.ofdspec.org/2016", SOURCE = "增值税票据.OFD";
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Path root() { return tmp.getRoot().toPath(); }
    private Workspace workspace() { return new Workspace(root().toString()); }
    private DocumentTool document() { return new DocumentTool(workspace(), null, null); }

    @Test public void readsChineseAndRespectsDirectoryOrderAndTemplatesAcrossBodies() throws Exception {
        Path file = sample(SOURCE);
        byte[] original = Files.readAllBytes(file);
        ToolResult inspect = document().execute(args("inspect","path",SOURCE));
        assertTrue(inspect.output, inspect.ok);
        assertTrue(inspect.output.contains("总页数: 4；子文档数: 2"));
        assertTrue(inspect.output.contains("含签章引用（未验签）"));
        ToolResult first = document().execute(args("ofd_read","path",SOURCE,"startPage","1","endPage","1"));
        assertTrue(first.output,first.ok);
        assertTrue(first.output.contains("标题：电子发票"));
        assertTrue(first.output.contains("发票号码 01230001"));
        assertTrue(first.output.contains("金额：¥100.00"));
        assertTrue(first.output.contains("税费 A&B <4>"));
        assertFalse(first.output.contains("第2页唯一文本"));
        assertTrue(first.output.contains("下一次 startPage=2"));
        ToolResult last = document().execute(args("extract","path",SOURCE,"startPage","4","endPage","4"));
        assertTrue(last.output,last.ok); assertTrue(last.output.contains("第二份文档最后页"));
        assertArrayEquals(original,Files.readAllBytes(file));
    }

    @Test public void attachmentAndReadPreviewDoNotPretendToReadFourthPage() throws Exception {
        Path file = sample(SOURCE);
        AttachmentReader.Result result = AttachmentReader.read(file);
        assertTrue(result.promptText.contains("type=\"ofd\""));
        assertTrue(result.promptText.contains("前3页"));
        assertTrue(result.promptText.contains("第2页唯一文本"));
        assertFalse(result.promptText.contains("第二份文档最后页"));
        ToolResult read = new ReadTool(workspace()).execute(args("ignored","path",SOURCE));
        assertTrue(read.output,read.ok);
        assertTrue(read.output.contains("Document ofd_read"));
        assertFalse(read.output.contains("第二份文档最后页"));
    }

    @Test public void knowledgeImportUsesTextAndPageProvenanceButDoesNotIndexScans() throws Exception {
        sample(SOURCE);
        scan("scanned.ofd",false);
        KnowledgeTool knowledge = new KnowledgeTool(workspace());
        assertTrue(knowledge.execute(args("import","path",SOURCE)).output.contains("新增=1"));
        ToolResult hit = knowledge.execute(args("search","query","第二份文档最后页"));
        assertTrue(hit.output,hit.output.contains(SOURCE));
        assertTrue(DocumentTool.extractText(root().resolve(SOURCE)).contains("[第4页]"));
        ToolResult skipped = knowledge.execute(args("import","path","scanned.ofd"));
        assertTrue(skipped.output,skipped.output.contains("新增=0"));
        assertTrue(skipped.output,skipped.output.contains("没有可索引文字"));
    }

    @Test public void longPageReturnsExactContinuationWithinToolContextBudget() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) text.append("发票项目").append(i).append("🙂");
        text.append("最终尾标");
        archive("long.ofd",single(page(text.toString())));
        ToolResult first = document().execute(args("ofd_read","path","long.ofd","startPage","1","endPage","1"));
        Matcher offset = Pattern.compile("pageTextOffset=(\\d+)").matcher(first.output);
        assertTrue(first.output,first.ok && offset.find());
        assertTrue(first.output.length()<=8000);
        ToolResult next = document().execute(args("ofd_read","path","long.ofd","startPage","1","endPage","1","pageTextOffset",offset.group(1)));
        assertTrue(next.output,next.ok);
        assertTrue(next.output.contains("最终尾标"));assertFalse(next.output.contains("发票项目0"));
    }

    @Test public void pureScanReturnsRealEmbeddedPixelsAndCleansTemporaryFile() throws Exception {
        Path file = scan("scanned.ofd",false);
        assertTrue(AttachmentReader.read(file).promptText.contains("不是空白页证明"));
        ToolResult preview = document().execute(args("ofd_view","path","scanned.ofd","width","10","height","12","rotate","90"));
        assertTrue(preview.output,preview.ok);
        assertTrue(preview.output.contains("不是整页渲染"));
        assertEquals("scanned.ofd#page=1&image=1",preview.images.get(0).name);
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(preview.images.get(0).base64)));
        assertEquals(12,actual.getWidth());assertEquals(10,actual.getHeight());
        assertEquals(0xffcc4422,actual.getRGB(3,3));
        try(Stream<Path> files=Files.list(root().resolve(".minion/tmp"))) { assertEquals(0,files.count()); }
        ToolResult ocr = document().execute(args("ofd_ocr","path","scanned.ofd","language","eng;invalid"));
        assertFalse(ocr.output,ocr.ok); assertTrue(ocr.output.contains("OCR语言"));
        try(Stream<Path> files=Files.list(root().resolve(".minion/tmp"))) { assertEquals(0,files.count()); }
    }

    @Test public void multiplePicturesRequireSelectionAndUseOnlyReferencedResources() throws Exception {
        scan("scanned.ofd",true);
        ToolResult list = document().execute(args("ofd_view","path","scanned.ofd"));
        assertTrue(list.output,list.ok);
        assertTrue(list.output.contains("imageIndex=1..2")); assertNull(list.images);
        ToolResult chosen = document().execute(args("ofd_view","path","scanned.ofd","imageIndex","2"));
        assertTrue(chosen.output,chosen.ok);
        assertTrue(chosen.images.get(0).name.endsWith("image=2"));
    }

    @Test public void actualLocalOcrReadsScanWithoutUsingAVisionModel() throws Exception {
        String exe=System.getProperty("minion.test.tesseract","");
        Assume.assumeTrue(!exe.isEmpty() && Files.isRegularFile(Paths.get(exe)));
        BufferedImage image=new BufferedImage(900,200,BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g=image.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,900,200);
            g.setColor(java.awt.Color.BLACK);g.setFont(new java.awt.Font("Dialog",java.awt.Font.BOLD,58));
            g.drawString("INVOICE 12345",40,110);
        } finally {g.dispose();}
        archive("ocr.ofd",scanEntries(false,image));
        Config config=Config.load(root());config.set("ocr.path",exe);
        ToolResult text=new DocumentTool(workspace(),null,null,config,null)
                .execute(args("ofd_ocr","path","ocr.ofd","language","eng","psm","7"));
        assertTrue(text.output,text.ok);assertNull(text.images);
        assertTrue(text.output,text.output.contains("INVOICE") && text.output.contains("12345"));
        assertTrue(text.output.contains("ocr.ofd#page=1&image=1"));
        try(Stream<Path> files=Files.list(root().resolve(".minion/tmp"))) { assertEquals(0,files.count()); }
    }

    @Test public void formatPathsAndXmlExternalEntitiesAreRejectedWithoutReadingOutside() throws Exception {
        Files.write(root().resolve("bad.ofd"),"not an archive".getBytes(StandardCharsets.UTF_8));
        assertTrue(document().execute(args("ofd_read","path","bad.ofd")).output.contains("不是有效的OFD"));
        Map<String,byte[]> files=single(page("不应读取"));
        put(files,"OFD.xml",entry("../test.xml",""));
        archive("bad-ref.ofd",files);
        ToolResult path = document().execute(args("ofd_read","path","bad-ref.ofd"));
        assertFalse(path.output,path.ok);assertTrue(path.output.contains("越出压缩包"));
        files = single(page("数据"));
        put(files,"Doc/Content.xml","<!DOCTYPE Page [<!ENTITY e SYSTEM 'file:///missing-sentinel'>]>"
                + page("&e;").replace("&amp;e;","&e;"));
        archive("external.ofd",files);
        ToolResult external=document().execute(args("ofd_read","path","external.ofd"));
        assertFalse(external.output,external.ok);assertTrue(external.output.contains("DTD")||external.output.contains("不允许"));
    }

    @Test public void pageBoundsAndArchiveExpansionAreEnforced() throws Exception {
        sample(SOURCE);
        assertFalse(document().execute(args("ofd_read","path",SOURCE,"startPage","0")).ok);
        assertFalse(document().execute(args("ofd_read","path",SOURCE,"startPage","50")).ok);
        assertFalse(document().execute(args("ofd_read","path",SOURCE,"startPage","1","endPage","21")).ok);
        assertFalse(document().execute(args("ofd_read","path",SOURCE,"pageTextOffset","2")).ok);
        assertFalse(document().execute(args("ofd_view","path",SOURCE,"startPage","1","endPage","2")).ok);
        Map<String,byte[]> huge = single(page("unused")); huge.put("Doc/Content.xml",new byte[8*1024*1024+1]);
        archive("huge.ofd",huge);
        ToolResult over=document().execute(args("ofd_read","path","huge.ofd"));
        assertFalse(over.output,over.ok);assertTrue(over.output.contains("解压数据超过"));
    }

    @Test public void ofdToolsUseExistingReadOnlyAndResourcePolicies() throws Exception {
        sample(SOURCE);
        for(String name : Arrays.asList("ofd_read","ofd_view","ofd_ocr")) {
            JsonObject args = args(name,"path",SOURCE);
            assertTrue(DocumentTool.readOnlyAction(name));
            assertFalse(document().isHighRisk(args));
            assertTrue(ToolResourceScope.resolve("Document",args,workspace()).known);
        }
        Path external = tmp.newFolder("external").toPath();
        Path copy = Files.copy(root().resolve(SOURCE),external.resolve("external.ofd"));
        Workspace isolated = new Workspace(tmp.newFolder("sandbox").toString());
        DocumentTool owned = new DocumentTool(isolated,null,null);
        try {
            owned.execute(args("ofd_read","path",copy.toString()));
            fail("workspace guard must reject");
        } catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("工作路径之外")); }
    }

    private Path sample(String file) throws Exception {
        Map<String, byte[]> data = new LinkedHashMap<>();
        put(data,"OFD.xml",entry("/Doc/Document.xml","<o:DocBody><o:DocInfo><o:Title>第二份</o:Title></o:DocInfo><o:DocRoot>子文档/Document.xml</o:DocRoot></o:DocBody>"));
        put(data,"Doc/Document.xml","<o:Document xmlns:o=\""+NS+"\"><o:CommonData>"
                +"<o:TemplatePage ID=\"9\" BaseLoc=\"Tpl/Content.xml\"/></o:CommonData><o:Pages>"
                +"<o:Page ID=\"1\" BaseLoc=\"Pages/z.xml\"/><o:Page ID=\"3\" BaseLoc=\"Pages/a.xml\"/>"
                +"<o:Page ID=\"2\" BaseLoc=\"/Doc/Pages/m.xml\"/></o:Pages></o:Document>");
        put(data,"Doc/Pages/z.xml", "<Page xmlns=\""+NS+"\"><Template TemplateID=\"9\"/><Content><Layer>"
                + object(70,30,"01230001") + object(10,40,"金额：¥100.00") + object(10,30,"发票号码")
                + object(10,50,"税费\\0020A&amp;B &lt;4&gt;") + "</Layer></Content></Page>");
        put(data,"Doc/Tpl/Content.xml",page("标题：电子发票"));
        put(data,"Doc/Pages/a.xml",page("第2页唯一文本"));
        put(data,"Doc/Pages/m.xml",page("第3页"));
        put(data,"子文档/Document.xml",index("<Page ID=\"1\" BaseLoc=\"Page.xml\"/>"));
        put(data,"子文档/Page.xml",page("第二份文档最后页"));
        // Unreferenced decoy must never be accidentally read.
        put(data,"Doc/Pages/Page_99/Content.xml",page("未引用页面"));
        return archive(file,data);
    }

    private Path scan(String file,boolean multi) throws Exception {
        BufferedImage image=new BufferedImage(30,20,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,0xffcc4422);
        return archive(file,scanEntries(multi,image));
    }
    private Map<String,byte[]> scanEntries(boolean multi,BufferedImage image) throws Exception {
        Map<String,byte[]> data=single("<Page xmlns=\""+NS+"\"><PageRes>./resources/Res.xml</PageRes>"
                +"<Content><Layer><ImageObject ResourceID=\"8\"/>"
                +(multi?"<ImageObject ResourceID=\"9\"/>":"")+"</Layer></Content></Page>");
        put(data,"Doc/resources/Res.xml","<Res xmlns=\""+NS+"\" BaseLoc=\"../images\"><MultiMedias>"
                +"<MultiMedia ID=\"8\" Type=\"Image\"><MediaFile>p.png</MediaFile></MultiMedia>"
                +"<MultiMedia ID=\"9\" Type=\"Image\"><MediaFile>q.png</MediaFile></MultiMedia>"
                +"<MultiMedia ID=\"10\" Type=\"Image\"><MediaFile>unused.png</MediaFile></MultiMedia></MultiMedias></Res>");
        ByteArrayOutputStream binary=new ByteArrayOutputStream();ImageIO.write(image,"png",binary);
        data.put("Doc/images/p.png",binary.toByteArray());data.put("Doc/images/q.png",binary.toByteArray());
        return data;
    }

    private static Map<String,byte[]> single(String content) {
        Map<String,byte[]> entries=new LinkedHashMap<>();
        put(entries,"OFD.xml",entry("Doc/Document.xml",""));
        put(entries,"Doc/Document.xml",index("<Page ID=\"1\" BaseLoc=\"Content.xml\"/>"));
        put(entries,"Doc/Content.xml",content);return entries;
    }
    private static String entry(String path,String rest) {
        return "<o:OFD xmlns:o=\""+NS+"\"><o:DocBody><o:DocInfo><o:Title>测试</o:Title></o:DocInfo><o:DocRoot>"
                +path+"</o:DocRoot><o:Signatures>Signs/Signatures.xml</o:Signatures></o:DocBody>"+rest+"</o:OFD>";
    }
    private static String index(String pageRefs) { return "<Document xmlns=\""+NS+"\"><Pages>"+pageRefs+"</Pages></Document>"; }
    private static String page(String text) {
        return "<Page xmlns=\""+NS+"\"><Content><Layer><PageBlock>"+object(10,5,text)+"</PageBlock></Layer></Content></Page>";
    }
    private static String object(int x,int y,String text) {
        return "<TextObject Boundary=\""+x+" "+y+" 100 10\"><TextCode X=\"0\" Y=\"3\">"+text+"</TextCode></TextObject>";
    }
    private Path archive(String file,Map<String,byte[]> entries) throws Exception {
        Path dst=root().resolve(file);
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(dst))) {
            for(Map.Entry<String,byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());zip.closeEntry();
            }
        }
        return dst;
    }
    private static void put(Map<String,byte[]> data,String name,String value) { data.put(name,value.getBytes(StandardCharsets.UTF_8)); }
    private static JsonObject args(String action,String... pairs) { return PdfWorkflowTest.args(action,pairs); }
}
