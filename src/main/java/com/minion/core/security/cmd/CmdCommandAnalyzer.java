package com.minion.core.security.cmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CMD 命令分析器（v2.4 §4.4 加厚版，切片 41）。
 *
 * <p>分层设计同 PowerShell：危险层 → 混淆/写入层 → 只读白名单。
 * CMD 特有检测：环境变量展开（%VAR%）、脱字符转义（^）、管道/重定向、
 * 子解释器嵌套（cmd /c、powershell -enc）、批处理脚本调用。
 */
public final class CmdCommandAnalyzer {

    public static final class Analysis {
        public final boolean readOnly;
        public final boolean dangerous;
        public final List<String> reasons;

        Analysis(boolean readOnly, boolean dangerous, List<String> reasons) {
            this.readOnly = readOnly;
            this.dangerous = dangerous;
            this.reasons = reasons;
        }
    }

    // ==================== 危险层 ====================

    private static final String[] DANGEROUS = {
        // 子解释器（嵌套执行）
        "powershell", "pwsh", "cmd\\s+/c", "cmd\\s+/k", "wscript", "cscript",
        "mshta", "rundll32", "regsvr32", "certutil.*-urlcache", "certutil.*-decode",
        "bitsadmin.*/transfer", "curl.*-o", "certutil.*-dump",
        // 文件/磁盘破坏
        "\\bdel\\b", "\\berase\\b", "\\brd\\b", "\\brmdir\\b", "\\bformat\\b",
        "\\bdiskpart\\b", "\\bcdedit\\b",
        // 系统修改
        "\\breg\\b.+(add|delete|import|restore)", "regedit\\s+/s",
        "schtasks.+/create", "schtasks.+/delete",
        "\\bsc\\b.+(config|create|delete|stop)",
        "net\\s+(user|localgroup|stop|start)",
        "\\bbcdedit\\b",
        // 网络写入
        "netsh.+(firewall|interface|winhttp)",
        "ipconfig.*/release", "ipconfig.*/renew", "ipconfig.*/flushdns",
        // 特权
        "runas\\b",
    };

    // ==================== 混淆/写入层 ====================

    private static final String[] OBFUSCATION_WRITE = {
        "%[a-z_]+%",               // 环境变量展开
        "\\^.",                     // 脱字符转义
        ">\\s", ">>", "1>", "2>",  // 重定向
        "\\bcopy\\b", "\\bxcopy\\b", "\\brobocopy\\b", "\\bmove\\b", "\\bren\\b",
        "\\bmklink\\b", "\\bmd\\b", "\\bmkdir\\b",
        "\\bset\\b.+=",            // set VAR=value
        "\\bsetx\\b",
        "\\btype\\b.*>",           // type xxx > yyy
        "\\bfind\\b.*>", "\\bfindstr\\b.*>",
        "\\battrib\\b", "\\bicacls\\b", "\\btakeown\\b",
        "\\btaskkill\\b", "\\btasklist\\b.*>",
        "\\bpause\\b", "\\bexit\\b", "\\bcls\\b",
        "\\bver\\b", "\\bvol\\b",
        "\\bassoc\\b", "\\bftype\\b",
        "\\bfor\\b.*\\bin\\b.*\\bdo\\b",  // for 循环
        "\\bcall\\b",
        "\\bgoto\\b",
    };

    // ==================== 只读白名单 ====================

    private static final Set<String> READONLY = new HashSet<String>(Arrays.asList(
        "dir", "cd", "type", "echo", "more", "tree", "find", "findstr", "sort",
        "whoami", "hostname", "ipconfig", "ping", "tracert", "nslookup", "netstat",
        "tasklist", "systeminfo", "ver", "vol", "time", "date", "chcp",
        "title", "color", "prompt", "path",
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
            // 管道/&分段：每段首个词为命令；全部在白名单即 readonly
            String[] segments = lower.split("[|&]");
            boolean allWhitelisted = segments.length > 0;
            for (String seg : segments) {
                String[] tokens = seg.trim().split("\\s+");
                String firstCommand = null;
                for (String t : tokens) {
                    if (t.isEmpty() || t.startsWith("/") || t.startsWith("-")
                            || t.startsWith("%") || t.startsWith("\"") || t.startsWith("'"))
                        continue;
                    firstCommand = t;
                    break;
                }
                if (firstCommand == null) continue;
                String name = firstCommand.contains("\\")
                        ? firstCommand.substring(firstCommand.lastIndexOf('\\') + 1) : firstCommand;
                name = name.replace(".exe", "").replace(".bat", "").replace(".cmd", "");
                if (!READONLY.contains(name)) {
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
        if (pattern.contains("powershell") || pattern.contains("pwsh"))
            return "嵌套 PowerShell（混淆面）";
        if (pattern.contains("cmd.*/c") || pattern.contains("cmd.*/k"))
            return "嵌套 CMD 子解释器";
        if (pattern.contains("mshta") || pattern.contains("rundll32") || pattern.contains("regsvr32"))
            return "LOLBin 执行（脚本宿主/DLL 工具）";
        if (pattern.contains("certutil") || pattern.contains("bitsadmin"))
            return " certutil/bitsadmin 下载（混淆面）";
        if (pattern.contains("\\bdel\\b") || pattern.contains("\\berase\\b") || pattern.contains("\\brd\\b")
                || pattern.contains("\\brmdir\\b") || pattern.contains("\\bformat\\b"))
            return "删除/格式化";
        if (pattern.contains("diskpart") || pattern.contains("bcdedit"))
            return "磁盘/引导修改";
        if (pattern.contains("reg.+(add|delete|import)") || pattern.contains("regedit"))
            return "注册表修改";
        if (pattern.contains("schtasks"))
            return "计划任务管理";
        if (pattern.contains("\\bsc\\b") || pattern.contains("net\\s+(user|stop|start)"))
            return "服务/账户管理";
        if (pattern.contains("netsh"))
            return "网络配置修改";
        if (pattern.contains("%[a-z_]+%"))
            return "环境变量展开（混淆面）";
        if (pattern.contains("\\^"))
            return "脱字符转义";
        if (pattern.contains(">") || pattern.contains(">>"))
            return "输出重定向";
        if (pattern.contains("copy") || pattern.contains("xcopy") || pattern.contains("move")
                || pattern.contains("robocopy") || pattern.contains("\\bren\\b"))
            return "文件复制/移动/重命名";
        if (pattern.contains("md") || pattern.contains("mkdir") || pattern.contains("mklink"))
            return "创建目录/链接";
        if (pattern.contains("set") || pattern.contains("setx"))
            return "环境变量设置";
        if (pattern.contains("taskkill") || pattern.contains("\\bkill\\b"))
            return "杀进程";
        if (pattern.contains("attrib") || pattern.contains("icacls") || pattern.contains("takeown"))
            return "文件属性/权限修改";
        if (pattern.contains("for.*/in.*/do") || pattern.contains("\\bcall\\b") || pattern.contains("\\bgoto\\b"))
            return "批处理控制流（复杂面）";
        return pattern;
    }
}
