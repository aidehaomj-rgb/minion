package com.minion.core.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §7.2 本地插件市场：只保留本地源（directory=目录直读；file=zip 解包目录说明的索引），
 * git 源需本机 git x64（内网 git 服务器），github/url/npm 一律剔除。
 * 已知市场存 {jarDir}/.minion/plugins/known_marketplaces.json。
 */
public final class PluginMarketplace {

    /** 市场条目：name + type(directory|git) + path/url */
    public static final class Source {
        public final String name;
        public final String type;     // directory | git
        public final String location; // 目录路径或内网 git URL
        public final Path dir;        // directory 类型解析出的目录

        Source(String name, String type, String location, Path dir) {
            this.name = name;
            this.type = type;
            this.location = location;
            this.dir = dir;
        }
    }

    public static final String KNOWN_FILE = "known_marketplaces.json";
    /** git 源暂不解析（需 Win7 实机 git x64 POC 证据后接入，§12.3 同口径） */

    private final Path knownFile;

    public PluginMarketplace(Path jarDir) {
        this.knownFile = jarDir.resolve(".minion").resolve("plugins").resolve(KNOWN_FILE);
    }

    /** 已知市场列表（无文件=空） */
    public List<Source> known() {
        List<Source> out = new ArrayList<Source>();
        try {
            if (!Files.isRegularFile(knownFile)) return out;
            JsonElement parsed = JsonParser.parseString(
                    new String(Files.readAllBytes(knownFile), StandardCharsets.UTF_8));
            if (!parsed.isJsonArray()) return out;
            for (JsonElement e : parsed.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String name = o.has("name") ? o.get("name").getAsString() : null;
                String type = o.has("type") ? o.get("type").getAsString() : "directory";
                String location = o.has("location") ? o.get("location").getAsString() : null;
                if (name == null || location == null) continue;
                if ("github".equalsIgnoreCase(type) || "url".equalsIgnoreCase(type)
                        || "npm".equalsIgnoreCase(type))
                    continue;   // 在线源剔除（§7.2）
                Path dir = "directory".equals(type) ? Paths.get(location) : null;
                out.add(new Source(name, type, location, dir));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 登记本地市场（directory 须为存在的目录；git 记录待 POC 后启用解析） */
    public synchronized String add(String name, String type, String location) {
        if (name == null || name.trim().isEmpty() || location == null || location.trim().isEmpty())
            return "用法: /plugin market add <名> <directory|git> <路径或内网URL>";
        String t = "git".equalsIgnoreCase(type) ? "git" : "directory";
        if ("directory".equals(t)) {
            try {
                if (!Files.isDirectory(Paths.get(location))) return "目录不存在: " + location;
            } catch (java.nio.file.InvalidPathException badPath) {
                return "非法路径: " + location + "（directory 类型须为本地目录）";
            }
        }
        List<Source> known = known();
        for (Source s : known)
            if (s.name.equals(name)) return "市场已存在: " + name + "（先 remove）";
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        for (Source s : known) row(rows, s);
        Map<String, String> fresh = new LinkedHashMap<String, String>();
        fresh.put("name", name);
        fresh.put("type", t);
        fresh.put("location", location);
        rows.add(fresh);
        try {
            Files.createDirectories(knownFile.getParent());
            JsonArray arr = new JsonArray();
            for (Map<String, String> r : rows) {
                JsonObject o = new JsonObject();
                for (Map.Entry<String, String> e : r.entrySet()) o.addProperty(e.getKey(), e.getValue());
                arr.add(o);
            }
            Files.write(knownFile, arr.toString().getBytes(StandardCharsets.UTF_8));
            return "已登记市场 " + name + "（" + t + "）";
        } catch (Exception e) {
            return "登记失败: " + e.getMessage();
        }
    }

    public synchronized String remove(String name) {
        List<Source> known = known();
        boolean removed = false;
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        for (Source s : known) {
            if (s.name.equals(name)) { removed = true; continue; }
            row(rows, s);
        }
        if (!removed) return "未找到市场: " + name;
        try {
            JsonArray arr = new JsonArray();
            for (Map<String, String> r : rows) {
                JsonObject o = new JsonObject();
                for (Map.Entry<String, String> e : r.entrySet()) o.addProperty(e.getKey(), e.getValue());
                arr.add(o);
            }
            Files.write(knownFile, arr.toString().getBytes(StandardCharsets.UTF_8));
            return "已移除市场 " + name;
        } catch (Exception e) {
            return "移除失败: " + e.getMessage();
        }
    }

    private static void row(List<Map<String, String>> rows, Source s) {
        Map<String, String> r = new LinkedHashMap<String, String>();
        r.put("name", s.name);
        r.put("type", s.type);
        r.put("location", s.location);
        rows.add(r);
    }

    /** 列出市场内插件（directory 类型直读子目录，逐个读 manifest） */
    public List<PluginManifest> listPlugins(Source market) {
        List<PluginManifest> out = new ArrayList<PluginManifest>();
        if (market == null || market.dir == null || !Files.isDirectory(market.dir)) return out;
        try {
            for (Path child : Files.newDirectoryStream(market.dir)) {
                if (!Files.isDirectory(child)) continue;
                try {
                    out.add(PluginManifest.load(child));
                } catch (Exception bad) {   // 单个坏插件不阻断列表
                    out.add(PluginManifest.broken(child));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 在全部本地 directory 市场里按名找插件 */
    public PluginManifest find(String pluginName) {
        for (Source market : known()) {
            for (PluginManifest m : listPlugins(market))
                if (m.name.equals(pluginName)) return m;
        }
        return null;
    }

    public Path knownFile() { return knownFile; }
}
