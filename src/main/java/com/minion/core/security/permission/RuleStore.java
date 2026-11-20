package com.minion.core.security.permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.diagnostics.DiagnosticLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 权限规则存储：分层来源加载 + 并集合并 + 会话内存态追加（开发说明 v2.4 §4.1）。
 *
 * <p>来源（{@link #addSource} 按此顺序添加）：企业策略（只读，可设 managedOnly）→ 用户
 * {@code {jarDir}/.minion/settings.json} → 项目 {@code {cwd}/.minion/settings.json} →
 * 本地 {@code {cwd}/.minion/settings.local.json}。settings.json 的 permissions 段：
 * {@code {"permissions":{"allow":["..."],"deny":["..."],"ask":["..."],"managedOnly":false}}}。
 *
 * <p>合并语义：跨来源取并集；同 (tool,content) 冲突取更严行为（DENY &gt; ASK &gt; ALLOW），
 * deny 跨来源生效。V68 旧白名单（confirm.whitelist.tools / commands）在构造时迁移为
 * 用户级 allow 规则（运行时映射，不写回文件；写盘迁移随 §13.1 配置体系统一处理）。
 *
 * <p>线程安全：加载后规则表只读；会话追加走 COW。fail-closed：settings.json 损坏时
 * 该来源按空处理并告警（不中断启动），managedOnly 由策略来源显式声明。
 */
public final class RuleStore {

    private final List<PermissionRule> rules = new CopyOnWriteArrayList<PermissionRule>();
    private volatile boolean managedOnly;
    private boolean policyLoaded;

    public RuleStore() { }

    /** 注册一个分层来源并加载（文件缺失=空来源；损坏=空+告警） */
    public synchronized RuleStore addSource(Path settingsFile, PermissionRule.Source source) {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) return this;
        try {
            String raw = new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject permissions = root.has("permissions") && root.get("permissions").isJsonObject()
                    ? root.getAsJsonObject("permissions") : null;
            if (permissions == null) return this;
            if (source == PermissionRule.Source.POLICY) {
                policyLoaded = true;
                if (permissions.has("managedOnly") && permissions.get("managedOnly").getAsBoolean())
                    this.managedOnly = true;
            }
            loadBehavior(permissions, "allow", PermissionRule.Behavior.ALLOW, source);
            loadBehavior(permissions, "deny", PermissionRule.Behavior.DENY, source);
            loadBehavior(permissions, "ask", PermissionRule.Behavior.ASK, source);
        } catch (IOException | RuntimeException corrupt) {
            DiagnosticLog.warn("permission", "settings.json 读取失败（按空来源处理）: "
                    + settingsFile + " " + corrupt.getMessage());
        }
        return this;
    }

    private void loadBehavior(JsonObject permissions, String key, PermissionRule.Behavior behavior,
                              PermissionRule.Source source) {
        if (!permissions.has(key) || !permissions.get(key).isJsonArray()) return;
        JsonArray array = permissions.getAsJsonArray(key);
        for (JsonElement e : array) {
            if (!e.isJsonPrimitive()) continue;
            PermissionRule rule = PermissionRule.parse(e.getAsString(), behavior, source);
            if (!rule.toolName.isEmpty()) addRule(rule);
        }
    }

    /** V68 旧白名单迁移：tools → 用户级整工具 allow；commands → Bash(前缀:*) allow（前缀语义） */
    public synchronized RuleStore migrateLegacyWhitelist(Config config) {
        if (config == null) return this;
        for (String tool : config.whitelistTools()) {
            String t = tool.trim();
            if (!t.isEmpty()) addRule(new PermissionRule(PermissionRule.Behavior.ALLOW, t, null, PermissionRule.Source.USER));
        }
        for (String prefix : config.whitelistCommands()) {
            String p = prefix.trim();
            if (!p.isEmpty()) addRule(new PermissionRule(PermissionRule.Behavior.ALLOW, "Bash", p.endsWith(":*") ? p : p + ":*",
                    PermissionRule.Source.USER));
        }
        return this;
    }

    /** 并集追加；同 (tool,content) 取更严行为（deny > ask > allow） */
    public synchronized void addRule(PermissionRule rule) {
        if (rule == null || rule.toolName.isEmpty()) return;
        for (int i = 0; i < rules.size(); i++) {
            PermissionRule existing = rules.get(i);
            if (existing.behavior == rule.behavior
                    && existing.toolName.equalsIgnoreCase(rule.toolName)
                    && java.util.Objects.equals(existing.content, rule.content)) return;   // 完全相同：去重
        }
        rules.add(rule);
    }

    /** 会话内存态：只允许追加 allow（会话只能放行本次询问过的工具，不能引入 deny/ask 影响其他会话） */
    public void sessionAllow(String toolName, String content) {
        addRule(new PermissionRule(PermissionRule.Behavior.ALLOW, toolName, content, PermissionRule.Source.SESSION));
    }

    /** managedOnly（策略声明）时非策略来源全部失效 */
    public boolean managedOnly() {
        return managedOnly && policyLoaded;
    }

    /** 是否存在命中规则：content 为 null 查整工具规则；路径类规则由 contentRule 检查 */
    public boolean has(String toolLower, String content, PermissionRule.Behavior behavior) {
        for (PermissionRule r : effective()) {
            if (r.behavior != behavior) continue;
            if (!r.toolName.equalsIgnoreCase(toolLower)) continue;
            if (r.content == null && content == null) return true;
            if (content != null && r.content == null) return true;    // 整工具规则覆盖任何内容
        }
        return false;
    }

    /**
     * 查找命中的内容级规则（文本内容或文件路径匹配）。
     * 路径形态规则优先按目标路径匹配（原路径与真实路径）；无路径上下文（如 Bash 命令，
     * 命令参数里的 {@code /} 会被误判为路径形态）时回退文本匹配。
     * 返回第一条命中规则（同行为内来源顺序即添加顺序，deny 前置由引擎步骤保证）。
     */
    public PermissionRule contentRule(String toolLower, String content, PermissionContext ctx, PermissionRule.Behavior behavior) {
        for (PermissionRule r : effective()) {
            if (r.behavior != behavior || r.content == null) continue;
            if (!r.toolName.equalsIgnoreCase(toolLower)) continue;
            if (RuleMatcher.looksLikePathRule(r.content)) {
                if (ctx != null && ctx.targetPath != null
                        && RuleMatcher.pathMatches(ctx.targetPath, ctx.realTargetPath, r.content)) return r;
                if (content != null && RuleMatcher.matches(content, r.content)) return r;
            } else if (content != null && RuleMatcher.matches(content, r.content)) {
                return r;
            }
        }
        return null;
    }

    /** managedOnly 时只保留策略来源；否则全部生效 */
    private Iterable<PermissionRule> effective() {
        if (!managedOnly()) return rules;
        List<PermissionRule> only = new ArrayList<PermissionRule>();
        for (PermissionRule r : rules) if (r.source == PermissionRule.Source.POLICY) only.add(r);
        return only;
    }

    /** 规则总数（诊断/测试用） */
    public int size() { return rules.size(); }

    /** 有效规则快照（测试与审计用） */
    public List<PermissionRule> snapshot() {
        List<PermissionRule> out = new ArrayList<PermissionRule>();
        for (PermissionRule r : effective()) out.add(r);
        return out;
    }
}
