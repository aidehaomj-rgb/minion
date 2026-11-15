package com.minion.core.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
            "(?im)^\\s*saved\\s+(.+?\\.(?:md|markdown|txt|html?|docx?|pdf|ofd|gd|pptx?|xlsx?|csv|png|jpe?g|gif|bmp))[\"']?(?=\\s|$)");
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
        if (found.size() >= MAX_ARTIFACTS || raw == null || raw.trim().isEmpty() || raw.length() > MAX_PATH) return;
        try {
            String value = raw.trim();
            Path p;
            if (value.regionMatches(true, 0, "file:", 0, 5)) p = Paths.get(new URI(value));
            else {
                // Only local paths; a Windows drive letter is not a URL scheme.
                if (value.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:.*") && !value.matches("(?s)^[A-Za-z]:[\\\\/].*")) return;
                p = Paths.get(value);
                if (!p.isAbsolute()) p = cwd.resolve(p);
            }
            p = p.toAbsolutePath().normalize();
            if (!allowed.test(p) || !Files.isRegularFile(p)) return;
            p = p.toRealPath();
            if (!allowed.test(p)) return;
            GeneratedArtifact a = new GeneratedArtifact();
            a.path = p.toString(); a.title = title == null || title.trim().isEmpty() ? p.getFileName().toString() : title.substring(0, Math.min(200, title.length()));
            a.kind = kind; a.taskId = taskId; a.revision = revision; a.sourceTool = tool;
            a.size = Files.size(p); a.updatedAt = System.currentTimeMillis();
            found.put(a.path, a);
        } catch (Exception invalidOrGone) { /* Invalid, unauthorized, missing or concurrently removed. */ }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : "";
    }

    private static String bounded(String text) {
        return text == null ? "" : text.substring(0, Math.min(MAX_OUTPUT, text.length()));
    }
}
