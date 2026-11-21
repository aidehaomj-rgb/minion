package com.minion.core.context;

import com.google.gson.JsonObject;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.StreamHandler;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** §6.2 记忆索引双上限 + 召回选择（SideQuery/回退）验收 */
public class MemoryIndexAndRecallTest {

    // ==================== 索引双上限 ====================

    @Test public void indexCapsAtLinesAndBytes() {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 300; i++) lines.add("- 行 " + i);
        StringBuilder warn = new StringBuilder();
        String idx = MemoryIndex.render(lines, warn);
        long lineCount = idx.chars().filter(c -> c == '\n').count();
        assertTrue(lineCount <= MemoryIndex.MAX_LINES + 2);   // 头部 2 行 + 条目 ≤200
        assertTrue(idx.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MemoryIndex.MAX_BYTES);
        assertTrue(warn.length() > 0);   // 超限警告

        // 少量行无警告
        StringBuilder noWarn = new StringBuilder();
        MemoryIndex.render(Arrays.asList("- a", "- b"), noWarn);
        assertEquals(0, noWarn.length());
    }

    /** 超长单行不能绕过：行数少但字节超 → 字节截断在换行处且不撕多字节 */
    @Test public void longSingleLineTruncatedByBytes() {
        List<String> lines = new ArrayList<String>();
        StringBuilder huge = new StringBuilder("- 长行");
        for (int i = 0; i < 30000; i++) huge.append('字');
        lines.add(huge.toString());
        lines.add("- 第二行");
        StringBuilder warn = new StringBuilder();
        String idx = MemoryIndex.render(lines, warn);
        assertTrue(idx.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MemoryIndex.MAX_BYTES);
        assertTrue(warn.length() > 0);
        assertFalse(idx.endsWith("\uFFFD"));   // 不以替换符收尾（未撕开多字节）
    }

    @Test public void lineFormatterClipsLongDescriptions() {
        String long1 = MemoryIndex.line("fact", "memory/a.md", "短描述");
        assertTrue(long1.startsWith("- fact | memory/a.md | 短描述"));
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 500; i++) d.append('描');
        String clipped = MemoryIndex.line("fact", "p", d.toString());
        assertTrue(clipped.length() < 150);
        assertTrue(clipped.endsWith("…"));
    }

    // ==================== 召回选择 ====================

    /** SideQuery 可用：JSON 数组选择、排除 MEMORY.md 与已展示、上限 5 */
    @Test public void recallViaSideQuerySelection() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "[\"- fact | memory/deploy.md | 部署步骤\", \"- fact | memory/api.md | 接口约定\"]";
        List<String> headers = Arrays.asList(
                "- fact | memory/deploy.md | 部署步骤",
                "- fact | memory/api.md | 接口约定",
                "- idx | MEMORY.md | 索引",
                "- fact | memory/old.md | 已展示");
        List<String> picked = MemoryRecall.select(llm, headers, "部署问题", Arrays.asList("memory/old.md"));
        assertEquals(2, picked.size());
        assertTrue(picked.get(0).contains("deploy.md"));
        // 输出里的 MEMORY.md/old 候选已被过滤，不可能被选中
    }

    /** SideQuery 失败/畸形 → 回退关键词（现状口径），仍上限 5 */
    @Test public void recallFallsBackToKeywordOnFailure() {
        LlmClient broken = new LlmClient() {
            public String completeChat(List<Message> m, String s) throws LlmException { throw new LlmException(LlmException.Type.OTHER, "挂了", true, 500, "", 0); }
            public void streamChat(List<Message> m, List<JsonObject> t, StreamHandler h) throws LlmException { throw new UnsupportedOperationException(); }
        };
        List<String> headers = new ArrayList<String>();
        for (int i = 0; i < 8; i++) headers.add("- fact | memory/db" + i + ".md | 数据库连接配置");
        headers.add("- fact | memory/web.md | 前端构建");
        List<String> picked = MemoryRecall.select(broken, headers, "数据库 连接 配置 怎么改", null);
        assertEquals(MemoryRecall.MAX_SELECT, picked.size());
        assertTrue(picked.get(0).contains("db"));
        // 无匹配词 → 空
        assertTrue(MemoryRecall.select(null, headers, "完全无关词", null).isEmpty());
    }
}
