package com.minion.core.agent;

import java.util.concurrent.ThreadLocalRandom;

/** 瞬时错误长重试策略：指数退避 + 抖动 + 墙钟总时长上限（纯算法，无依赖）。
 *  默认：5s 起步、×2 递增、60s 封顶、±20% 抖动、墙钟 20 分钟——内网模型资源差场景
 *  （429 限流 / 500 服务端报错 / 502 网关报错 / 网络超时 / 可恢复网络错误）。
 *  指数退避避免固定间隔持续撞限流窗口，抖动避免多会话并发重试同步成脉冲。
 *  旧线性构造器（multiplier=1）语义保留，测试可构造小参数实例覆写。
 *  maxTotalMs 的计时口径由调用方决定，本类累计的是
 *  "进入重试态以来的真实时间"（含每次请求自身耗时），非纯等待时长。 */
public class RetryPolicy {

    /** 默认瞬时错误策略：5s 起步指数退避（×2，60s 封顶，±20% 抖动），墙钟总时长 20 分钟 */
    public static RetryPolicy transientErrors() {
        return new RetryPolicy(5000, 0, 60000, 20 * 60 * 1000L, 2.0, 0.2);
    }

    /**
     * cc 复刻策略（v2.4 §1.2，retry.profile=cc）：base×2^(n-1)、capMs 封顶、0–25% 抖动、
     * 最大 maxAttempts 次；服务端 Retry-After 由调用方与策略延迟取大值（可绕过封顶）。
     * 参数默认 500ms/32s/10 次，可经 retry.baseMs/retry.capMs/retry.max 覆盖。
     */
    public static RetryPolicy ccProfile(int maxAttempts, long baseMs, long capMs) {
        return new RetryPolicy(baseMs, 0, capMs, 20 * 60 * 1000L, 2.0, 0.25, maxAttempts);
    }

    /** 按 Config 选择 profile：retry.profile=cc 用复刻参数；legacy/未知值保持现状默认 */
    public static RetryPolicy fromProfile(com.minion.core.config.Config config) {
        if (config != null && "cc".equalsIgnoreCase(config.get("retry.profile", "legacy"))) {
            int max = intOf(config.get("retry.max", "10"), 10);
            long base = longOf(config.get("retry.baseMs", "500"), 500L);
            long cap = longOf(config.get("retry.capMs", "32000"), 32000L);
            return ccProfile(Math.max(1, max), Math.max(1, base), Math.max(base, cap));
        }
        return transientErrors();
    }

    private static int intOf(String v, int fallback) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; }
    }

    private static long longOf(String v, long fallback) {
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return fallback; }
    }

    public final long initialDelayMs;
    public final long incrementMs;
    public final long maxDelayMs;
    public final long maxTotalMs;
    /** >1 时按指数退避 initial×multiplier^(attempt-1)；=1 时维持线性 initial+increment×(attempt-1) */
    public final double multiplier;
    /** nextDelayMs 在基础延迟上施加 ±jitterRatio 的随机抖动；0=无抖动 */
    public final double jitterRatio;
    /** 最大重试次数（0=不限，仅受墙钟约束）。legacy 恒 0；cc=10 */
    public final int maxAttempts;

    public RetryPolicy(long initialDelayMs, long incrementMs, long maxDelayMs, long maxTotalMs) {
        this(initialDelayMs, incrementMs, maxDelayMs, maxTotalMs, 1.0, 0.0);
    }

    public RetryPolicy(long initialDelayMs, long incrementMs, long maxDelayMs, long maxTotalMs,
                       double multiplier, double jitterRatio) {
        this(initialDelayMs, incrementMs, maxDelayMs, maxTotalMs, multiplier, jitterRatio, 0);
    }

    public RetryPolicy(long initialDelayMs, long incrementMs, long maxDelayMs, long maxTotalMs,
                       double multiplier, double jitterRatio, int maxAttempts) {
        this.initialDelayMs = initialDelayMs;
        this.incrementMs = incrementMs;
        this.maxDelayMs = maxDelayMs;
        this.maxTotalMs = maxTotalMs;
        this.multiplier = multiplier;
        this.jitterRatio = jitterRatio;
        this.maxAttempts = maxAttempts;
    }

    /** 重试次数是否已耗尽（maxAttempts>0 时生效；与墙钟上限独立） */
    public boolean attemptsExhausted(int attempts) {
        return maxAttempts > 0 && attempts >= maxAttempts;
    }

    /** 第 attempt 次重试（1 起）前的基础等待时长（无抖动），封顶 maxDelayMs */
    public long delayMs(int attempt) {
        double d = multiplier > 1.0
                ? initialDelayMs * Math.pow(multiplier, attempt - 1)
                : initialDelayMs + incrementMs * (attempt - 1);
        return (long) Math.min(d, maxDelayMs);
    }

    /** 实际等待时长：基础延迟 ±jitterRatio 抖动，不小于 0 */
    public long nextDelayMs(int attempt) {
        long base = delayMs(attempt);
        if (jitterRatio <= 0) return base;
        double j = ThreadLocalRandom.current().nextDouble(-jitterRatio, jitterRatio);
        return Math.max(0, Math.round(base * (1 + j)));
    }

    /** 距重试态起点已过去的真实时间是否达到总时长上限（入参由调用方以墙钟计算） */
    public boolean isExhausted(long elapsedMs) {
        return elapsedMs >= maxTotalMs;
    }
}