package com.minion.core.security.permission;

/**
 * 权限规则值对象与解析（开发说明 v2.4 §4.1）。
 *
 * <p>规则字符串格式 {@code Tool} 或 {@code Tool(content)}：
 * <ul>
 *   <li>首个<b>未转义</b> {@code (} 与末尾<b>未转义</b> {@code )} 之间为规则内容；</li>
 *   <li>内容支持转义 {@code \(} {@code \)} {@code \\}；</li>
 *   <li>{@code Bash()} 与 {@code Bash(*)} 等价于整工具规则（内容为 null）；</li>
 *   <li>畸形串（如未闭合括号）整体当作工具名——内容丢失但规则保留，行为偏保守。</li>
 * </ul>
 *
 * <p>规则值不可变；匹配语义见 {@link RuleMatcher}。
 */
public final class PermissionRule {

    public enum Behavior { ALLOW, ASK, DENY }

    /** 规则来源层级：企业策略 &gt; 用户 &gt; 项目 &gt; 本地 &gt; 会话内存态 */
    public enum Source { POLICY, USER, PROJECT, LOCAL, SESSION }

    public final Behavior behavior;
    public final String toolName;
    /** 规则内容（可空 = 整工具规则）；已做反转义 */
    public final String content;
    public final Source source;

    public PermissionRule(Behavior behavior, String toolName, String content, Source source) {
        this.behavior = behavior;
        this.toolName = toolName == null ? "" : toolName;
        this.content = content == null || content.isEmpty() ? null : content;
        this.source = source;
    }

    /** 是否整工具规则（无内容限定） */
    public boolean wholeTool() { return content == null; }

    /**
     * 解析规则字符串。找不到合法的未转义括号对时，整串（剥尾部空白）当作工具名。
     */
    public static PermissionRule parse(String spec, Behavior behavior, Source source) {
        if (spec == null || spec.trim().isEmpty())
            return new PermissionRule(behavior, "", null, source);
        String text = spec.trim();
        int open = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '(' && !precededByOddBackslashes(text, i)) { open = i; break; }
        }
        if (open <= 0) return new PermissionRule(behavior, text, null, source);   // 无括号：整工具
        int close = -1;
        for (int i = text.length() - 1; i > open; i--) {
            if (text.charAt(i) == ')' && !precededByOddBackslashes(text, i)) { close = i; break; }
        }
        if (close != text.length() - 1 || close <= open)
            return new PermissionRule(behavior, text, null, source);              // 未闭合/非末尾：畸形当工具名
        String tool = text.substring(0, open).trim();
        String rawContent = text.substring(open + 1, close);
        String content = unescape(rawContent).trim();
        if (content.isEmpty() || "*".equals(content)) content = null;             // Bash() / Bash(*) = 整工具
        return new PermissionRule(behavior, tool, content, source);
    }

    /** 位置 i 前的反斜杠是否为奇数个（即该字符被转义） */
    private static boolean precededByOddBackslashes(String text, int i) {
        int count = 0;
        for (int j = i - 1; j >= 0 && text.charAt(j) == '\\'; j--) count++;
        return count % 2 == 1;
    }

    /** 反转义：\( → (，\) → )，\\ → \ */
    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '(' || next == ')' || next == '\\') { out.append(next); i++; continue; }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 规则的显示串（转义敏感字符，可回解析） */
    @Override public String toString() {
        if (content == null) return toolName;
        return toolName + "(" + content.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")";
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PermissionRule)) return false;
        PermissionRule that = (PermissionRule) o;
        return behavior == that.behavior && toolName.equals(that.toolName)
                && java.util.Objects.equals(content, that.content);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(behavior, toolName, content);
    }
}
