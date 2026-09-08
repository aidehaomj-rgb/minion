package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Qwen 等模型工具参数的保守 JSON 容错；只修复常见格式，不猜测业务值。 */
public final class ToolArguments {
    private ToolArguments() { }

    public static JsonObject parseObject(String raw) {
        String value = raw == null ? "{}" : raw.trim();
        if (value.isEmpty()) value = "{}";
        try { return JsonParser.parseString(value).getAsJsonObject(); }
        catch (Exception first) {
            String repaired = repair(value);
            try { return JsonParser.parseString(repaired).getAsJsonObject(); }
            catch (Exception second) {
                throw new IllegalArgumentException("JSON 参数无法解析；原始=" + preview(value)
                        + "；修复后=" + preview(repaired) + "；原因=" + second.getMessage());
            }
        }
    }

    static String repair(String raw) {
        String s = raw.trim()
                .replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u2018', '\'').replace('\u2019', '\'');
        if (s.startsWith("```")) {
            int firstLine = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) s = s.substring(firstLine + 1, lastFence).trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) s = s.substring(start, end + 1);
        s = s.replaceAll("([,{]\\s*)([A-Za-z_][A-Za-z0-9_.-]*)(\\s*:)", "$1\"$2\"$3");
        s = s.replaceAll(",\\s*([}\\]])", "$1");
        return s;
    }

    private static String preview(String value) {
        String one = value.replace('\n', ' ').replace('\r', ' ');
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
    }
}
