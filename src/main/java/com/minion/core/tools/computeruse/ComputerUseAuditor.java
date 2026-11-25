package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.tools.PermissionCheck;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.ValidationResult;

/** 三批 C5：computeruse 审计包装器。每次执行记 DiagnosticLog 结构化条目
 *  （tool/action/参数摘要/前台窗口标题）；maxActionsPerSession>0 时超限拒绝（默认 0=不限）。
 *  全部分类/校验/权限元数据原样委托——ConfirmGate 与权限引擎看到的仍是原工具，零收紧。 */
public final class ComputerUseAuditor implements Tool {
    private final Tool delegate;
    private final ComputerUseConfig config;

    public ComputerUseAuditor(Tool delegate, ComputerUseConfig config) {
        this.delegate = delegate;
        this.config = config;
    }

    @Override public String name() { return delegate.name(); }
    @Override public String description() { return delegate.description(); }
    @Override public JsonObject schema() { return delegate.schema(); }
    @Override public boolean isHighRisk(JsonObject args) { return delegate.isHighRisk(args); }
    @Override public boolean isReadOnly(JsonObject args) { return delegate.isReadOnly(args); }
    @Override public boolean isConcurrencySafe(JsonObject args) { return delegate.isConcurrencySafe(args); }
    @Override public boolean isDestructive(JsonObject args) { return delegate.isDestructive(args); }
    @Override public boolean isEnabled() { return delegate.isEnabled(); }
    @Override public ValidationResult validateInput(JsonObject args) { return delegate.validateInput(args); }
    @Override public PermissionCheck checkPermissions(JsonObject args,
            com.minion.core.security.permission.PermissionContext ctx) { return delegate.checkPermissions(args, ctx); }
    @Override public int maxResultSizeChars() { return delegate.maxResultSizeChars(); }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = args != null && args.has("action") && args.get("action").isJsonPrimitive()
                ? args.get("action").getAsString() : "";
        int max = config.maxActionsPerSessionSafe();
        if (max > 0 && config.actionCounter().incrementAndGet() > max) {
            DiagnosticLog.tool(name(), 0, false, "action=" + action + " | rejected=action_limit(" + max + ")");
            return ToolResult.error("computeruse 动作已达上限（maxActionsPerSession=" + max
                    + "）：累计已执行 " + max + " 次计算机使用动作，本次未执行。"
                    + "如需继续，请在 tools.json 调大 computeruse.maxActionsPerSession 或新建会话");
        }
        long start = System.currentTimeMillis();
        try {
            ToolResult r = delegate.execute(args);
            DiagnosticLog.tool(name(), System.currentTimeMillis() - start, r.ok,
                    "action=" + action + " | args=" + argsSummary(args) + " | 前台=" + Win32Gui.foregroundTitle());
            return r;
        } catch (Exception e) {
            DiagnosticLog.tool(name(), System.currentTimeMillis() - start, false,
                    "action=" + action + " | exception=" + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + " | 前台=" + Win32Gui.foregroundTitle());
            throw e;
        }
    }

    /** 参数摘要：压成单行并预截断，给前台标题留出审计行空间 */
    private static String argsSummary(JsonObject args) {
        if (args == null) return "{}";
        String s = args.toString().replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}