package com.minion.core.bridge;

import com.minion.core.bridge.IdeBridge.ToolApproval;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * §12.3 IDE 桥接确认通道（2026-11-23 接线；此前 IdeBridge.canUseTool 没有任何调用方）。
 * ConfirmGate 的判定不变，只在所在工作区有已连接的 IDE 时把确认挪到 IDE 里；其余情况与接线前完全一致。
 */
public class IdeConfirmUiTest {
    /** 假 IDE 通道：connected=false 表示该工作区没有已连接的 IDE */
    private static final class FakeChannel implements IdeConfirmUi.Channel {
        boolean connected;
        ToolApproval response = ToolApproval.failed("未设置");
        Runnable onApprove;
        final List<String> asked = new ArrayList<String>();

        @Override public boolean connected() { return connected; }

        @Override public ToolApproval approve(String toolLabel, String summary, long timeoutMillis) {
            assertEquals(IdeConfirmUi.TOOL_LABEL, toolLabel);
            assertTrue(timeoutMillis > 0);
            asked.add(summary);
            if (onApprove != null) onApprove.run();
            return response;
        }
    }

    private final FakeChannel ide = new FakeChannel();
    private final List<String> localAsks = new ArrayList<String>();
    private volatile ConfirmUi.Decision localDecision = ConfirmUi.Decision.REJECT;
    private final ConfirmUi local = new ConfirmUi() {
        @Override public ConfirmUi.Decision ask(String message) { localAsks.add(message); return localDecision; }
    };
    private final IdeConfirmUi ui = new IdeConfirmUi(local, () -> ide.connected ? ide : null);

    /** 没有 IDE：一律问本地确认框，本地能给的四种决策原样透传（与接线前完全一致） */
    @Test public void withoutConnectedIdeEverythingGoesToLocalDialog() {
        assertEquals(ConfirmUi.Decision.REJECT, ui.ask("! 高危操作 Bash → rm -rf build"));
        localDecision = ConfirmUi.Decision.APPROVE_WHITELIST;
        assertEquals(ConfirmUi.Decision.APPROVE_WHITELIST, ui.ask("! 越界写入 Write → C:/Windows/x"));
        assertEquals(2, localAsks.size());
        assertTrue(ide.asked.isEmpty());
    }

    /** IDE 已连接：确认在 IDE 里完成，本地确认框不被打扰 */
    @Test public void connectedIdeAnswersInsteadOfLocalDialog() {
        ide.connected = true;
        ide.response = ToolApproval.allow("用户在 VS Code 中点击允许");
        assertEquals(ConfirmUi.Decision.APPROVE, ui.ask("! 高危操作 Bash → rm -rf build"));
        assertEquals(Collections.singletonList("! 高危操作 Bash → rm -rf build"), ide.asked);
        assertTrue("IDE 已给出结论后不应再问本地", localAsks.isEmpty());
    }

    /** 通道故障（未响应/断线/畸形响应）不算用户拒绝：回落本地确认框；两条通道都不会自动放行 */
    @Test public void channelFailureFallsBackToLocalDialog() {
        ide.connected = true;
        ide.response = ToolApproval.failed("IDE 权限回调失败（fail-closed 拒绝）: IDE 请求超时 (can_use_tool)");
        localDecision = ConfirmUi.Decision.REJECT;   // 通道坏了也不能悄悄放行
        assertEquals(ConfirmUi.Decision.REJECT, ui.ask("! 高危操作 Bash → rm -rf build"));
        assertEquals(Collections.singletonList("! 高危操作 Bash → rm -rf build"), localAsks);
    }

    /** IDE 明确拒绝：就是拒绝，不再回头问本地 */
    @Test public void explicitIdeDenialIsFinal() {
        ide.connected = true;
        ide.response = ToolApproval.deny("用户在 VS Code 中拒绝或取消");
        assertEquals(ConfirmUi.Decision.REJECT, ui.ask("! 高危操作 Bash → rm -rf build"));
        assertTrue(localAsks.isEmpty());
    }

    /** 结构化确认：没有 IDE 时本地富卡片确认照常；IDE 端只有纯文本的允许/拒绝 */
    @Test public void detailedConfirmationIsPlainYesNoOnTheIdeSide() {
        assertEquals(ConfirmUi.Decision.REJECT, ui.askDetailed(null, "! 高危操作 Bash → rm -rf build"));
        assertEquals(1, localAsks.size());
        assertTrue(ide.asked.isEmpty());

        ide.connected = true;
        ide.response = ToolApproval.allow("ok");
        assertEquals(ConfirmUi.Decision.APPROVE, ui.askDetailed(null, "! 高危操作 Bash → del /s"));
        assertEquals(1, ide.asked.size());
        assertEquals("IDE 的允许只放行这一次，不会变成“写规则/本会话不再询问”", 1, localAsks.size());
    }

    /** 等待 IDE 确认时回合被中断：直接拒绝，不要再弹本地确认框 */
    @Test public void interruptedWaitRejectsWithoutLocalDialog() {
        ide.connected = true;
        ide.response = ToolApproval.failed("IDE 权限回调失败（fail-closed 拒绝）: IDE 请求被中断 (can_use_tool)");
        ide.onApprove = () -> Thread.currentThread().interrupt();
        assertEquals(ConfirmUi.Decision.REJECT, ui.ask("! 高危操作 Bash → rm -rf build"));
        assertTrue(localAsks.isEmpty());
        assertTrue(Thread.interrupted());   // 清掉测试线程的中断标志
    }

    @Test public void missingBridgeMeansNoChannel() {
        assertNull(IdeConfirmUi.channel(null));
    }
}
