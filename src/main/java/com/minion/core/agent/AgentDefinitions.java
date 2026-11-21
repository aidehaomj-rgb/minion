package com.minion.core.agent;

import com.minion.core.diagnostics.DiagnosticLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自定义子代理定义（开发说明 v2.4 §7.3）：.minion/agents/*.md
 * frontmatter：name/description/tools/model → Agent 工具 subagent_type 可选值。
 * tools 为逗号分隔白名单（该子代理只注册白名单内工具）；model 记录但被锁定为
 * 主会话模型（既有规则：不允许单独指定 model）。同名按项目层级就近优先。
 */
public final class AgentDefinitions {

    public static final class Definition {
        public final String name;
        public final String description;
        public final String tools;     // 逗号分隔白名单；空=不限制
        public final String model;     // 记录用（实际锁定主会话模型）
        public final Path path;

        Definition(String name, String description, String tools, String model, Path path) {
            this.name = name;
            this.description = description;
            this.tools = tools;
            this.model = model;
            this.path = path;
        }
    }

    private AgentDefinitions() { }

    /** 从 workDir 向上到盘根收集 .minion/agents/*.md（就近优先，同名去重） */
    public static List<Definition> loadAll(String workDir) {
        List<Definition> out = new ArrayList<Definition>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Path dir = Paths.get(workDir).toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            Path agentsDir = dir.resolve(".minion").resolve("agents");
            if (!Files.isDirectory(agentsDir)) continue;
            List<Path> files = new ArrayList<Path>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(agentsDir, "*.md")) {
                for (Path p : ds) files.add(p);
            } catch (IOException unreadable) {
                continue;
            }
            Collections.sort(files, (a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            for (Path f : files) {
                Definition d = parse(f);
                if (d != null && seen.add(d.name.toLowerCase(java.util.Locale.ROOT))) out.add(d);
            }
        }
        return out;
    }

    public static Definition find(String workDir, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        for (Definition d : loadAll(workDir))
            if (d.name.equalsIgnoreCase(name.trim())) return d;
        return null;
    }

    /** 解析 frontmatter（--- 分隔的 YAML 子集：key: value 行） */
    static Definition parse(Path file) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (!text.startsWith("---")) return null;
            int end = text.indexOf("\n---", 3);
            if (end < 0) return null;
            String name = null, description = "", tools = "", model = "";
            for (String line : text.substring(3, end).split("\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim().replace("\"", "");
                if ("name".equals(key) && !value.isEmpty()) name = value;
                else if ("description".equals(key)) description = value;
                else if ("tools".equals(key)) tools = value;
                else if ("model".equals(key)) model = value;
            }
            if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
                DiagnosticLog.warn("agents", "定义缺 name 或非法，跳过: " + file);
                return null;
            }
            return new Definition(name, description, tools, model, file);
        } catch (Exception unreadable) {
            return null;
        }
    }
}
