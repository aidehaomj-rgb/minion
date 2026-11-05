package com.minion.core.tools.data;
import com.google.gson.JsonObject;
import com.minion.core.tools.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.nio.file.*;

public class PdfWorkflowTest {
 @Rule public TemporaryFolder tmp=new TemporaryFolder();
 private DocumentTool tool(){return new DocumentTool(new Workspace(tmp.getRoot().toString()),null,null);}
 private Path make(String first)throws Exception{
  Path p=tmp.getRoot().toPath().resolve("test.pdf");
  try(PDDocument d=new PDDocument()){
   for(String text:new String[]{first,"Second page", ""}){
    PDPage page=new PDPage();d.addPage(page);
    if(!text.isEmpty())try(PDPageContentStream c=new PDPageContentStream(d,page)){
     c.beginText();c.setFont(PDType1Font.HELVETICA,12);c.newLineAtOffset(20,700);c.showText(text);c.endText();
    }
   }
   d.save(p.toFile());
  }return p;
 }
 static JsonObject args(String action,String... pairs){JsonObject o=new JsonObject();o.addProperty("action",action);for(int i=0;i<pairs.length;i+=2)o.addProperty(pairs[i],pairs[i+1]);return o;}
 @Test public void pageRangesSourceAndCacheVersion()throws Exception{
  make("First version");ToolResult r=tool().execute(args("pdf_read","path","test.pdf","startPage","1","endPage","1"));
  assertTrue(r.output,r.ok);assertTrue(r.output.contains("First version"));assertFalse(r.output.contains("Second page"));assertTrue(r.output.contains("下一次startPage=2"));
  make("New version");r=tool().execute(args("pdf_read","path","test.pdf","startPage","1","endPage","1"));
  assertTrue(r.output.contains("New version"));assertFalse(r.output.contains("First version"));
  r=tool().execute(args("pdf_read","path","test.pdf","startPage","3"));assertTrue(r.output.contains("pdf_view"));
 }
 @Test public void visualPageReturnsPixelsWithoutPersistentTempFile()throws Exception{
  make("Visual page");ToolResult r=tool().execute(args("pdf_view","path","test.pdf","startPage","2","dpi","100","x","0","y","0","width","100","height","80","rotate","90"));
  assertTrue(r.output,r.ok);assertEquals(1,r.images.size());assertEquals("test.pdf#page=2",r.images.get(0).name);
  assertTrue(r.imageContext().toApiJson().getAsJsonArray("content").size()>1);
  try(java.util.stream.Stream<Path> s=Files.list(tmp.getRoot().toPath().resolve(".minion/tmp"))){assertEquals(0,s.count());}
 }
 @Test public void boundariesAndReadRouting()throws Exception{
  make("Page");assertFalse(tool().execute(args("pdf_read","path","test.pdf","startPage","0")).ok);
  assertFalse(tool().execute(args("pdf_view","path","test.pdf","startPage","1","endPage","2")).ok);
  assertFalse(tool().execute(args("pdf_view","path","test.pdf","dpi","900")).ok);
  JsonObject a=new JsonObject();a.addProperty("path","test.pdf");ToolResult route=new ReadTool(new Workspace(tmp.getRoot().toString())).execute(a);assertFalse(route.ok);assertTrue(route.output.contains("pdf_view"));
  try {tool().execute(args("pdf_view","path",tmp.getRoot().getParent()+"/outside.pdf"));fail("must reject");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("工作路径之外"));}
 }
 @Test public void oversizedPageAndOcrInvalidArgumentsFailClosed()throws Exception{
  Path p=tmp.getRoot().toPath().resolve("huge.pdf");try(PDDocument d=new PDDocument()){d.addPage(new PDPage(new PDRectangle(10000,10000)));d.save(p.toFile());}
  assertFalse(tool().execute(args("pdf_view","path","huge.pdf")).ok);
  assertFalse(LocalOcr.read("not-installed", "eng;bad", p,3).ok);
  assertFalse(LocalOcr.read("not-installed", "eng", p,100).ok);
  assertFalse(LocalOcr.status("minion-nonexistent-tesseract").ok);
 }
}
