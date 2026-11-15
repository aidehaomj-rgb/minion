package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Resource declarations derived from audited built-in contracts, NOT model-supplied lock hints. */
public final class ToolResourceScope {
    public final boolean known;
    public final List<Path> paths;

    private ToolResourceScope(boolean known, List<Path> paths) {
        this.known = known;
        this.paths = Collections.unmodifiableList(new ArrayList<Path>(paths));
    }

    private static ToolResourceScope unknown() {
        return new ToolResourceScope(false, Collections.<Path>emptyList());
    }

    /** No tool execution, directory creation, permission grants, or shell parsing. */
    public static ToolResourceScope resolve(String name, JsonObject args, Workspace workspace) {
        List<String> raw = new ArrayList<String>();
        try {
            if (Arrays.asList("Read", "ViewImage", "Write", "Edit").contains(name)) {
                if (args.has("paths")) return unknown();
                required(raw, args, "path");
            } else if ("Excel".equals(name)) {
                String action = text(args, "action").toLowerCase(Locale.ROOT);
                // pandas filter expressions are code, not a bounded resource declaration.
                if (!text(args, "filter").trim().isEmpty() || args.has("paths")) return unknown();
                if (!Arrays.asList("inspect", "read", "profile", "validate", "merge_audit", "write",
                        "append", "merge", "pivot", "groupby", "export", "chart", "patch_cells").contains(action)) return unknown();
                if (!"write".equals(action)) required(raw, args, "path");
                else optional(raw, args, "path");
                if ("merge".equals(action) || "merge_audit".equals(action)) required(raw, args, "otherPath");
                if (!Arrays.asList("inspect", "read", "profile", "validate", "merge_audit").contains(action))
                    required(raw, args, "outputPath");
            } else if ("Document".equals(name)) {
                String action = text(args, "action").toLowerCase(Locale.ROOT);
                // Directory outputs, shared Office profiles and multi-input parsers stay exclusive.
                if (args.has("paths") || !Arrays.asList("inspect", "extract", "pdf_read", "pdf_view", "ofd_read", "ofd_view", "ofd_ocr", "gd_read", "gd_view", "ocr",
                        "docx_validate", "docx_create", "docx_replace", "pptx_create", "docx_template", "docx_report").contains(action)) return unknown();
                if (Arrays.asList("docx_create", "pptx_create", "docx_report").contains(action)) optional(raw, args, "path");
                else required(raw, args, "path");
                if (Arrays.asList("gd_read","gd_view","inspect","extract").contains(action)) optional(raw,args,"exportPath");
                if (Arrays.asList("docx_create", "docx_replace", "pptx_create", "docx_template", "docx_report").contains(action)) {
                    required(raw, args, "outputPath");
                    if ("docx_report".equals(action)) raw.add(text(args, "outputPath") + ".material-state.json");
                }
                if ("docx_validate".equals(action)) raw.add(text(args, "path") + ".material-state.json");
            } else return unknown();
            List<Path> resolved = new ArrayList<Path>();
            for (String item : raw) {
                Path lexical = PathsGuard.resolve(workspace.cwd().toString(), item).toAbsolutePath().normalize();
                // Include lexical locks too: direct Write/Edit acquire those locks inside execute().
                resolved.add(lexical);
                resolved.add(canonical(lexical));
            }
            return new ToolResourceScope(!resolved.isEmpty(), resolved);
        } catch (IOException | RuntimeException uncertain) { return unknown(); }
    }

    /** Resolve existing ancestors as well, so a new file under a symlink has one lock identity. */
    static Path canonical(Path path) throws IOException {
        Path ancestor = path;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null) throw new IOException("No existing resource ancestor");
        return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
    }

    /** Whitelist excludes browsers, Shell, control/state tools and arbitrary plugin code. */
    public static boolean parallelReadOnly(String name, JsonObject args) {
        try {
            if (Arrays.asList("Read", "ViewImage", "Glob", "Grep", "WebFetch").contains(name)) return true;
            String action = text(args, "action").toLowerCase(Locale.ROOT);
            if ("Excel".equals(name)) return text(args, "filter").trim().isEmpty()
                    && Arrays.asList("inspect", "read", "profile", "validate", "merge_audit").contains(action);
            return "Document".equals(name) && !text(args,"path").trim().toLowerCase(Locale.ROOT).endsWith(".gd")
                    && Arrays.asList("inspect", "extract", "pdf_read", "ofd_read", "docx_validate").contains(action);
        } catch (RuntimeException malformed) { return false; }
    }

    private static String text(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }
    private static void required(List<String> raw, JsonObject args, String key) {
        String value = text(args, key);
        if (value.trim().isEmpty()) throw new IllegalArgumentException("Missing resource: " + key);
        raw.add(value);
    }
    private static void optional(List<String> raw, JsonObject args, String key) {
        if (!text(args, key).trim().isEmpty()) raw.add(text(args, key));
    }
}
