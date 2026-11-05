package com.minion.core.tools;
import com.google.gson.JsonObject;
import com.minion.core.llm.Message;
import org.junit.Test;
import static org.junit.Assert.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;
public class ViewImageTest {
    @Test public void pixelsAndRotationEnterContext() throws Exception {
        Path p=Files.createTempFile("minion-image-", ".png");
        try {
            ImageIO.write(new BufferedImage(40,20,BufferedImage.TYPE_INT_RGB),"png",p.toFile());
            JsonObject a=new JsonObject();a.addProperty("rotate",90);a.addProperty("width",30);
            ToolResult r=ViewImageTool.load(p,a);assertTrue(r.output,r.ok);
            JsonObject readArgs=new JsonObject();readArgs.addProperty("path",p.getFileName().toString());
            ToolResult rootRead=new ReadTool(new Workspace(p.getParent().toString())).execute(readArgs);
            assertTrue(rootRead.output,rootRead.ok);assertNotNull(rootRead.images);
            readArgs.addProperty("path",p.toString());
            assertFalse(new ReadTool(new Workspace(Files.createTempDirectory("image-denied-").toString())).execute(readArgs).ok);
            BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(r.images.get(0).base64)));
            assertEquals(20,decoded.getWidth());assertEquals(30,decoded.getHeight());
            Message m=r.imageContext();assertTrue(m.toolImage);
            com.minion.core.agent.TaskState task=new com.minion.core.agent.TaskState();
            task.restoreFromHistory(java.util.Arrays.asList(Message.user("核对商品铭牌"),m));
            assertEquals("核对商品铭牌",task.objective);
            Message restored=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(m),Message.class);
            assertTrue(restored.toolImage);assertNotNull(restored.images);
            assertEquals("image_url",m.toApiJson().getAsJsonArray("content").get(1).getAsJsonObject().get("type").getAsString());
            a.addProperty("x",41);assertFalse(ViewImageTool.load(p,a).ok);
        } finally { Files.deleteIfExists(p); }
    }
    @Test public void invalidImageIsNotText() throws Exception {
        Path p=Files.createTempFile("minion-image-bad-", ".jpg");
        try {assertFalse(ViewImageTool.load(p,new JsonObject()).ok);} finally {Files.deleteIfExists(p);}
    }
    @Test public void pathAndComposerUseIdenticalOriginalPixels() throws Exception {
        Path p=Files.createTempFile("minion-image-same-", ".jpg");
        try {
            BufferedImage source=new BufferedImage(80,40,BufferedImage.TYPE_INT_RGB);
            ImageIO.write(source,"jpg",p.toFile());
            ToolResult path=ViewImageTool.load(p,new JsonObject());
            ToolResult composer=ViewImageTool.loadOverview(p);
            assertTrue(path.output,path.ok);assertTrue(composer.output,composer.ok);
            assertEquals("image/jpeg",path.images.get(0).mime);
            assertEquals(composer.images.get(0).base64,path.images.get(0).base64);
            assertArrayEquals(Files.readAllBytes(p),Base64.getDecoder().decode(path.images.get(0).base64));
        } finally {Files.deleteIfExists(p);}
    }
    @Test public void largeImageHasOverviewAndCoordinateTiles() throws Exception {
        Path p=Files.createTempFile("minion-image-tiles-", ".png");
        try {
            BufferedImage source=new BufferedImage(1900,800,BufferedImage.TYPE_INT_RGB);
            ImageIO.write(source,"png",p.toFile());
            ToolResult result=ViewImageTool.load(p,new JsonObject());
            assertTrue(result.output,result.ok);
            assertEquals(3,result.images.size());
            assertTrue(result.images.get(1).name.contains("#局部("));
            assertTrue(result.images.get(2).name.contains("#局部("));
            BufferedImage left=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(result.images.get(1).base64)));
            assertTrue(left.getWidth()<source.getWidth());
            assertEquals(source.getHeight(),left.getHeight());
            assertEquals(1,ViewImageTool.loadOverview(p).images.size());
        } finally {Files.deleteIfExists(p);}
    }
    @Test public void invalidDetailDoesNotSendPixels() throws Exception {
        Path p=Files.createTempFile("minion-image-detail-", ".png");
        try {
            ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",p.toFile());
            JsonObject args=new JsonObject();args.addProperty("detail","unknown");
            assertFalse(ViewImageTool.load(p,args).ok);
        } finally {Files.deleteIfExists(p);}
    }
}
