package com.minion.core.llm;

import java.util.Locale;

/** 仅给出恢复类别，不自动重放、换模型、删图或修改工具消息配对。 */
public final class LlmRecovery {
    private LlmRecovery() { }
    public enum Category { AUTH, RATE_LIMIT, CONNECTION, TIMEOUT, IMAGE_REJECTED, CONTEXT_LIMIT,
        PARAMETER_UNSUPPORTED, FORMAT, TRUNCATED, CANCELLED, BAD_REQUEST, SERVER, OTHER }
    public enum Action { CHECK_CONFIGURATION, RETRY_WITH_BUDGET, CHANGE_REQUEST, KEEP_PARTIAL_AND_REPAIR, STOP }

    public static final class Failure extends LlmException {
        public final Category category;
        public Failure(Category category, Type type, String message, boolean retryable, int code, String body) {
            super(type, message, retryable, code, body);
            this.category = category;
        }
    }

    public static Category classify(LlmException e) {
        if (e instanceof Failure) return ((Failure) e).category;
        String detail = (String.valueOf(e.body) + " " + e.getMessage()).toLowerCase(Locale.ROOT);
        if (e.type == LlmException.Type.AUTH) return Category.AUTH;
        if (e.type == LlmException.Type.RATE_LIMIT) return Category.RATE_LIMIT;
        if (e.type == LlmException.Type.TIMEOUT) return Category.TIMEOUT;
        if (e.type == LlmException.Type.NETWORK) return Category.CONNECTION;
        if (e.httpCode == 400 || e.httpCode == 413 || e.httpCode == 422) {
            if ((detail.contains("image") || detail.contains("vision") || detail.contains("图片"))
                    && (detail.contains("unsupported") || detail.contains("not support") || detail.contains("不支持")
                    || detail.contains("invalid") || detail.contains("not allowed") || detail.contains("only text")))
                return Category.IMAGE_REJECTED;
            if (detail.contains("context length") || detail.contains("context_length")
                    || detail.contains("maximum context") || detail.contains("上下文超")) return Category.CONTEXT_LIMIT;
            if ((detail.contains("response_format") || detail.contains("tool_choice") || detail.contains("stream"))
                    && (detail.contains("unsupported") || detail.contains("not support") || detail.contains("unknown")
                    || detail.contains("unrecognized") || detail.contains("unrecognised") || detail.contains("不支持")))
                return Category.PARAMETER_UNSUPPORTED;
            return Category.BAD_REQUEST;
        }
        return e.httpCode >= 500 ? Category.SERVER : Category.OTHER;
    }

    public static Action action(Category c) {
        switch (c) {
            case AUTH: return Action.CHECK_CONFIGURATION;
            case RATE_LIMIT: case CONNECTION: case TIMEOUT: case SERVER: return Action.RETRY_WITH_BUDGET;
            case IMAGE_REJECTED: case CONTEXT_LIMIT: case PARAMETER_UNSUPPORTED: case BAD_REQUEST: return Action.CHANGE_REQUEST;
            case FORMAT: case TRUNCATED: return Action.KEEP_PARTIAL_AND_REPAIR;
            default: return Action.STOP;
        }
    }

    /** DNS 等永久连接故障须保留异常上的 retryable=false，不能只按大类盲重试。 */
    public static Action action(LlmException error) {
        Category c=classify(error);Action proposed=action(c);
        if(proposed==Action.RETRY_WITH_BUDGET&&!error.retryable)
            return c==Category.CONNECTION?Action.CHECK_CONFIGURATION:Action.STOP;
        return proposed;
    }

    public static Failure format() {
        return new Failure(Category.FORMAT, LlmException.Type.BAD_REQUEST,
                "模型流式响应格式不合法；请检查接口协议，不应原样无限重试", false, 0, null);
    }
}
