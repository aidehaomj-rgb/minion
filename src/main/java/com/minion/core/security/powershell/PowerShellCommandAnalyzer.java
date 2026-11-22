package com.minion.core.security.powershell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PowerShell 命令分析器（v2.4 §4.4 加厚版，切片 41）。
 *
 * <p>分层设计：<b>危险层</b>（高危确认）→ <b>混淆层</b>（编码/别名/拼接绕过检测）
 * → <b>写入层</b>（文件/注册表/进程副作用）→ <b>只读白名单</b>（安全放行）。
 * 未命中任何层的命令默认 ask（fail-closed，不误放）。
 */
public final class PowerShellCommandAnalyzer {

    public static final class Analysis {
        public final boolean readOnly;
        public final boolean dangerous;       // 命中危险层（高危确认，不可白名单放行）
        public final List<String> reasons;

        Analysis(boolean readOnly, boolean dangerous, List<String> reasons) {
            this.readOnly = readOnly;
            this.dangerous = dangerous;
            this.reasons = reasons;
        }
    }

    // ==================== 危险层 ====================

    private static final String[] DANGEROUS = {
        // 代码执行
        "invoke-expression", "\\biex\\b", "invoke-command", "\\bicm\\b",
        "-encodedcommand", "\\b-enc\\b",
        "frombase64string", "frombase32string",
        "add-type", "rundll32", "regsvr32", "mshta", "certutil.*-(urlcache|decode)",
        "[system.", "[reflection.", "[microsoft.powershell.",
        "system.net.webclient", "downloadfile", "downloaddata",
        "invoke-webrequest.*-outfile", "invoke-restmethod.*-outfile",
        // 进程/作业
        "start-process", "\\bsaps\\b", "start-job",
        "-windowstyle\\s+hidden", "-noprofile", "-noninteractive",
        // 系统修改
        "set-executionpolicy", "new-service", "set-service",
        "remove-item", "\\bri\\b", "\\bdel\\b", "\\brm\\b", "\\berase\\b",
        "format-volume", "initialize-volume",
        // 杀进程/停服务
        "stop-process", "\\bkill\\b", "stop-service",
        // 注册表写入
        "new-itemproperty", "remove-itemproperty",
        // 特权
        "-verb\\s+runas",
    };

    // ==================== 混淆/写入层 ====================

    private static final String[] OBFUSCATION_WRITE = {
        "-encodedcommand", "\\b-enc\\b", "-e\\s+-", "-w\\s+hidden", "-noprofile",
        "frombase64string", "frombase32string",
        "`[a-z]", "\\[char\\]", "\\[convert\\]",
        "\\$\\(", "\\$\\{",         // 子表达式
        "\\b&\\s",                   // 调用操作符
        "set-content", "\\bsc\\b", "add-content", "\\bac\\b",
        "out-file", ">\\s", ">>", "1>", "2>",
        "copy-item", "\\bcp\\b", "copy-item", "\\bcopy\\b", "\\bxcopy\\b", "\\brobocopy\\b",
        "move-item", "\\bmv\\b", "\\bmi\\b", "\\bmove\\b",
        "new-item", "\\bni\\b", "mkdir", "\\bmd\\b",
        "set-variable", "\\bsv\\b", "new-variable",
        "set-itemproperty", "\\bsp\\b",
        "tee-object", "\\btee\\b",
        "invoke-webrequest", "\\biwr\\b", "invoke-restmethod", "\\birm\\b",
        "curl", "wget",
    };

    // ==================== 只读白名单 ====================

    private static final Set<String> READONLY = new HashSet<String>(Arrays.asList(
        "get-childitem", "get-item", "get-content", "get-itemproperty", "get-process",
        "get-service", "get-command", "get-help", "get-member", "get-history",
        "get-location", "get-variable", "get-date", "get-random", "get-unique",
        "get-acl", "get-job", "get-eventlog",
        "measure-command", "measure-object",
        "select-string", "select-object", "sort-object", "group-object", "where-object",
        "compare-object", "test-path", "test-connection",
        "format-list", "format-table", "format-wide", "format-custom",
        "out-string", "out-default", "out-host", "out-null",
        "write-host", "write-output", "write-verbose", "write-debug", "write-warning",
        "read-host", "clear-host",
        // 别名
        "dir", "ls", "cat", "type", "echo", "pwd", "gl", "gps", "gsv", "gc",
        "sls", "select", "sort", "where", "foreach", "ft", "fl", "fw", "oh",
        "measure", "compare", "diff"
    ));

    private static final Set<String> READONLY_EXECUTABLES = new HashSet<String>(Arrays.asList(
        "whoami", "hostname", "ipconfig", "ping", "tracert", "nslookup", "netstat",
        "tasklist", "systeminfo", "ver", "vol", "time", "date", "chcp",
        "find", "findstr", "sort", "more", "tree", "title", "color", "prompt",
        "java", "python", "node", "git", "mvn", "npm"
    ));

