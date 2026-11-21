package com.minion.core.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §7.2 插件 manifest（.minion-plugin/plugin.json；缺失时按目录约定推断）。
 * 结构：commands/ agents/ skills/ hooks/hooks.json output-styles/ 目录 +
 * manifest 可声明 mcpServers、lspServers、userConfig、dependencies。
 * 只使用本地/内网依赖；hooks/MCP/LSP/statusLine 命令按 §10.9 做内容与变更信任复核。
 */
public final class PluginManifest {

    public final String name;
    public final String version;          // 缺省 "0.0.0"
    public final String description;
    /** 依赖（name → 版本要求；本地市场解析，安装时禁止联网） */
    public final Map<String, String> dependencies;
    /** 声明的 MCP servers（server 名 → {command,args,env}）——启用须过 §10.9 信任 */
    public final Map<String, JsonObject> mcpServers;
    /** 声明的 LSP servers（语言 → command）——启用须过 §10.9 信任 */
    public final Map<String, String> lspServers;
    /** hooks/hooks.json 声明的可执行命令（信任复核清单用） */
    public final List<String> hookCommands;
    /** statusLine 命令（可空） */
    public final String statusLine;
    public final Path dir;

    private PluginManifest(String name, String version, String description,
            Map<String, String> dependencies, Map<String, JsonObject> mcpServers,
            Map<String, String> lspServers, List<String> hookCommands,
            String statusLine, Path dir) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.dependencies = dependencies;
        this.mcpServers = mcpServers;
        this.lspServers = lspServers;
        this.hookCommands = hookCommands;
        this.statusLine = statusLine;
        this.dir = dir;
    }

    public static PluginManifest load(Path pluginDir) throws Exception {
        Path manifestFile = pluginDir.resolve(".minion-plugin").resolve("plugin.json");
        JsonObject root = null;
        if (Files.isRegularFile(manifestFile)) {
            JsonElement parsed = JsonParser.parseString(
                    new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) root = parsed.getAsJsonObject();
        }
        String name = pluginDir.getFileName() == null ? "plugin" : pluginDir.getFileName().toString();
        String version = "0.0.0", description = "", statusLine = null;
        Map<String, String> deps = new LinkedHashMap<String, String>();
        Map<String, JsonObject> mcp = new LinkedHashMap<String, JsonObject>();
        Map<String, String> lsp = new LinkedHashMap<String, String>();
        if (root != null) {
            if (str(root, "name") != null) name = str(root, "name");
            if (str(root, "version") != null) version = str(root, "version");
            if (str(root, "description") != null) description = str(root, "description");
            if (str(root, "statusLine") != null) statusLine = str(root, "statusLine");
            if (root.has("dependencies") && root.get("dependencies").isJsonObject())
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("dependencies").entrySet())
                    deps.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "*");
            if (root.has("mcpServers") && root.get("mcpServers").isJsonObject())
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("mcpServers").entrySet())
                    if (e.getValue().isJsonObject()) mcp.put(e.getKey(), e.getValue().getAsJsonObject());
            if (root.has("lspServers") && root.get("lspServers").isJsonObject())
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("lspServers").entrySet())
                    if (e.getValue().isJsonPrimitive()) lsp.put(e.getKey(), e.getValue().getAsString());
        }
        return new PluginManifest(name, version, description,
                Collections.unmodifiableMap(deps), Collections.unmodifiableMap(mcp),
                Collections.unmodifiableMap(lsp),
                Collections.unmodifiableList(readHookCommands(pluginDir)),
                statusLine, pluginDir);
    }

    /** hooks/hooks.json（CC 结构）中的 command 值列表——§10.9 信任复核清单 */
    private static List<String> readHookCommands(Path pluginDir) {
        List<String> commands = new ArrayList<String>();
        try {
            Path hooks = pluginDir.resolve("hooks").resolve("hooks.json");
            if (!Files.isRegularFile(hooks)) return commands;
            JsonElement parsed = JsonParser.parseString(
                    new String(Files.readAllBytes(hooks), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return commands;
            JsonObject events = parsed.getAsJsonObject().getAsJsonObject("hooks");
            if (events == null) return commands;
            for (Map.Entry<String, JsonElement> event : events.entrySet()) {
                if (!event.getValue().isJsonArray()) continue;
                for (JsonElement group : event.getValue().getAsJsonArray()) {
                    if (!group.isJsonObject()) continue;
                    JsonArray hooksArr = group.getAsJsonObject().getAsJsonArray("hooks");
                    if (hooksArr == null) continue;
                    for (JsonElement h : hooksArr) {
                        if (!h.isJsonObject()) continue;
                        String command = str(h.getAsJsonObject(), "command");
                        if (command != null && !command.trim().isEmpty()) commands.add(command.trim());
                    }
                }
            }
        } catch (Exception ignored) {   // 声明文件损坏：无命令可执行（fail-closed 方向）
        }
        return commands;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** 市场列表用：manifest 无效的占位（不抛异常，标记版本 '?'） */
    static PluginManifest broken(Path dir) {
        return new PluginManifest(dir.getFileName() == null ? "plugin" : dir.getFileName().toString(),
                "?", "(manifest 无效)", Collections.<String, String>emptyMap(),
                Collections.<String, JsonObject>emptyMap(), Collections.<String, String>emptyMap(),
                Collections.<String>emptyList(), null, dir);
    }
}
