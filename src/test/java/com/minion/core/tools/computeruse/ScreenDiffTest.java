package com.minion.core.tools.computeruse;

import org.junit.Test;

import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

/** C4 截图 diff 纯逻辑测试：合成图像对（全同/局部/全变/尺寸变化）占比与 bbox、基线降采样。 */
public class ScreenDiffTest {

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, rgb);
        return img;
    }

    @Test public void identicalImagesHaveZeroRatio() {
        ScreenDiff.Result r = ScreenDiff.compare(solid(40, 30, 0x336699), solid(40, 30, 0x336699));
        assertFalse(r.sizeMismatch);
        assertEquals(0.0, r.ratio, 1e-9);
        assertFalse(r.changed());
        assertTrue(ScreenDiff.describe(r).contains("无显著变化"));
    }

    @Test public void localPatchProducesExactRatioAndBbox() {
        BufferedImage a = solid(100, 100, 0x000000);
        BufferedImage b = solid(100, 100, 0x000000);
        for (int y = 20; y < 30; y++) for (int x = 40; x < 60; x++) b.setRGB(x, y, 0xFFFFFF);
        ScreenDiff.Result r = ScreenDiff.compare(a, b);
        assertEquals(200.0 / 10000, r.ratio, 1e-9);
        assertEquals(40, r.minX); assertEquals(20, r.minY);
        assertEquals(59, r.maxX); assertEquals(29, r.maxY);
        String text = ScreenDiff.describe(r);
        assertTrue(text.contains("2.00%"));
        assertTrue(text.contains("(40,20)-(59,29)"));
    }

    @Test public void fullChangeIsHundredPercent() {
        ScreenDiff.Result r = ScreenDiff.compare(solid(10, 10, 0x000000), solid(10, 10, 0xFFFFFF));
        assertEquals(1.0, r.ratio, 1e-9);
        assertEquals(0, r.minX); assertEquals(9, r.maxX);
    }

    @Test public void channelDifferenceBelowThresholdIsIgnored() {
        BufferedImage a = solid(8, 8, 0x808080);
        BufferedImage b = solid(8, 8, 0x808080 + 24 * 0x010101);   // 每通道恰 +24，不超阈值
        assertEquals(0.0, ScreenDiff.compare(a, b).ratio, 1e-9);
        BufferedImage c = solid(8, 8, 0x808080 + 25);              // 蓝通道 +25 超阈值
        assertEquals(1.0, ScreenDiff.compare(a, c).ratio, 1e-9);
    }

    @Test public void sizeMismatchReportedAsFullChange() {
        ScreenDiff.Result r = ScreenDiff.compare(solid(10, 10, 0), solid(20, 10, 0));
        assertTrue(r.sizeMismatch);
        assertEquals(1.0, r.ratio, 1e-9);
        assertTrue(ScreenDiff.describe(r).contains("尺寸已变化"));
    }

    @Test public void baselineDownscalesLongEdgeTo640() {
        BufferedImage big = solid(1920, 1080, 0x123456);
        BufferedImage small = ScreenDiff.baseline(big);
        assertEquals(640, small.getWidth());
        assertEquals(360, small.getHeight());
        BufferedImage tiny = solid(320, 200, 0);
        assertSame(tiny, ScreenDiff.baseline(tiny));   // 已达标不重采样
    }
}