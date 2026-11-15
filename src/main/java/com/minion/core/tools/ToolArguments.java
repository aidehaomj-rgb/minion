package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Qwen 等模型工具参数的保守 JSON 容错；只修复常见格式，不猜测业务值。
 *  取值层（主流对齐：codex-rs 以 serde 为唯一参数解析层、OpenCode 以 zod 单层校验）：
 *  全部工具的数值/布尔取值收敛到这里，宽严分离——宽松派（缺失/类型不符回退默认值）用本类；
 *  需要拒绝非法输入的严格语义（如 RuntimeJobsTool/OfdSupport）在工具内显式校验并报错。 */
public final class ToolArguments {
    private ToolArguments() {}

    /** 宽松整数取值：缺失/null/类型不符/溢出一律回退默认值；数字字面量按 Gson 规则取整（30.0→30）。 */
    public static int integer(JsonObject o, String key, int fallback) {
        if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try { return o.get(key).getAsInt(); } catch (RuntimeException e) { return fallback; }
    }

    /** 宽松长整数取值：语义同 {@link #integer}。 */
    public static long longVal(JsonObject o, String key, long fallback) {
        if (o == null || key == null || !o.has(key) || o.get(key).isJsonNull()) return fallback;
        try { return o.get(key).getAsLong(); } catch (RuntimeException e) { return fallback; }
    }

    /** 宽松整数取值并把结果钳制到 [min,max]（用于超时/上限类参数的防御范围）。 */
    public static int intInRange(JsonObject o, String key, int fallback, int min, int max) {
        int value = integer(o, key, fallback);
        return value < min ? min : (value > max ? max : value);
    }

    public static JsonObject parseObject(String raw) {
        String value = raw == null ? "{}" : raw.trim();
        if (value.isEmpty()) value = "{}";
        try { return JsonParser.parseString(value).getAsJsonObject(); }
        catch (Exception first) {
            JsonObject nested = repairNestedArguments(value);
            if (nested != null) return nested;
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

    private static JsonObject repairNestedArguments(String raw) {
        String s = raw.trim();
        if (!s.startsWith("{")) return null;
        if (!"arguments".equals(firstKey(s))) return null;
        String closed = closeUnterminated(s);
        if (closed == null) return null;
        try {
            JsonObject o = JsonParser.parseString(closed).getAsJsonObject();
            return o.has("arguments") ? o : null;
        } catch (RuntimeException e) {
            return null; // 补全后仍畸形：不吞错，交调用方报错
        }
    }

    /** 首个键名（跳过 '{' 与空白；支持引号/裸键）：取不到返回 null */
    private static String firstKey(String s) {
        int i = 1;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '"' || c == '\'') {
            StringBuilder sb = new StringBuilder();
            for (i++; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\\' && i + 1 < s.length()) {
                    sb.append(s.charAt(++i));
                    continue;
                }
                if (ch == c) break;
                sb.append(ch);
            }
            return sb.toString().trim();
        }
        int start = i;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '-') break;
            i++;
        }
        return i > start ? s.substring(start, i) : null;
    }

    /**
     * 补齐尾部未闭合的字符串与括号（只补，不改内容）；无需补全或转义悬挂时返回 null。
     * 引号内（含转义）的括号不参与计数，避免把字符串里的 { } [ ] 当结构。
     */
    static String closeUnterminated(String s) {
        StringBuilder stack = new StringBuilder(); // 未闭合的 { / [
        boolean inString = false;
        boolean escape = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == quote) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '{' || c == '[') {
                stack.append(c);
            } else if (c == '}' || c == ']') {
                if (stack.length() == 0) return null; // 多余的右括号：结构已乱，不猜
                if (stack.charAt(stack.length() - 1) != (c == '}' ? '{' : '[')) return null;
                stack.setLength(stack.length() - 1);
            }
        }
        if (escape) return null; // 悬挂转义：补引号语义不明，放弃
        if (!inString && stack.length() == 0) return null; // 本来就完整：不是本兜底的活
        StringBuilder out = new StringBuilder(s);
        if (inString) out.append(quote);
        for (int i = stack.length() - 1; i >= 0; i--) {
            out.append(stack.charAt(i) == '{' ? '}' : ']');
        }
        return out.toString();
    }


    private static String preview(String value) {
        String one = value.replace('\n', ' ').replace('\r', ' ');
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
    }
}
