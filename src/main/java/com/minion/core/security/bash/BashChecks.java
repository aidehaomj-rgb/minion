package com.minion.core.security.bash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bash 命令静态校验器清单（开发说明 v2.4 §4.4，语义对齐参考 bashSecurity.ts，独立实现）。
 *
 * <p>每条校验器返回 pass 或 ask（带理由）；结果三态由 {@link BashCommandAnalyzer} 汇总：
 * simple（全部通过）/ too-complex（含展开/替换/控制流 → 整体 ask）/ parse-failed。
 *
 * <p>关键执行序：misparsing 类（CR/引号去同步/控制字符）最后跑完——前面校验器命中 ask 时
 * 延迟返回，先跑完 misparsing 防止短路绕过。
 */
final class BashChecks {

    private BashChecks() {}

    static final class Finding {
        final String check;
        final String reason;
        Finding(String check, String reason) { this.check = check; this.reason = reason; }
    }

    /** 对单条子命令跑全部校验；返回命中列表（空=通过） */
    static List<Finding> runAll(String cmd) {
        List<Finding> findings = new ArrayList<Finding>();
        checkUnclosedQuotes(cmd, findings);
        checkEmpty(cmd, findings);
        checkIncomplete(cmd, findings);
        checkCommandSubstitution(cmd, findings);
        checkProcessSubstitution(cmd, findings);
        checkParameterExpansion(cmd, findings);
        checkDangerousVariables(cmd, findings);
        checkUnquotedMetacharacters(cmd, findings);
        checkCommentQuoteDesync(cmd, findings);
        checkQuotedNewline(cmd, findings);
        checkCarriageReturn(cmd, findings);
        checkLineFeedInjection(cmd, findings);
        checkProcEnviron(cmd, findings);
        checkOutputRedirection(cmd, findings);
        checkSensitiveRedirection(cmd, findings);
        checkEscapedWhitespace(cmd, findings);
        checkUnicodeWhitespace(cmd, findings);
        checkBraceExpansion(cmd, findings);
        checkControlChars(cmd, findings);
        checkInterpreter(cmd, findings);
        checkQuoteSplice(cmd, findings);
        checkXargs(cmd, findings);
        checkDangerousBuiltins(cmd, findings);
        return findings;
    }

