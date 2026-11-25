package com.minion.core.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolResult;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded extraction from explicit successful receipts only. Never enumerates a directory. */
final class GeneratedArtifactCollector {
    static final int MAX_ARTIFACTS = 100;
    private static final int MAX_OUTPUT = 262144, MAX_PATH = 4096;
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[((?:\\\\.|[^\\]\\\\\\r\\n]){1,200})\\]\\(");
    private static final Pattern LINK_TITLE = Pattern.compile("\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\))\\s*$");
    private static final Pattern SAVED_RECEIPT = Pattern.compile(
            "(?im)^\\s*(?:[-*•✅✓]\\s*)?"
            + "(?:saved(?:\\s+to)?|created|generated|exported|written(?:\\s+to)?|output(?:\\s+file)?"
            + "|已保存(?:至|到)?|保存(?:至|到|成功)?|已生成|生成(?:成功|完成|至|到)?|已输出|输出(?:文件|路径|至|到)?|已导出|导出(?:至|到|成功)?|已写入|写入(?:至|到)?|文件路径|交付文件)"
            + "(?:\\s*(?:[:：=]|->|→)\\s*|\\s+)[\"'`《「]?(.+?\\.(?:md|markdown|txt|html?|docx?|pdf|ofd|gd|pptx?|xlsx?|csv|png|jpe?g|gif|bmp))[\"'`》」]?(?=\\s|$|[，。；,;（(])");
    /** F1 措辞容错（additive，旧 SAVED_RECEIPT 逐字保留）：中文回执允许行中出现（非行首锚定），
     *  覆盖“脚本完成，已生成 <path>”“文件：<path>”等 SAVED_RECEIPT 行首锚定漏掉的措辞。 */
    private static final Pattern EXTENDED_RECEIPT = Pattern.compile(
            "(?:已(?:生成|保存|写入|导出|输出)|(?:文件|产物|交付物|报告)(?:路径)?)"
            + "(?:\\s*(?:[:：=]|->|→|到|至|为)\\s*|\\s+)[\"'`《「]?(.+?\\.(?:md|markdown|txt|html?|docx?|pdf|ofd|gd|pptx?|xlsx?|csv|png|jpe?g|gif|bmp))[\"'`》」]?(?=\\s|$|[，。；,;（(])");
    /** F1 兜底扫描交付扩展名（不含 .txt，与旧回执特征化兼容）：只认盘上真实存在文件，不猜。
     *  线性分词（引号段含空格 / 裸 token）+ endsWith 判定，避免正则在超大 stdout 上逐位置回溯。 */
    private static final Pattern FALLBACK_TOKEN = Pattern.compile(
            "\"([^\"\\r\\n]{1,4096})\"|'([^'\\r\\n]{1,4096})'|([^\\s\"'`《「》」<>|*?（(\\r\\n]+)");
    private static final String[] DELIV_SUFFIXES = {
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".pdf", ".md", ".csv",
            ".html", ".htm", ".png", ".jpg", ".jpeg"};
    /** 每次工具结果兜底扫描登记上限（防 stdout 刷路径轰炸） */
    static final int MAX_FALLBACK = 10;
    private final Predicate<Path> allowed;

    GeneratedArtifactCollector(Predicate<Path> allowed) { this.allowed = allowed; }

    List<GeneratedArtifact> fromTool(ToolCall call, ToolResult result, Path cwd, String taskId, long revision) {
        Map<String, GeneratedArtifact> found = new LinkedHashMap<String, GeneratedArtifact>();
        if (call == null || result == null || !result.ok) return new ArrayList<GeneratedArtifact>();
        JsonObject args;
        try { args = JsonParser.parseString(call.arguments).getAsJsonObject(); }
        catch (RuntimeException invalid) { return new ArrayList<GeneratedArtifact>(); }
        String tool = call.name;
        if ("Write".equals(tool) || "Edit".equals(tool)
                || "Artwork".equals(tool) && "template".equals(string(args, "action"))) {
            add(found, string(args, "path"), "", cwd, GeneratedArtifact.DELIVERABLE, taskId, revision, tool);
        } else if (Arrays.asList("Document", "Excel", "Artwork", "Database").contains(tool)
                && StopGate.mutationEvidence(tool, args)) {
            add(found, string(args, "outputPath"), "", cwd, GeneratedArtifact.DELIVERABLE, taskId, revision, tool);
        } else if ("ArtifactVerification".equals(tool) && "webpage".equals(string(args,"action"))) {
            add(found,string(args,"screenshotPath"),"页面验证截图",cwd,GeneratedArtifact.REFERENCE,taskId,revision,tool);
        } else if (Arrays.asList("Python", "Bash", "Shell").contains(tool)) {
            String output = bounded(result.output);
            // A whole JSON receipt or a complete JSON line in stdout. No argument/code/path guessing.
            structured(found, output, cwd, taskId, revision, tool);
            saved(found, output, cwd, taskId, revision, tool);
            for (String line : output.split("\\r?\\n")) {
                if (found.size() >= MAX_ARTIFACTS) break;
                if (line.trim().startsWith("{")) structured(found, line, cwd, taskId, revision, tool);
            }
            links(found, output, cwd, GeneratedArtifact.DELIVERABLE, taskId, revision, tool);
            extended(found, output, cwd, taskId, revision, tool);
            fallbackScan(found, output, cwd, taskId, revision, tool);
        }
        return new ArrayList<GeneratedArtifact>(found.values());
    }

    List<GeneratedArtifact> references(String answer, Path cwd, String taskId, long revision) {
        Map<String, GeneratedArtifact> found = new LinkedHashMap<String, GeneratedArtifact>();
        links(found, bounded(answer), cwd, GeneratedArtifact.REFERENCE, taskId, revision, "assistant");
        return new ArrayList<GeneratedArtifact>(found.values());
    }

    GeneratedArtifact executionRecord(Path file, String taskId, long revision) {
        Map<String, GeneratedArtifact> found = new LinkedHashMap<String, GeneratedArtifact>();
        add(found, file.toString(), "任务执行记录", file.getParent(), GeneratedArtifact.EXECUTION_RECORD,
                taskId, revision, "TaskReportWriter");
        return found.isEmpty() ? null : found.values().iterator().next();
    }

    private void structured(Map<String, GeneratedArtifact> found, String text, Path cwd,
                            String taskId, long revision, String tool) {
        if (text.isEmpty()) return;
        try {
            JsonElement json = JsonParser.parseString(text);
            if (!json.isJsonObject()) return;
            JsonElement artifacts = json.getAsJsonObject().get("artifacts");
            if (artifacts == null || !artifacts.isJsonArray()) return;
            int inspected = 0;
            for (JsonElement item : artifacts.getAsJsonArray()) {
                if (++inspected > MAX_ARTIFACTS || found.size() >= MAX_ARTIFACTS) break;
                if (item.isJsonObject()) {
                    JsonObject a = item.getAsJsonObject();
                    add(found, string(a, "path"), string(a, "title"), cwd,
                            GeneratedArtifact.DELIVERABLE, taskId, revision, tool);
                } else if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    add(found, item.getAsString(), "", cwd, GeneratedArtifact.DELIVERABLE, taskId, revision, tool);
                }
            }
        } catch (RuntimeException invalid) { /* Non-JSON stdout is not a structured receipt. */ }
    }

    /** Build scripts commonly emit a durable one-line receipt: saved C:\path\report.docx ... */
    private void saved(Map<String, GeneratedArtifact> found, String text, Path cwd,
                       String taskId, long revision, String tool) {
        Matcher matcher = SAVED_RECEIPT.matcher(text);
        int inspected = 0;
        while (matcher.find() && inspected++ < MAX_ARTIFACTS && found.size() < MAX_ARTIFACTS) {
            String raw = matcher.group(1).trim();
            if ((raw.startsWith("\"") && raw.endsWith("\""))
                    || (raw.startsWith("'") && raw.endsWith("'"))) {
                raw = raw.substring(1, raw.length() - 1);
            }
            add(found, raw, "", cwd, GeneratedArtifact.DELIVERABLE, taskId, revision, tool);
        }
    }

    /** F1 中文措辞容错回执（行中可匹配）；命中记 pattern=extended 便于评估误报率 */
    private void extended(Map<String, GeneratedArtifact> found, String text, Path cwd,
                          String taskId, long revision, String tool) {
        Matcher matcher = EXTENDED_RECEIPT.matcher(text);
        int inspected = 0;
        while (matcher.find() && inspected++ < MAX_ARTIFACTS && found.size() < MAX_ARTIFACTS) {
            String raw = matcher.group(1).trim();
            if ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'")))
                raw = raw.substring(1, raw.length() - 1);
            int before = found.size();
            add(found, raw, "", cwd, GeneratedArtifact.DELIVERABLE, taskId, revision, tool);
            if (found.size() > before) log("pattern=extended tool=" + tool + " path=" + raw);
        }
    }

    /** F1 stdout 交付扩展名兜底扫描（仅 Bash/Python/Shell ok==true 分支调用）：
     *  逐个走现有 add() 白名单+isRegularFile+toRealPath 校验，存在才登记；≤MAX_FALLBACK 条。 */
    private void fallbackScan(Map<String, GeneratedArtifact> found, String text, Path cwd,
                              String taskId, long revision, String tool) {
        Matcher matcher = FALLBACK_TOKEN.matcher(text);
        int added = 0;
        while (matcher.find() && added < MAX_FALLBACK && found.size() < MAX_ARTIFACTS) {
            String raw = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (raw == null) continue;
            raw = raw.trim();
            while (raw.length() > 0 && "，。；,;）)】]》」`'\"".indexOf(raw.charAt(raw.length() - 1)) >= 0)
                raw = raw.substring(0, raw.length() - 1);
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            boolean deliverable = false;
            for (String suffix : DELIV_SUFFIXES) if (lower.endsWith(suffix)) { deliverable = true; break; }
            if (!deliverable) continue;
            int before = found.size();
            add(found, raw, "", cwd, GeneratedArtifact.DELIVERABLE, taskId, revision, tool);
            if (found.size() > before) { added++; log("pattern=fallback-scan tool=" + tool + " path=" + raw); }
        }
    }

    private static void log(String message) { DiagnosticLog.info("artifact-collect", message); }

    private void links(Map<String, GeneratedArtifact> found, String text, Path cwd, String kind,
                       String taskId, long revision, String tool) {
        Matcher matcher = LINK.matcher(text);
        int inspected = 0;
        while (matcher.find() && inspected++ < MAX_ARTIFACTS && found.size() < MAX_ARTIFACTS) {
            int start = matcher.end(), end = start, depth = 1;
            boolean angle = start < text.length() && text.charAt(start) == '<';
            for (; end < text.length() && end - start <= MAX_PATH + 200; end++) {
                char c = text.charAt(end);
                if (c == '\n' || c == '\r') break;
                if (c == '\\' && end + 1 < text.length() && "()[]\\".indexOf(text.charAt(end + 1)) >= 0) { end++; continue; }
                if (angle && c == '>') angle = false;
                else if (!angle && c == '(') depth++;
                else if (!angle && c == ')' && --depth == 0) break;
            }
            // An incomplete/truncated Markdown link must never turn into a receipt.
            if (depth != 0) continue;
            String raw = text.substring(start, end).trim();
            if (raw.startsWith("<")) {
                int close = raw.indexOf('>');
                if (close < 0) continue;
                String suffix = raw.substring(close + 1);
                if (!suffix.trim().isEmpty() && !LINK_TITLE.matcher(suffix).matches()) continue;
                raw = raw.substring(1, close);
            } else {
                Matcher title = LINK_TITLE.matcher(raw);
                if (title.find()) raw = raw.substring(0, title.start());
            }
            raw = raw.replace("\\(", "(").replace("\\)", ")").replace("\\[", "[").replace("\\]", "]");
            add(found, raw, matcher.group(1), cwd, kind, taskId, revision, tool);
        }
    }

    private void add(Map<String, GeneratedArtifact> found, String raw, String title, Path cwd,
                     String kind, String taskId, long revision, String tool) {
        if (found.size() >= MAX_ARTIFACTS) { log("drop=too-many tool=" + tool); return; }
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_PATH) return;
        try {
            String value = raw.trim();
            Path p;
            if (value.regionMatches(true, 0, "file:", 0, 5)) p = Paths.get(new URI(value));
            else {
                // Only local paths; a Windows drive letter is not a URL scheme.
                if (value.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:.*") && !value.matches("(?s)^[A-Za-z]:[\\\\/].*")) {
                    log("drop=parse-failed tool=" + tool + " path=" + value); return;
                }
                p = Paths.get(value);
                if (!p.isAbsolute()) p = cwd.resolve(p);
            }
            p = p.toAbsolutePath().normalize();
            if (!allowed.test(p)) { log("drop=not-allowed tool=" + tool + " path=" + p); return; }
            if (!Files.isRegularFile(p)) { log("drop=not-regular-file tool=" + tool + " path=" + p); return; }
            p = p.toRealPath();
            if (!allowed.test(p)) { log("drop=not-allowed tool=" + tool + " path=" + p); return; }
            GeneratedArtifact a = new GeneratedArtifact();
            a.path = p.toString(); a.title = title == null || title.trim().isEmpty() ? p.getFileName().toString() : title.substring(0, Math.min(200, title.length()));
            a.kind = kind; a.taskId = taskId; a.revision = revision; a.sourceTool = tool;
            a.size = Files.size(p); a.updatedAt = System.currentTimeMillis();
            found.put(a.path, a);
            log("accept=" + a.path + " tool=" + tool + " kind=" + kind);
        } catch (Exception invalidOrGone) {
            log("drop=parse-failed tool=" + tool + " path=" + raw + " err=" + invalidOrGone.getClass().getSimpleName());
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
    }

    private static String bounded(String text) {
        return text == null ? "" : text.substring(0, Math.min(MAX_OUTPUT, text.length()));
    }
}
