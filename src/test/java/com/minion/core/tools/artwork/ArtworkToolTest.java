package com.minion.core.tools.artwork;

import com.google.gson.*;
import com.minion.core.agent.*;
import com.minion.core.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import static org.junit.Assert.*;

public class ArtworkToolTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private ArtworkTool tool(){return new ArtworkTool(new Workspace(tmp.getRoot().getPath()),null);}
    private JsonObject args(String action,String path,String output){JsonObject a=new JsonObject();a.addProperty("action",action);if(path!=null)a.addProperty("path",path);if(output!=null)a.addProperty("outputPath",output);return a;}
    private JsonObject simple(){return JsonParser.parseString("{\"width\":320,\"height\":180,\"background\":\"transparent\",\"layers\":[{\"type\":\"rect\",\"x\":20,\"y\":20,\"width\":80,\"height\":80,\"radius\":8,\"fill\":\"#178F83\"}]}").getAsJsonObject();}
    private Path write(JsonObject scene)throws Exception {Path p=tmp.getRoot().toPath().resolve("design.json");Files.write(p,scene.toString().getBytes(StandardCharsets.UTF_8));return p;}

    @Test public void rendersAntialiasedPngAndEditableSvgWithoutPythonOrServices()throws Exception {
        Path source=write(simple());byte[] original=Files.readAllBytes(source);
        ToolResult r=tool().execute(args("render","design.json","image.png"));assertTrue(r.output,r.ok);
        BufferedImage image=ImageIO.read(tmp.getRoot().toPath().resolve("image.png").toFile());
        assertEquals(640,image.getWidth());assertEquals(360,image.getHeight());assertEquals(0,image.getRGB(0,0)>>>24);
        assertEquals(0x178F83,image.getRGB(100,100)&0xFFFFFF);
        assertTrue(Files.isRegularFile(tmp.getRoot().toPath().resolve("image.svg")));
        JsonObject report=JsonParser.parseString(new String(Files.readAllBytes(tmp.getRoot().toPath().resolve("image.artwork-report.json")),StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("not_performed",report.get("visualReview").getAsString());assertEquals(0,report.getAsJsonArray("warnings").size());
        assertArrayEquals(original,Files.readAllBytes(source));assertNull("纯文本部署不能被自动附图触发400",r.images);
    }
    @Test public void templatesRenderWithoutTextOverflow()throws Exception {
        for(String kind:new String[]{"cover","process","dashboard"}) {
            JsonObject a=args("template",kind+".json",null);a.addProperty("template",kind);ToolResult made=tool().execute(a);assertTrue(made.output,made.ok);
            ToolResult r=tool().execute(args("render",kind+".json",kind+".png"));assertTrue(r.output,r.ok);
            JsonObject report=JsonParser.parseString(new String(Files.readAllBytes(tmp.getRoot().toPath().resolve(kind+".artwork-report.json")),StandardCharsets.UTF_8)).getAsJsonObject();
            for(JsonElement w:report.getAsJsonArray("warnings"))assertEquals(w.toString(),"missing_glyph",w.getAsJsonObject().get("code").getAsString());
        }
    }
    @Test public void escapesSvgTextAndFlagsOverflowWithoutDiscardingOriginalText()throws Exception {
        JsonObject s=simple();JsonObject text=JsonParser.parseString("{\"type\":\"text\",\"x\":5,\"y\":5,\"width\":50,\"height\":10,\"fontSize\":20}").getAsJsonObject();
        text.addProperty("text","A<&\"B\n第二行必须保留完整文字");s.getAsJsonArray("layers").add(text);
        ArtworkRenderer.Result r=new ArtworkRenderer().render(s,Collections.emptyMap(),1,true);
        assertTrue(r.svg.contains("&amp;"));assertTrue(r.svg.contains("&lt;"));
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.newDocumentBuilder().parse(new ByteArrayInputStream(r.svg.getBytes(StandardCharsets.UTF_8)));
        assertTrue(r.report.getAsJsonArray("textLayout").get(0).getAsJsonObject().get("overflow").getAsBoolean());
        assertEquals("A<&\"B\n第二行必须保留完整文字",text.get("text").getAsString());
    }
    @Test public void rendererCanBeReusedWithoutLeakingPreviousLayers()throws Exception {
        ArtworkRenderer renderer=new ArtworkRenderer();JsonObject s=simple();ArtworkRenderer.Result first=renderer.render(s,Collections.emptyMap(),1,true);
        ArtworkRenderer.Result second=renderer.render(s,Collections.emptyMap(),1,true);assertEquals(first.svg,second.svg);
    }
    @Test public void preventsOverwriteOfPngSvgReportOrSource()throws Exception {
        Path source=write(simple());Path svg=tmp.getRoot().toPath().resolve("image.svg");Files.write(svg,"原有源稿".getBytes(StandardCharsets.UTF_8));
        ToolResult r=tool().execute(args("render","design.json","image.png"));assertFalse(r.ok);assertTrue(r.noSideEffect);
        assertFalse(Files.exists(tmp.getRoot().toPath().resolve("image.png")));assertEquals("原有源稿",new String(Files.readAllBytes(svg),StandardCharsets.UTF_8));
        assertFalse(tool().execute(args("template",source.toString(),null)).ok);
    }
    @Test public void inspectWritesNothingAndReturnsWarnings()throws Exception {
        JsonObject s=simple();s.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("x",300);write(s);
        ToolResult result=tool().execute(args("inspect","design.json",null));assertTrue(result.output,result.ok);assertTrue(result.output.contains("outside_canvas"));
        try(java.util.stream.Stream<Path> files=Files.list(tmp.getRoot().toPath())){assertEquals(1,files.count());}
    }
    @Test public void rejectsPathEscapeAndRemoteImageAssetsBeforeWriting()throws Exception {
        write(simple());ToolResult escaped=tool().execute(args("render","design.json",tmp.getRoot().toPath().getParent().resolve("escape.png").toString()));
        assertFalse(escaped.ok);assertTrue(escaped.noSideEffect);
        JsonObject s=simple(),image=new JsonObject();image.addProperty("type","image");image.addProperty("path","https://example.invalid/private.png");s.getAsJsonArray("layers").add(image);write(s);
        ToolResult remote=tool().execute(args("render","design.json","remote.png"));assertFalse(remote.ok);assertTrue(remote.noSideEffect);assertFalse(Files.exists(tmp.getRoot().toPath().resolve("remote.png")));
    }
    @Test public void embedsLocalAssetWithoutSvgExternalLinks()throws Exception {
        BufferedImage asset=new BufferedImage(20,10,BufferedImage.TYPE_INT_RGB);asset.setRGB(1,1,0xFF0000);ImageIO.write(asset,"png",tmp.getRoot().toPath().resolve("asset.png").toFile());
        JsonObject s=simple();s.getAsJsonArray("layers").add(JsonParser.parseString("{\"type\":\"image\",\"path\":\"asset.png\",\"x\":120,\"y\":20,\"width\":100,\"height\":100,\"fit\":\"cover\",\"radius\":8}"));write(s);
        ToolResult result=tool().execute(args("render","design.json","asset-result.png"));assertTrue(result.output,result.ok);
        String svg=new String(Files.readAllBytes(tmp.getRoot().toPath().resolve("asset-result.svg")),StandardCharsets.UTF_8);assertTrue(svg.contains("data:image/png;base64,"));assertFalse(svg.contains("xlink:href=\"file:"));
    }
    @Test public void rejectsOversizedCanvasAndInvalidNodeTypes()throws Exception {
        JsonObject s=simple();s.addProperty("width",8192);s.addProperty("height",8192);write(s);
        assertFalse(tool().execute(args("render","design.json","large.png")).ok);
        s=simple();s.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("type","script");write(s);
        ToolResult r=tool().execute(args("render","design.json","script.png"));assertFalse(r.ok);assertTrue(r.noSideEffect);
    }
    @Test public void readOnlyAndIsolatedChildPoliciesMatchRealWrites() throws Exception {
        JsonObject render=args("render","design.json",null),inspect=args("inspect","design.json",null);
        assertTrue(ToolExecutionGuard.readOnly("Artwork",inspect));assertFalse(ToolExecutionGuard.readOnly("Artwork",render));
        assertFalse(StopGate.mutationEvidence("Artwork",inspect));assertTrue(StopGate.mutationEvidence("Artwork",render));
        AgentToolPolicy explore=new AgentToolPolicy(true,"");assertTrue(explore.permits("Artwork",inspect));assertFalse(explore.permits("Artwork",render));
        Path isolated=tmp.getRoot().toPath().resolve("child");Files.createDirectories(isolated);AgentToolPolicy worker=new AgentToolPolicy(false,"",isolated.toString());
        render.addProperty("outputPath",isolated.resolve("new.png").toString());assertTrue(worker.permits("Artwork",render));
        render.addProperty("outputPath",tmp.getRoot().toPath().resolve("wrong.png").toString());assertFalse(worker.permits("Artwork",render));
    }
    @Test public void helpAndFontInspectionDoNotClaimAiPhotoGeneration() {
        ToolResult help=tool().execute(args("help",null,null)),fonts=tool().execute(args("fonts",null,null));
        assertTrue(help.ok);assertTrue(help.output.contains("不是AI照片生图"));assertTrue(help.output.contains("1600万像素"));assertTrue(fonts.ok);assertTrue(fonts.output.contains("本机实际检测"));
    }
}