    static void checkUnclosedQuotes(String cmd, List<Finding> out) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
        }
        if (inSingle || inDouble) out.add(new Finding("unclosed-quote", "引号未闭合"));
    }

    static void checkEmpty(String cmd, List<Finding> out) {
        if (cmd.trim().isEmpty()) out.add(new Finding("empty", "空命令段"));
    }

    static void checkIncomplete(String cmd, List<Finding> out) {
        String t = cmd.trim();
        if (t.endsWith("&&") || t.endsWith("||") || t.endsWith("|") || t.endsWith(";")
                || t.startsWith("&&") || t.startsWith("||") || t.startsWith("|") || t.startsWith(";"))
            out.add(new Finding("incomplete", "悬空操作符"));
    }

    static void checkCommandSubstitution(String cmd, List<Finding> out) {
        if (cmd.contains("$(") || cmd.contains("`"))
            out.add(new Finding("command-substitution", "命令替换 $() 或反引号"));
    }

    static void checkProcessSubstitution(String cmd, List<Finding> out) {
        if (cmd.contains("<(") || cmd.contains(">("))
            out.add(new Finding("process-substitution", "进程替换 <() >()"));
    }

    static void checkParameterExpansion(String cmd, List<Finding> out) {
        if (cmd.contains("${") || cmd.contains("$["))
            out.add(new Finding("parameter-expansion", "参数/算术展开 ${} $[]"));
    }

    static void checkDangerousVariables(String cmd, List<Finding> out) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (lower.contains("ifs=") || lower.contains("ld_preload=") || lower.matches("(?s).*path=[^ ]*.*"))
            if (lower.contains("ifs=") || lower.contains("ld_preload=") || lower.matches("(?s).*(^|[ ;&|])(export +)?path=.*"))
                out.add(new Finding("dangerous-variable", "IFS/LD_PRELOAD/PATH 赋值"));
    }

    static void checkUnquotedMetacharacters(String cmd, List<Finding> out) {
        // 引号外裸 shell 元字符序列（括号/花括号/方括号/星/问号/反斜杠等）视为注入面；
        // $ 与 ~ 是常见安全形态（变量/home 展开），由 $() / ${} 专项检查覆盖
        if (hasUnquoted(cmd, "(){}[]*?\\"))
            out.add(new Finding("metacharacters", "引号外 shell 元字符"));
    }

    static void checkCommentQuoteDesync(String cmd, List<Finding> out) {
        // # 出现在词中间（非行首/非空白后）：可能吞掉后续引号造成解析漂移
        for (int i = 1; i < cmd.length(); i++) {
            if (cmd.charAt(i) == '#' && !Character.isWhitespace(cmd.charAt(i - 1)) && !isInsideQuotes(cmd, i)) {
                out.add(new Finding("comment-desync", "# 出现在词中间"));
                return;
            }
        }
    }

    static void checkQuotedNewline(String cmd, List<Finding> out) {
        if (isInsideQuotes(cmd, cmd.length() - 1) && cmd.contains("\n"))
            out.add(new Finding("quoted-newline", "引号内换行"));
    }

    static void checkCarriageReturn(String cmd, List<Finding> out) {
        if (cmd.contains("\r"))
            out.add(new Finding("cr", "CR 回车（misparsing 风险）"));
    }

    static void checkLineFeedInjection(String cmd, List<Finding> out) {
        if (cmd.contains("\n"))
            out.add(new Finding("lf", "LF 注入（多行命令段）"));
    }

    static void checkProcEnviron(String cmd, List<Finding> out) {
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (lower.contains("/proc/self/environ") || lower.contains("/proc/environ"))
            out.add(new Finding("proc-environ", "读取 /proc 环境变量"));
    }

    static void checkOutputRedirection(String cmd, List<Finding> out) {
        // 输出重定向到非 /dev/null 的目标 = 写文件副作用 → 询问（fd 复制 2>&1 不算）
        String[] targets = extractRedirectTargets(cmd);
        for (String target : targets) {
            if (!target.equals("/dev/null") && !target.equals("nul"))
                { out.add(new Finding("redirect-output", "输出重定向写文件: " + target)); return; }
        }
    }

    static void checkSensitiveRedirection(String cmd, List<Finding> out) {
        // > 或 >> 目标命中敏感路径（.git、.ssh、shell rc、authorized_keys、/etc）
        String lower = cmd.toLowerCase(Locale.ROOT);
        String[] targets = extractRedirectTargets(cmd);
        for (String t : targets) {
            String lt = t.toLowerCase(Locale.ROOT);
            if (lt.contains(".git/") || lt.endsWith(".git") || lt.contains(".ssh")
                    || lt.contains("authorized_keys") || lt.contains("id_rsa")
                    || lt.contains(".bashrc") || lt.contains(".profile") || lt.contains(".zshrc")
                    || lt.startsWith("/etc/") || lt.contains("\\etc\\")
                    || lt.contains(".minion/settings"))
                out.add(new Finding("sensitive-redirect", "重定向到敏感路径: " + t));
        }
        if (lower.contains("> /dev/sd") || lower.contains(">\\\\.\\physicaldrive"))
            out.add(new Finding("sensitive-redirect", "重定向到块设备"));
    }

    static void checkEscapedWhitespace(String cmd, List<Finding> out) {
        // 反斜杠转义空白或操作符（rm \-rf、echo a\;b）：绕过简单 token 分析
        if (cmd.matches("(?s).*\\\\[ ;&|<>().*?\\[\\]{}$`'\"].*"))
            out.add(new Finding("escaped-whitespace", "反斜杠转义空白/操作符"));
    }

    static void checkUnicodeWhitespace(String cmd, List<Finding> out) {
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            // Unicode 空白族：NBSP、EN/EM SPACE 族（U+2000-U+200B）、NNBSP、表意空格、BOM/零宽
            if (c == ' ' || (c >= ' ' && c <= '​') || c == ' '
                    || c == '　' || c == '﻿') {
                out.add(new Finding("unicode-whitespace", "Unicode 空白 U+" + Integer.toHexString(c)));
                return;
            }
        }
    }

    static void checkBraceExpansion(String cmd, List<Finding> out) {
        if (cmd.matches("(?s).*\\{[a-zA-Z0-9,\\.\\-]+,(?:[^{}]*,)*[^{}]*\\}.*"))
            out.add(new Finding("brace-expansion", "花括号展开 {a,b}"));
    }

    static void checkControlChars(String cmd, List<Finding> out) {
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                out.add(new Finding("control-chars", "控制字符 U+" + Integer.toHexString(c)));
                return;
            }
        }
    }

    static void checkInterpreter(String cmd, List<Finding> out) {
        String word = firstWord(cmd).toLowerCase(Locale.ROOT);
        if (word.equals("sh") || word.equals("bash") || word.equals("dash") || word.equals("zsh")
                || word.equals("ash") || word.equals("cmd") || word.equals("cmd.exe") || word.equals("powershell")
                || word.equals("powershell.exe") || word.endsWith("/sh") || word.endsWith("/bash"))
            out.add(new Finding("interpreter", "子解释器执行: " + word));
        if (word.equals("sudo") || word.equals("su") || word.equals("doas"))
            out.add(new Finding("privilege", "提权执行: " + word));
        if (word.equals("sudo")) {   // sudo tee/cp/mv 目标文件类：去 sudo 后再判
            String rest = cmd.trim().substring(word.length()).trim();
            String second = firstWord(rest).toLowerCase(Locale.ROOT);
            if (second.equals("tee") || second.equals("cp") || second.equals("mv") || second.equals("dd") || second.equals("truncate"))
                out.add(new Finding("file-write", "sudo 下文件写入: " + second));
        }
    }

    static void checkQuoteSplice(String cmd, List<Finding> out) {
        // 词中间的成对相邻引号（e""cho / r''m）：拼接后等效命令名，绕过首词判定
        for (String token : cmd.trim().split("[ \\t]+")) {
            if (token.isEmpty()) continue;
            if (token.length() > 2) {
                String body = token.substring(1, token.length() - 1);
                if (body.contains("\"\"") || body.contains("''"))
                    { out.add(new Finding("quote-splice", "词内引号拼接: " + token)); return; }
                if (body.contains("\\") && body.matches("[A-Za-z0-9\\\\]+"))
                    { out.add(new Finding("backslash-splice", "词内反斜杠拼接: " + token)); return; }
            }
            if (token.startsWith("$'"))
                { out.add(new Finding("ansi-c-quote", "$'...' ANSI-C 引号: " + token)); return; }
            if (token.contains("!") && !token.equals("!="))
                { out.add(new Finding("history-expansion", "历史展开 !: " + token)); return; }
        }
    }

    static void checkXargs(String cmd, List<Finding> out) {
        String word = firstWord(cmd).toLowerCase(Locale.ROOT);
        if (word.equals("xargs"))
            out.add(new Finding("xargs", "xargs 构造执行（参数即命令面）"));
    }

    static void checkDangerousBuiltins(String cmd, List<Finding> out) {
        String lower = firstWord(cmd).toLowerCase(Locale.ROOT);
        if (lower.equals("eval") || lower.equals("exec") || lower.equals("source") || lower.equals(".")
                || lower.equals("history"))
            out.add(new Finding("dangerous-builtin", "eval/exec/source/history"));
        if (lower.equals("zmodload") || lower.equals("emulate") || lower.equals("syswrite")
                || lower.equals("ztcp") || lower.equals("zpty"))
            out.add(new Finding("dangerous-builtin", "zsh 危险命令"));
    }

    // ==================== 辅助 ====================

    static String firstWord(String cmd) {
        String t = cmd.trim();
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i))) i++;
        return t.substring(0, i);
    }

    /** 位置 i 是否在引号内（单/双引号配对粗判，够静态分析用） */
    static boolean isInsideQuotes(String cmd, int pos) {
        if (pos < 0) return false;
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i <= pos && i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
        }
        return inSingle || inDouble;
    }

    private static boolean hasUnquoted(String cmd, String metachars) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (!inSingle && !inDouble && metachars.indexOf(c) >= 0) {
                // 允许常见的安全形态：路径分隔、单点、命令分隔已在 split 阶段处理；
                // 管道 | 与重定向 <> 由 split/redirect 捕获，此处不重复报
                if (c == '|' || c == '<' || c == '>' || c == ';') continue;
                return true;
            }
        }
        return false;
    }

    /** 提取 > 与 >> 的目标 token（简化：操作符后第一个非空 token） */
    static String[] extractRedirectTargets(String cmd) {
        List<String> out = new ArrayList<String>();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '\'' && !inDouble) { inSingle = !inSingle; continue; }
            if (c == '"' && !inSingle) { inDouble = !inDouble; continue; }
            if (!inSingle && !inDouble && c == '>') {
                int j = i + 1;
                if (j < cmd.length() && cmd.charAt(j) == '>') j++;
                while (j < cmd.length() && Character.isWhitespace(cmd.charAt(j))) j++;
                int start = j;
                while (j < cmd.length() && !Character.isWhitespace(cmd.charAt(j))
                        && cmd.charAt(j) != ';' && cmd.charAt(j) != '|' && cmd.charAt(j) != '&') j++;
                if (j > start) out.add(cmd.substring(start, j));
                i = j - 1;
            }
        }
        return out.toArray(new String[0]);
    }
}
