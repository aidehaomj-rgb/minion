package com.minion.core.security.powershell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PowerShell 命令分析器（开发说明 v2.4 §4.4）：独立实现，不复用 Bash 分词结果。
 *
 * <p>首版 fail-closed 保守实现：检测 PowerShell 特有的表达式/调用操作符/变量展开/常见危险
 * cmdlet；<b>默认一切命令非只读、串行、询问</b>。后续按验证器清单逐个放行（扩展只收紧）。
 */
public final class PowerShellCommandAnalyzer {

    public static final class Analysis {
        public final boolean readOnly;          // 首版恒 false
        public final List<String> reasons;
        Analysis(boolean readOnly, List<String> reasons) { this.readOnly = readOnly; this.reasons = reasons; }
    }

    public Analysis analyze(String command) {
        List<String> reasons = new ArrayList<String>();
        if (command != null && !command.trim().isEmpty()) {
            String lower = command.toLowerCase(Locale.ROOT);
            if (lower.contains("$(") || lower.contains("$((")) reasons.add("子表达式 $()");
            if (lower.contains("& ") || lower.matches("(?s)^\\s*&.*")) reasons.add("调用操作符 &");
            if (lower.contains("invoke-expression") || lower.contains("iex ")) reasons.add("Invoke-Expression");
            if (lower.contains("remove-item") || lower.contains("ri ") || lower.contains("del ")
                    || lower.contains("rm ") || lower.contains("erase ")) reasons.add("删除类 cmdlet");
            if (lower.contains("start-process") || lower.contains("start-job")) reasons.add("启动进程/作业");
            if (lower.contains("set-executionpolicy") || lower.contains("new-service")
                    || lower.contains("set-itemproperty") || lower.contains("sp ")) reasons.add("系统修改 cmdlet");
            if (lower.contains("-enc") || lower.contains("base64")) reasons.add("编码执行（混淆面）");
            if (lower.contains("out-file") || lower.contains(">") || lower.contains(">>")
                    || lower.contains("set-content") || lower.contains("add-content")) reasons.add("输出/写入文件");
            if (reasons.isEmpty()) reasons.add("PowerShell 专属验证器未覆盖（fail-closed 默认询问）");
        }
        return new Analysis(false, reasons);
    }
}
