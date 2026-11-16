package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.search.RipgrepSidecar;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 正则内容搜索。参数: pattern(必), path(可选搜索起点), ignoreCase, maxResults。
 *  单行内容截断 1000 字符；显示层 250 条 + 30k 字符双上限，超限全量落盘
 *  会话临时目录 <jarDir>/.session/tmp/<sessionId>/grep-*.txt（模型可用 Read 查看）。 */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 250;    // 显示条数默认与钳制上限
    private static final int LINE_MAX = 1000;      // 单行内容截断（防 minified JS/单行 JSON 爆炸）
    private static final int DISPLAY_CHARS = 30000; // 显示总字符上限

    private final Workspace workspace;
    private final String skillsDir;
    /** 会话临时目录（jarDir/.session/tmp/<sessionId>；null = 落盘降级） */
    private final Path tmpDir;
    private final ConfirmGate confirm;

    public GrepTool(Workspace workspace) { this(workspace, null); }

    public GrepTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public GrepTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this(workspace, skillsDir, null, confirm);
    }

    public GrepTool(Workspace workspace, String skillsDir, String tmpDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir == null ? null : Paths.get(tmpDir);
        this.confirm = confirm;
    }

    @Override
    public String name() { return "Grep"; }

    @Override
    public String description() { return "在工作路径内按正则搜索文件内容，输出 文件:行号:内容"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("正则搜索文件内容",
                new String[]{"pattern", "path", "ignoreCase", "maxResults"},
                new String[]{"pattern"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        if (pattern.isEmpty()) return ToolResult.error("缺少 pattern 参数");
        final boolean ignoreCase = args.has("ignoreCase") && args.get("ignoreCase").getAsBoolean();
        final Pattern p;
        try {
            p = Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("正则语法错误: " + e.getMessage());
        }
        String start = args.has("path") ? args.get("path").getAsString() : ".";
        final Path root = PathsGuard.resolve(workspace.cwd().toString(), start);
        if (!Files.exists(root)) return ToolResult.error("路径不存在: " + root);
        ToolResult guard = PathsGuard.errorIfOutside(workspace, skillsDir,
                tmpDir == null ? null : tmpDir.toString(), root);
        if (guard != null) {
            if (confirm == null || !confirm.checkReadOutside(this, args, root.toString())) return guard;
        }
        // 结果路径格式：cwd 内输出相对路径（模型可直接 Read）；cwd 外（技能目录等）输出绝对路径
        final Path rootAbs = workspace.cwd().toAbsolutePath().normalize();
        final boolean rootInWork = root.toAbsolutePath().normalize().startsWith(rootAbs);
        final int max;
        try {
            // 钳制到 MAX_RESULTS：模型传超大值不放大显示量（防上下文爆炸）
            max = args.has("maxResults")
                    ? Math.min(args.get("maxResults").getAsInt(), MAX_RESULTS)
                    : MAX_RESULTS;
        } catch (NumberFormatException e) {
            return ToolResult.error("参数 maxResults 格式错误: " + e.getMessage());
        }
        if (max < 0) return ToolResult.error("参数 maxResults 不能为负: " + max);

        // 落盘：全量匹配流式写入；失败降级（dumpWriter[0] == null 纯内存截断，不阻断执行）
        final Path dumpPath = OutputDump.write(tmpDir, "grep", "");
        final BufferedWriter[] dumpWriter = { dumpPath == null ? null
                : Files.newBufferedWriter(dumpPath, StandardCharsets.UTF_8) };
        // 跳过落盘目录：jar 目录可能嵌套/重叠工作空间（如 jar 放在项目目录运行），
        // dump 文件内容本身含匹配文本，遍历到会自噬（自身被匹配再写入自身）
        final Path dumpDirPath = tmpDir == null ? null : tmpDir.toAbsolutePath().normalize();

        final StringBuilder disp = new StringBuilder();  // 显示层（≤max 条 且 ≤30k 字符）
        final int[] dispCount = {0};   // 已入显示层条数
        final int[] totalCount = {0};  // 全量匹配条数（含落盘）
        final boolean[] dispFull = {false}; // 显示层已满：继续遍历落盘，不再 append
        final com.minion.core.tools.search.GitignoreFilter ignore =
                new com.minion.core.tools.search.GitignoreFilter(root);
        final Path rootNormalized = root.toAbsolutePath().normalize();

        // ripgrep 旁路：命中则显示/落盘状态已填好；失败自动重置并回退 Java 遍历
        boolean rgDone = false;
        String rg = RipgrepSidecar.resolve();
        if (rg != null && rgSafePattern(pattern) && Files.isDirectory(root)) {
            rgDone = tryRipgrep(rg, pattern, ignoreCase, root, rootInWork, rootNormalized,
                    dumpPath, dumpWriter, dumpDirPath, disp, dispCount, totalCount, dispFull, max);
        }
        if (!rgDone) Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                boolean isDumpDir = dumpDirPath != null
                        && dir.toAbsolutePath().normalize().equals(dumpDirPath);
                // 仅当命中落盘目录且不是搜索根时才跳过：root==tmpDir 时根自身即落盘目录，
                // 跳过会导致"未匹配"（模型 grep 自己的临时目录与"可自由读写"承诺相悖）
                if (isDumpDir && !dir.toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (com.minion.core.tools.search.GitignoreFilter.isAlwaysSkippedDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (ignore.isIgnored(dir, true)) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.toAbsolutePath().normalize().equals(rootNormalized)) ignore.enterDirectory(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                ignore.leaveDirectory();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // 落盘文件本身跳过：遍历中持续写入，读它会自噬（匹配内容写入自身）
                if (dumpPath != null
                        && file.toAbsolutePath().normalize().equals(dumpPath.toAbsolutePath().normalize())) {
                    return FileVisitResult.CONTINUE;
                }
                if (ignore.isIgnored(file, false)) return FileVisitResult.CONTINUE;
                if (com.minion.core.tools.search.GitignoreFilter.isBinary(file)) return FileVisitResult.CONTINUE;
                Path rel = rootInWork ? workspace.cwd().relativize(file) : file;
                try {
                    java.util.List<String> lines = TextFiles.readAllLines(file).lines;
                    // 不提前终止：全量收集写落盘，显示层受双上限约束
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (p.matcher(line).find()) {
                            totalCount[0]++;
                            String entry = rel.toString().replace('\\', '/') + ":" + (i + 1)
                                    + ": " + truncateLine(line.trim()) + "\n";
                            if (dumpWriter[0] != null) {
                                try { dumpWriter[0].write(entry); } catch (IOException ignored) { }
                            }
                            if (!dispFull[0]) {
                                // 先检查后 append：max=0 时显示 0 条（旧实现先 append 后检查会多显示 1 条）
                                if (dispCount[0] >= max || disp.length() >= DISPLAY_CHARS) {
                                    dispFull[0] = true; // 显示层满了，继续遍历但不再 append
                                } else {
                                    disp.append(entry);
                                    dispCount[0]++;
                                }
                            }
                        }
                    }
                } catch (IOException ignored) { }
                return FileVisitResult.CONTINUE;
            }
        });
        if (dumpWriter[0] != null) {
            try { dumpWriter[0].close(); } catch (IOException ignored) { }
        }

        boolean truncated = totalCount[0] > max || disp.length() >= DISPLAY_CHARS;
        if (!truncated) {
            if (dumpPath != null) {
                dumpPath.toFile().delete();
                // 顺带删空的 tmp 目录，保证"不超限不落盘"零磁盘痕迹
                try { Files.deleteIfExists(dumpPath.getParent()); } catch (IOException ignored) { }
            }
            return ToolResult.success(disp.toString().trim().isEmpty()
                    ? "未匹配: " + pattern : disp.toString());
        }
        String note = dumpPath == null
                ? "\n... 共 " + totalCount[0] + " 条，落盘失败未保存完整结果，以上为仅存内容\n"
                : "\n... 共 " + totalCount[0] + " 条，完整结果已保存到 "
                        + dumpPath.toAbsolutePath()
                        + "，可用 Read 查看\n";
        return ToolResult.success(disp.toString() + note);
    }

    /** .git 目录恒不进入（与 Java 遍历一致；--no-require-git 下 rg 不再特判 .git，双保险） */
    private static boolean inGitDir(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (part.toString().equals(".git")) return true;
        }
        return false;
    }

    /** rg 输出行解析：路径:行号:内容（路径取到第一个 ":数字:" 为止） */
    private static final Pattern RG_LINE = Pattern.compile("^(.*?):(\\d+):(.*)$");

    /** 旁路适用性：非 ASCII 模式回退（rg 按 UTF-8 搜索，GBK 文件中文会漏配，Java 遍历有 GBK 回退）；
     *  \b\w\d\s 类转义回退（Java 默认 ASCII 语义，rg 是 Unicode 语义，中文邻接处行为不同）。
     *  rg 不支持的语法（环视/反向引用等）会退出码 2，运行时自动回退。 */
    public static boolean rgSafePattern(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c > 127) return false;
            if (c == '\\') {
                if (i + 1 >= pattern.length()) return true;
                char n = pattern.charAt(i + 1);
                if ("bBwWdDsS".indexOf(n) >= 0) return false;
                i++;
            }
        }
        return true;
    }

    /** ripgrep 旁路。成功（退出码 0/1）返回 true；任何失败重置显示/计数/落盘状态后返回 false。 */
    private boolean tryRipgrep(String rg, String pattern, boolean ignoreCase, Path root,
            boolean rootInWork, Path rootNormalized, Path dumpPath, BufferedWriter[] dumpWriter,
            Path dumpDirPath, StringBuilder disp, int[] dispCount, int[] totalCount,
            boolean[] dispFull, int max) {
        Process proc = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<String>();
            cmd.add(rg);
            // 与 Java 遍历对齐：不读全局/父目录/.ignore/exclude 规则；含隐藏文件
            cmd.add("--no-config"); cmd.add("--no-require-git");
            cmd.add("--no-ignore-parent"); cmd.add("--no-ignore-dot"); cmd.add("--no-ignore-exclude");
            cmd.add("--hidden"); cmd.add("--no-messages");
            cmd.add("--no-heading"); cmd.add("--with-filename"); cmd.add("--line-number");
            cmd.add("--sort"); cmd.add("path"); cmd.add("--color"); cmd.add("never");
            if (ignoreCase) cmd.add("-i");
            // .git 恒不进入：--no-require-git 下 rg 不再特判 .git，需显式排除
            cmd.add("-g"); cmd.add("!.git");
            // 落盘目录防自噬：与 Java 遍历同语义（root==dumpDir 时只排除落盘文件本身）
            if (dumpPath != null && dumpDirPath != null) {
                if (dumpDirPath.equals(rootNormalized)) {
                    cmd.add("-g"); cmd.add("!" + dumpPath.getFileName().toString());
                } else if (dumpDirPath.startsWith(rootNormalized)) {
                    cmd.add("-g"); cmd.add("!" + rootNormalized.relativize(dumpDirPath).toString().replace('\\', '/') + "/");
                }
            }
            cmd.add("-e"); cmd.add(pattern);
            cmd.add(".");
            // cwd=root：输出相对路径，避免 Windows 盘符冒号与 文件:行号 格式歧义
            proc = new ProcessBuilder(cmd).directory(root.toFile()).start();
            final Process fp = proc;
            final StringBuilder err = new StringBuilder();
            Thread drain = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buf = new byte[4096];
                    try {
                        int n;
                        while ((n = fp.getErrorStream().read(buf)) >= 0) {
                            if (err.length() < 2048) err.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    } catch (IOException ignored) { }
                }
            });
            drain.setDaemon(true);
            drain.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = RG_LINE.matcher(line);
                if (!m.matches()) continue;
                Path file = root.resolve(m.group(1)).normalize();
                if (inGitDir(rootNormalized, file)) continue;
                Path rel = rootInWork ? workspace.cwd().relativize(file) : file;
                totalCount[0]++;
                String entry = rel.toString().replace('\\', '/') + ":" + m.group(2)
                        + ": " + truncateLine(m.group(3).trim()) + "\n";
                if (dumpWriter[0] != null) {
                    try { dumpWriter[0].write(entry); } catch (IOException ignored) { }
                }
                if (!dispFull[0]) {
                    if (dispCount[0] >= max || disp.length() >= DISPLAY_CHARS) {
                        dispFull[0] = true;
                    } else {
                        disp.append(entry);
                        dispCount[0]++;
                    }
                }
            }
            int exit = proc.waitFor();
            drain.join(2000);
            if (exit == 0 || exit == 1) return true;
            DiagnosticLog.warn("grep", "rg 退出码 " + exit + "，回退 Java 遍历: " + err);
        } catch (IOException e) {
            DiagnosticLog.warn("grep", "rg 执行失败，回退 Java 遍历: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (proc != null) proc.destroyForcibly();
        }
        // 失败重置：显示层/计数清零，落盘文件重建，保证 Java 回退从零开始
        disp.setLength(0); dispCount[0] = 0; totalCount[0] = 0; dispFull[0] = false;
        if (dumpWriter[0] != null) {
            try { dumpWriter[0].close(); } catch (IOException ignored) { }
        }
        if (dumpPath != null) {
            try {
                Files.deleteIfExists(dumpPath);
                dumpWriter[0] = Files.newBufferedWriter(dumpPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                dumpWriter[0] = null;
            }
        }
        return false;
    }

    /** 单行截断：>1000 字符截断并加省略号（落盘与显示同格式，文件大小可控） */
    private static String truncateLine(String s) {
        if (s.length() <= LINE_MAX) return s;
        String cut = s.substring(0, LINE_MAX);
        // 截断点可能切在代理对（如 emoji）中间，丢弃尾部孤立高代理，避免输出非法字符
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }
}
