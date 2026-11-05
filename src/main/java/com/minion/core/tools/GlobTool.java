package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** 路径模式匹配。参数: pattern(必, 如 **\/*.java) */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;
    private static final boolean WINDOWS = java.io.File.separatorChar == '\\';

    private final Workspace workspace;
    private final String skillsDir;
    private final String tmpDir;
    private final ConfirmGate confirm;

    public GlobTool(Workspace workspace) { this(workspace, null); }

    public GlobTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public GlobTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this(workspace, skillsDir, null, confirm);
    }

    public GlobTool(Workspace workspace, String skillsDir, String tmpDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir;
        this.confirm = confirm;
    }

    @Override
    public String name() { return "Glob"; }

    @Override
    public String description() { return "按 glob 模式在工作路径内查找文件，如 **/*.java"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("按 glob 模式查找文件",
                new String[]{"pattern", "path"}, new String[]{"pattern"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        if (pattern.isEmpty()) return ToolResult.error("缺少 pattern 参数");
        pattern = normalizePattern(pattern);
        final List<PathMatcher> matchers;
        try {
            matchers = compileMatchers(pattern);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("glob 模式语法错误: " + e.getMessage());
        }
        final Path workRoot = workspace.cwd();
        final Path workRootAbs = workRoot.toAbsolutePath().normalize();
        final List<String> found = new ArrayList<String>();
        // 遍历根：无 path 时 = cwd + 技能目录（若在 cwd 之外且存在）；
        // 指定 path 时 = 该路径（工作区/技能目录/会话临时目录内直搜；其外经确认放行后直搜）。
        // 结果路径格式：工作区内输出相对路径；工作区外输出绝对路径（模型可直接 Read）
        final List<Path> roots = new ArrayList<Path>();
        String start = args.has("path") ? args.get("path").getAsString() : null;
        if (start != null && !start.isEmpty()) {
            final Path root = PathsGuard.resolve(workspace.cwd().toString(), start);
            if (!Files.exists(root)) return ToolResult.error("路径不存在: " + root);
            ToolResult guard = PathsGuard.errorIfOutside(workspace, skillsDir, tmpDir, root);
            if (guard != null) {
                if (confirm == null || !confirm.checkReadOutside(this, args, root.toString())) return guard;
            }
            roots.add(root);
        } else {
            roots.add(workRoot);
            if (skillsDir != null && !skillsDir.isEmpty() && Files.isDirectory(Paths.get(skillsDir))) {
                Path skillsAbs = Paths.get(skillsDir).toAbsolutePath().normalize();
                if (!skillsAbs.startsWith(workRoot.toAbsolutePath().normalize())) roots.add(skillsAbs);
            }
        }
        for (final Path root : roots) {
            final Path rootAbs = root.toAbsolutePath().normalize();
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path abs = file.toAbsolutePath().normalize();
                        if (matchesAny(matchers, abs, rootAbs, workRootAbs)) {
                            found.add(abs.startsWith(workRootAbs)
                                    ? workRootAbs.relativize(abs).toString().replace('\\', '/')
                                    : abs.toString().replace('\\', '/'));
                        }
                        return found.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE
                                : FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                return ToolResult.error("无法遍历路径 " + root + ": " + e.getMessage());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String f : found) sb.append(f).append('\n');
        if (found.size() >= MAX_RESULTS) sb.append("... 结果超过 ").append(MAX_RESULTS).append(" 条，已截断\n");
        return ToolResult.success(sb.toString().trim().isEmpty()
                ? "未找到匹配文件: " + pattern : sb.toString());
    }

    /** 工作空间相对、搜索根相对、绝对路径三种写法都可匹配。 */
    private static boolean matchesAny(List<PathMatcher> matchers, Path abs, Path root, Path work) {
        List<Path> candidates = new ArrayList<Path>();
        if (abs.startsWith(work)) candidates.add(work.relativize(abs));
        if (!root.equals(work) && abs.startsWith(root)) candidates.add(root.relativize(abs));
        candidates.add(abs);
        for (PathMatcher matcher : matchers) for (Path candidate : candidates)
            if (matcher.matches(candidate)) return true;
        return false;
    }

    private static String normalizePattern(String pattern) {
        String normalized = WINDOWS ? pattern.replace('\\', '/') : pattern;
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    /** Java glob 的递归前缀不覆盖零层目录，给它增加省略前缀的匹配变体。 */
    private static List<PathMatcher> compileMatchers(String pattern) {
        List<PathMatcher> result = new ArrayList<PathMatcher>();
        result.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        if (pattern.startsWith("**/"))
            result.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)));
        return result;
    }
}
