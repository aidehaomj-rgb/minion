package com.minion.core.tools.search;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.tools.GlobTool;
import com.minion.core.tools.GrepTool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** ripgrep 旁路 vs Java 遍历一致性。用随包 vendored 二进制（offline-package/ripgrep/rg.exe），
 *  不存在时 Assume 跳过（不阻塞无 rg 环境的回归）。 */
public class RipgrepSidecarTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path ws;
    private Workspace workspace;
    private static final Path VENDORED = Paths.get("offline-package/ripgrep/rg.exe").toAbsolutePath();

    @Before
    public void setup() throws Exception {
        Assume.assumeTrue("vendored rg.exe 不存在，跳过旁路测试", Files.isRegularFile(VENDORED));
        RipgrepSidecar.overrideForTests(VENDORED.toString());
        ws = tmp.newFolder("ws").toPath();
        workspace = new Workspace(ws.toString());
        write(".gitignore", "ignored/\n*.log\n!important.log\nbuild/\n/rootonly.md\n");
        write("a.txt", "needle alpha\nneedle beta\n");
        write("ignored/b.txt", "needle\n");
        write("debug.log", "needle\n");
        write("important.log", "needle\n");
        write("rootonly.md", "needle\n");
        write("build/x.txt", "needle\n");
        write("sub/.gitignore", "*.txt\n");
        write("sub/c.txt", "needle\n");
        write("sub/d.md", "needle\n");
        write("sub/rootonly.md", "needle\n");
        write(".git/config", "needle\n");
        Files.write(ws.resolve("bin.dat"), "needle\0needle".getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void teardown() {
        RipgrepSidecar.overrideForTests(null);
    }

    private void write(String rel, String content) throws Exception {
        Path p = ws.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private ToolResult grep(String pattern, String dumpDir) throws Exception {
        GrepTool tool = new GrepTool(workspace, null, dumpDir, null);
        return tool.execute(args("{\"pattern\":\"" + pattern + "\"}"));
    }

    private ToolResult glob(String pattern) throws Exception {
        return new GlobTool(workspace).execute(args("{\"pattern\":\"" + pattern + "\"}"));
    }

    private static List<String> sortedLines(String output) {
        List<String> lines = new ArrayList<String>();
        for (String line : output.split("\n")) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        Collections.sort(lines);
        return lines;
    }

    @Test
    public void grep_rgOutput_equalsJavaFallback() throws Exception {
        ToolResult rgResult = grep("needle", null);
        RipgrepSidecar.overrideForTests(""); // 强制 Java 遍历
        ToolResult javaResult = grep("needle", null);
        assertTrue(rgResult.ok);
        assertTrue(javaResult.ok);
        assertEquals(sortedLines(javaResult.output), sortedLines(rgResult.output));
        assertTrue(rgResult.output.contains("a.txt:1: needle alpha"));
        assertTrue(rgResult.output.contains("important.log"));
        assertFalse(rgResult.output.contains("ignored/b.txt"));
        assertFalse(rgResult.output.contains("bin.dat"));
        assertFalse(rgResult.output.contains(".git/config"));
    }

    @Test
    public void glob_rgOutput_equalsJavaFallback() throws Exception {
        for (String pattern : new String[]{"**/*.txt", "**/*.md", "*.md", "**/*"}) {
            ToolResult rgResult = glob(pattern);
            RipgrepSidecar.overrideForTests("");
            ToolResult javaResult = glob(pattern);
            RipgrepSidecar.overrideForTests(VENDORED.toString());
            assertTrue(rgResult.ok);
            assertEquals("pattern=" + pattern, sortedLines(javaResult.output), sortedLines(rgResult.output));
        }
    }

    @Test
    public void glob_rg_excludesGitDir() throws Exception {
        ToolResult r = glob("**/*");
        assertTrue(r.ok);
        assertFalse(r.output.contains(".git/config"));
        assertFalse(r.output.contains(".git\\"));
    }

    @Test
    public void grep_unsupportedRegex_fallsBackToJava() throws Exception {
        // 环视：rg 语法不支持（退出码 2）→ 自动回退 Java，结果不丢
        write("look.txt", "foobar\n");
        ToolResult r = grep("foo(?=bar)", null);
        assertTrue(r.ok);
        assertTrue("回退后应命中: " + r.output, r.output.contains("look.txt"));
    }

    @Test
    public void grep_nonAsciiPattern_gbkFile_stillFound() throws Exception {
        // 中文模式不走 rg（rg 按 UTF-8 搜索会漏 GBK 文件），Java 遍历 GBK 回退兜底
        Files.write(ws.resolve("gbk.txt"), "命中关键词".getBytes(Charset.forName("GBK")));
        ToolResult r = grep("命中关键词", null);
        assertTrue(r.ok);
        assertTrue("GBK 文件应被 Java 回退命中: " + r.output, r.output.contains("gbk.txt"));
    }

    @Test
    public void grep_rg_dumpContractPreserved() throws Exception {
        // 落盘目录放工作区内：验证 rg 排除落盘目录防自噬 + 双上限截断格式不变
        Path dump = ws.resolve("dumpdir");
        write("m1.txt", "needle\n");
        write("m2.txt", "needle\n");
        GrepTool tool = new GrepTool(workspace, null, dump.toString(), null);
        ToolResult r = tool.execute(args("{\"pattern\":\"needle\",\"maxResults\":1}"));
        assertTrue(r.ok);
        assertTrue("应提示全量条数: " + r.output, r.output.contains("共 "));
        assertTrue("应提示落盘路径: " + r.output, r.output.contains("完整结果已保存到"));
        assertFalse("落盘目录不应被搜到", r.output.contains("dumpdir/"));
        Path[] dumps = dump.toFile().listFiles() == null ? new Path[0]
                : Arrays.stream(dump.toFile().listFiles()).map(java.io.File::toPath).toArray(Path[]::new);
        assertEquals(1, dumps.length);
        String full = new String(Files.readAllBytes(dumps[0]), StandardCharsets.UTF_8);
        assertTrue(full.contains("m1.txt"));
        assertTrue(full.contains("m2.txt"));
        assertTrue(full.contains("a.txt:2: needle beta"));
    }

    @Test
    public void rgSafePattern_rules() {
        assertTrue(GrepTool.rgSafePattern("needle"));
        assertTrue(GrepTool.rgSafePattern("foo.*bar"));
        assertTrue(GrepTool.rgSafePattern("\\\\d"));   // 转义反斜杠 + 字面 d
        assertFalse(GrepTool.rgSafePattern("\\d"));
        assertFalse(GrepTool.rgSafePattern("\\bfoo"));
        assertFalse(GrepTool.rgSafePattern("\\w+"));
        assertFalse(GrepTool.rgSafePattern("\\s"));
        assertFalse(GrepTool.rgSafePattern("命中"));
    }

    @Test
    public void override_seam() {
        RipgrepSidecar.overrideForTests("");
        assertNull(RipgrepSidecar.resolve());
        RipgrepSidecar.overrideForTests(VENDORED.toString());
        assertEquals(VENDORED.toString(), RipgrepSidecar.resolve());
    }
}