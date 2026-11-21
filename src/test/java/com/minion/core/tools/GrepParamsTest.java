package com.minion.core.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * v2.4 §3.1 Grep 参数补全验收（切片 29）：glob/type 文件过滤、output_mode 三态、
 * context 上下文行、head_limit/offset、multiline 显式不支持；旧参数语义非回归。
 */
public class GrepParamsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private GrepTool tool() {
        Workspace ws = new Workspace(tmp.getRoot().getPath());
        return new GrepTool(ws);
    }

    private com.google.gson.JsonObject args(String json) {
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    }

    private void file(String name, String content) throws Exception {
        Path p = tmp.newFolder().toPath().getParent().resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void globAndTypeFilter() throws Exception {
        file("a/one.md", "alpha keyword\n");
        file("a/two.txt", "beta keyword\n");
        ToolResult r1 = tool().execute(args(
                "{\"pattern\":\"keyword\",\"glob\":\"*.md\"}"));
        assertTrue(r1.output, r1.ok && r1.output.contains("one.md") && !r1.output.contains("two.txt"));
        ToolResult r2 = tool().execute(args(
                "{\"pattern\":\"keyword\",\"type\":\"txt\"}"));
        assertTrue(r2.output, r2.ok && r2.output.contains("two.txt") && !r2.output.contains("one.md"));
    }

    @Test public void outputModes() throws Exception {
        file("b/x.md", "hit\nhit\n");
        file("b/y.md", "hit\n");
        ToolResult files = tool().execute(args(
                "{\"pattern\":\"hit\",\"output_mode\":\"files_with_matches\"}"));
        assertTrue(files.output, files.ok);
        assertTrue(files.output.contains("x.md") && files.output.contains("y.md"));
        assertFalse("不含行号内容", files.output.contains(":1:"));

        ToolResult count = tool().execute(args(
                "{\"pattern\":\"hit\",\"output_mode\":\"count\"}"));
        assertTrue(count.output, count.ok);
        assertTrue("计数格式 文件:N", count.output.contains("x.md:2") && count.output.contains("y.md:1"));
    }

    @Test public void contextAndHeadLimitOffset() throws Exception {
        file("c/ctx.md", "l1\nMATCH\nl3\nl4\nMATCH2\nl6\n");
        ToolResult ctx = tool().execute(args(
                "{\"pattern\":\"MATCH\",\"context\":1}"));
        assertTrue(ctx.output, ctx.ok && ctx.output.contains("l1") && ctx.output.contains("l3"));
        ToolResult paged = tool().execute(args(
                "{\"pattern\":\"MATCH\",\"head_limit\":1,\"output_mode\":\"content\"}"));
        assertTrue(paged.output, paged.ok && paged.output.contains("MATCH") && !paged.output.contains("MATCH2"));
        ToolResult offset = tool().execute(args(
                "{\"pattern\":\"MATCH\",\"head_limit\":1,\"offset\":1}"));
        assertTrue(offset.output, offset.ok && offset.output.contains("MATCH2") && !offset.output.contains("MATCH\n"));
    }

    @Test public void multilineExplicitlyUnsupported() throws Exception {
        ToolResult r = tool().execute(args(
                "{\"pattern\":\"a.*b\",\"multiline\":true}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("不支持"));
    }

    @Test public void legacyParamsUnchanged() throws Exception {
        file("d/legacy.txt", "needle in haystack\n");
        ToolResult r = tool().execute(args(
                "{\"pattern\":\"needle\",\"path\":\".\",\"ignoreCase\":true,\"maxResults\":10}"));
        assertTrue(r.output, r.ok && r.output.contains("legacy.txt:1"));
        // 旧语义：默认 output 仍为 content 带行号
        assertTrue(r.output.contains(":1: "));
    }
}
