package com.minion.core.tools.db;

/** 仅清洗配置描述和驱动错误；不篡改用户查询成功返回的数据。 */
final class DbSecrets {
    private DbSecrets() { }

    static String redact(String text, DataSourceConfig source) {
        if (text == null) return "";
        String safe = text;
        if (source != null && source.password != null && !source.password.isEmpty())
            safe = safe.replace(source.password, "[redacted]");
        // URL 参数/Oracle user/password@/URL user:password@ 都可能携带口令。
        safe = safe.replaceAll("(?i)((?:password|passwd|pwd)\\s*[=:]\\s*)[^;&\\s]+", "$1[redacted]");
        safe = safe.replaceAll("(//[^/@\\s:]+:)[^@/\\s]+@", "$1[redacted]@");
        return safe.replaceAll("(?i)(jdbc:oracle:[^:]+:[^/@\\s]+/)[^@\\s]+@", "$1[redacted]@");
    }
}
