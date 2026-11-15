package com.minion.core.tools.dev;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 按内容绑定验证证据；只在显式验证/收口时调用，不缓存、无启动扫描。 */
public final class SourceVersion {
    public static final String POLICY = "minion.source.v1";
    private static final Set<String> OUTPUTS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            ".git", ".svn", "target", "build", "dist", "out", "node_modules", ".gradle", ".idea",
            "venv", ".venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".tox", "coverage", ".minion")));

    private SourceVersion() { }

    /** 失败抛 IOException，调用方不得把无权限/不完整扫描解释为版本一致。 */
    public static Snapshot capture(Path directory) throws IOException {
        final Path root = directory.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("源码根不是目录: " + root);
        final List<Path> files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && OUTPUTS.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.toRealPath().startsWith(root)) throw new IOException("源码目录越界: " + dir);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root))
                    throw new IOException("源码含链接，无法完整绑定版本: " + file);
                if (attrs.isRegularFile() && sourceFileName(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        Path config = root.resolve(".minion/validation.json");
        if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(config) || Files.isSymbolicLink(config) || !config.toRealPath().startsWith(root))
                throw new IOException("validation.json 不可读取或越界");
            files.add(config);
        }
        Collections.sort(files, (a, b) -> relative(root, a).compareTo(relative(root, b)));
        MessageDigest digest = sha256();
        digest.update((POLICY + "\n").getBytes(StandardCharsets.UTF_8));
        for (Path file : files) {
            digest.update(relative(root, file).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            // 每文件独立摘要，路径/内容边界不会发生串接歧义。
            digest.update(fileDigest(file).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return new Snapshot("sha256:" + hex(digest.digest()), files.size());
    }

    /** 轻量公共接线 API：与报告 sourceDigest 比对；I/O 错误一律 false。 */
    public static boolean verify(Path root, String sourceDigest) {
        if (root == null || sourceDigest == null || !sourceDigest.matches("sha256:[0-9a-f]{64}")) return false;
        try { return sourceDigest.equals(capture(root).digest); }
        catch (IOException | SecurityException e) { return false; }
    }

    static String fileDigest(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    public static List<String> excludedDirectories() {
        List<String> list = new ArrayList<String>(OUTPUTS);
        Collections.sort(list);
        return list;
    }

    static boolean excluded(String name) { return OUTPUTS.contains(name); }
    private static boolean sourceFileName(Path file) {
        String name=file.getFileName().toString();
        return !name.endsWith(".pyc")&&!name.endsWith(".pyo")&&!name.endsWith(".class");
    }

    /** Whether a current source digest covers this already-canonical, authorized file. */
    public static boolean covers(Path root,Path file) {
        if(!file.startsWith(root)||file.equals(root)||!sourceFileName(file))return false;
        Path relative=root.relativize(file);
        for(int i=0;i<relative.getNameCount()-1;i++)
            if(excluded(relative.getName(i).toString()))return false;
        return true;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    private static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }

    public static final class Snapshot {
        public final String digest;
        public final int fileCount;
        private Snapshot(String digest, int fileCount) { this.digest = digest; this.fileCount = fileCount; }
    }
}
