package com.minion.core.diagnostics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 日志与诊断输出脱敏；不改变原始配置，只处理展示文本。 */
public final class SecretRedactor {
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|authorization|passwd|pwd)(\\s*[:=]\\s*)([^\\s,;\\\"']+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+\\-/=]{6,}");
    private static final Pattern URL_PASSWORD = Pattern.compile("(://[^:/\\s]+:)([^@/\\s]+)(@)");

    private SecretRedactor() { }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = BEARER.matcher(text).replaceAll("$1<redacted>");
        out = replace(KEY_VALUE.matcher(out), 1, 2, "<redacted>");
        out = URL_PASSWORD.matcher(out).replaceAll("$1<redacted>$3");
        return out;
    }

    private static String replace(Matcher matcher, int nameGroup, int separatorGroup, String replacement) {
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group(nameGroup) + matcher.group(separatorGroup) + replacement;
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
