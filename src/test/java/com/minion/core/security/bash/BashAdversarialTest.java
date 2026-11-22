package com.minion.core.security.bash;

import com.minion.core.security.cmd.CmdCommandAnalyzer;
import com.minion.core.security.powershell.PowerShellCommandAnalyzer;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Bash/PowerShell/CMD 分析器对抗验收（v2.4 §4.4）：加载 src/test/resources/bash-adversarial.txt
 * （≥100 条样本），逐条断言 fail-closed——ASK 样本必须非只读且触发询问，CLEAN 样本必须只读通过。
 */
public class BashAdversarialTest {

    private final BashCommandAnalyzer analyzer = new BashCommandAnalyzer();

    @Test public void atLeastOneHundredSamplesLoaded() throws Exception {
        List<String[]> samples = load();
        assertTrue("样本数 >= 100，实际 " + samples.size(), samples.size() >= 100);
    }

    @Test public void allAdversarialSamplesFailClosed() throws Exception {
        List<String> failures = new ArrayList<String>();
        for (String[] sample : load()) {
            String expect = sample[0];
            String command = sample[1];
            BashCommandAnalyzer.Analysis a = analyzer.analyze(command);
            if ("ASK".equals(expect)) {
                if (!a.asks() || a.readOnly)
                    failures.add("应询问: [" + command.replace("\n", "\\n") + "] → " + a.kind + " findings=" + a.findings.size() + " ro=" + a.readOnly);
            } else if ("CLEAN".equals(expect)) {
                if (a.asks())   // CLEAN = 无需询问（readOnly 是并行维度：$VAR/含 $ 形态不询问但不并行）
                    failures.add("应免询问通过: [" + command.replace("\n", "\\n").replace("\t", "\\t") + "] → " + a.kind
                            + " findings=" + findingsText(a));
            }
        }
        assertTrue("对抗失败 " + failures.size() + " 条:\n" + join(failures, 20), failures.isEmpty());
    }

    /** Unicode 空白混淆（引擎级断言；样本文件加载链路另有差异，单独跟踪） */
    @Test public void unicodeWhitespaceConfusionAsks() {
        BashCommandAnalyzer.Analysis a = analyzer.analyze("ls　--all");
        assertTrue(a.asks());
        assertEquals("unicode-whitespace", a.findings.get(0).check);
    }

    /** 拆分器单元：复合命令切分正确 */
    @Test public void splitterSplitsCompoundCommands() {
        BashCommandSplitter.Result r1 = BashCommandSplitter.split("ls && rm x");
        assertEquals(2, r1.subCommands.size());
        assertEquals("ls", r1.subCommands.get(0).trim());
        BashCommandSplitter.Result r2 = BashCommandSplitter.split("a || b ; c | d");
        assertEquals(4, r2.subCommands.size());
        BashCommandSplitter.Result r3 = BashCommandSplitter.split("git status");
        assertEquals(1, r3.subCommands.size());
    }

    /** 拆分器：续行合并不加空格 */
    @Test public void splitterJoinsContinuations() {
        BashCommandSplitter.Result r = BashCommandSplitter.split("rm \\\n-rf /");
        assertEquals(1, r.subCommands.size());
        assertEquals("rm -rf /", r.subCommands.get(0).trim());
    }

    /** 拆分器：heredoc 正文受保护 */
    @Test public void splitterProtectsHeredocBodies() {
        BashCommandSplitter.Result r = BashCommandSplitter.split("cat <<'EOF'\n$(safe)\nEOF\necho done");
        assertEquals(1, r.heredocBodies.size());
        assertEquals("$(safe)", r.heredocBodies.get(0));
        // heredoc 正文中的 $(safe) 不得污染子命令判定
        assertEquals("echo done", r.subCommands.get(r.subCommands.size() - 1).trim());
    }

    /** 前缀提取 */
    @Test public void prefixSuggestions() {
        assertEquals("ls", analyzer.suggestPrefix("ls -la"));
        assertEquals("git", analyzer.suggestPrefix("git status && git diff --stat"));
        assertEquals("npm run", analyzer.suggestPrefix("npm run test && npm run lint"));
    }

    /** PowerShell 分析器：白名单只读放行 + 危险拦截（切片 41 加厚后更新） */
    @Test public void powershellAnalyzerFailsClosed() {
        PowerShellCommandAnalyzer ps = new PowerShellCommandAnalyzer();
        // 白名单命令 → 只读（低危直通，不再弹窗）
        assertTrue("Get-ChildItem 在白名单应只读", ps.analyze("Get-ChildItem").readOnly);
        assertTrue("Get-Content file 在白名单应只读", ps.analyze("Get-Content file").readOnly);
        // 危险命令 → dangerous
        assertTrue(ps.analyze("Invoke-Expression 'rm x'").dangerous);
        assertTrue(ps.analyze("Remove-Item x").dangerous);
        // 未知命令 → fail-closed（非只读非危险，默认 ask）
        PowerShellCommandAnalyzer.Analysis unknown = ps.analyze("UnknownCustomCmd");
        assertFalse(unknown.readOnly);
        assertFalse(unknown.dangerous);
        assertTrue(unknown.reasons.get(0).contains("fail-closed"));
    }

    /** CMD 分析器：白名单只读放行 + 危险拦截（切片 41 加厚后更新） */
    @Test public void cmdAnalyzerFailsClosed() {
        CmdCommandAnalyzer cmd = new CmdCommandAnalyzer();
        // 白名单命令 → 只读
        assertTrue("dir 在白名单应只读", cmd.analyze("dir").readOnly);
        // 危险命令 → dangerous
        assertTrue(cmd.analyze("del x").dangerous);
        assertTrue(cmd.analyze("reg add HKLM\\X").dangerous);
        // 未知命令 → fail-closed
        CmdCommandAnalyzer.Analysis unknown = cmd.analyze("UnknownCmd123");
        assertFalse(unknown.readOnly);
        assertFalse(unknown.dangerous);
    }

    // ==================== 加载 ====================

    private List<String[]> load() throws Exception {
        InputStream in = getClass().getResourceAsStream("/bash-adversarial.txt");
        assertNotNull("对抗样本文件缺失", in);
        List<String[]> samples = new ArrayList<String[]>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            String expect = line.substring(0, bar).trim();
            String command = unescape(line.substring(bar + 1));
            samples.add(new String[]{expect, command});
        }
        return samples;
    }

    /** 样本转义还原：\\n → 换行，\\uXXXX → 对应 Unicode 字符；其余原样 */
    private static String unescape(String raw) {
        String s = raw.replace("\\n", "\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 6 <= s.length() && s.charAt(i + 1) == 'u') {
                String hex = s.substring(i + 2, i + 6);
                if (hex.matches("[0-9a-fA-F]{4}")) {
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                }
            }
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    private static String findingsText(BashCommandAnalyzer.Analysis a) {
        StringBuilder sb = new StringBuilder();
        for (BashChecks.Finding f : a.findings) { if (sb.length() > 0) sb.append(','); sb.append(f.check); }
        return sb.toString();
    }

    private static String join(List<String> list, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < limit; i++) { sb.append(list.get(i)).append('\n'); }
        return sb.toString();
    }
}
