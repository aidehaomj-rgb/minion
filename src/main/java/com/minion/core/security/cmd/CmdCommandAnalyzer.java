package com.minion.core.security.cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CMD 命令分析器（开发说明 v2.4 §4.4）：独立实现，不复用 Bash 分词结果。
 *
 * <p>首版 fail-closed 保守实现：检测 CMD 特有的危险命令/环境变量展开/转义序列；
 * <b>默认一切命令非只读、串行、询问</b>。后续按验证器清单逐个放行（扩展只收紧）。
 */
public final class CmdCommandAnalyzer {

    public static final class Analysis {
        public final boolean readOnly;          // 首版恒 false
        public final List<String> reasons;
        Analysis(boolean readOnly, List<String> reasons) { this.readOnly = readOnly; this.reasons = reasons; }
    }

    public Analysis analyze(String command) {
        List<String> reasons = new ArrayList<String>();
        if (command != null && !command.trim().isEmpty()) {
            String lower = command.toLowerCase(Locale.ROOT);
            if (lower.contains("%") && lower.contains("%")) reasons.add("环境变量展开 %VAR%");
            if (lower.contains("del ") || lower.contains("erase ") || lower.contains("rd ")
                    || lower.contains("rmdir ") || lower.contains("format ")) reasons.add("删除/格式化命令");
            if (lower.contains("reg ") || lower.contains("regedit ") || lower.contains("bcdedit ")) reasons.add("注册表/引导修改");
            if (lower.contains("net user") || lower.contains("net localgroup")) reasons.add("账户管理");
            if (lower.contains("schtasks ") || lower.contains("sc ")) reasons.add("计划任务/服务");
            if (lower.contains("powershell") || lower.contains("cmd /c") || lower.contains("cmd /k")
                    || lower.contains("start ")) reasons.add("子解释器/进程启动");
            if (lower.contains(">>") || lower.contains(">")) reasons.add("输出重定向");
            if (lower.contains("^") && lower.matches("(?s).*\\^[<>|&].*")) reasons.add("脱字符转义操作符");
            if (reasons.isEmpty()) reasons.add("CMD 专属验证器未覆盖（fail-closed 默认询问）");
        }
        return new Analysis(false, reasons);
    }
}
