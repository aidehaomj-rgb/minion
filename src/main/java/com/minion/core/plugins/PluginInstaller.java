package com.minion.core.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * §7.2 插件安装器：复制到版本化缓存 plugins/cache/{market}/{plugin}/{version}/，
 * 元数据 installed_plugins.json（含启用版本）；缺依赖安装失败并保留旧版本（不半安装）；
 * 版本升级缓存隔离（每版本独立目录，回退=改元数据指向旧目录）。
 * 启用状态存 settings.json enabledPlugins（经 SettingsChain/Config 读取；写入走
 * jarDir config.properties 键 plugins.enabled.<name>，settings.json 由用户/上层维护）。
 */
public final class PluginInstaller {

    /** 已安装条目 */
    public static final class Installed {
        public final String name, market, version;
        public final boolean enabled;
        public final Path cacheDir;

        Installed(String name, String market, String version, boolean enabled, Path cacheDir) {
            this.name = name;
            this.market = market;
            this.version = version;
            this.enabled = enabled;
            this.cacheDir = cacheDir;
        }
    }

    private final Path cacheRoot;      // {jarDir}/.minion/plugins/cache
    private final Path installedFile;  // {jarDir}/.minion/plugins/installed_plugins.json
    private final PluginMarketplace market;

    public PluginInstaller(Path jarDir, PluginMarketplace market) {
        Path plugins = jarDir.resolve(".minion").resolve("plugins");
        this.cacheRoot = plugins.resolve("cache");
        this.installedFile = plugins.resolve("installed_plugins.json");
        this.market = market;
    }

    public Path cacheRoot() { return cacheRoot; }

    /** 已安装列表（enabledPlugins 逗号键经 enabledOf 判定） */
    public List<Installed> installed() {
        List<Installed> out = new ArrayList<Installed>();
        try {
            if (!Files.isRegularFile(installedFile)) return out;
            JsonElement parsed = JsonParser.parseString(
                    new String(Files.readAllBytes(installedFile), StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) return out;
            for (JsonElement e : parsed.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String name = o.has("name") ? o.get("name").getAsString() : null;
                String ver = o.has("version") ? o.get("version").getAsString() : "?";
                String mk = o.has("market") ? o.get("market").getAsString() : "local";
                String dir = o.has("dir") ? o.get("dir").getAsString() : null;
                if (name == null || dir == null) continue;
                out.add(new Installed(name, mk, ver, enabledNames().contains(name), Paths(dir)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** settings.json enabledPlugins（JSON 数组文本）或 config.properties plugins.enabled 列表 */
    public java.util.Set<String> enabledNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        try {
            JsonElement parsed = JsonParser.parseString(
                    new String(Files.readAllBytes(enabledFile()), StandardCharsets.UTF_8));
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("enabledPlugins")
                    && parsed.getAsJsonObject().get("enabledPlugins").isJsonArray())
                for (JsonElement e : parsed.getAsJsonObject().getAsJsonArray("enabledPlugins"))
                    if (e.isJsonPrimitive()) names.add(e.getAsString());
        } catch (Exception ignored) {
        }
        if (names.isEmpty()) {
            Path legacy = installedFile.getParent().resolve("enabled_plugins.txt");
            try {
                if (Files.isRegularFile(legacy))
                    for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8))
                        if (!line.trim().isEmpty()) names.add(line.trim());
            } catch (IOException ignored) {
            }
        }
        return names;
    }

    private Path enabledFile() {
        return installedFile.getParent().resolve("settings.json");
    }

    /**
     * 安装/升级：依赖本地市场解析（缺任一依赖 → 失败保留旧版本）；成功=复制到
     * 版本化缓存目录 + 元数据指向新目录（旧版本目录保留=缓存隔离，可回退）。
     */
    public synchronized String install(String pluginName) {
        PluginManifest found = market.find(pluginName);
        if (found == null) return "未在本地市场找到插件: " + pluginName;
        // 依赖检查：全部依赖须能在本地市场找到（禁止联网解析）
        for (String dep : found.dependencies.keySet()) {
            if (market.find(dep) == null)
                return "安装失败：缺少依赖 " + dep + "（本地市场无此插件，禁止联网解析）；已保留原版本";
        }
        Path target = cacheRoot.resolve(marketNameOf(found)).resolve(found.name).resolve(found.version);
        try {
            if (Files.exists(target)) deleteRecursively(target);   // 同版本重装=干净覆盖
            copyRecursively(found.dir, target);
        } catch (Exception e) {
            return "安装失败（缓存复制）: " + e.getMessage() + "；已保留原版本";
        }
        PluginManifest cached;
        try {
            cached = PluginManifest.load(target);
        } catch (Exception e) {
            try { deleteRecursively(target); } catch (Exception ignored) { }
            return "安装失败（缓存 manifest 无效）: " + e.getMessage() + "；已保留原版本";
        }
        saveMetadata(cached.name, found, target);
        if (!enabledNames().contains(cached.name)) setEnabled(cached.name, true);   // 默认启用
        return "已安装 " + cached.name + "@" + cached.version
                + "（缓存隔离目录 " + target + "；默认启用，/plugin disable 关闭）";
    }

    public synchronized String uninstall(String pluginName) {
        List<JsonObject> kept = new ArrayList<JsonObject>();
        boolean removed = false;
        for (Installed i : installed()) {
            if (i.name.equals(pluginName)) { removed = true; continue; }
            kept.add(record(i));
        }
        if (!removed) return "未安装: " + pluginName;
        writeMetadata(kept);
        return "已卸载 " + pluginName + "（缓存目录保留可重装；彻底清理请手动删除 "
                + cacheRoot.resolve("*/" + pluginName) + "）";
    }

    public synchronized String setEnabled(String pluginName, boolean enabled) {
        java.util.Set<String> names = enabledNames();
        if (enabled) names.add(pluginName);
        else names.remove(pluginName);
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String n : names) arr.add(n);
            root.add("enabledPlugins", arr);
            Files.createDirectories(enabledFile().getParent());
            Files.write(enabledFile(), root.toString().getBytes(StandardCharsets.UTF_8));
            return (enabled ? "已启用 " : "已停用 ") + pluginName
                    + (enabled ? "（技能/命令生效需 /reload-plugins 或新会话）" : "（下一轮起完全失效）");
        } catch (Exception e) {
            return "写入启用状态失败: " + e.getMessage();
        }
    }

