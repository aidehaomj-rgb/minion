package com.minion.core.context;

import com.minion.core.diagnostics.DiagnosticLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MINION.md 指令装载器（开发说明 v2.4 §6.1，instructions.enabled 默认 false）。
 *
 * <p>V68 projectMd 保留独立事实源（调用方先行注入）；本类只负责自动发现层级（后加载优先级高）：
 * 企业 {ProgramData}/minion/MINION.md → 用户 {jarDir}/.minion/MINION.md →
 * 项目 {cwd}/MINION.md、{cwd}/.minion/MINION.md、{cwd}/.minion/rules/*.md（cwd 向上到根）→
 * 本地 {cwd}/MINION.local.md。相同 realpath 去重。
 *
 * <p>支持 {@code @import}：`@path`/`@./`/`@~/`/`@/abs`；仅叶子文本节点生效（代码块内不展开）；
 * 最大深度 5；processedPaths 防环；白名单文本扩展名；缺失静默忽略；被包含文件插在包含者之前。
 * rules/*.md 支持 frontmatter {@code paths:}（gitignore glob）按触碰目录条件激活。
 * 超 40000 字符告警。子目录触发加载由调用方传 touchedDir 实现（向上到 cwd）。
 */
public final class InstructionLoader {

    public static final int MAX_TOTAL_CHARS = 40000;
    private static final int MAX_IMPORT_DEPTH = 5;
    private static final Set<String> TEXT_EXT = new HashSet<String>(Arrays.asList(
            "md", "txt", "json", "xml", "yaml", "yml", "csv", "properties", "cfg", "ini", "html", "sql"));

    private final Path cwd;
    private final Path jarDir;
    private final boolean enabled;

    public InstructionLoader(Path cwd, Path jarDir, boolean enabled) {
        this.cwd = cwd == null ? Paths.get(".") : cwd.toAbsolutePath().normalize();
        this.jarDir = jarDir;
        this.enabled = enabled;
    }

    /** 单条指令层：路径 + 正文（已展开 import） */
    public static final class Layer {
        public final Path path;
        public final String content;
        Layer(Path path, String content) { this.path = path; this.content = content; }
    }

