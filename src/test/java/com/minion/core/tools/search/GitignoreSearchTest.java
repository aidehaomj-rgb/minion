package com.minion.core.tools.search;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.tools.GlobTool;
import com.minion.core.tools.GrepTool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class GitignoreSearchTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path ws;
    private Workspace workspace;

    @Before
    public void setup() throws Exception {
        ws = tmp.newFolder("ws").toPath();
        workspace = new Workspace(ws.toString());
        write(".gitignore", "ignored/\n*.log\n!important.log\nbuild/\n/rootonly.md\n");
        write("a.txt", "命中关键词\n");
        write("ignored/b.txt", "命中关键词\n");
        write("debug.log", "命中关键词\n");
        write("important.log", "命中关键词\n");
        write("rootonly.md", "命中关键词\n");
        write("build/x.txt", "命中关键词\n");
        write("sub/.gitignore", "*.txt\n");
        write("sub/c.txt", "命中关键词\n");
        write("sub/d.md", "命中关键词\n");
        write("sub/rootonly.md", "命中关键词\n");
        write(".git/config", "命中关键词\n");
        Files.write(ws.resolve("bin.dat"), "命中\0关键词".getBytes(StandardCharsets.UTF_8));
    }

    private void write(String rel, String content) throws Exception {
        Path p = ws.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private static boolean hasLineStarting(String output, String prefix) {
        for (String line : output.split("\n")) if (line.startsWith(prefix)) return true;
        return false;
    }

    private ToolResult grep(String pattern, String path) throws Exception {
        Path dump = tmp.newFolder("dump").toPath();
        GrepTool tool = new GrepTool(workspace, null, dump.toString(), null);
        JsonObject a = args("{\"pattern\":\"" + pattern + "\"" + (path == null ? "" : ",\"path\":\"" + path + "\"") + "}");
        return tool.execute(a);
    }

    private ToolResult glob(String pattern) throws Exception {
        return new GlobTool(workspace).execute(args("{\"pattern\":\"" + pattern + "\"}"));
    }

    @Test
    public void grep_respectsGitignore_binary_andGitDir() throws Exception {
        ToolResult r = grep("命中关键词", null);
        assertTrue(r.ok);
        assertTrue(r.output.contains("a.txt"));
        assertTrue(r.output.contains("important.log"));   // 取反重新包含
        assertTrue(r.output.contains("sub/d.md"));
        assertTrue(r.output.contains("sub/rootonly.md")); // 锚定规则只管根层
        assertFalse(r.output.contains("ignored/b.txt"));
        assertFalse(r.output.contains("debug.log"));
        assertFalse(r.output.contains("sub/c.txt"));       // 子目录 .gitignore 叠加
        assertFalse(hasLineStarting(r.output, "rootonly.md:"));
        assertFalse(r.output.contains("build/x.txt"));
        assertFalse(r.output.contains(".git/config"));
        assertFalse(r.output.contains("bin.dat"));         // 二进制跳过
    }

    @Test
    public void glob_respectsGitignore() throws Exception {
        ToolResult r = glob("**/*.txt");
        assertTrue(r.ok);
        assertTrue(r.output.contains("a.txt"));
        assertFalse(r.output.contains("ignored/b.txt"));
        assertFalse(r.output.contains("sub/c.txt"));
        assertFalse(r.output.contains("build/x.txt"));
        ToolResult md = glob("**/*.md");
        assertTrue(md.output.contains("sub/rootonly.md"));
        assertTrue(md.output.contains("sub/d.md"));
        assertFalse(hasLineStarting(md.output, "rootonly.md"));
    }

    @Test
    public void explicitIgnoredRoot_stillSearchable() throws Exception {
        ToolResult r = grep("命中关键词", "ignored");
        assertTrue(r.ok);
        assertTrue("显式指定被忽略目录为搜索根时仍直搜: " + r.output, r.output.contains("b.txt"));
    }

    @Test
    public void deeperNegation_overridesParentIgnore() throws Exception {
        write("keep/.gitignore", "!keep.log\n");
        write("keep/keep.log", "命中关键词\n");
        ToolResult r = grep("命中关键词", null);
        assertTrue(r.output.contains("keep/keep.log"));
    }

    @Test
    public void directoryOnlyRule_doesNotIgnoreSameNameFile() throws Exception {
        write("sub/build", "命中关键词\n"); // 与 build/ 目录规则同名的普通文件（子目录下无同名目录）
        ToolResult r = grep("命中关键词", null);
        assertTrue(hasLineStarting(r.output, "sub/build:"));
    }

    @Test
    public void noGitignore_behaviorUnchanged() throws Exception {
        Path plain = tmp.newFolder("plain").toPath();
        Files.write(plain.resolve("x.txt"), "命中关键词".getBytes(StandardCharsets.UTF_8));
        GrepTool tool = new GrepTool(new Workspace(plain.toString()));
        ToolResult r = tool.execute(args("{\"pattern\":\"命中关键词\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("x.txt"));
    }

    @Test
    public void parse_commentsAndInvalidLines_safe() {
        assertNull(GitignoreFilter.parse("# comment"));
        assertNull(GitignoreFilter.parse(""));
        assertNull(GitignoreFilter.parse("   "));
        assertNull(GitignoreFilter.parse("[")); // 非法 glob
        assertNotNull(GitignoreFilter.parse("*.log"));
        assertNotNull(GitignoreFilter.parse("!keep.log"));
        assertNotNull(GitignoreFilter.parse("build/"));
        assertNotNull(GitignoreFilter.parse("/root.txt"));
    }

    @Test
    public void isBinary_detectsNul() throws Exception {
        Path bin = ws.resolve("b.bin");
        Files.write(bin, new byte[]{1, 2, 0, 3});
        assertTrue(GitignoreFilter.isBinary(bin));
        assertFalse(GitignoreFilter.isBinary(ws.resolve("a.txt")));
    }
}