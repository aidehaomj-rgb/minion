package com.minion.core.security.bash;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bash 命令安全分析器（开发说明 v2.4 §4.4）：拆分 → 校验 → 只读判定 → 前缀建议。
 *
 * <p>结果三态：simple（干净子命令列表）/ too-complex（含展开/替换/控制流等 → 整体 ask）/
 * parse-failed（拆分失败 → 整条命令单元素，同样整体 ask；与参考一致不降级 deny）。
 *
 * <p>只读判定 = 白名单首词 + 逐命令验证器 + 无重定向/替换。fail-closed：未知 flag、
 * 输出文件类参数、含 $ 或花括号展开的 token 一律非只读；验证器与白名单可经
 * {@code bash.readonlyCommands} 扩展但只能收紧（新增命令默认非只读直到有验证器）。
 */
public final class BashCommandAnalyzer {

    /** 分析结论 */
    public static final class Analysis {
        public enum Kind { SIMPLE, TOO_COMPLEX, PARSE_FAILED }
        public final Kind kind;
        public final List<String> subCommands;
        public final List<BashChecks.Finding> findings;
        public final boolean readOnly;
        Analysis(Kind kind, List<String> subCommands, List<BashChecks.Finding> findings, boolean readOnly) {
            this.kind = kind;
            this.subCommands = subCommands;
            this.findings = findings;
            this.readOnly = readOnly;
        }
        public boolean asks() { return kind != Kind.SIMPLE || !findings.isEmpty(); }
    }

    /** 默认只读命令白名单（首词；可经配置扩展——扩展只收紧） */
    private static final Set<String> DEFAULT_READONLY = new HashSet<String>(Arrays.asList(
            "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "find", "pwd", "echo",
            "which", "where", "wc", "file", "stat", "du", "df", "whoami", "hostname", "date",
            "uname", "env", "printenv", "type", "command", "id", "groups", "tty", "true",
            "rg", "sort", "uniq", "cut", "awk", "sed", "diff", "cmp", "md5sum", "sha1sum",
            "sha256sum", "basename", "dirname", "realpath", "readlink", "java", "python"));

    private final Set<String> readonlyCommands;

    public BashCommandAnalyzer() { this(new String[0]); }

