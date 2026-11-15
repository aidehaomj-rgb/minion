package com.minion.core.tools.data;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.minion.core.tools.*;
public class OcrIntegrationTest {
 @Test public void localEngineReturnsCoordinatesOnSyntheticImage()throws Exception {
  String exe=System.getProperty("minion.test.ocr","");Assume.assumeTrue("requires installed OCR",Files.isRegularFile(Paths.get(exe)));
  Path png=Files.createTempFile("minion-ocr-fixture-",".png");
  try {
   BufferedImage img=new BufferedImage(600,120,BufferedImage.TYPE_INT_RGB);Graphics2D g=img.createGraphics();
   g.setColor(Color.WHITE);g.fillRect(0,0,600,120);g.setColor(Color.BLACK);g.setFont(new Font("Arial",Font.BOLD,52));g.drawString("ABC123",25,85);g.dispose();ImageIO.write(img,"png",png.toFile());
   ToolResult r=LocalOcr.read(exe,"eng",png,7);assertTrue(r.output,r.ok);assertTrue(r.output,r.output.contains("ABC123"));assertTrue(r.output,r.output.contains("left\ttop\twidth\theight\tconf\ttext"));
   assertFalse(LocalOcr.read(exe,"nonexistent_language_package",png,7).ok);
  }finally {Files.deleteIfExists(png);}
 }
}
