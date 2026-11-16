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

    public final long initialDelayMs;
    public final long incrementMs;
    public final long maxDelayMs;
    public final long maxTotalMs;
    /** >1 时按指数退避 initial×multiplier^(attempt-1)；=1 时维持线性 initial+increment×(attempt-1) */
    public final double multiplier;
    /** nextDelayMs 在基础延迟上施加 ±jitterRatio 的随机抖动；0=无抖动 */
    public final double jitterRatio;

    public RetryPolicy(long initialDelayMs, long incrementMs, long maxDelayMs, long maxTotalMs) {
        this(initialDelayMs, incrementMs, maxDelayMs, maxTotalMs, 1.0, 0.0);
    }

    public RetryPolicy(long initialDelayMs, long incrementMs, long maxDelayMs, long maxTotalMs,
                       double multiplier, double jitterRatio) {
        this.initialDelayMs = initialDelayMs;
        this.incrementMs = incrementMs;
        this.maxDelayMs = maxDelayMs;
        this.maxTotalMs = maxTotalMs;
        this.multiplier = multiplier;
        this.jitterRatio = jitterRatio;
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