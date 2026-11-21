package com.minion.core.tools;

/**
 * 工具输入校验结果（v2.4 §2.1）：validateInput 在权限检查<b>之前</b>执行；
 * ok=false 时调度侧拒绝执行并回传 message/errorCode，不进入权限管线。
 */
public final class ValidationResult {
    public final boolean ok;
    public final String message;
    public final String errorCode;

    private ValidationResult(boolean ok, String message, String errorCode) {
        this.ok = ok;
        this.message = message;
        this.errorCode = errorCode;
    }

    public static ValidationResult ok() { return new ValidationResult(true, null, null); }

    public static ValidationResult invalid(String message) {
        return new ValidationResult(false, message, "invalid_input");
    }

    public static ValidationResult invalid(String message, String errorCode) {
        return new ValidationResult(false, message, errorCode);
    }
}
