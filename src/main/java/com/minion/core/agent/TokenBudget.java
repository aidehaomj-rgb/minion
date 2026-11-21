package com.minion.core.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户输入 token 预算解析（开发说明 v2.4 §1.5）：行首/行尾 {@code +500k}/{@code +2M}
 * 独立标记，或「用/花 N k tokens」句式。k=1000、m=1000000。
 * 返回 null = 无预算（默认路径，零影响）。
 */
public final class TokenBudget {

    /** 独立成行的 +Nk/+Nm 标记（允许 +2K 大写；前后空白容忍） */
    private static final Pattern MARK = Pattern.compile(
            "(?m)^\\s*\\+\\s*(\\d{1,6})\\s*([kKmM])?\\s*$");
    /** "用/花 500k tokens" / "花 2M token" 句式 */
    private static final Pattern PHRASE = Pattern.compile(
            "(?:用|花)\\s*(\\d{1,9})\\s*([kKmM]?)\\s*(?:个\\s*)?tokens?",
            Pattern.CASE_INSENSITIVE);

    private TokenBudget() { }

    /** 解析输入中的预算标记；无/非法返回 null */
    public static Long parse(String input) {
        if (input == null || input.isEmpty()) return null;
        try {
            Matcher m = MARK.matcher(input);
            if (m.find()) return scale(Long.parseLong(m.group(1)), m.group(2));
            m = PHRASE.matcher(input);
            if (m.find()) return scale(Long.parseLong(m.group(1)), m.group(2));
        } catch (NumberFormatException tooLarge) {
            return null;   // 超长数字视为无效标记
        }
        return null;
    }

    private static long scale(long n, String unit) {
        if (unit == null || unit.isEmpty()) return n;
        if (unit.equalsIgnoreCase("k")) return n * 1000L;
        return n * 1_000_000L;   // m/M
    }
}
