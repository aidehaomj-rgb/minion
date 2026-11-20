package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.util.List;

/** 计算机使用·窗口：按标题找窗口并激活/最小化/温和关闭。列表只读；激活低干扰；
 *  关闭走 WM_CLOSE（等同点关闭按钮，应用可弹保存确认）。 */
public final class WindowTool implements Tool {
    public WindowTool() {}

    @Override public String name() { return "Window"; }

    @Override public String description() {
        return "计算机使用-窗口管理。action=list：可见窗口清单（同 Screen windows）；"
                + "action=activate：按标题模糊匹配把窗口切到前台（如政务微信、关信的会话窗口）；"
                + "action=minimize / action=close：最小化 / 温和关闭（等同点关闭按钮，未保存内容应用会弹确认）。"
                + "title 为标题子串（忽略大小写）。操作后可 Screen 截图验证。";
    }

    @Override public JsonObject schema() {
        return com.minion.core.tools.SchemaGenerator.objectSchema(description(),
                new String[]{"action", "title"}, new String[]{"action"});
    }

    @Override public boolean isHighRisk(JsonObject args) {
        return "close".equals(str(args, "action"));   // 关窗口可能丢未保存内容；activate/minimize/list 低危
    }

    @Override public ToolResult execute(JsonObject args) {
        String action = str(args, "action");
        if ("list".equals(action)) {
            List<Win32Gui.WindowInfo> windows = Win32Gui.visibleWindows();
            StringBuilder sb = new StringBuilder("可见窗口（前台在前）：\n");
            int i = 1;
            for (Win32Gui.WindowInfo w : windows) {
                if (sb.length() > 6000) { sb.append("…（已截断）"); break; }
                sb.append(i++).append(". ").append(w.title).append(" | pid=").append(w.pid).append('\n');
            }
            return ToolResult.success(sb.toString());
        }
        String title = str(args, "title");
        if (title.isEmpty()) return ToolResult.error("activate/minimize/close 需要 title（标题子串）");
        Win32Gui.WindowInfo w = Win32Gui.findWindow(title);
        if (w == null) return ToolResult.error("未找到标题包含「" + title + "」的可见窗口；可用 action=list 查看当前窗口");
        if ("activate".equals(action)) {
            boolean ok = Win32Gui.activate(w);
            return ok ? ToolResult.success("已激活窗口：" + w.title)
                    : ToolResult.error("激活失败（前台锁定）：" + w.title + "；可重试或先点击任务栏");
        }
        if ("minimize".equals(action)) {
            Win32Gui.minimize(w);
            return ToolResult.success("已请求最小化：" + w.title);
        }
        if ("close".equals(action)) {
            boolean ok = Win32Gui.close(w);
            return ok ? ToolResult.success("已发送关闭请求（WM_CLOSE）：" + w.title + "；若有未保存内容应用会弹确认")
                    : ToolResult.error("关闭请求发送失败：" + w.title);
        }
        return ToolResult.error("action 必须是 list/activate/minimize/close");
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }
}