    /** 回退到上一个缓存版本（版本目录按名排序的前一个） */
    public synchronized String rollback(String pluginName) {
        List<Installed> all = installed();
        Installed current = null;
        for (Installed i : all) if (i.name.equals(pluginName)) { current = i; break; }
        if (current == null) return "未安装: " + pluginName;
        Path versionsRoot = current.cacheDir.getParent();
        List<String> versions = new ArrayList<String>();
        try {
            for (Path p : Files.newDirectoryStream(versionsRoot))
                if (Files.isDirectory(p)) versions.add(p.getFileName().toString());
        } catch (IOException e) {
            return "读取版本目录失败: " + e.getMessage();
        }
        versions.sort(Comparator.naturalOrder());
        int idx = versions.indexOf(current.version);
        if (idx <= 0) return "无更早版本可回退（当前 " + current.version + "）";
        String previous = versions.get(idx - 1);
        for (Installed i : all) if (i.name.equals(pluginName)) {
            saveMetadata(i.name, previous, versionsRoot.resolve(previous), i.market);
            return "已回退 " + pluginName + ": " + current.version + " → " + previous;
        }
        return "回退失败";
    }

    // ==================== 内部 ====================

    private void saveMetadata(String name, PluginManifest m, Path dir) {
        saveMetadata(name, m.version, dir, marketNameOf(m));
    }

    private void saveMetadata(String name, String version, Path dir, String marketName) {
        List<JsonObject> rows = new ArrayList<JsonObject>();
        for (Installed i : installed()) if (!i.name.equals(name)) rows.add(record(i));
        JsonObject fresh = new JsonObject();
        fresh.addProperty("name", name);
        fresh.addProperty("version", version);
        fresh.addProperty("market", marketName);
        fresh.addProperty("dir", dir.toString());
        rows.add(fresh);
        writeMetadata(rows);
    }

    private static JsonObject record(Installed i) {
        JsonObject o = new JsonObject();
        o.addProperty("name", i.name);
        o.addProperty("version", i.version);
        o.addProperty("market", i.market);
        o.addProperty("dir", i.cacheDir.toString());
        return o;
    }

    private void writeMetadata(List<JsonObject> rows) {
        try {
            Files.createDirectories(installedFile.getParent());
            JsonArray arr = new JsonArray();
            for (JsonObject o : rows) arr.add(o);
            Files.write(installedFile, arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[minion] 写入插件元数据失败: " + e.getMessage());
        }
    }

    private static String marketNameOf(PluginManifest m) {
        return m.dir == null || m.dir.getParent() == null || m.dir.getParent().getFileName() == null
                ? "local" : m.dir.getParent().getFileName().toString();
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path dest = to.resolve(from.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e)
                    throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 兼容 Paths.get 单参（避免与 java.nio.file.Paths 混用的局部别名） */
    private static Path Paths(String path) { return java.nio.file.Paths.get(path); }
}
