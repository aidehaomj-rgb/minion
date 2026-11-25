package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

/** 计算机使用·剪贴板：read/write 文本。剪贴板是敏感通道（可能含密码/隐私），
 *  两动作一律 isHighRisk=true 进 ConfirmGate，随插件门控。 */
public final class ClipboardTool implements Tool {
    private final ClipboardAccess access;

    public ClipboardTool() { this(new AwtClipboard()); }
    public ClipboardTool(ClipboardAccess access) { this.access = access; }

    @Override public String name() { return "Clipboard"; }

    @Override public String description() {
        return "计算机使用-剪贴板。action=read：读取当前剪贴板文本；action=write：写入文本到剪贴板（覆盖原内容）。"
                + "剪贴板可能含敏感信息，两动作均需用户确认。";
    }

    @Override public JsonObject schema() {
        return com.minion.core.tools.SchemaGenerator.objectSchema(description(),
                new String[]{"action", "text"}, new String[]{"action"});
    }

    @Override public boolean isHighRisk(JsonObject args) { return true; }

    @Override public boolean isReadOnly(JsonObject args) { return "read".equals(str(args, "action")); }

    @Override public boolean isDestructive(JsonObject args) { return !"read".equals(str(args, "action")); }

    @Override public ToolResult execute(JsonObject args) {
        String action = str(args, "action");
        try {
            if ("read".equals(action)) {
                String text = access.readText();
                if (text == null) return ToolResult.success("剪贴板当前无文本内容");
                if (text.isEmpty()) return ToolResult.success("剪贴板文本为空字符串");
                return ToolResult.success("剪贴板文本（" + text.length() + " 字符）:\n" + text);
            }
            if ("write".equals(action)) {
                String text = str(args, "text");
                if (text.isEmpty()) return ToolResult.error("text 不能为空");
                access.writeText(text);
                return ToolResult.success("已写入剪贴板 " + text.length() + " 字符（原内容已被覆盖）");
            }
            return ToolResult.error("action 必须是 read 或 write");
        } catch (Exception e) {
            return ToolResult.error("剪贴板操作失败: " + e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}