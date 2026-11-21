package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

/** §1.5 预算标记解析：行首/行尾 +Nk/+Nm、用/花 N k tokens、无效输入返回 null */
public class TokenBudgetTest {

    @Test public void parsesLineMarks() {
        assertEquals(Long.valueOf(500_000L), TokenBudget.parse("+500k\n做数据分析"));
        assertEquals(Long.valueOf(2_000_000L), TokenBudget.parse("整理报告\n+2M"));
        assertEquals(Long.valueOf(3000L), TokenBudget.parse("+3K"));
        assertEquals(Long.valueOf(1200L), TokenBudget.parse("  +1200  "));
    }

    @Test public void parsesPhraseForms() {
        assertEquals(Long.valueOf(50_000L), TokenBudget.parse("用 50k tokens 完成重构"));
        assertEquals(Long.valueOf(1_000_000L), TokenBudget.parse("花 1m tokens"));
        assertEquals(Long.valueOf(800L), TokenBudget.parse("这轮用800tokens跑完"));
    }

    @Test public void noBudgetReturnsNull() {
        assertNull(TokenBudget.parse("正常任务指令"));
        assertNull(TokenBudget.parse("+abc"));
        assertNull(TokenBudget.parse("加500块预算"));   // 非 token 句式
        assertNull(TokenBudget.parse(null));
        assertNull(TokenBudget.parse(""));
    }
}
