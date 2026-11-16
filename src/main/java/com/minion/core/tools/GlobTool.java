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
            // ripgrep 旁路：rg 负责快速枚举+gitignore 过滤，Java matcher 后置过滤保证 glob 语义一致；
            // 失败丢弃本根已收集结果，回退 Java 遍历
            String rg = com.minion.core.tools.search.RipgrepSidecar.resolve();
            boolean rgDone = false;
            if (rg != null && Files.isDirectory(root)) {
                int before = found.size();
                rgDone = tryRipgrep(rg, root, rootAbs, workRootAbs, matchers, found);
                if (!rgDone) {
                    while (found.size() > before) found.remove(found.size() - 1);
                }
            }
            if (rgDone) {
                if (found.size() >= MAX_RESULTS) break;
                continue;
            }
            final com.minion.core.tools.search.GitignoreFilter ignore =
                    new com.minion.core.tools.search.GitignoreFilter(root);
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (com.minion.core.tools.search.GitignoreFilter.isAlwaysSkippedDirectory(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (ignore.isIgnored(dir, true)) return FileVisitResult.SKIP_SUBTREE;
                        if (!dir.toAbsolutePath().normalize().equals(rootAbs)) ignore.enterDirectory(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        ignore.leaveDirectory();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (ignore.isIgnored(file, false)) return FileVisitResult.CONTINUE;
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

    /** ripgrep 枚举旁路。退出码 0/1 视为成功；失败返回 false（调用方丢弃部分结果回退 Java 遍历）。
     *  达到 MAX_RESULTS 提前 destroy 进程，同样视为成功（截断语义与 Java 遍历一致）。 */
    private boolean tryRipgrep(String rg, Path root, Path rootAbs, Path workRootAbs,
            List<PathMatcher> matchers, List<String> found) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(rg, "--files",
                    "--no-config", "--no-require-git",
                    "--no-ignore-parent", "--no-ignore-dot", "--no-ignore-exclude",
                    "--hidden", "--no-messages", "--sort", "path", "-g", "!.git", ".")
                    .directory(root.toFile()).start();
            final Process fp = proc;
            Thread drain = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buf = new byte[4096];
                    try { while (fp.getErrorStream().read(buf) >= 0) { /* 排空防管道阻塞 */ } }
                    catch (IOException ignored) { }
                }
            });
            drain.setDaemon(true);
            drain.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                Path abs = root.resolve(line).toAbsolutePath().normalize();
                // .git 恒不进入（与 Java 遍历一致，不依赖 rg 版本行为）
                boolean inGitDir = false;
                for (Path part : rootAbs.relativize(abs)) {
                    if (part.toString().equals(".git")) { inGitDir = true; break; }
                }
                if (inGitDir) continue;
                if (matchesAny(matchers, abs, rootAbs, workRootAbs)) {
                    found.add(abs.startsWith(workRootAbs)
                            ? workRootAbs.relativize(abs).toString().replace('\\', '/')
                            : abs.toString().replace('\\', '/'));
                    if (found.size() >= MAX_RESULTS) return true;
                }
            }
            int exit = proc.waitFor();
            return exit == 0 || exit == 1;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (proc != null) proc.destroyForcibly();
        }
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
