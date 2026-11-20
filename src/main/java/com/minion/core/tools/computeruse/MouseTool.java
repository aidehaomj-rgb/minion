package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolArguments;
import com.minion.core.tools.ToolResult;

import java.awt.Dimension;
import java.awt.Robot;

/** 计算机使用·鼠标：点击/双击/右键/拖拽/移动/滚动。坐标为截图坐标，按 scale（ScreenTool 输出）换算屏幕坐标。
 *  点击/拖拽为高危动作（走确认）；move/scroll 低危。 */
public final class MouseTool implements Tool {
    private final ComputerUseConfig config;
    public MouseTool(ComputerUseConfig config) { this.config = config; }

    @Override public String name() { return "Mouse"; }

    @Override public String description() {
        return "计算机使用-鼠标。action=click/doubleclick/rightclick/move/drag/scroll。"
                + "x,y 为截图坐标（必须把最近一次 Screen 的 scale 一并传入，自动换算屏幕坐标）。"
                + "scroll 的 direction=up/down，amount 为格数（1-30）。drag 从 (x1,y1) 按住拖到 (x2,y2)。"
                + "操作后应再次 Screen 截图验证结果。";
    }

    @Override public JsonObject schema() {
        JsonObject schema = com.minion.core.tools.SchemaGenerator.objectSchema(description(),
                new String[]{"action", "x", "y", "x1", "y1", "x2", "y2", "direction", "amount", "scale"}, new String[]{"action"});
        JsonObject props = schema.getAsJsonObject("properties");
        for (String k : new String[]{"x", "y", "x1", "y1", "x2", "y2", "amount"}) props.getAsJsonObject(k).addProperty("type", "integer");
        JsonObject s = new JsonObject(); s.addProperty("type", "number"); props.add("scale", s);
        return schema;
    }

    @Override public boolean isHighRisk(JsonObject args) {
        String a = str(args, "action");
        // 点击/双击/右键/拖拽会真实作用于应用（发消息、提交表单），高危确认；move/scroll 仅移动视口
        return "click".equals(a) || "doubleclick".equals(a) || "rightclick".equals(a) || "drag".equals(a);
    }

    @Override public ToolResult execute(JsonObject args) {
        String action = str(args, "action");
        try {
            Robot robot = new Robot();
            double scale = scale(args);
            if ("move".equals(action)) {
                int[] p = toScreen(args, "x", "y", scale);
                robot.mouseMove(p[0], p[1]);
                return ToolResult.success("已移动到 " + p[0] + "," + p[1] + "（屏幕坐标）");
            }
            if ("click".equals(action) || "doubleclick".equals(action) || "rightclick".equals(action)) {
                int[] p = toScreen(args, "x", "y", scale);
                robot.mouseMove(p[0], p[1]);
                robot.delay(config.clickDelayMsSafe());
                int buttons = "rightclick".equals(action) ? java.awt.event.InputEvent.BUTTON3_DOWN_MASK
                        : java.awt.event.InputEvent.BUTTON1_DOWN_MASK;
                int times = "doubleclick".equals(action) ? 2 : 1;
                for (int i = 0; i < times; i++) {
                    robot.mousePress(buttons);
                    robot.mouseRelease(buttons);
                    if (i + 1 < times) robot.delay(60);
                }
                return ToolResult.success((times > 1 ? "已双击" : "rightclick".equals(action) ? "已右键" : "已点击")
                        + " " + p[0] + "," + p[1] + "（屏幕坐标）。建议 Screen 截图验证。");
            }
            if ("drag".equals(action)) {
                int[] from = toScreen(args, "x1", "y1", scale);
                int[] to = toScreen(args, "x2", "y2", scale);
                robot.mouseMove(from[0], from[1]);
                robot.delay(config.clickDelayMsSafe());
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                int steps = 12;
                for (int i = 1; i <= steps; i++) {
                    robot.mouseMove(from[0] + (to[0] - from[0]) * i / steps, from[1] + (to[1] - from[1]) * i / steps);
                    robot.delay(16);
                }
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                return ToolResult.success("已从 " + from[0] + "," + from[1] + " 拖到 " + to[0] + "," + to[1] + "。建议 Screen 截图验证。");
            }
            if ("scroll".equals(action)) {
                int[] p = toScreen(args, "x", "y", scale);
                int amount = ToolArguments.intInRange(args, "amount", 3, 1, 30);
                robot.mouseMove(p[0], p[1]);
                String dir = str(args, "direction");
                int wheel = "up".equals(dir) ? -1 : 1;
                robot.mouseWheel(wheel * amount);
                return ToolResult.success("已在 " + p[0] + "," + p[1] + " 滚动 " + amount + " 格（" + ("up".equals(dir) ? "向上" : "向下") + "）");
            }
            return ToolResult.error("action 必须是 click/doubleclick/rightclick/move/drag/scroll");
        } catch (Exception e) {
            return ToolResult.error("鼠标操作失败: " + e.getMessage());
        }
    }

    static double scale(JsonObject args) {
        double s = 1.0;
        if (args.has("scale") && args.get("scale").isJsonPrimitive()) {
            try { s = args.get("scale").getAsDouble(); } catch (RuntimeException ignored) { }
        }
        return s > 0.05 && s < 20 ? s : 1.0;
    }

    /** 截图坐标 → 屏幕坐标（四舍五入），并夹到屏幕内 */
    static int[] toScreen(JsonObject args, String xKey, String yKey, double scale) {
        if (scale < 0.05 || scale > 20) scale = 1.0;   // 模型传坏值防御
        int x = (int) Math.round(ToolArguments.integer(args, xKey, 0) * scale);
        int y = (int) Math.round(ToolArguments.integer(args, yKey, 0) * scale);
        Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        if (screen != null && screen.width > 0) {
            x = Math.max(0, Math.min(x, screen.width - 1));
            y = Math.max(0, Math.min(y, screen.height - 1));
        }
        return new int[]{x, y};
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }
}
