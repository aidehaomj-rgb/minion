package com.minion.core.llm;

import com.minion.core.diagnostics.DiagnosticLog;

import java.util.List;

/**
 * 侧路请求通道（开发说明 v2.4 §1.6）：压缩摘要、标题生成、记忆召回等辅助调用统一入口。
 *
 * <p>约束：最大重试 2 次（辅助请求不放大重试）；限流/过载类错误立即放弃（侧路不值得等）；
 * 任何失败只告警返回 null，<b>绝不中断主循环</b>。usage/成本由底层客户端既有统计路径计入
 * （CostTracker 的 side 标记随 §1.1 批量接线）。
 */
public final class SideQuery {

    public static final int MAX_RETRIES = 2;

    private SideQuery() { }

    /**
     * 执行一次侧路补全；purpose 仅用于诊断日志归因。
     * 返回 null = 侧路失败（调用方按各自降级路径处理，不得抛出）。
     */
    public static String complete(LlmClient llm, List<Message> messages, String systemPrompt, String purpose) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return llm.completeChat(messages, systemPrompt);
            } catch (LlmException e) {
                boolean giveUp = attempt == MAX_RETRIES || !isWorthRetry(e);
                if (giveUp) {
                    DiagnosticLog.warn("sidequery", purpose + " 侧路请求失败（第 " + attempt + " 次，放弃）: "
                            + e.getMessage());
                    return null;
                }
            } catch (RuntimeException unexpected) {
                DiagnosticLog.warn("sidequery", purpose + " 侧路请求异常（放弃）: " + unexpected.getMessage());
                return null;
            }
        }
        return null;   // 不可达
    }

    /** 限流/过载立即放弃：侧路请求没有等的价值（规格 §1.2 辅助请求不放大重试） */
    private static boolean isWorthRetry(LlmException e) {
        return e.type != LlmException.Type.RATE_LIMIT
                && e.httpCode != 529
                && e.type != LlmException.Type.AUTH;   // 401/403 直接配置错误，重试无意义
    }
}
