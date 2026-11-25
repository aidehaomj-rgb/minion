package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import org.junit.Test;

import java.awt.image.BufferedImage;

import static org.junit.Assert.*;

/**
 * 三批 C4 ScreenTool diff 接线（headless 可测部分）：无基线可操作错误、
 * 旧错误文案子串保留、基线记录/对比/刷新链路。逐像素占比与 bbox 精断言见 ScreenDiffTest。
 */
public class ScreenToolDiffTest {

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, rgb);
        return img;
    }

    private static JsonObject action(String a) {
        JsonObject o = new JsonObject();
        o.addProperty("action", a);
        return o;
    }

    /** 无上次截图：可操作错误，指引先全屏 screenshot（headless 不触 Robot） */
    @Test
    public void diffWithoutBaseline_actionableError() {
        ScreenTool tool = new ScreenTool(new ComputerUseConfig());
        ToolResult r = tool.execute(action("diff"));
        assertFalse(r.ok);
        assertTrue("应指引先截图: " + r.output, r.output.contains("screenshot"));
        assertTrue(r.output.contains("基线"));
    }

    /** 未知动作错误文案保留旧子串（旧测试锚点），增量提及 diff */
    @Test
    public void unknownAction_keepsLegacyMessageSubstring() {
        ScreenTool tool = new ScreenTool(new ComputerUseConfig());
        ToolResult r = tool.execute(action("zoom"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("screenshot 或 windows"));
        assertTrue(r.output.contains("diff"));
    }

    /** 基线记录 → 相同帧对比：无显著变化 */
    @Test
    public void rememberThenDiffIdentical_reportsNoChange() {
        ScreenTool tool = new ScreenTool(new ComputerUseConfig());
        tool.rememberBaseline(solid(200, 100, 0x306090));
        ToolResult r = tool.diffAgainst(solid(200, 100, 0x306090));
        assertTrue(r.ok);
        assertTrue(r.output.contains("无显著变化"));
    }

    /** 局部变化：输出占比与 bbox */
    @Test
    public void localPatch_reportsRatioAndBbox() {
        ScreenTool tool = new ScreenTool(new ComputerUseConfig());
        tool.rememberBaseline(solid(100, 100, 0));
        BufferedImage cur = solid(100, 100, 0);
        for (int y = 10; y < 20; y++) for (int x = 40; x < 60; x++) cur.setRGB(x, y, 0xFFFFFF);
        ToolResult r = tool.diffAgainst(cur);
        assertTrue(r.ok);
        assertTrue(r.output.contains("2.00%"));
        assertTrue(r.output.contains("bbox=(40,10)-(59,19)"));
    }

    /** 尺寸变化：按全变化处理并提示重建基线 */
    @Test
    public void sizeMismatch_reportsAndSuggestsRebaseline() {
        ScreenTool tool = new ScreenTool(new ComputerUseConfig());
        tool.rememberBaseline(solid(100, 100, 0));
        ToolResult r = tool.diffAgainst(solid(120, 100, 0));
        assertTrue(r.ok);
        assertTrue(r.output.contains("尺寸已变化"));
        assertTrue(r.output.contains("screenshot"));
    }

    /** diff 后基线刷新为当前帧：连续 diff 对比相邻状态 */
    @Test
    public void diffRefreshesBaselineToCurrentFrame() {
        ScreenTool tool = new ScreenTool(new ComputerUseConfig());
        tool.rememberBaseline(solid(100, 100, 0));
        BufferedImage white = solid(100, 100, 0xFFFFFF);
        assertTrue(tool.diffAgainst(white).output.contains("100.00%"));
        assertTrue("第二次 diff 应对比刷新后的白帧", tool.diffAgainst(white).output.contains("无显著变化"));
    }

    /** 大分辨率基线降采样到 ≤640 边后仍可对齐对比 */
    @Test
    public void largeBaseline_downscaledAndComparable() {
        ScreenTool tool = new ScreenTool(new ComputerUseConfig());
        tool.rememberBaseline(solid(1920, 1080, 0x204060));
        ToolResult r = tool.diffAgainst(solid(1920, 1080, 0x204060));
        assertTrue(r.ok);
        assertTrue("同色大图降采样后应无变化: " + r.output, r.output.contains("无显著变化"));
    }
}