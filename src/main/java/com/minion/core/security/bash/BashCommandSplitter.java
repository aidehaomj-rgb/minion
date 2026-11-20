package com.minion.core.security.bash;

import java.util.ArrayList;
import java.util.List;

/**
 * Bash 命令拆分器（开发说明 v2.4 §4.4，参考 utils/bash/commands.ts 的语义子集，独立实现）。
 *
 * <p>拆分顺序：提取 heredoc 内容保护 → 奇数反斜杠续行合并（不加空格）→
 * 引号保护后按 {@code && || ; |} 与换行切分为子命令 → 还原占位符。
 * <b>任何解析失败返回整条命令的单元素列表</b>（fail-closed：整体交给上层询问，绝不部分放行）。
 *
 * <p>不追求完整 shell 语法——目标是把复合命令切成可判定的子命令段；切不动就整体保守。
 */
public final class BashCommandSplitter {

    private BashCommandSplitter() {}

    /** 不可变拆分结果：subCommands 为切出的子命令文本（含重定向目标）；heredocBodies 为受保护正文段 */
    public static final class Result {
        public final List<String> subCommands;
        public final List<String> heredocBodies;
        /** 与 heredocBodies 一一对应：tag 是否带引号（带引号=正文字面，不展开 $( 与反引号） */
        public final List<Boolean> heredocQuoted;
        public final boolean parseFailed;
        Result(List<String> subCommands, List<String> heredocBodies, List<Boolean> heredocQuoted, boolean parseFailed) {
            this.subCommands = subCommands;
            this.heredocBodies = heredocBodies;
            this.heredocQuoted = heredocQuoted;
            this.parseFailed = parseFailed;
        }
    }

    public static Result split(String command) {
        if (command == null || command.trim().isEmpty())
            return new Result(new ArrayList<String>(), new ArrayList<String>(), new ArrayList<Boolean>(), false);
        String text = command.trim();
        // 1) heredoc 提取（<<EOF ... EOF / <<-EOF）：正文整体替换为占位符并单独记录
        List<String> heredocs = new ArrayList<String>();
        List<Boolean> heredocQuoted = new ArrayList<Boolean>();
        text = extractHeredocs(text, heredocs, heredocQuoted);
        // 2) 续行合并：行尾奇数反斜杠与下一行直接拼接（不加空格）
        text = joinContinuations(text);
        // 3) 引号/反引号保护为占位符后按操作符切分
        StringBuilder protectedText = new StringBuilder();
        List<String> tokens = new ArrayList<String>();
        protectQuotes(text, protectedText, tokens);
        List<String> parts = splitOperators(protectedText.toString());
        // 4) 还原占位符
        List<String> subCommands = new ArrayList<String>();
        for (String part : parts) {
            String restored = restore(part, tokens).trim();
            if (!restored.isEmpty()) subCommands.add(restored);
        }
        if (subCommands.isEmpty()) subCommands.add(text);
        return new Result(subCommands, heredocs, heredocQuoted, false);
    }