    public Analysis analyze(String command) {
        if (command == null || command.trim().isEmpty())
            return new Analysis(true, false, new ArrayList<String>());

        String lower = command.toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<String>();
        boolean dangerous = false;

        for (String p : DANGEROUS) {
            if (matches(lower, p)) {
                dangerous = true;
                reasons.add(describe(p));
            }
        }

        boolean writeOrObfuscation = false;
        for (String p : OBFUSCATION_WRITE) {
            if (matches(lower, p)) {
                writeOrObfuscation = true;
                reasons.add(describe(p));
            }
        }

        boolean readOnly = false;
        if (!dangerous && !writeOrObfuscation) {
            // 管道/分号分段：每段首个非参数词为命令；全部命令在白名单即 readonly
            String[] segments = lower.split("[|;]");
            boolean allWhitelisted = segments.length > 0;
            for (String seg : segments) {
                String[] tokens = seg.trim().split("\\s+");
                String firstCommand = null;
                for (String t : tokens) {
                    if (t.isEmpty() || t.startsWith("-") || t.startsWith("$") || t.startsWith("/")
                            || t.startsWith("{") || t.startsWith("}") || t.startsWith("(")
                            || t.startsWith("'") || t.startsWith("\""))
                        continue;
                    firstCommand = t;
                    break;
                }
                if (firstCommand == null) continue;   // 空段（如 ;;）
                String name = firstCommand.contains("\\")
                        ? firstCommand.substring(firstCommand.lastIndexOf('\\') + 1) : firstCommand;
                name = name.replace(".exe", "").replace(".ps1", "");
                if (!READONLY.contains(name) && !READONLY_EXECUTABLES.contains(name)) {
                    allWhitelisted = false;
                    break;
                }
            }
            readOnly = allWhitelisted;
        }

        if (reasons.isEmpty() && !readOnly)
            reasons.add("未匹配只读白名单（fail-closed 默认询问）");

        return new Analysis(readOnly, dangerous, reasons);
    }

    private static boolean matches(String text, String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text).find();
        } catch (Exception e) {
            return text.contains(pattern);
        }
    }

    private static String describe(String pattern) {
        if (pattern.contains("encodedcommand") || pattern.contains("-enc"))
            return "Base64 编码执行（混淆）";
        if (pattern.contains("invoke-expression") || pattern.contains("\\biex\\b"))
            return "Invoke-Expression（动态代码执行）";
        if (pattern.contains("invoke-command") || pattern.contains("\\bicm\\b"))
            return "Invoke-Command（远程执行）";
        if (pattern.contains("frombase64"))
            return "Base64 解码";
        if (pattern.contains("add-type"))
            return "Add-Type（编译 C#）";
        if (pattern.contains("webclient") || pattern.contains("downloadfile"))
            return "网络下载";
        if (pattern.contains("start-process") || pattern.contains("start-job") || pattern.contains("\\bsaps\\b"))
            return "启动进程/作业";
        if (pattern.contains("remove-item") || pattern.contains("\\bdel\\b") || pattern.contains("\\bri\\b")
                || pattern.contains("\\brm\\b") || pattern.contains("\\berase\\b"))
            return "删除类操作";
        if (pattern.contains("set-executionpolicy"))
            return "修改执行策略";
        if (pattern.contains("stop-process") || pattern.contains("\\bkill\\b") || pattern.contains("stop-service"))
            return "停止进程/服务";
        if (pattern.contains("itemproperty"))
            return "注册表操作";
        if (pattern.contains("format-volume") || pattern.contains("initialize-volume"))
            return "磁盘格式化";
        if (pattern.contains("hidden") || pattern.contains("noprofile"))
            return "隐藏窗口/无配置";
        if (pattern.contains("new-service") || pattern.contains("set-service"))
            return "服务管理";
        if (pattern.contains("set-content") || pattern.contains("out-file") || pattern.contains("add-content"))
            return "文件写入";
        if (pattern.contains("copy-item") || pattern.contains("\\bcp\\b") || pattern.contains("\\bmove\\b")
                || pattern.contains("move-item") || pattern.contains("\\bmv\\b") || pattern.contains("\\bxcopy\\b"))
            return "文件复制/移动";
        if (pattern.contains("new-item") || pattern.contains("mkdir") || pattern.contains("\\bmd\\b"))
            return "创建文件/目录";
        if (pattern.contains("invoke-webrequest") || pattern.contains("\\biwr\\b")
                || pattern.contains("curl") || pattern.contains("wget"))
            return "网络请求";
        if (pattern.contains("$(") || pattern.contains("${") || pattern.contains("&\\s"))
            return "子表达式/调用操作符";
        if (pattern.contains("char") || pattern.contains("[convert"))
            return "类型转换/字符编码";
        if (pattern.contains(">") || pattern.contains(">>"))
            return "输出重定向";
        return pattern;
    }
}
