package com.minion.core.bridge;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.PermissionExplainer;

import java.util.function.Supplier;

/**
 * §12.3 IDE 桥接确认通道：所在工作区有已连接的 IDE 时，需要用户确认的操作改在 IDE 里弹确认
 * （ConfirmGate 的判定逻辑不变，只换提问的位置）；未连接或通道故障（未响应/断线）时回落本地确认框。
 * 两条通道都不会自动放行。
 *
 * <p>只支持“允许/拒绝”：IDE 端没有“本会话不再询问/写入规则”的选项，这类决策仍只能在本地确认框里做。
 */
public final class IdeConfirmUi implements ConfirmUi {
    /** 发给 IDE 的请求里显示的操作名（ConfirmGate 只给确认文案，没有结构化的工具名/参数） */
    static final String TOOL_LABEL = "权限确认";
    /** 等用户在 IDE 里点确认的最长时间；超时按通道故障回落本地确认框 */
    static final long CONFIRM_TIMEOUT_MS = 300_000L;

    /** 提问通道（生产环境是已连接的 IDE；测试可替换） */
    public interface Channel {
        boolean connected();

        IdeBridge.ToolApproval approve(String toolLabel, String summary, long timeoutMillis);
    }

    /** 工作区桥接 → 提问通道；bridge 为 null（该工作区没启动桥接）时返回 null */
    public static Channel channel(IdeBridge bridge) {
        if (bridge == null) return null;
        return new Channel() {
            @Override public boolean connected() { return bridge.isConnected(); }

            @Override public IdeBridge.ToolApproval approve(String toolLabel, String summary, long timeoutMillis) {
                return bridge.canUseTool(toolLabel, null, summary, timeoutMillis);
            }
        };
    }

    private final ConfirmUi local;
    /** 每次提问时取当前工作区的通道：桥接可能在会话创建之后才启动/关闭 */
    private final Supplier<Channel> workspaceChannel;
    private final long timeoutMillis;

    public IdeConfirmUi(ConfirmUi local, Supplier<Channel> workspaceChannel) {
        this(local, workspaceChannel, CONFIRM_TIMEOUT_MS);
    }

    IdeConfirmUi(ConfirmUi local, Supplier<Channel> workspaceChannel, long timeoutMillis) {
        this.local = local;
        this.workspaceChannel = workspaceChannel;
        this.timeoutMillis = timeoutMillis;
    }

    @Override public Decision ask(String message) {
        Channel channel = connectedChannel();
        return channel == null ? local.ask(message) : askIde(channel, message, () -> local.ask(message));
    }

    @Override public Decision askDetailed(PermissionExplainer.Explanation explanation, String message) {
        Channel channel = connectedChannel();
        return channel == null
                ? local.askDetailed(explanation, message)
                : askIde(channel, message, () -> local.askDetailed(explanation, message));
    }

    /** 本地通道的“允许并写规则”仍可能带回编辑过的规则文本 */
    @Override public String editedRule() { return local.editedRule(); }

    private Channel connectedChannel() {
        Channel channel = workspaceChannel.get();
        return channel != null && channel.connected() ? channel : null;
    }

    private Decision askIde(Channel channel, String message, Supplier<Decision> fallback) {
        IdeBridge.ToolApproval approval = channel.approve(TOOL_LABEL, message, timeoutMillis);
        if (!approval.failed) return approval.allowed ? Decision.APPROVE : Decision.REJECT;
        // 回合正在被停止（等待确认时用户点了中断）：不要再弹本地确认框
        if (Thread.currentThread().isInterrupted()) return Decision.REJECT;
        return fallback.get();
    }
}