    /** heredoc：<<[-]"TAG" 换行…换行TAG；正文（含转义/引号原样）替换为占位符；未闭合按失败=原文返回 */
    static String extractHeredocs(String text, List<String> out, List<Boolean> quoted) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int lt = text.indexOf("<<", i);
            if (lt < 0) { sb.append(text.substring(i)); break; }
            // 排除 <<< 与 <<-（后者合法，剥离 -）
            int after = lt + 2;
            boolean dash = false;
            if (after < text.length() && text.charAt(after) == '-') { dash = true; after++; }
            if (after < text.length() && text.charAt(after) == '<') {  // <<<：非 heredoc，原样
                sb.append(text, i, after); i = after; continue;
            }
            // 读 tag（可带引号）
            int j = after;
            while (j < text.length() && !Character.isWhitespace(text.charAt(j)) && text.charAt(j) != '\n') j++;
            String rawTag = text.substring(after, j);
            if (rawTag.isEmpty()) { sb.append(text, i, j); i = j; continue; }
            String tag = rawTag.replace("\"", "").replace("'", "");
            quoted.add(rawTag.contains("'") || rawTag.contains("\""));
            // 找行尾（tag 之后到换行）
            int lineEnd = text.indexOf('\n', j);
            if (lineEnd < 0) { sb.append(text.substring(i)); break; }   // 无正文行：原样
            int bodyEnd = text.indexOf("\n" + tag, lineEnd);
            if (bodyEnd < 0) return text;                                // 未闭合：整体返回（上层保守）
            String body = text.substring(lineEnd + 1, bodyEnd);
            if (dash) body = stripLeadingTabs(body);
            out.add(body);
            sb.append(text, i, after).append(rawTag).append(text, lineEnd, lineEnd + 1).append(text, bodyEnd, bodyEnd + 1 + tag.length());
            i = bodyEnd + 1 + tag.length();
        }
        return sb.toString();
    }

    private static String stripLeadingTabs(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            int k = 0;
            while (k < line.length() && line.charAt(k) == '\t') k++;
            sb.append(line.substring(k)).append('\n');
        }
        return sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "";
    }

    /** 行尾奇数反斜杠才是续行（最后一根转义换行）：删除该「反斜杠+换行」无缝拼接；
     *  偶数根（如成对 {@code \\\\}）是字面反斜杠结尾，保留换行。 */
    static String joinContinuations(String text) {
        if (!text.contains("\\\n")) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                // 数这根反斜杠前面的连续反斜杠数：偶数 → 本根是转义符（续行），删除；奇数 → 字面
                int run = 1;
                while (i - run >= 0 && text.charAt(i - run) == '\\') run++;
                if (run % 2 == 1) { i += 2; continue; }   // 前面偶数根字面 + 本根转义 = 续行
            }
            sb.append(text.charAt(i));
            i++;
        }
        return sb.toString();
    }

    private static int countTrailingBackslashes(String line) {
        int n = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) n++;
        return n;
    }

    /** 引号/反引号/$( ) 内容替换为不可冲突占位符；占位符原文按序存入 tokens */
    static void protectQuotes(String text, StringBuilder out, List<String> tokens) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = text.indexOf(c, i + 1);
                if (end < 0) { out.append(text.substring(i)); return; }   // 未闭合：剩余原样（保守）
                tokens.add(text.substring(i, end + 1));
                out.append('\u0001').append(tokens.size() - 1).append('\u0001');
                i = end + 1;
                continue;
            }
            if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '(') {
                int end = findMatching(text, i + 1, '(', ')');
                if (end < 0) { out.append(text.substring(i)); return; }
                tokens.add(text.substring(i, end + 1));
                out.append('\u0001').append(tokens.size() - 1).append('\u0001');
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
    }

    private static int findMatching(String text, int open, char openCh, char closeCh) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == openCh) depth++;
            else if (c == closeCh) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** 按 && || ; | 与换行切分（保护后的文本；管道切分保留各段以供判定） */
    static List<String> splitOperators(String protectedText) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < protectedText.length(); i++) {
            char c = protectedText.charAt(i);
            if (c == '&' && i + 1 < protectedText.length() && protectedText.charAt(i + 1) == '&') {
                parts.add(current.toString()); current.setLength(0); i++; continue;
            }
            if (c == '|' && i + 1 < protectedText.length() && protectedText.charAt(i + 1) == '|') {
                parts.add(current.toString()); current.setLength(0); i++; continue;
            }
            if (c == ';' || c == '|' || c == '&' || c == '\n') {   // 单 & 后台执行同样切分（&& 已在前面消费）
                parts.add(current.toString()); current.setLength(0); continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static String restore(String part, List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < part.length()) {
            if (part.charAt(i) == '\u0001') {
                int end = part.indexOf('\u0001', i + 1);
                if (end > i) {
                    int idx = Integer.parseInt(part.substring(i + 1, end));
                    sb.append(idx < tokens.size() ? tokens.get(idx) : "");
                    i = end + 1;
                    continue;
                }
            }
            sb.append(part.charAt(i));
            i++;
        }
        return sb.toString();
    }
}
