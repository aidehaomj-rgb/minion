package com.minion.core.security.permission;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 权限引擎的调用上下文（开发说明 v2.4 §4.1）。
 *
 * <p>不可变快照：模式、工具自检结果（§2.1 四态，未接线时恒 PASSTHROUGH）、
 * 敏感路径命中（§4.2）、文件类工具的目标路径与工作区根（用于 acceptEdits 自动放行；
 * workspaceRoot 为 null 时 fail-closed，不自动放行任何写入）。
 */
public final class PermissionContext {

    /** 权限模式；BYPASS 仅在第 9 步放行，DONT_ASK 把任何 ask 收紧为 deny */
    public enum Mode { DEFAULT, ACCEPT_EDITS, PLAN, BYPASS, DONT_ASK }

    /** 工具自检四态；PASSTHROUGH 表示工具无额外决定，不等于 allow */
    public enum ToolCheck { ALLOW, DENY, ASK, PASSTHROUGH }

    public final Mode mode;
    public final ToolCheck toolCheck;
    public final boolean requiresUserInteraction;
    public final boolean safetyCheckHit;
    /** 文件类工具目标路径（原路径与 toRealPath 结果；规则同时检查两者） */
    public final Path targetPath, realTargetPath;
    /** 工作区根；acceptEdits 仅在目标位于其内时自动放行 */
    public final Path workspaceRoot;

    public PermissionContext(Mode mode, ToolCheck toolCheck, boolean requiresUserInteraction,
                             boolean safetyCheckHit, Path targetPath, Path realTargetPath, Path workspaceRoot) {
        this.mode = mode;
        this.toolCheck = toolCheck;
        this.requiresUserInteraction = requiresUserInteraction;
        this.safetyCheckHit = safetyCheckHit;
        this.targetPath = targetPath;
        this.realTargetPath = realTargetPath;
        this.workspaceRoot = workspaceRoot;
    }

    /** 最小上下文：指定模式，其余全保守（无自检/无敏感命中/无路径） */
    public static PermissionContext of(Mode mode) {
        return new PermissionContext(mode, ToolCheck.PASSTHROUGH, false, false, null, null, null);
    }

    /** 旧配置值到新模式空间的映射（read-only→PLAN、workspace-write→ACCEPT_EDITS、full→BYPASS；未知回落 DEFAULT） */
    public static Mode mapLegacyMode(String raw) {
        if (raw == null) return Mode.DEFAULT;
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("read-only") || v.equals("plan")) return Mode.PLAN;
        if (v.equals("workspace-write") || v.equals("acceptEdits") || v.equals("accept-edits")) return Mode.ACCEPT_EDITS;
        if (v.equals("full") || v.equals("bypassPermissions") || v.equals("bypass")) return Mode.BYPASS;
        if (v.equals("dontask") || v.equals("dont-ask")) return Mode.DONT_ASK;
        if (v.equals("default")) return Mode.DEFAULT;
        return Mode.DEFAULT;
    }

    /** acceptEdits 的自动放行条件：文件类目标在工作区内（整文件级 startsWith，realpath 优先） */
    boolean insideWorkspace() {
        if (workspaceRoot == null || targetPath == null) return false;
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path probe = realTargetPath != null ? realTargetPath.toAbsolutePath().normalize()
                : targetPath.toAbsolutePath().normalize();
        return probe.startsWith(root);
    }

    /** 生成决策审计用的简明描述 */
    public String describe() {
        List<String> parts = new ArrayList<String>();
        parts.add("mode=" + mode);
        if (toolCheck != ToolCheck.PASSTHROUGH) parts.add("toolCheck=" + toolCheck);
        if (requiresUserInteraction) parts.add("requiresUserInteraction");
        if (safetyCheckHit) parts.add("safetyCheck");
        if (targetPath != null) parts.add("target=" + targetPath);
        return Objects.toString(parts);
    }
}
