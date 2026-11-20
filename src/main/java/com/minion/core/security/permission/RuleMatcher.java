package com.minion.core.security.permission;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 规则内容匹配（开发说明 v2.4 §4.1，参考 shellRuleMatching.ts 的语义子集）。
 *
 * <p>非文件类内容三种语义，优先级从上到下：
 * <ol>
 *   <li>末尾 {@code :*} → 前缀规则（旧语法兼容；剥掉 {@code :*} 后按前缀匹配）；</li>
 *   <li>含未转义 {@code *} → 通配规则（转为正则；{@code \*} 为字面星号；
 *       形如 {@code git *} 的「词 + 空格 + 星」也匹配裸词本身）；</li>
 *   <li>否则精确匹配。</li>
 * </ol>
 *
 * <p>文件类规则（路径模式）走 gitignore 语义子集：目录前缀（形如 dir 后跟双星）、
 * 任意层匹配（双星开头后跟文件名）、单层文件名通配（单星）、其余按路径段精确。
 * 命中判定同时检查原路径与符号链接解析后的真实路径（由调用方传入两者）。
 * 匹配失败/畸形模式一律返回 false（fail-closed：不命中即落到后续更严步骤）。
 */
public final class RuleMatcher {

    private RuleMatcher() {}

    /** 通用内容匹配（命令/参数文本）。ruleContent 为 null 表示整工具规则（恒真）。 */
    public static boolean matches(String content, String ruleContent) {
        if (ruleContent == null || ruleContent.isEmpty()) return true;
        if (content == null) return false;
        String value = content.trim();
        String rule = ruleContent.trim();
        if (rule.endsWith(":*")) {                     // 1) 前缀语义（旧语法）：词边界匹配
            String head = rule.substring(0, rule.length() - 2);
            return isSingleCommand(value) && (value.equals(head) || value.startsWith(head + " "));
        }
        if (containsUnescapedStar(rule))               // 2) 通配语义
            return wildcard(value, rule);
        return value.equals(unescapeStars(rule));      // 3) 精确语义（\* 为字面星号）
    }

    /** 精确语义的反转义：\* → *（与通配分支的转义处理一致） */
    private static String unescapeStars(String rule) {
        if (!rule.contains("\\*")) return rule;
        StringBuilder out = new StringBuilder(rule.length());
        for (int i = 0; i < rule.length(); i++) {
            if (rule.charAt(i) == '\\' && i + 1 < rule.length() && rule.charAt(i + 1) == '*') { out.append('*'); i++; continue; }
            out.append(rule.charAt(i));
        }
        return out.toString();
    }

    /**
     * 前缀/「词 + 空格 + 星」形态只在单命令上生效：复合命令（含 &amp;&amp; || ; | 或换行）
     * 不被首段前缀放行——须由 Shell 分析器逐子命令判定（§4.4）。
     */
    private static boolean isSingleCommand(String value) {
        return !(value.contains("&&") || value.contains("||") || value.contains(";")
                || value.contains("|") || value.contains("`") || value.contains("\n") || value.contains("\r"));
    }

    /** 文件类匹配：检查原路径与真实路径，任一命中即真 */
    public static boolean pathMatches(Path target, Path realTarget, String ruleContent) {
        if (ruleContent == null || ruleContent.isEmpty()) return true;
        if (target == null) return false;
        return pathPattern(target, ruleContent) || (realTarget != null && pathPattern(realTarget, ruleContent));
    }

    /** 是否应按文件规则处理（./ ../ 开头或包含路径分隔符的规则内容） */
    public static boolean looksLikePathRule(String ruleContent) {
        if (ruleContent == null) return false;
        String r = ruleContent.trim();
        return r.startsWith("./") || r.startsWith("../") || r.contains("/");
    }

    // ---------------- 通配 ----------------

    private static boolean containsUnescapedStar(String rule) {
        for (int i = 0; i < rule.length(); i++) {
            if (rule.charAt(i) == '*' && (i == 0 || rule.charAt(i - 1) != '\\')) return true;
        }
        return false;
    }

    /** 通配匹配：* 匹配任意字符（含空白）；「词 + 空格 + *」同时匹配裸词（git * 匹配 git）。
     *  「词 + 空格 + 星」特判与前缀同样只对单命令生效（复合命令交给 §4.4 分析器逐段判定）。 */
    private static boolean wildcard(String value, String rule) {
        // 尾部「 *」形态：git * → git(\s.*)?（裸 git 或带参数）
        if (rule.endsWith(" *")) {
            String head = rule.substring(0, rule.length() - 2);
            if (!containsUnescapedStar(head))
                return isSingleCommand(value) && (value.equals(head) || value.startsWith(head + " "));
        }
        Pattern p = compileWildcard(rule);
        return p != null && p.matcher(value).matches();
    }

    private static Pattern compileWildcard(String rule) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '\\' && i + 1 < rule.length() && rule.charAt(i + 1) == '*') { regex.append("\\*"); i++; continue; }
            if (c == '*') { regex.append(".*"); continue; }
            regex.append(Pattern.quote(String.valueOf(c)));
        }
        try { return Pattern.compile(regex.append('$').toString()); }
        catch (RuntimeException malformed) { return null; }
    }

    // ---------------- 文件路径（gitignore 语义子集） ----------------

    private static boolean pathPattern(Path target, String rule) {
        String normalized = rule.trim().replace('\\', '/');
        String abs = target.toAbsolutePath().normalize().toString().replace('\\', '/');
        boolean anyLayer = normalized.startsWith("**/");
        String pattern = anyLayer ? normalized.substring(3) : normalized;

        if (pattern.endsWith("/**")) {                 // 目录前缀：./src/** → src 目录内任意层
            String dir = stripLeading(pattern.substring(0, pattern.length() - 3));
            return !dir.isEmpty() && (abs.endsWith("/" + dir) || abs.contains("/" + dir + "/"));
        }
        String rel = stripLeading(pattern);
        if (anyLayer)                                  // **/foo.md → 任意层下的 foo.md
            return abs.endsWith("/" + rel) || abs.equals(rel);
        if (rel.contains("*")) {                       // 单层文件名通配（仅最后一段）
            String name = target.getFileName() == null ? "" : target.getFileName().toString();
            Pattern p = compileWildcard(rel.substring(rel.lastIndexOf('/') + 1));
            return p != null && p.matcher(name).matches();
        }
        return abs.endsWith("/" + rel) || abs.equals(rel);   // 精确相对路径
    }

    /** 剥掉规则开头的 ./（保持与 gitignore 相对语义一致） */
    private static String stripLeading(String s) {
        return s.startsWith("./") ? s.substring(2) : s;
    }
}
