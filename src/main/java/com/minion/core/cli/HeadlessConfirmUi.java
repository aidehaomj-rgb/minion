package com.minion.core.cli;

/**
 * Headless 确认 UI（§12 协议要求）：无 JavaFX——ask 一律拒绝（headless 场景 ask→deny，
 * 规格 §4.7 明确）；不弹任何对话框。--dangerously-skip-permissions 时全放行。
 */
public final class HeadlessConfirmUi implements com.minion.core.tools.confirm.ConfirmUi {
    private final boolean bypassAll;

    /** @param bypassAll true=--dangerously-skip-permissions（全放行）；false=ask→deny */
    public HeadlessConfirmUi(boolean bypassAll) { this.bypassAll = bypassAll; }

    @Override public Decision ask(String message) {
        return bypassAll ? Decision.APPROVE : Decision.REJECT;
    }
}