    /** 全量发现（企业→用户→项目→本地；同 realpath 去重；touchedDir 为本次触碰目录可为 null） */
    public List<Layer> loadAll(Path touchedDir) {
        List<Layer> out = new ArrayList<Layer>();
        if (!enabled) return out;
        Set<String> seen = new LinkedHashSet<String>();
        addIfExists(out, seen, enterprisePath());
        addIfExists(out, seen, jarDir == null ? null : jarDir.resolve(".minion").resolve("MINION.md"));
        // 项目层级：cwd 向上逐级到根（每级 MINION.md 与 .minion/MINION.md）
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            addIfExists(out, seen, dir.resolve("MINION.md"));
            addIfExists(out, seen, dir.resolve(".minion").resolve("MINION.md"));
        }
        // rules/*.md：项目根（cwd）与触碰目录链上的，按 frontmatter paths 条件激活
        for (Path rulesDir : rulesDirs(touchedDir)) addRules(out, seen, rulesDir, touchedDir);
        // 子目录按需：触碰目录链上的 MINION.md（cwd 以下部分，层级发现已覆盖 cwd 及以上）
        if (touchedDir != null)
            for (Path dir = touchedDir.toAbsolutePath().normalize(); dir != null && dir.startsWith(cwd); dir = dir.getParent())
                addIfExists(out, seen, dir.resolve("MINION.md"));
        addIfExists(out, seen, cwd.resolve("MINION.local.md"));
        int total = 0;
        for (Layer l : out) total += l.content.length();
        if (total > MAX_TOTAL_CHARS)
            DiagnosticLog.warn("instructions", "MINION.md 合计 " + total + " 字符超过 " + MAX_TOTAL_CHARS + " 上限，请精简");
        return out;
    }

    private Path enterprisePath() {
        String programData = System.getenv("ProgramData");
        return programData == null ? null : Paths.get(programData, "minion", "MINION.md");
    }

    private List<Path> rulesDirs(Path touchedDir) {
        List<Path> dirs = new ArrayList<Path>();
        Set<String> seen = new HashSet<String>();
        for (Path dir : Arrays.asList(cwd, touchedDir))
            for (Path d = dir == null ? null : dir.toAbsolutePath().normalize();
                 d != null && d.startsWith(cwd); d = d.getParent()) {
                Path rules = d.resolve(".minion").resolve("rules");
                if (seen.add(rules.toString())) dirs.add(rules);
            }
        return dirs;
    }

    private void addRules(List<Layer> out, Set<String> seen, Path rulesDir, Path touchedDir) {
        if (rulesDir == null || !Files.isDirectory(rulesDir)) return;
        List<Path> files = new ArrayList<Path>();
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(rulesDir, "*.md")) {
            for (Path p : ds) files.add(p);
        } catch (IOException unreadable) {
            return;
        }
        files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path f : files) {
            if (!pathsActivated(f, touchedDir)) continue;
            addIfExists(out, seen, f);
        }
    }

    /** frontmatter `paths:` 条件激活（gitignore glob 语义近似：模式与触碰路径后缀匹配即可激活） */
    private boolean pathsActivated(Path ruleFile, Path touchedDir) {
        try {
            String text = new String(Files.readAllBytes(ruleFile), StandardCharsets.UTF_8);
            if (!text.startsWith("---")) return true;   // 无 frontmatter → 恒激活
            int end = text.indexOf("\n---", 3);
            if (end < 0) return true;
            String header = text.substring(3, end);
            for (String line : header.split("\n")) {
                if (!line.startsWith("paths:")) continue;
                String patterns = line.substring("paths:".length()).trim().replace("[", "").replace("]", "");
                if (patterns.isEmpty()) return true;
                if (touchedDir == null) return false;
                String touched = touchedDir.toAbsolutePath().normalize().toString().replace('\\', '/');
                for (String pattern : patterns.split(",")) {
                    String p = pattern.trim().replace("\"", "");
                    if (p.isEmpty()) continue;
                    // gitignore 语义近似：裸目录名或后缀 glob 视为命中该目录树
                    String norm = p.startsWith("/") ? p.substring(1) : p;
                    if (touched.endsWith("/" + norm) || touched.endsWith(norm)
                            || (norm.endsWith("/**") && touched.contains(norm.substring(0, norm.length() - 3))))
                        return true;
                }
                return false;
            }
            return true;
        } catch (Exception unreadable) {
            return false;
        }
    }

    private void addIfExists(List<Layer> out, Set<String> seen, Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Path real = file.toRealPath();
            if (!seen.add(real.toString())) return;   // 相同 realpath 去重
            StringBuilder sb = new StringBuilder();
            expandImports(file, new String(Files.readAllBytes(real), StandardCharsets.UTF_8), 0,
                    new HashSet<String>(Arrays.asList(real.toString())), sb);
            out.add(new Layer(real, sb.toString()));
        } catch (IOException unreadable) {
            // 缺失/不可读：静默忽略（规格）
        }
    }

    /**
     * 展开 @import：仅叶子文本行生效（代码块 `` ``` `` 内不展开）；被包含文件插在包含者之前；
     * 深度 >5 或环路或非白名单扩展名跳过；@~/ 用户目录、@/ 绝对、@./ 相对包含者、@裸 相对 cwd。
     */
    private void expandImports(Path includer, String text, int depth, Set<String> processed, StringBuilder out) {
        boolean inCodeBlock = false;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                out.append(line).append('\n');
                continue;
            }
            if (!inCodeBlock && depth < MAX_IMPORT_DEPTH && trimmed.startsWith("@")
                    && trimmed.length() > 1 && !trimmed.startsWith("@@")) {
                Path target = resolveImport(includer, trimmed.substring(1));
                if (target != null && isTextFile(target) && Files.isRegularFile(target)) {
                    String key;
                    try { key = target.toRealPath().toString(); } catch (IOException e) { key = target.toString(); }
                    if (processed.add(key)) {
                        try {
                            expandImports(target, new String(Files.readAllBytes(target), StandardCharsets.UTF_8),
                                    depth + 1, processed, out);   // 被包含者在包含者之前
                        } catch (IOException unreadable) { /* 静默 */ }
                        continue;
                    }
                }
            }
            out.append(line).append('\n');
        }
    }

    private Path resolveImport(Path includer, String spec) {
        try {
            if (spec.startsWith("~/"))
                return Paths.get(System.getProperty("user.home"), spec.substring(2));
            if (spec.startsWith("/"))
                return Paths.get(spec);
            if (spec.startsWith("./") || spec.startsWith("../"))
                return includer.getParent() == null ? Paths.get(spec) : includer.getParent().resolve(spec).normalize();
            return cwd.resolve(spec).normalize();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean isTextFile(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXT.contains(ext);
    }
}
