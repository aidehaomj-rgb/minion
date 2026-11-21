package com.minion.core.ui.outputstyles;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.4 §10.5 输出样式目录：内置 default/Explanatory/Learning + 自定义
 * {jarDir 与 workspace}/.minion/output-styles/*.md；优先级 built-in &lt; user &lt; project
 * （同名覆盖）；markdown frontmatter 解析（与 SkillManager 同格式约定）。
 */
public final class OutputStyleCatalog {

    private final List<OutputStyle> merged;

    private OutputStyleCatalog(List<OutputStyle> merged) { this.merged = merged; }

    public static OutputStyleCatalog load(Path jarDir, Path workspace) {
        Map<String, OutputStyle> byName = new LinkedHashMap<String, OutputStyle>();
        for (OutputStyle s : builtins()) byName.put(s.name, s);
        scan(dirStyles(jarDir), "user", byName);
        scan(dirStyles(workspace), "project", byName);
        List<OutputStyle> out = new ArrayList<OutputStyle>(byName.values());
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return new OutputStyleCatalog(out);
    }

    private static Path dirStyles(Path root) {
        return root == null ? null : root.resolve(".minion").resolve("output-styles");
    }

    public List<OutputStyle> all() { return new ArrayList<OutputStyle>(merged); }

    /** 按名解析（缺省/default/未知 → 内置 default；大小写不敏感） */
    public OutputStyle resolve(String name) {
        if (name != null && !name.trim().isEmpty())
            for (OutputStyle s : merged)
                if (s.name.equalsIgnoreCase(name.trim())) return s;
        return resolve(OutputStyle.DEFAULT);
    }

    private static void scan(Path dir, String source, Map<String, OutputStyle> byName) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.md")) {
            for (Path file : files) {
                OutputStyle parsed = parse(file, source);
                if (parsed != null) byName.put(parsed.name, parsed);   // 同名高优先级覆盖
            }
        } catch (Exception ignored) {
        }
    }

    /** .md 解析：--- frontmatter --- body；缺 frontmatter 整文为 body（name=文件名） */
    static OutputStyle parse(Path file, String source) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            String description = "", body = text.trim();
            boolean keep = true;
            if (text.startsWith("---")) {
                int end = text.indexOf("\n---", 3);
                if (end > 0) {
                    String fm = text.substring(3, end);
                    body = text.substring(end + 4).trim();
                    for (String line : fm.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon <= 0) continue;
                        String key = line.substring(0, colon).trim();
                        String value = line.substring(colon + 1).trim()
                                .replaceAll("^[\"']|[\"']$", "");
                        if ("name".equals(key)) name = value;
                        else if ("description".equals(key)) description = value;
                        else if ("keep-coding-instructions".equals(key))
                            keep = !"false".equalsIgnoreCase(value);
                    }
                }
            }
            if (name.trim().isEmpty()) return null;
            return new OutputStyle(name, description, keep, body, source);
        } catch (Exception bad) {
            return null;
        }
    }

    private static List<OutputStyle> builtins() {
        List<OutputStyle> out = new ArrayList<OutputStyle>();
        out.add(OutputStyle.builtin(OutputStyle.DEFAULT, "默认（不改动系统提示风格）", true, ""));
        out.add(OutputStyle.builtin(OutputStyle.EXPLANATORY,
                "解释模式：交付后附加简短教学段", true,
                "输出风格：在完成任务的同时，最后附加一个简短的「原理说明」段，"
                        + "用 2-4 句解释本次改动为什么这样设计，帮助用户理解；不要展开成长篇教程。"));
        out.add(OutputStyle.builtin(OutputStyle.LEARNING,
                "学习模式：在关键步骤插入用户动手点", true,
                "输出风格：采用教学式推进。遇到核心实现步骤时，插入一个明确的 "
                        + "TODO(human) 标记点并暂停，等用户亲手补写 2-10 行代码后再继续；"
                        + "其余部分照常完成并给出核对要点。"));
        return out;
    }
}
