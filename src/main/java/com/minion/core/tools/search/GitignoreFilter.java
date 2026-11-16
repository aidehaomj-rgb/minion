package com.minion.core.tools.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/** 轻量 .gitignore 过滤（Grep/Glob 的 Java 遍历路径共用）。
 *  支持常见语法：注释 #、取反 !、目录限定尾 /、锚定（含 / 或头 /）、* ? ** []；
 *  子目录 .gitignore 就近叠加、深层优先，同层后规则优先；父目录被忽略则整棵子树跳过
 *  （与 git 一致：被排除目录下的文件无法被取反重新包含）。
 *  不支持的边角（转义 \#、\! 与尾随空格转义）按"宁可多搜不漏搜"处理。
 *  搜索根自身永不被过滤（显式指定被忽略目录为根时仍直搜）。 */
public final class GitignoreFilter {

    private static final class Rule {
        final PathMatcher matcher;
        final boolean negated;
        final boolean directoryOnly;
        final boolean anchored;
        Rule(PathMatcher matcher, boolean negated, boolean directoryOnly, boolean anchored) {
            this.matcher = matcher; this.negated = negated; this.directoryOnly = directoryOnly; this.anchored = anchored;
        }
    }

    private static final class Layer {
        final Path base; // 相对搜索根的目录；null = 根层（JDK8 空路径 nameCount=1 且 startsWith 不成立，不能用空路径）
        final List<Rule> rules;
        Layer(Path base, List<Rule> rules) { this.base = base; this.rules = rules; }
    }

    private final Path root;
    private final List<Layer> layers = new ArrayList<Layer>();

    public GitignoreFilter(Path root) {
        this.root = root.toAbsolutePath().normalize();
        loadLayer(this.root, null);
    }

    /** 进入子目录前调用（目录自身已通过 isIgnored 检查）；加载该目录的 .gitignore。 */
    public void enterDirectory(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        loadLayer(abs, root.relativize(abs));
    }

    /** 离开子目录时调用（walkFileTree postVisitDirectory；SKIP_SUBTREE 的目录不会回调）。 */
    public void leaveDirectory() {
        if (layers.size() > 1) layers.remove(layers.size() - 1);
    }

    /** .git 目录恒跳过（版本库内部对象不是用户代码）。 */
    public static boolean isAlwaysSkippedDirectory(Path dir) {
        Path name = dir.getFileName();
        return name != null && ".git".equals(name.toString());
    }

    public boolean isIgnored(Path path, boolean isDir) {
        Path abs = path.toAbsolutePath().normalize();
        if (abs.equals(root)) return false;
        Path rel = root.relativize(abs);
        for (int d = layers.size() - 1; d >= 0; d--) {
            Layer layer = layers.get(d);
            if (layer.rules.isEmpty()) continue;
            if (layer.base != null && !rel.startsWith(layer.base)) continue;
            Path sub = layer.base == null ? rel
                    : rel.subpath(layer.base.getNameCount(), rel.getNameCount());
            Boolean last = null;
            for (Rule rule : layer.rules) {
                if (rule.directoryOnly && !isDir) continue;
                Path candidate = rule.anchored ? sub : sub.getFileName();
                if (candidate == null) continue;
                if (rule.matcher.matches(candidate)) last = !rule.negated;
            }
            if (last != null) return last;
        }
        return false;
    }

    private void loadLayer(Path dir, Path base) {
        List<Rule> rules = new ArrayList<Rule>();
        Path ignoreFile = dir.resolve(".gitignore");
        if (Files.isRegularFile(ignoreFile)) {
            try {
                for (String line : Files.readAllLines(ignoreFile, StandardCharsets.UTF_8)) {
                    Rule rule = parse(line);
                    if (rule != null) rules.add(rule);
                }
            } catch (IOException ignored) {
                // 读不到 .gitignore 时按无规则处理（宁可多搜不漏搜）
            }
        }
        layers.add(new Layer(base, rules));
    }

    static Rule parse(String line) {
        String s = line;
        // 尾随空格视为修剪（git 允许 \ 转义尾随空格，此处不支持，按修剪处理）
        while (s.endsWith(" ") && !s.endsWith("\\ ")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.startsWith("#")) return null;
        boolean negated = false;
        if (s.startsWith("!")) { negated = true; s = s.substring(1); }
        else if (s.startsWith("\\!")) s = s.substring(1);
        if (s.isEmpty()) return null;
        boolean directoryOnly = s.endsWith("/");
        if (directoryOnly) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return null;
        // 含 /（除已剥离的尾部）= 锚定到本 .gitignore 所在目录；头 / 剥离
        boolean anchored = s.contains("/");
        if (s.startsWith("/")) s = s.substring(1);
        if (s.isEmpty()) return null;
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + s);
            // Java glob 的 **/ 不覆盖零层目录，与 GlobTool 同口径补变体
            PathMatcher zeroDepth = s.startsWith("**/")
                    ? FileSystems.getDefault().getPathMatcher("glob:" + s.substring(3)) : null;
            if (zeroDepth != null) {
                final PathMatcher a = matcher, b = zeroDepth;
                matcher = new PathMatcher() {
                    @Override public boolean matches(Path path) { return a.matches(path) || b.matches(path); }
                };
            }
            return new Rule(matcher, negated, directoryOnly, anchored);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return null; // 非法 glob 按无规则处理
        }
    }

    /** 二进制探测：前 8KB 含 NUL 字节即视为二进制（与 ripgrep 默认行为对齐）。 */
    public static boolean isBinary(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) if (buf[i] == 0) return true;
            return false;
        } catch (IOException e) {
            return false; // 读不到交给后续全文读取报错/跳过
        }
    }
}