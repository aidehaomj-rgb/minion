package com.minion.core.context;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class MemoryRecallQueryTest {
    @Test public void chineseWithoutWhitespaceIsAQueryNotAnEmptyWordList() {
        MemoryRecallQuery query = MemoryRecallQuery.from(null, null, "不要连接外网仅允许内网数据源");
        assertEquals(1, query.segments().size());
        assertEquals("不要连接外网仅允许内网数据源", query.segments().get(0).text);
        assertEquals("correction", query.segments().get(0).origin);
    }

    @Test public void nullAndBlankInputsAreEmpty() {
        assertTrue(MemoryRecallQuery.from(null, "\n\t", "  ").isEmpty());
    }

    @Test public void allThreeInputsReserveTheirOwnHeadAndTailWithinBudget() {
        String goal = longInput("目标开头", "目标末尾禁止上传客户资料");
        String step = longInput("步骤开头", "步骤末尾仅处理尚未核验条目");
        String correction = longInput("纠正开头", "纠正末尾保持原始来源路径");
        MemoryRecallQuery query = MemoryRecallQuery.from(goal, step, correction);
        int chars = 0;
        for (MemoryRecallQuery.Segment s : query.segments()) {
            assertTrue(s.text.length() <= MemoryRecallQuery.MAX_QUERY_CHARS);
            chars += s.text.length();
        }
        assertTrue(query.segments().size() <= MemoryRecallQuery.MAX_QUERIES);
        assertTrue(chars <= MemoryRecallQuery.MAX_TOTAL_CHARS);
        assertContains(query, "目标开头"); assertContains(query, "目标末尾禁止上传客户资料");
        assertContains(query, "步骤开头"); assertContains(query, "步骤末尾仅处理尚未核验条目");
        assertContains(query, "纠正开头"); assertContains(query, "纠正末尾保持原始来源路径");
    }

    @Test public void tailClauseIsNotCoupledToUnrelatedNumbers() {
        MemoryRecallQuery query = MemoryRecallQuery.from(longInput("请审查数据2026", "前文有38个分块；禁止上传客户资料"));
        boolean exactClause = false;
        for (MemoryRecallQuery.Segment s : query.segments()) if (s.text.equals("禁止上传客户资料")) exactClause = true;
        assertTrue("final clause must not require an unrelated 38/2026 match", exactClause);
    }

    @Test public void deduplicationKeepsCorrectionPriorityAndListsAreImmutable() {
        MemoryRecallQuery query = MemoryRecallQuery.from("Keep source paths", "KEEP SOURCE PATHS", "Keep source paths");
        assertEquals(1, query.segments().size());
        assertEquals("correction", query.segments().get(0).origin);
        try { query.segments().clear(); fail("immutable"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void slicingNeverCreatesAnUnpairedSurrogate() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 500; i++) longText.append("限制\uD83D\uDD12");
        longText.append("保留最后限制");
        MemoryRecallQuery query = MemoryRecallQuery.from(longText.toString());
        Set<String> unique = new HashSet<String>();
        for (MemoryRecallQuery.Segment s : query.segments()) {
            assertTrue(unique.add(LocalMemoryIndex.normalize(s.text)));
            for (int i = 0; i < s.text.length(); i++) {
                char c = s.text.charAt(i);
                if (Character.isHighSurrogate(c)) assertTrue(++i < s.text.length() && Character.isLowSurrogate(s.text.charAt(i)));
                else assertFalse(Character.isLowSurrogate(c));
            }
        }
        assertContains(query, "保留最后限制");
    }

    private static String longInput(String head, String tail) {
        StringBuilder text = new StringBuilder(head).append('；');
        for (int i = 0; i < 1000; i++) text.append("已有说明需要完整审查，");
        return text.append(tail).toString();
    }

    private static void assertContains(MemoryRecallQuery query, String text) {
        for (MemoryRecallQuery.Segment s : query.segments()) if (s.text.contains(text)) return;
        fail("missing query text: " + text);
    }
}
