package com.minion.core.skills;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.PatternSyntaxException;

/**
 * §7.1 paths 条件激活：带 paths glob 的技能初始不入清单（conditionalSkills），
 * Read/Edit/Write 触碰匹配文件时激活进 dynamicSkills 并提示模型；同时从被操作文件
 * 目录向上动态发现嵌套 .minion/skills（gitignored 目录跳过——不可见目录的技能不生效）。
 *
 * <p>glob 采用 gitignore 风格子集（{@code PathMatcher glob:} 语法）：裸名匹配文件名任意
 * 段、含 / 锚定路径、* 与 ** 通配。线程安全：会话线程触碰、注册表读并发。
 */
public final class SkillPathActivation {

    private final List<Skill> conditional;
    private final List<Skill> activated = new CopyOnWriteArrayList<Skill>();

    public SkillPathActivation(List<Skill> conditionalSkills) {
        this.conditional = conditionalSkills == null
                ? Collections.<Skill>emptyList() : new ArrayList<Skill>(conditionalSkills);
    }

    /** 触碰文件：激活所有 paths 匹配的未激活技能；返回本次新激活列表（提示注入用） */
    public List<Skill> onFileTouched(String path) {
        if (path == null || path.trim().isEmpty() || conditional.isEmpty())
            return Collections.emptyList();
        List<Skill> newly = new ArrayList<Skill>();
        for (Skill s : conditional) {
            if (activated.contains(s) || !matchesAny(s, path)) continue;
            activated.add(s);
            newly.add(s);
        }
        return newly;
    }

    /** 已激活技能（只读快照；并入会话可用技能表） */
    public List<Skill> dynamicSkills() {
        return new ArrayList<Skill>(activated);
    }

    /** 收纳嵌套发现的技能（去重；返回 true=本次新收纳） */
    public boolean adopt(Skill skill) {
        if (skill == null) return false;
        for (Skill s : activated) if (s.name.equalsIgnoreCase(skill.name)) return false;
        activated.add(skill);
        return true;
    }

    /** gitignore 风格子集匹配：任一 pattern 命中即激活 */
    public static boolean matchesAny(Skill skill, String path) {
        for (String pattern : skill.paths) {
            String p = pattern == null ? "" : pattern.trim();
            if (p.isEmpty() || p.startsWith("!")) continue;   // 否定模式不支持（保守不激活）
            if (matches(p, path)) return true;
        }
        return false;
    }

    private static boolean matches(String pattern, String path) {
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        // 裸名（无 /）：匹配任意目录下的文件名/目录名段
        if (!pattern.contains("/")) {
            if (matchesGlob(pattern, name)) return true;
            for (String segment : normalized.split("/"))
                if (matchesGlob(pattern, segment)) return true;
            return false;
        }
        String anchored = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        // 尾部 /**：目录前缀匹配（gitignore 语义——任意目录边界对齐，不要求路径开头）
        if (anchored.endsWith("/**")) {
            String prefix = anchored.substring(0, anchored.length() - 2);   // 如 "docs/"
            return normalized.startsWith(prefix) || normalized.contains("/" + prefix);
        }
        return matchesGlob(anchored, normalized)
                || matchesGlob("**/" + anchored, normalized);
    }

    private static boolean matchesGlob(String glob, String value) {
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            return matcher.matches(Paths.get(value));
        } catch (PatternSyntaxException invalid) {
            return false;
        }
    }

    /** 从被操作文件目录向上发现嵌套 .minion/skills（至多 8 层；跳过 gitignored 目录名） */
    public static List<Skill> discoverNested(Path operatedFile) {
        List<Skill> found = new ArrayList<Skill>();
        if (operatedFile == null) return found;
        Path dir = operatedFile.toAbsolutePath().getParent();
        for (int depth = 0; depth < 8 && dir != null; depth++) {
            if (isGitIgnoredName(dir)) return found;   // 不可见目录下的技能不生效
            Path skills = dir.resolve(".minion").resolve("skills");
            if (Files.isDirectory(skills)) {
                SkillManager.ScanResult scan =
                        SkillManager.scanTree(skills, SkillSet.MAX_SCAN_DEPTH, 32);
                for (Skill s : scan.skills)
                    if (!containsName(found, s.name)) found.add(s);
            }
            dir = dir.getParent();
        }
        return found;
    }

    private static boolean isGitIgnoredName(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return name.equals(".git") || name.equals("node_modules") || name.equals("target")
                || name.equals("build") || name.equals("dist");
    }

    private static boolean containsName(List<Skill> list, String name) {
        for (Skill s : list) if (s.name.equalsIgnoreCase(name)) return true;
        return false;
    }
}
