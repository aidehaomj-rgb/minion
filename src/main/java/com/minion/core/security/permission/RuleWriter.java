package com.minion.core.security.permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * v2.4 §10.3/§13.1 规则落盘：把用户批准的规则写进对应作用域 settings.json 的
 * permissions.allow/deny（读旧值→写唯一目标→原子落盘；保留文件内其他全部键）。
 * 作用域文件：project={workspace}/.minion/settings.json、local=settings.local.json、
 * user={jarDir}/.minion/settings.json（与 RuleStore/SettingsChain 路径口径一致）。
 * 企业策略层只读不写。写回的规则经 RuleStore 下次构建自然生效（不形成第二真源）。
 */
public final class RuleWriter {

    /** 作用域 → settings.json 路径（workspace 可空：project/local 需要工作空间） */
    public static Path scopeFile(Path workspaceRoot, Path jarDir, String scope) {
        if ("local".equals(scope))
            return workspaceRoot.resolve(".minion").resolve("settings.local.json");
        if ("user".equals(scope))
            return jarDir.resolve(".minion").resolve("settings.json");
        return workspaceRoot.resolve(".minion").resolve("settings.json");   // 默认 project
    }

    /** 追加 allow 规则（去重；文件/父目录不存在则创建；原子写） */
    public static void writeAllow(Path settingsFile, String ruleSpec) throws java.io.IOException {
        writeBehavior(settingsFile, ruleSpec, "allow");
    }

    public static void writeDeny(Path settingsFile, String ruleSpec) throws java.io.IOException {
        writeBehavior(settingsFile, ruleSpec, "deny");
    }

    private static void writeBehavior(Path settingsFile, String ruleSpec, String behavior)
            throws java.io.IOException {
        if (ruleSpec == null || ruleSpec.trim().isEmpty())
            throw new IllegalArgumentException("规则文本为空");
        // 解析校验：畸形规则直接拒绝落盘（UI 层用户输入兜底）
        PermissionRule parsed = PermissionRule.parse(ruleSpec, PermissionRule.Behavior.ALLOW,
                PermissionRule.Source.PROJECT);
        if (parsed.toolName.isEmpty())
            throw new IllegalArgumentException("规则无法解析: " + ruleSpec);
        JsonObject root = new JsonObject();
        Files.createDirectories(settingsFile.getParent());
        if (Files.isRegularFile(settingsFile)) {
            try {
                JsonElement existing = JsonParser.parseString(
                        new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8));
                if (existing.isJsonObject()) root = existing.getAsJsonObject();
            } catch (Exception corrupt) {
                throw new java.io.IOException("settings.json 无法解析（拒绝覆盖，先人工修复）: "
                        + corrupt.getMessage());
            }
        }
        JsonObject permissions = root.has("permissions") && root.get("permissions").isJsonObject()
                ? root.getAsJsonObject("permissions") : new JsonObject();
        JsonArray list = permissions.has(behavior) && permissions.get(behavior).isJsonArray()
                ? permissions.getAsJsonArray(behavior) : new JsonArray();
        for (JsonElement e : list)
            if (e.isJsonPrimitive() && e.getAsString().equals(ruleSpec)) return;   // 已存在
        list.add(ruleSpec);
        permissions.add(behavior, list);
        root.add("permissions", permissions);
        // 原子写：tmp + REPLACE_EXISTING（与 trust.json 同口径）
        Path tmp = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
        Files.write(tmp, root.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private RuleWriter() { }
}
