package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.minion.core.llm.ImagePart;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolArguments;
import com.minion.core.tools.ToolResult;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/** 计算机使用·屏幕：全屏/区域截图（附入模型视觉上下文）与可见窗口清单。
 *  只读工具，无高危确认。坐标规范：返回截图的实际像素尺寸与 scale；
 *  Mouse 操作按截图坐标换算屏幕坐标（scale 见 ScreenTool 输出，Mouse 传同值）。 */
public final class ScreenTool implements Tool {
    private final ComputerUseConfig config;
    /** 三批 C4：最近一次全屏截图基线（软引用 1 张，缩 ≤640 边）；实例随会话注册，会话结束可回收 */
    private volatile java.lang.ref.SoftReference<BufferedImage> lastFullShot;

    public ScreenTool(ComputerUseConfig config) { this.config = config; }

    @Override public String name() { return "Screen"; }

    @Override public String description() {
        return "计算机使用-屏幕（只读）。action=screenshot：全屏或区域截图，图像自动附入你的视觉上下文；"
                + "返回屏幕实际尺寸、截图尺寸与 scale（Mouse 坐标换算用，操作时把该 scale 传给 Mouse）。"
                + "action=windows：列出当前可见顶层窗口（标题/PID/位置，按前台到后台排序）。"
                + "action=diff：与上次全屏截图逐像素对比，输出变化占比与区域，验证操作是否生效（需先有一次全屏 screenshot）。"
                + "截图坐标规范：左上角为原点，x 向右 y 向下，单位像素。";
    }

    @Override public JsonObject schema() {
        JsonObject schema = com.minion.core.tools.SchemaGenerator.objectSchema(description(),
                new String[]{"action", "x", "y", "w", "h"}, new String[]{"action"});
        JsonObject props = schema.getAsJsonObject("properties");
        for (String k : new String[]{"x", "y", "w", "h"}) props.getAsJsonObject(k).addProperty("type", "integer");
        return schema;
    }

    @Override public ToolResult execute(JsonObject args) {
        String action = text(args, "action");
        if ("windows".equals(action)) {
            List<Win32Gui.WindowInfo> windows = Win32Gui.visibleWindows();
            StringBuilder sb = new StringBuilder("可见窗口（前台在前）：\n");
            int i = 1;
            for (Win32Gui.WindowInfo w : windows) {
                if (sb.length() > 6000) { sb.append("…（共 ").append(windows.size()).append(" 个，已截断）"); break; }
                sb.append(i++).append(". ").append(w.title).append(" | pid=").append(w.pid)
                        .append(" | 矩形=").append(w.x).append(',').append(w.y).append(' ').append(w.w).append('x').append(w.h).append('\n');
            }
            return ToolResult.success(sb.toString());
        }
        if ("diff".equals(action)) {
            if (lastFullShot == null || lastFullShot.get() == null) {
                return ToolResult.error("没有上次全屏截图基线：先调用 Screen(action=screenshot) 全屏截图，操作后再用 diff 验证");
            }
            try {
                Robot robot = new Robot();
                Rectangle screen = new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
                return diffAgainst(robot.createScreenCapture(screen));
            } catch (Exception e) {
                return ToolResult.error("diff 截图失败: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "（无头/无桌面会话或远程场景不可用）");
            }
        }
        if (!"screenshot".equals(action)) return ToolResult.error("action 必须是 screenshot 或 windows（亦支持 diff）");
        try {
            Robot robot = new Robot();
            Rectangle screen = new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
            int x = ToolArguments.intInRange(args, "x", screen.x, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int y = ToolArguments.intInRange(args, "y", screen.y, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int w = ToolArguments.intInRange(args, "w", screen.width, 1, screen.width);
            int h = ToolArguments.intInRange(args, "h", screen.height, 1, screen.height);
            Rectangle region = new Rectangle(x, y, w, h).intersection(screen);
            if (region.width <= 0 || region.height <= 0) return ToolResult.error("区域超出屏幕：屏幕=" + screen.width + "x" + screen.height);
            BufferedImage image = robot.createScreenCapture(region);
            int maxEdge = config.maxScreenshotEdgeSafe();
            double scale = 1.0;
            if (Math.max(image.getWidth(), image.getHeight()) > maxEdge) {
                scale = Math.max(image.getWidth(), image.getHeight()) / (double) maxEdge;
                image = scale(image, (int) Math.round(image.getWidth() / scale), (int) Math.round(image.getHeight() / scale));
            }
            if (region.equals(screen)) rememberBaseline(image); // C4：仅全屏截图建立/刷新 diff 基线
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            ImagePart part = new ImagePart();
            part.mime = "image/png";
            part.base64 = java.util.Base64.getEncoder().encodeToString(out.toByteArray());
            part.name = "screen.png";
            String caption = "屏幕截图。屏幕区域=" + region.width + "x" + region.height + "（原点 " + region.x + "," + region.y + "）；"
                    + "本图=" + image.getWidth() + "x" + image.getHeight() + "；scale=" + String.format("%.4f", scale)
                    + "。本图左上角为 (0,0)；把此 scale 传给 Mouse 换算屏幕坐标。";
            return ToolResult.image(caption, part);
        } catch (Exception e) {
            return ToolResult.error("截图失败: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "（无头/无桌面会话或远程场景不可用）");
        }
    }

    /** C4：记录全屏基线（缩 ≤640 边，软引用内存护栏） */
    void rememberBaseline(BufferedImage fullShot) {
        lastFullShot = new java.lang.ref.SoftReference<BufferedImage>(ScreenDiff.baseline(fullShot));
    }

    /** C4：当前全屏截图对比基线；对比后基线刷新为当前帧（连续 diff 对比相邻状态） */
    ToolResult diffAgainst(BufferedImage currentFullShot) {
        BufferedImage prev = lastFullShot == null ? null : lastFullShot.get();
        if (prev == null) {
            return ToolResult.error("没有上次全屏截图基线：先调用 Screen(action=screenshot) 全屏截图，操作后再用 diff 验证");
        }
        BufferedImage cur = ScreenDiff.baseline(currentFullShot);
        ScreenDiff.Result r = ScreenDiff.compare(prev, cur);
        lastFullShot = new java.lang.ref.SoftReference<BufferedImage>(cur);
        return ToolResult.success(ScreenDiff.describe(r));
    }

    static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static String text(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }
}
