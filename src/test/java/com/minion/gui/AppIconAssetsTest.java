package com.minion.gui;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppIconAssetsTest {
    @Test public void selectedArtworkHasAllRequiredTransparentWindowSizes()throws Exception{
        for(int size:new int[]{16,24,32,48,64,128,256})try(InputStream in=getClass().getResourceAsStream("/app/minion-"+size+".png")){
            assertNotNull(in);BufferedImage image=ImageIO.read(in);assertNotNull(image);assertEquals(size,image.getWidth());assertEquals(size,image.getHeight());
            assertTrue(image.getColorModel().hasAlpha());assertEquals(0,image.getRGB(0,0)>>>24);assertTrue((image.getRGB(size/2,size/2)>>>24)>240);
        }
    }
    private int u16(InputStream in)throws IOException{return in.read()|(in.read()<<8);}
    @Test public void executableIconIsASevenFrame32BitIco()throws Exception{
        try(InputStream in=getClass().getResourceAsStream("/app/minion.ico")){
            assertNotNull(in);assertEquals(0,u16(in));assertEquals(1,u16(in));assertEquals(7,u16(in));
            for(int expected:new int[]{16,24,32,48,64,128,256}){
                int width=in.read(),height=in.read();assertEquals(expected,width==0?256:width);assertEquals(expected,height==0?256:height);
                assertEquals(0,in.read());assertEquals(0,in.read());assertEquals(1,u16(in));assertEquals(32,u16(in));for(int i=0;i<8;i++)assertTrue(in.read()>=0);
            }
        }
    }
}
