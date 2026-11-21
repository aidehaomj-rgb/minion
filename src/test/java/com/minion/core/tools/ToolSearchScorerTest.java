package com.minion.core.tools;

import com.minion.core.tools.ToolSearchScorer.Candidate;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** §5.4 ToolSearch 打分与自动启用阈值验收 */
public class ToolSearchScorerTest {

    private static List<Candidate> pool() {
        return new ArrayList<Candidate>(Arrays.asList(
                new Candidate("Read", null, "读取文件内容"),
                new Candidate("Grep", null, "在工作路径内按正则搜索文件内容"),
                new Candidate("mcp__wiki__search", "知识库检索", "检索企业知识库"),
                new Candidate("mcp__db__query", "数据库", "执行只读 SQL 查询"),
                new Candidate("TodoWrite", null, "维护任务清单"),
                new Candidate("Bash", "shell", "执行 shell 命令")));
    }

    /** select: 直选按给定顺序返回，忽略打分 */
    @Test public void selectDirectPick() {
        List<Candidate> out = ToolSearchScorer.search(pool(), "select:TodoWrite,Bash", 5);
        assertEquals(2, out.size());
        assertEquals("TodoWrite", out.get(0).name);
        assertEquals("Bash", out.get(1).name);
    }

    /** 精确名快速路径：MCP 12 / 普通 10；子串 MCP 6 / 普通 5 */
    @Test public void exactAndSubstringScores() {
        assertEquals("mcp__wiki__search", ToolSearchScorer.search(pool(), "mcp__wiki__search", 1).get(0).name);
        assertEquals("Read", ToolSearchScorer.search(pool(), "read", 1).get(0).name);
        // 子串：search 命中 mcp__wiki__search（6）高于描述词边界命中者（2）
        List<Candidate> out = ToolSearchScorer.search(pool(), "search", 3);
        assertEquals("mcp__wiki__search", out.get(0).name);
    }

    /** searchHint 命中 4；mcp__server 前缀（server 名）匹配 6 */
    @Test public void hintAndServerPrefix() {
        List<Candidate> hint = ToolSearchScorer.search(pool(), "知识库", 3);
        assertEquals("mcp__wiki__search", hint.get(0).name);   // searchHint「知识库检索」命中
        List<Candidate> server = ToolSearchScorer.search(pool(), "wiki", 3);
        assertEquals("mcp__wiki__search", server.get(0).name); // server 名 wiki 前缀匹配
    }

    /** 描述词边界命中 2；无命中不返回；top N 截断 */
    @Test public void descriptionBoundaryAndCap() {
        List<Candidate> out = ToolSearchScorer.search(pool(), "清单", 5);
        assertEquals("TodoWrite", out.get(0).name);
        assertTrue(ToolSearchScorer.search(pool(), "zzz无关词", 5).isEmpty());
        List<Candidate> capped = ToolSearchScorer.search(pool(), "e", 2);
        assertTrue(capped.size() <= 2);
    }

    /** 自动启用阈值：延迟 schema（2.5 字符/token）> 窗口×10% */
    @Test public void autoEnableThreshold() {
        // 131072 窗口 ×10% = 13107 token → 需要 schema > 32768 字符
        assertFalse(ToolSearchScorer.autoEnable(32768, 131072));   // 恰好等于不启用
        assertTrue(ToolSearchScorer.autoEnable(40000, 131072));
        assertFalse(ToolSearchScorer.autoEnable(100000, 0));       // 零窗口保守不启用
        assertFalse(ToolSearchScorer.autoEnable(0, 131072));
    }
}
