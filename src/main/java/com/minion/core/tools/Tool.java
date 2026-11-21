package com.minion.core.tools;

import com.google.gson.JsonObject;

/**
 * 工具接口。v2.4 §2.1 扩展的元数据 default 方法全部 fail-closed：
 * 未声明视为非只读、不可并行、可能破坏；判定函数抛异常时调度侧同样按保守值处理。
 * isHighRisk 保留兼容：ConfirmGate 旧路径继续使用；新权限引擎将其映射为 ask 建议。
 */
public interface Tool {
    String name();
    String description();
    JsonObject schema();
    ToolResult execute(JsonObject args) throws Exception;
    default boolean isHighRisk(JsonObject args) { return false; }

    /** 未声明=视为非只读（fail-closed） */
    default boolean isReadOnly(JsonObject args) { return false; }

    /** 未声明=串行；批调度按此判定并行波次（§2.2） */
    default boolean isConcurrencySafe(JsonObject args) { return false; }

    /** 未知按可能破坏处理——只影响提示/审批文案，不直接拒绝 */
    default boolean isDestructive(JsonObject args) { return true; }

    /** 工具可用性（插件/配置禁用）；默认可用 */
    default boolean isEnabled() { return true; }

    /** 输入校验：在权限检查之前执行；失败直接拒绝，不进入权限管线 */
    default ValidationResult validateInput(JsonObject args) { return ValidationResult.ok(); }

    /** 权限自检四态（allow/deny/ask/passthrough）；passthrough 交权限引擎裁决 */
    default PermissionCheck checkPermissions(JsonObject args,
                                             com.minion.core.security.permission.PermissionContext ctx) {
        return PermissionCheck.passthrough();
    }

    /** 单次结果字符上限（超限由既有截断层处理） */
    default int maxResultSizeChars() { return 30000; }
}
