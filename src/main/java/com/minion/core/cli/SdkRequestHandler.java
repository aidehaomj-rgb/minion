package com.minion.core.cli;

import com.google.gson.JsonObject;

/**
 * §12.2 SDK 二期控制请求处理器（宿主→Minion）：subtype → 处理器；返回成功响应体；
 * 抛 {@link SdkRequestError}（code+message → control_response error）。处理须幂等、
 * 有界（SdkDuplex 统一 30s 超时）且不得绕过 StopGate/TaskValidation/权限管线——
 * 需要权限的操作走 ConfirmGate.evaluatePolicy / §10.9 TrustGate。
 */
public interface SdkRequestHandler {

    JsonObject handle(JsonObject body) throws SdkRequestError;

    /** 带错误码的失败（映射 control_response error） */
    final class SdkRequestError extends Exception {
        public final String code;
        public SdkRequestError(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
