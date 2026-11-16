package com.minion.core.llm;

import com.minion.core.context.TokenCounter;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.Assert.*;

public class ImageTokensTest {

    private static String base64Image(String format, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h,
                "jpg".equals(format) || "bmp".equals(format) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue("ImageIO 应支持 " + format, ImageIO.write(img, format, out));
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static ImagePart part(String base64) {
        ImagePart p = new ImagePart();
        p.base64 = base64;
        return p;
    }

    @Test
    public void png_largeImage_scalesByPatches() throws Exception {
        // 1000x1000 → ceil(1000/28)=36 → 36*36=1296
        assertEquals(1296, ImageTokens.estimate(part(base64Image("png", 1000, 1000))));
    }

    @Test
    public void smallImage_keepsLegacyFloor500() throws Exception {
        assertEquals(500, ImageTokens.estimate(part(base64Image("png", 100, 100))));
        assertEquals(500, ImageTokens.estimate(part(base64Image("gif", 56, 56))));
        assertEquals(500, ImageTokens.estimate(part(base64Image("bmp", 280, 280))));
    }

    @Test
    public void hugeImage_cappedAt16384() throws Exception {
        assertEquals(16384, ImageTokens.estimate(part(base64Image("png", 4000, 4000))));
    }

    @Test
    public void jpeg_dimensionsParsed() throws Exception {
        // 800x600 → ceil(800/28)=29, ceil(600/28)=22 → 638
        assertEquals(638, ImageTokens.estimate(part(base64Image("jpg", 800, 600))));
    }

    @Test
    public void gif_and_bmp_dimensionsParsed() throws Exception {
        // 560x280 → 20*10=200 → 下限 500；2800x280 → 100*10=1000
        assertEquals(500, ImageTokens.estimate(part(base64Image("gif", 560, 280))));
        assertEquals(1000, ImageTokens.estimate(part(base64Image("bmp", 2800, 280))));
    }

    @Test
    public void badOrMissingBase64_fallsBackTo500() {
        assertEquals(500, ImageTokens.estimate(part(null)));
        assertEquals(500, ImageTokens.estimate(part("")));
        assertEquals(500, ImageTokens.estimate(part("!!!not-base64!!!")));
        assertEquals(500, ImageTokens.estimate(part(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))));
        assertEquals(0, ImageTokens.estimate(null));
    }

    @Test
    public void estimateCachedInTokenHint() throws Exception {
        ImagePart p = part(base64Image("png", 1000, 1000));
        assertEquals(1296, ImageTokens.estimate(p));
        assertEquals(1296, p.tokenHint);
        p.base64 = null; // 缓存命中，不再解析
        assertEquals(1296, ImageTokens.estimate(p));
    }

    @Test
    public void tokenCounter_usesDimensionEstimate() throws Exception {
        Message m = Message.user("看图");
        m.images = java.util.Collections.singletonList(part(base64Image("png", 1000, 1000)));
        int expected = 4 + TokenCounter.estimate("看图") + 1296;
        assertEquals(expected, TokenCounter.estimateMessages(java.util.Collections.singletonList(m)));
    }
}