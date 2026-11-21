package com.minion.core.tools.confirm;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.security.permission.PermissionContext;
import com.minion.core.security.permission.PermissionEngine;
import com.minion.core.security.permission.PermissionRule;
import com.minion.core.security.permission.RuleStore;
import com.minion.core.tools.BashTool;
import com.minion.core.tools.DangerousCommands;
import com.minion.core.tools.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 高危操作确认门（开发说明 v2.4 §4.1）。
 *
 * <p>双引擎：{@code permission.engine.v2=true}（默认）时 check() 委托
 * {@link PermissionEngine} 的 13 步统一裁决——deny 前置、内容级 deny 免疫 bypass、
 * 模式映射（read-only→plan / workspace-write→acceptEdits / full→bypass）；
 * ask 结果走既有 ConfirmUi 交互（允许一次 / 写会话规则 / 本会话允许 / 拒绝）。
 * {@code false} 时完整保留 V68 旧管线（read-only 拒绝 → isHighRisk → bypass →
 * 白名单 → 弹窗），仅用于 legacy 对照排障。
 *
 * <p>规则来源（v2）：企业策略与用户层 settings.json（构造时惰性加载）→
 * 项目/本地层（{@link #setProjectSettings} 由 SessionManager 装配时注入）→
 * V68 旧白名单运行时迁移为用户级 allow。
 */
public class ConfirmGate {

    private final Config config;
    private final ConfirmUi ui;
    private boolean sessionBypass = false;          // legacy 全局放行 + v2 BYPASS 提升
    private volatile RuleStore ruleStore;           // v2 惰性构建
    private volatile Path projectSettings, localSettings;

    public ConfirmGate(Config config, ConfirmUi ui) {
        this.config = config;
        this.ui = ui;
    }

    /** §4.8 SSH TOFU 首次主机指纹确认：不走规则，直接问用户；APPROVE 系（一次/白名单/会话）均算信任 */
    public boolean askHostKeyTrust(String host, int port, String fingerprint) {
        ConfirmUi.Decision d = ui.ask("SSH 首次连接未知主机: " + host + ":" + port
                + "\n主机公钥指纹: " + fingerprint
                + "\n确认信任并记录到 known_hosts？（拒绝将不发送任何凭据）");
        return d == ConfirmUi.Decision.APPROVE || d == ConfirmUi.Decision.APPROVE_WHITELIST
                || d == ConfirmUi.Decision.APPROVE_SESSION;
    }

    /** §4.8：ssh.strictHostKey（默认 true）；false 时 SshExecutor 回退 StrictHostKeyChecking=no */
    public boolean sshTofuEnabled() { return config.sshStrictHostKey(); }

    /** 项目/本地层 settings.json（SessionManager 装配时按 workspace 注入；规则下次构建时生效） */
    public void setProjectSettings(Path projectSettings, Path localSettings) {
        this.projectSettings = projectSettings;
        this.localSettings = localSettings;
        this.ruleStore = null;   // 失效重建
    }

    /** 返回 true = 放行执行 */
    public synchronized boolean check(Tool tool, JsonObject args) {
        if (readOnlyDataWorkflowDeny(tool, args)) return false;
        if (config.permissionEngineV2()) return checkV2(tool, args);
        return checkLegacy(tool, args);
    }

    // ==================== V68 read-only 数据工作流前置（两种引擎共用） ====================

    private boolean readOnlyDataWorkflowDeny(Tool tool, JsonObject args) {
        if (!"read-only".equals(config.permissionMode())) return false;
        String action = args.has("action") && !args.get("action").isJsonNull()
                ? args.get("action").getAsString().toLowerCase(java.util.Locale.ROOT) : "";
        if ((tool instanceof com.minion.core.tools.data.ExcelTool && !com.minion.core.tools.data.ExcelTool.readOnlyAction(action))
                || (tool instanceof com.minion.core.tools.data.DocumentTool && !com.minion.core.tools.data.DocumentTool.readOnlyAction(action))
                || (tool instanceof com.minion.core.tools.data.ExcelTool && args.has("filter") && !args.get("filter").isJsonNull() && !args.get("filter").getAsString().trim().isEmpty())) {
            DiagnosticLog.info("permission", "DENY read-only data workflow " + tool.name() + "." + action);
            return true;
        }
        return false;
    }

    // ==================== v2：13 步裁决 + 交互 ====================

    private boolean checkV2(Tool tool, JsonObject args) {
        String content = contentOf(tool, args);
        Path target = pathOf(args);
        Path real = toReal(target);
        // §2.1 工具自检接线前的过渡映射：低危工具（isHighRisk=false）按自检 allow 放行（等价 V68
        // 不弹窗行为，经第 10 步生效、仍受 1-8 步 deny/ask/safetyCheck 约束）；高危工具走完整管线
        PermissionContext.ToolCheck tc = tool.isHighRisk(args)
                ? PermissionContext.ToolCheck.PASSTHROUGH : PermissionContext.ToolCheck.ALLOW;
        PermissionContext ctx = new PermissionContext(
                sessionBypass ? PermissionContext.Mode.BYPASS : PermissionContext.mapLegacyMode(config.permissionMode()),
                tc,
                false,                                         // requiresUserInteraction（§2.1 接线）
                com.minion.core.security.permission.SensitivePaths.hits(target)
                        || com.minion.core.security.permission.SensitivePaths.hits(real),   // §4.2 敏感路径（bypass 免疫）
                target, real, null);                           // workspaceRoot 未接线：acceptEdits fail-closed 不放行
        // MCP 整 server allow 规则（§3.7）：server 键形如 mcp:<server>，作为独立工具名域匹配
        if (tool instanceof com.minion.core.tools.mcp.McpProxyTool
                && rules().has(((com.minion.core.tools.mcp.McpProxyTool) tool).serverWhitelistKey(),
                        null, PermissionRule.Behavior.ALLOW)
                && !rules().has(tool.name().toLowerCase(java.util.Locale.ROOT), null, PermissionRule.Behavior.DENY)) {
            DiagnosticLog.info("permission", "ALLOW v2 mcp-server " + tool.name());
            return true;
        }
        PermissionEngine.Verdict v = new PermissionEngine().evaluate(tool.name(), content, rules(), ctx);
        if (v.allowed()) {
            DiagnosticLog.info("permission", "ALLOW v2 step" + v.step + " " + tool.name() + " " + v.reason);
            return true;
        }
        if (v.denied()) {
            DiagnosticLog.info("permission", "DENY v2 step" + v.step + " " + tool.name() + " " + v.reason);
            return false;
        }
        // ask：交互决策（拒绝理由回传见 §10.3 UI 增强）
        String detail = highRiskDetail(tool, args);
        ConfirmUi.Decision d = ui.ask("! 高危操作 " + detail);
        if (d == ConfirmUi.Decision.APPROVE) {
            DiagnosticLog.info("permission", "ALLOW once(v2) " + detail);
            return true;
        }
        if (d == ConfirmUi.Decision.REJECT) {
            DiagnosticLog.info("permission", "DENY user(v2) " + detail);
            return false;
        }
        if (d == ConfirmUi.Decision.APPROVE_WHITELIST) {
            rememberAllow(tool, args, content);
            return true;
        }
        if (d == ConfirmUi.Decision.APPROVE_SESSION) {
            rules().sessionAllow(tool.name(), null);   // 会话层整工具 allow（比 V68 全局 bypass 收紧）
            DiagnosticLog.info("permission", "ALLOW session(v2) " + detail);
            return true;
        }
        return false;
    }

    /** 用户选择"允许并写规则"：会话规则立即生效 + V68 旧白名单双写（写盘迁移随 §13.1） */
    private void rememberAllow(Tool tool, JsonObject args, String content) {
        if (tool instanceof BashTool && args.has("command")) {
            String prefix = DangerousCommands.firstToken(args.get("command").getAsString());
            rules().sessionAllow("Bash", prefix + ":*");
            config.appendWhitelist("confirm.whitelist.commands", prefix);
        } else {
            rules().sessionAllow(tool.name(), null);
            config.appendWhitelist("confirm.whitelist.tools", tool.name());
        }
    }

    private RuleStore rules() {
        RuleStore rs = ruleStore;
        if (rs == null) {
            synchronized (this) {
                if (ruleStore == null) {
                    rs = new RuleStore();
                    String programData = System.getenv("ProgramData");
                    if (programData != null)
                        rs.addSource(Paths.get(programData, "minion", "settings.json"), PermissionRule.Source.POLICY);
                    Path jarDir = config.jarDir();
                    if (jarDir != null)
                        rs.addSource(jarDir.resolve(".minion").resolve("settings.json"), PermissionRule.Source.USER);
                    if (projectSettings != null)
                        rs.addSource(projectSettings, PermissionRule.Source.PROJECT);
                    if (localSettings != null)
                        rs.addSource(localSettings, PermissionRule.Source.LOCAL);
                    rs.migrateLegacyWhitelist(config);
                    ruleStore = rs;
                }
                rs = ruleStore;
            }
        }
        return rs;
    }

    /** 工具级内容（Bash 命令全文；MCP 用 server 键前缀待 §4.1 命名统一后接入） */
    private static String contentOf(Tool tool, JsonObject args) {
        if (tool instanceof BashTool && args.has("command") && args.get("command").isJsonPrimitive())
            return args.get("command").getAsString();
        return null;
    }

    /** 文件类工具的目标路径（有 path 参数即认为文件类） */
    private static Path pathOf(JsonObject args) {
        if (args.has("path") && args.get("path").isJsonPrimitive()) {
            try { return Paths.get(args.get("path").getAsString()); }
            catch (RuntimeException invalid) { return null; }
        }
        return null;
    }

    private static Path toReal(Path path) {
        if (path == null) return null;
        try { return path.toRealPath(); } catch (Exception unavailable) { return null; }
    }

    // ==================== legacy：V68 原管线（permission.engine.v2=false 对照） ====================

    private boolean checkLegacy(Tool tool, JsonObject args) {
        if (!tool.isHighRisk(args)) return true;
        String mode = config.permissionMode();
        if ("read-only".equals(mode)) { DiagnosticLog.info("permission", "DENY read-only " + highRiskDetail(tool,args)); return false; }
        if ("full".equals(mode) || sessionBypass || config.confirmSkip()) { DiagnosticLog.info("permission", "ALLOW bypass " + highRiskDetail(tool,args)); return true; }
        if (isWhitelisted(tool, args)) { DiagnosticLog.info("permission", "ALLOW whitelist " + highRiskDetail(tool,args)); return true; }

        String detail = highRiskDetail(tool, args);
        // ⚠ 在 mintty 默认字体链中渲染为 ?，高危确认用 ASCII 的 !
        ConfirmUi.Decision d = ui.ask("! 高危操作 " + detail);
        if (d == ConfirmUi.Decision.APPROVE) { DiagnosticLog.info("permission", "ALLOW once " + detail); return true; }
        if (d == ConfirmUi.Decision.REJECT) { DiagnosticLog.info("permission", "DENY user " + detail); return false; }
        if (d == ConfirmUi.Decision.APPROVE_WHITELIST) {
            addToWhitelist(tool, args);
            return true;
        }
        if (d == ConfirmUi.Decision.APPROVE_SESSION) {
            sessionBypass = true;
            return true;
        }
        return false;
    }

    // ==================== 旁路审批（越界读写；§4.3 统一前保持 V68 语义） ====================

    /** 越界读审批：开关开或会话放行 → 直接放行；否则弹确认（Y 放行本次 / N 拒绝 / A/W 置位会话放行）。
     *  W 对越界读按会话放行处理、不落持久化白名单（与高危操作的白名单语义区分，YAGNI）。 */
    public synchronized boolean checkReadOutside(Tool tool, JsonObject args, String path) {
        return checkOutside(tool, args, path, "越界读取");
    }

    /** 越界写审批（截图等输出类工具）：与越界读同语义，仅弹窗文案区分，避免"越界读取"误导 */
    public synchronized boolean checkWriteOutside(Tool tool, JsonObject args, String path) {
        return checkOutside(tool, args, path, "越界写入");
    }

    /** 空间外写审批（Write/Edit 越界写专用）：
     *  开关关 → 直接拒绝（不弹框，无视会话放行/确认跳过/白名单）；
     *  开关开 → 高危放行链：会话放行/确认跳过/白名单命中即放行，否则弹框。
     *  与 checkWriteOutside 语义不同，勿混用。 */
    public synchronized boolean checkEscapeWrite(Tool tool, JsonObject args, String path) {
        if (!config.writeAllowOutside()) return false;
        if (sessionBypass || config.confirmSkip()) return true;
        if (isWhitelisted(tool, args)) return true;
        ConfirmUi.Decision d = ui.ask("! 越界写入 " + tool.name() + " → " + path);
        if (d == ConfirmUi.Decision.APPROVE) return true;
        if (d == ConfirmUi.Decision.REJECT) return false;
        sessionBypass = true;
        return true;
    }

    private synchronized boolean checkOutside(Tool tool, JsonObject args, String path, String label) {
        if ("full".equals(config.permissionMode()) || config.readAllowOutside() || sessionBypass) return true;
        if ("read-only".equals(config.permissionMode()) && "越界写入".equals(label)) { DiagnosticLog.info("permission", "DENY read-only outside-write " + path); return false; }
        String detail = tool.name() + " → " + path;
        ConfirmUi.Decision d = ui.ask("! " + label + " " + detail);
        if (d == ConfirmUi.Decision.APPROVE) return true;
        if (d == ConfirmUi.Decision.REJECT) return false;
        sessionBypass = true;
        return true;
    }

    // ==================== 共用辅助 ====================

    private boolean isWhitelisted(Tool tool, JsonObject args) {
        String toolName = tool.name().toLowerCase();
        if (config.whitelistTools().contains(toolName)) return true;
        // MCP 整 server 放行：白名单配置 mcp:<server>（v2.4 §3.7——免确认只认用户存过的 allow 规则）
        if (tool instanceof com.minion.core.tools.mcp.McpProxyTool
                && config.whitelistTools().contains(((com.minion.core.tools.mcp.McpProxyTool) tool).serverWhitelistKey()))
            return true;
        if (tool instanceof BashTool && args.has("command")) {
            return CommandWhitelist.matches(args.get("command").getAsString(), config.whitelistCommands());
        }
        return false;
    }

    private void addToWhitelist(Tool tool, JsonObject args) {
        if (tool instanceof BashTool && args.has("command")) {
            config.appendWhitelist("confirm.whitelist.commands",
                    DangerousCommands.firstToken(args.get("command").getAsString()));
        } else {
            config.appendWhitelist("confirm.whitelist.tools", tool.name());
        }
    }

    private String highRiskDetail(Tool tool, JsonObject args) {
        if (tool instanceof BashTool && args.has("command")) {
            return "Bash → " + args.get("command").getAsString();
        }
        if (args.has("path")) {
            return tool.name() + " → " + args.get("path").getAsString();
        }
        return tool.name();
    }

    /** 测试与诊断：当前规则快照 */
    public java.util.List<PermissionRule> ruleSnapshot() { return rules().snapshot(); }
}
