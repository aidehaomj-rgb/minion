package com.minion.core.tools.system;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 更新包只读检查器；运行中的 JAR 不自替换，实际替换继续交给 Update-Minion.cmd。 */
public final class UpdateTool implements Tool {
    private final Path jarDir;
    public UpdateTool(Path jarDir) { this.jarDir = jarDir.toAbsolutePath().normalize(); }
    @Override public String name() { return "Update"; }
    @Override public String description() { return "检查离线增量更新包、验证 JAR SHA-256、查看备份与回滚状态；实际更新需关闭 Minion 后运行 Update-Minion.cmd"; }
    @Override public JsonObject schema() { return SchemaGenerator.objectSchema("离线更新管理", new String[]{"action", "packageDir"}, new String[]{"action"}); }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        String action = str(args, "action").toLowerCase(Locale.ROOT);
        if ("status".equals(action) || "find".equals(action)) return ToolResult.success(status());
        if ("verify".equals(action)) {
            String value = str(args, "packageDir");
            if (value.isEmpty()) return ToolResult.error("verify 缺少 packageDir");
            Path dir = java.nio.file.Paths.get(value).toAbsolutePath().normalize();
            Path jar = dir.resolve("payload").resolve("minion-0.1.0.jar");
            Path hash = dir.resolve("payload").resolve("minion-0.1.0.jar.sha256");
            if (!Files.isRegularFile(jar) || !Files.isRegularFile(hash)) return ToolResult.error("更新包缺少 payload JAR 或 SHA-256 文件");
            String expected = new String(Files.readAllBytes(hash), StandardCharsets.US_ASCII).trim().replace(" ", "").toLowerCase(Locale.ROOT);
            String actual = sha256(jar);
            return expected.equals(actual) ? ToolResult.success("更新包校验通过\nJAR=" + jar + "\nSHA-256=" + actual)
                    : ToolResult.error("更新包校验失败\nexpected=" + expected + "\nactual=" + actual);
        }
        return ToolResult.error("未知 action: " + action + "（支持 status/find/verify）");
    }

    private String status() throws Exception {
        StringBuilder sb = new StringBuilder("更新状态\n安装目录: ").append(jarDir);
        Path jar = jarDir.resolve("minion-0.1.0.jar");
        sb.append("\n当前 JAR: ").append(Files.isRegularFile(jar) ? jar + " | " + Files.size(jar) + " bytes" : "未找到");
        Path backup = jarDir.resolve("backup").resolve("minion-0.1.0.jar.bak");
        sb.append("\n回滚备份: ").append(Files.isRegularFile(backup) ? backup.toString() : "无");
        List<Path> packages = findPackages();
        sb.append("\n发现更新包: ").append(packages.size());
        for (Path p : packages) sb.append("\n- ").append(p);
        sb.append("\n说明: 关闭 Minion 后双击更新包中的 Update-Minion.cmd；失败会自动回滚。");
        return sb.toString();
    }

    private List<Path> findPackages() throws Exception {
        List<Path> out = new ArrayList<Path>();
        scan(jarDir, out);
        if (jarDir.getParent() != null) scan(jarDir.getParent(), out);
        Collections.sort(out);
        return out;
    }

    private static void scan(Path root, List<Path> out) throws Exception {
        if (!Files.isDirectory(root)) return;
        DirectoryStream<Path> ds = Files.newDirectoryStream(root, "minion-update-*");
        try {
            for (Path p : ds) if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("Update-Minion.cmd"))) out.add(p.toAbsolutePath());
        } finally { ds.close(); }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        java.io.InputStream in = Files.newInputStream(file);
        try {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        } finally { in.close(); }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}
