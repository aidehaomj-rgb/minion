package com.minion.core.tools;

/**
 * 工具权限自检四态（v2.4 §2.1）：allow/deny/ask/passthrough。
 * <b>passthrough ≠ allow</b>——工具不表态，交给权限引擎后续步骤裁决；
 * 四种结果在 §4.1 统一顺序中各有一个唯一定义位。
 */
public final class PermissionCheck {

    public enum Outcome { ALLOW, DENY, ASK, PASSTHROUGH }

    public final Outcome outcome;
    public final String reason;

    private PermissionCheck(Outcome outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason;
    }

    public static PermissionCheck allow() { return new PermissionCheck(Outcome.ALLOW, null); }
    public static PermissionCheck allow(String reason) { return new PermissionCheck(Outcome.ALLOW, reason); }
    public static PermissionCheck deny(String reason) { return new PermissionCheck(Outcome.DENY, reason); }
    public static PermissionCheck ask(String reason) { return new PermissionCheck(Outcome.ASK, reason); }
    public static PermissionCheck passthrough() { return new PermissionCheck(Outcome.PASSTHROUGH, null); }
}
