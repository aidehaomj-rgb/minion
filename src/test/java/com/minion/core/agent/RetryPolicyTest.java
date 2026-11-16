package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

public class RetryPolicyTest {

    @Test
    public void delay_linearIncrement_cappedAtMax() {
        RetryPolicy p = new RetryPolicy(2000, 2000, 10000, 1800000);
        assertEquals(2000, p.delayMs(1));
        assertEquals(4000, p.delayMs(2));
        assertEquals(6000, p.delayMs(3));
        assertEquals(8000, p.delayMs(4));
        assertEquals(10000, p.delayMs(5));  // 命中上限
        assertEquals(10000, p.delayMs(100)); // 封顶不再涨
    }

    @Test
    public void isExhausted_boundary() {
        RetryPolicy p = new RetryPolicy(2000, 2000, 10000, 1000);
        assertFalse(p.isExhausted(999));
        assertTrue(p.isExhausted(1000));
        assertTrue(p.isExhausted(1001));
    }

    @Test
    public void transientErrors_defaultParams() {
        RetryPolicy p = RetryPolicy.transientErrors();
        assertEquals(5000, p.initialDelayMs);
        assertEquals(60000, p.maxDelayMs);
        assertEquals(1200000L, p.maxTotalMs); // 20 分钟
        assertEquals(2.0, p.multiplier, 0.0001);
        assertEquals(0.2, p.jitterRatio, 0.0001);
    }

    @Test
    public void transientErrors_exponentialBackoff_cappedAt60s() {
        RetryPolicy p = RetryPolicy.transientErrors();
        assertEquals(5000, p.delayMs(1));
        assertEquals(10000, p.delayMs(2));
        assertEquals(20000, p.delayMs(3));
        assertEquals(40000, p.delayMs(4));
        assertEquals(60000, p.delayMs(5));  // 封顶
        assertEquals(60000, p.delayMs(100)); // 任意次数不超上限
    }

    @Test
    public void transientErrors_jitterWithinBounds() {
        RetryPolicy p = RetryPolicy.transientErrors();
        for (int attempt = 1; attempt <= 6; attempt++) {
            long base = p.delayMs(attempt);
            for (int i = 0; i < 50; i++) {
                long d = p.nextDelayMs(attempt);
                assertTrue("抖动下界: " + d, d >= Math.round(base * 0.8));
                assertTrue("抖动上界: " + d, d <= Math.round(base * 1.2));
            }
        }
    }

    @Test
    public void linearPolicy_nextDelayEqualsBase() {
        RetryPolicy p = new RetryPolicy(2000, 2000, 10000, 1800000);
        assertEquals(p.delayMs(3), p.nextDelayMs(3)); // 无抖动配置时恒等
    }
}
