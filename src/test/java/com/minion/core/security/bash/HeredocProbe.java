package com.minion.core.security.bash;

/**
 * 探针：直接打印 analyze 结果（仅本地调试用，不由 surefire 断言）。
 * 运行方式：mvn -q test-compile exec:java -Dexec.mainClass=... 或 IDE 运行。
 */
public final class HeredocProbe {
    public static void main(String[] args) {
        String[] cases = {
                "cat <<EOF\ncontent $(danger)\nEOF",
                "cat <<'EOF'\nliteral $(safe)\nEOF",
                "ls & rm x",
                "ls　--all",
                "echo a｜b",
        };
        BashCommandAnalyzer analyzer = new BashCommandAnalyzer();
        for (String c : cases) {
            BashCommandSplitter.Result r = BashCommandSplitter.split(c);
            System.out.println("命令: " + c.replace("\n", "\\n"));
            System.out.println("  子命令: " + r.subCommands);
            System.out.println("  heredoc正文: " + r.heredocBodies + " quoted: " + r.heredocQuoted);
            BashCommandAnalyzer.Analysis a = analyzer.analyze(c);
            System.out.println("  分析: kind=" + a.kind + " ro=" + a.readOnly + " findings=" + a.findings.size());
            for (BashChecks.Finding f : a.findings) System.out.println("    - " + f.check + ": " + f.reason);
        }
    }
}