    /** extraReadonly 为用户配置追加（仅当对应验证器覆盖时才可能只读；git/find/awk/sed 等自带验证） */
    public BashCommandAnalyzer(String[] extraReadonly) {
        this.readonlyCommands = new HashSet<String>(DEFAULT_READONLY);
        if (extraReadonly != null)
            for (String c : extraReadonly) if (c != null && !c.trim().isEmpty()) readonlyCommands.add(c.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isReadOnlyCommand(String word) {
        return word != null && readonlyCommands.contains(word.trim().toLowerCase(Locale.ROOT));
    }

    /** 分析整条命令（可能含多段子命令） */
    public Analysis analyze(String command) {
        if (command == null || command.trim().isEmpty())
            return new Analysis(Analysis.Kind.PARSE_FAILED,
                    Arrays.asList(command == null ? "" : command), new ArrayList<BashChecks.Finding>(), false);
        BashCommandSplitter.Result split = BashCommandSplitter.split(command);
        List<BashChecks.Finding> all = new ArrayList<BashChecks.Finding>();
        // 无引号 tag 的 heredoc 正文会做参数展开/命令替换：正文中的 $( 与反引号必须触发询问
        for (int h = 0; h < split.heredocBodies.size(); h++) {
            if (h < split.heredocQuoted.size() && Boolean.TRUE.equals(split.heredocQuoted.get(h))) continue;
            String body = split.heredocBodies.get(h);
            if (body.contains("$(") || body.contains("`"))
                all.add(new BashChecks.Finding("heredoc-substitution", "无引号 heredoc 正文含命令替换"));
        }
        boolean anySubstitution = false;
        for (String sub : split.subCommands) {
            List<BashChecks.Finding> findings = BashChecks.runAll(sub);
            // DangerousCommands 黑名单逐段检查（BashTool 层保留整条首词检查；此处覆盖复合命令第二段）
            if (com.minion.core.tools.DangerousCommands.isDangerous(sub))
                findings.add(new BashChecks.Finding("dangerous-command", "黑名单命令段: " + BashChecks.firstWord(sub)));
            String word = BashChecks.firstWord(sub).toLowerCase(Locale.ROOT);
            if (word.equals("find") && !FindValidator.passes(sub))
                findings.add(new BashChecks.Finding("find-mutating", "find 含改变文件系统的动作 flag"));
            if (word.equals("git") && !GitDiffValidator.passes(sub))
                findings.add(new BashChecks.Finding("git-mutating", "git 子命令含输出/写参数"));
            all.addAll(findings);
            for (BashChecks.Finding f : findings)
                if (f.check.startsWith("command-substitution") || f.check.startsWith("process-substitution")
                        || f.check.startsWith("parameter-expansion") || f.check.startsWith("brace-expansion"))
                    anySubstitution = true;
        }
        if (anySubstitution)
            return new Analysis(Analysis.Kind.TOO_COMPLEX, split.subCommands, all, false);
        boolean readOnly = all.isEmpty() && allReadOnly(split.subCommands) && !hasRedirectOrPipeEffect(command);
        return new Analysis(Analysis.Kind.SIMPLE, split.subCommands, all, readOnly);
    }

    /** 全部子命令都通过白名单+验证器才只读 */
    private boolean allReadOnly(List<String> subCommands) {
        for (String sub : subCommands) {
            if (!isReadOnlySub(sub)) return false;
        }
        return true;
    }

    /** 单条子命令只读判定：白名单首词 + 验证器 + 无重定向/替换/展开 token */
    boolean isReadOnlySub(String sub) {
        // 剥离 heredoc 起始符 <<[-]TAG（输入流，不写文件）后再查输出/输入重定向
        String noHeredoc = sub.replaceAll("<<-?['\\\"]?[A-Za-z0-9_-]+", " ");
        if (noHeredoc.contains(">")) return false;
        if (noHeredoc.matches("(?s).*[^<]<[^<].*")) return false;
        if (sub.contains("$") || sub.contains("`")) return false;
        if (sub.contains("{") && sub.contains("}")) return false;
        String word = BashChecks.firstWord(sub).toLowerCase(Locale.ROOT);
        if (!isReadOnlyCommand(word)) return false;
        // 逐命令验证器
        if (word.equals("find") && !FindValidator.passes(sub)) return false;
        if (word.equals("git") && !GitDiffValidator.passes(sub)) return false;
        if (!GenericValidator.passes(sub)) return false;
        return true;
    }

    /** 管道本身允许只读链（ls | grep）；重定向出文件则非只读（> 目标在上层已拦） */
    private boolean hasRedirectOrPipeEffect(String command) {
        String[] lines = command.split("\n", -1);
        for (String line : lines) {
            String t = line.trim();
            if (t.contains(">") || t.contains(">>")) {
                // 2>&1 / >&2 这类 fd 复制不产生文件
                String noFdDup = t.replaceAll("\\d*>&\\d*", "");
                if (noFdDup.contains(">")) return true;
            }
        }
        return false;
    }

    // ==================== 前缀提取（确认 UI 规则建议用，参考 prefix.ts 语义） ====================

    /** 按子命令首词取最长公共前缀建议；wrapper（nice/env/timeout）递归解包 ≤2 层 */
    public String suggestPrefix(String command) {
        if (command == null || command.trim().isEmpty()) return null;
        BashCommandSplitter.Result split = BashCommandSplitter.split(command);
        if (split.subCommands.isEmpty()) return null;
        // 词对齐最长公共前缀（npm run test && npm run lint → npm run；git status → git）
        return commonPrefixOfTokens(split.subCommands);
    }

    private static String unwrapWrapper(String word, int depth) {
        if (depth > 2) return word;
        if (word.equals("nice") || word.equals("env") || word.equals("timeout") || word.equals("nohup") || word.equals("sudo"))
            return word;   // 简化：wrapper 的真实下词需要 token 级解析，保守只返回 wrapper 自身
        return word;
    }

    /** 词对齐最长公共前缀（跨子命令） */
    private static String commonPrefixOfTokens(List<String> subs) {
        List<String[]> tokenLists = new ArrayList<String[]>();
        for (String s : subs) tokenLists.add(s.trim().split("\\s+"));
        if (tokenLists.isEmpty()) return null;
        if (tokenLists.size() == 1) return tokenLists.get(0)[0];   // 单命令只取首词
        int min = Integer.MAX_VALUE;
        for (String[] t : tokenLists) min = Math.min(min, t.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < min && i < 3; i++) {   // 最多 3 列，防超长前缀
            String token = tokenLists.get(0)[i];
            boolean same = true;
            for (String[] t : tokenLists) if (!t[i].equals(token)) { same = false; break; }
            if (!same) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(token);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ==================== 逐命令验证器 ====================

    /** find：封禁改变文件系统的动作 flag（-delete/-exec/-execdir/-ok/-okdir 及 fprint 族） */
    static final class FindValidator {
        private static final String[] BANNED = {"-delete", "-exec", "-execdir", "-ok", "-okdir",
                "-fprint", "-fprint0", "-fls", "-fprintf"};
        static boolean passes(String sub) {
            String lower = sub.toLowerCase(Locale.ROOT);
            for (String flag : BANNED) if (lower.contains(flag)) return false;
            return true;
        }
    }

    /** git 只读子命令白名单：status/log/diff/show/blame/ls-files/rev-parse/describe/shortlog/branch/remote -v/tag list/stash list/help；
     *  git 整词不在 DEFAULT_READONLY——push/reset/commit/clean/checkout 等一律非只读。
     *  diff 段再过 safeFlags；--output=/-o 与任何未知 flag → 非只读 */
    static final class GitDiffValidator {
        private static final Set<String> SAFE_SUB = new HashSet<String>(Arrays.asList(
                "status", "log", "diff", "show", "blame", "ls-files", "rev-parse", "describe",
                "shortlog", "branch", "remote", "tag", "help"));
        private static final Set<String> SAFE_DIFF_FLAGS = new HashSet<String>(Arrays.asList(
                "--stat", "--name-only", "--name-status", "-p", "--patch", "--shortstat",
                "--numstat", "--raw", "--cached", "--staged", "HEAD", "--summary", "--check",
                "--color", "--no-color", "-U0", "-U1", "-U3", "--word-diff", "--ignore-all-space",
                "-w", "--ignore-space-change", "-b", "--exit-code", "--quiet", "--merge-base"));

        static boolean passes(String sub) {
            String[] tokens = sub.trim().split("\\s+");
            if (tokens.length == 0 || !tokens[0].equalsIgnoreCase("git")) return true;   // 非 git 命令不适用
            if (tokens.length == 1) return true;                                        // 裸 git
            String subCmd = tokens[1];
            if (!SAFE_SUB.contains(subCmd)) return false;                               // push/reset/commit... 非只读
            if (subCmd.equals("remote") && tokens.length >= 3 && !tokens[2].equals("-v")) return false;
            if (subCmd.equals("branch") && tokens.length >= 3 && (tokens[2].equals("-d") || tokens[2].equals("-D") || tokens[2].equals("-m"))) return false;
            if (subCmd.equals("tag") && tokens.length >= 3 && (tokens[2].equals("-d") || tokens[2].equals("-f"))) return false;
            if (subCmd.equals("stash") && tokens.length >= 3 && !tokens[2].equals("list")) return false;
            // diff 段 safeFlags 校验
            boolean isDiff = false;
            for (String t : tokens) {
                if (t.equals("diff")) { isDiff = true; continue; }
                if (!isDiff) continue;
                if (t.startsWith("--output=") || t.equals("-o") || t.equals("--output")) return false;
                if (t.startsWith("-") && !SAFE_DIFF_FLAGS.contains(t) && !t.matches("[0-9a-fA-F]{7,40}")
                        && !t.startsWith("--diff-filter=") && !t.startsWith("--src-prefix")
                        && !t.startsWith("--dst-prefix")) return false;
            }
            return true;
        }
    }

    /** 通用规则：任何未知 flag（--xxx/-x）与输出文件类参数 → 非只读（fail-closed） */
    static final class GenericValidator {
        static boolean passes(String sub) {
            String[] tokens = sub.trim().split("\\s+");
            for (String t : tokens) {
                if (t.startsWith("--output") || t.equals("-o") || t.equals("-O")
                        || t.startsWith("--result") || t.startsWith("--out"))
                    return false;
            }
            return true;
        }
    }
}
