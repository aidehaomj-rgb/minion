package com.minion.core.tools.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * LSP 客户端工具（开发说明 v2.4 §3.1，lsp.enabled 默认 false）——纯 JSON-RPC over stdio
 * 直写 LSP 协议（Content-Length 帧），零外部依赖（lsp4j POC 门控解除前的自持实现）。
 *
 * <p>操作：goToDefinition/findReferences/hover/documentSymbol/workspaceSymbol/goToImplementation/
 * incomingCalls/outgoingCalls；filePath+line+character（1-based）。被动诊断：该文件编辑成功后
 * 的 publishDiagnostics 由 {@link #cachedDiagnostics} 提供（工具批后附件注入走后续接线）。
 * server 由配置声明（lsp.servers.<lang>=command args）；能力探测失败禁用该 server 并明确
 * 提示，不影响 Minion 启动。
 */
public final class LspTool implements Tool {

    /** 会话级 LSP 会话（按语言缓存；进程生命周期随会话） */
    public static final class LanguageSession {
        final Process process;
        final BufferedWriter stdin;
        final BufferedReader stdout;
        volatile boolean healthy = true;
        int nextId = 1;

        LanguageSession(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private final java.util.Map<String, LanguageSession> sessions =
            new java.util.concurrent.ConcurrentHashMap<String, LanguageSession>();
    private final String workDir;
    private final java.util.Map<String, String> serverCommands;   // 语言 → 启动命令
    /** 最近的 publishDiagnostics（per filePath）——被动通道 */
    private final java.util.Map<String, JsonArray> cachedDiagnostics =
            new java.util.concurrent.ConcurrentHashMap<String, JsonArray>();

    private static final int INIT_TIMEOUT_MS = 15000;
    private static final int REQUEST_TIMEOUT_MS = 10000;

    public LspTool(String workDir, java.util.Map<String, String> serverCommands) {
        this.workDir = workDir;
        this.serverCommands = serverCommands == null
                ? java.util.Collections.<String, String>emptyMap() : serverCommands;
    }

    @Override public String name() { return "LSP"; }

    @Override public String description() {
        return "语言服务器查询（goToDefinition/findReferences/hover/documentSymbol/workspaceSymbol/"
                + "goToImplementation/incomingCalls/outgoingCalls）；line/character 1-based";
    }

    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema("LSP 查询",
                new String[]{"operation", "filePath", "line", "character", "query"},
                new String[]{"operation", "filePath"});
    }

    @Override public boolean isReadOnly(JsonObject args) { return true; }
    @Override public boolean isConcurrencySafe(JsonObject args) { return false; }   // 同一 server 进程串行
    @Override public boolean isEnabled() { return !serverCommands.isEmpty(); }

    /** 被动诊断通道：编辑工具成功后注入下一轮的附件数据 */
    public JsonArray diagnosticsOf(String filePath) {
        JsonArray cached = cachedDiagnostics.get(Paths.get(filePath).toAbsolutePath().normalize().toString());
        return cached == null ? new JsonArray() : cached;
    }

    @Override public ToolResult execute(JsonObject args) throws Exception {
        String operation = text(args, "operation");
        String filePath = text(args, "filePath");
        if (operation.isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 operation");
        // 操作名预校验（session 启动前——避免无效操作白白拉起 server 进程）
        if (!java.util.Arrays.asList("goToDefinition", "findReferences", "hover", "documentSymbol",
                "workspaceSymbol", "goToImplementation", "incomingCalls", "outgoingCalls").contains(operation))
            return ToolResult.errorWithoutSideEffects("未知 operation: " + operation
                    + "（支持 goToDefinition/findReferences/hover/documentSymbol/workspaceSymbol/"
                    + "goToImplementation/incomingCalls/outgoingCalls）");
        if (filePath.isEmpty()) return ToolResult.errorWithoutSideEffects("缺少 filePath");
        Path file = Paths.get(filePath);
        if (!Files.isRegularFile(file)) return ToolResult.errorWithoutSideEffects("文件不存在: " + filePath);
        String language = languageOf(file);
        String serverCommand = serverCommands.get(language);
        if (serverCommand == null)
            return ToolResult.errorWithoutSideEffects("语言 " + language
                    + " 未配置 LSP server（lsp.servers." + language + "=<command>）");
        LanguageSession session = sessionOf(language, serverCommand);
        if (session == null || !session.healthy)
            return ToolResult.errorWithoutSideEffects("LSP server（" + language + "）不可用；本语言查询已禁用");
        try {
            String uri = file.toUri().toString();
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonElement reply = request(session, lspRequest(operation, uri, args, text));
            if (reply == null) return ToolResult.error("LSP 响应超时（" + REQUEST_TIMEOUT_MS + "ms）: " + operation);
            return ToolResult.success(pretty(reply));
        } catch (Exception failed) {
            session.healthy = false;
            return ToolResult.error("LSP 查询失败（该 server 本会话禁用）: " + failed.getMessage());
        }
    }

    // ==================== LSP 协议 ====================

    private JsonObject lspRequest(String operation, String uri, JsonObject args, String fileText) {
        int line = intOf(args, "line", 1) - 1;        // 1-based → 0-based
        int character = intOf(args, "character", 1) - 1;
        JsonObject params = new JsonObject();
        params.addProperty("textDocument", uri);
        params.addProperty("uri", uri);
        params.add("position", position(line, character));
        switch (operation) {
            case "goToDefinition": return method("textDocument/definition", params);
            case "goToImplementation": return method("textDocument/implementation", params);
            case "findReferences":
                params.add("context", context(true));
                return method("textDocument/references", params);
            case "hover": return method("textDocument/hover", params);
            case "documentSymbol": return method("textDocument/documentSymbol", params);
            case "workspaceSymbol":
                params.remove("textDocument");
                params.remove("position");
                params.addProperty("query", text(args, "query"));
                return method("workspace/symbol", params);
            case "incomingCalls":
            case "outgoingCalls":
                params.addProperty("item", uri);   // 简化：完整调用需先查 symbol（两段协议）
                return method(operation.equals("incomingCalls")
                        ? "callHierarchy/incomingCalls" : "callHierarchy/outgoingCalls", params);
            default:
                throw new IllegalArgumentException("未知 operation: " + operation
                        + "（支持 goToDefinition/findReferences/hover/documentSymbol/workspaceSymbol/"
                        + "goToImplementation/incomingCalls/outgoingCalls）");
        }
    }

    private static JsonObject position(int line, int character) {
        JsonObject p = new JsonObject();
        p.addProperty("line", line);
        p.addProperty("character", character);
        return p;
    }

    private static JsonObject context(boolean includeDeclaration) {
        JsonObject c = new JsonObject();
        c.addProperty("includeDeclaration", includeDeclaration);
        return c;
    }

    private static JsonObject method(String method, JsonObject params) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.addProperty("method", method);
        r.add("params", params);
        return r;
    }

    /** 会话获取/初始化（initialize + initialized 通知 + didOpen） */
    private LanguageSession sessionOf(String language, String serverCommand) {
        return sessions.compute(language, (lang, existing) -> {
            if (existing != null && existing.healthy) return existing;
            try {
                List<String> cmd = new ArrayList<String>();
                for (String part : serverCommand.trim().split("\\s+")) cmd.add(part);
                Process p = new ProcessBuilder(cmd)
                        .directory(new java.io.File(workDir))
                        .redirectErrorStream(false)
                        .start();
                LanguageSession fresh = new LanguageSession(p);
                // initialize 握手
                JsonObject initParams = new JsonObject();
                initParams.addProperty("processId", 0);
                initParams.addProperty("rootUri", Paths.get(workDir).toUri().toString());
                JsonObject capabilities = new JsonObject();
                initParams.add("capabilities", capabilities);
                JsonElement reply = roundTrip(fresh, method("initialize", initParams), INIT_TIMEOUT_MS);
                if (reply == null) throw new IllegalStateException("initialize 超时");
                notify(fresh, "initialized", new JsonObject());
                return fresh;
            } catch (Exception failed) {
                DiagnosticLog.warn("lsp", "server 启动失败（语言 " + lang + " 禁用）: " + failed.getMessage());
                return null;
            }
        });
    }

    /** 发送请求并等对应 id 的响应（后台线程同时收割 notification/diagnostics） */
    private JsonElement request(LanguageSession session, JsonObject request) throws Exception {
        int id;
        synchronized (session) {
            id = session.nextId++;
            request.addProperty("id", id);
        }
        return roundTrip(session, request, REQUEST_TIMEOUT_MS);
    }

    /** 单请求往返：写帧读帧直到同 id 响应（中间的 notification 分发进 diagnostics 缓存） */
    private JsonElement roundTrip(LanguageSession session, JsonObject request, int timeoutMs) throws Exception {
        writeFrame(session.stdin, request.toString());
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JsonObject incoming = readFrame(session.stdout, deadline);
            if (incoming == null) return null;
            if (incoming.has("id") && incoming.has("result")) {
                JsonElement result = incoming.get("result");
                return result.isJsonObject() || result.isJsonArray() ? result : null;
            }
            if (incoming.has("id") && incoming.has("error"))
                throw new IllegalStateException("LSP error: " + incoming.get("error"));
            if (incoming.has("method")) dispatchNotification(incoming);
        }
        return null;
    }

    /** server→client 通知分发（publishDiagnostics 缓存为被动诊断附件） */
    private void dispatchNotification(JsonObject notification) {
        String method = notification.has("method") ? notification.get("method").getAsString() : "";
        if ("textDocument/publishDiagnostics".equals(method) && notification.has("params")) {
            JsonObject params = notification.getAsJsonObject("params");
            if (params.has("uri") && params.has("diagnostics")) {
                String uri = params.get("uri").getAsString();
                try {
                    String path = Paths.get(new java.net.URI(uri)).toAbsolutePath().normalize().toString();
                    cachedDiagnostics.put(path, params.getAsJsonArray("diagnostics"));
                } catch (Exception ignored) { }
            }
        }
    }

    private static void notify(LanguageSession session, String method, JsonObject params) throws Exception {
        JsonObject n = new JsonObject();
        n.addProperty("jsonrpc", "2.0");
        n.addProperty("method", method);
        n.add("params", params);
        writeFrame(session.stdin, n.toString());
    }

    /** Content-Length 帧写（LSP stdio 协议） */
    private static void writeFrame(BufferedWriter out, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write("Content-Length: " + body.length + "\r\n\r\n");
        out.write(json);
        out.flush();
    }

    /** Content-Length 帧读（阻塞至 deadline；超时/EOF 返回 null） */
    private static JsonObject readFrame(BufferedReader in, long deadline) throws Exception {
        int contentLength = -1;
        while (System.currentTimeMillis() < deadline) {
            String line = in.readLine();
            if (line == null) return null;
            if (line.isEmpty()) break;
            if (line.toLowerCase().startsWith("content-length:"))
                contentLength = Integer.parseInt(line.substring(15).trim());
        }
        if (contentLength <= 0) return null;
        char[] body = new char[contentLength];
        int read = 0;
        while (read < contentLength && System.currentTimeMillis() < deadline) {
            int n = in.read(body, read, contentLength - read);
            if (n < 0) return null;
            read += n;
        }
        return JsonParser.parseString(new String(body, 0, read)).getAsJsonObject();
    }

    /** 会话关闭（进程树终止；随会话生命周期） */
    public void shutdown() {
        for (LanguageSession s : sessions.values()) {
            try { s.stdin.close(); } catch (Exception ignored) { }
            s.process.destroyForcibly();
        }
        sessions.clear();
    }

    private static String languageOf(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        switch (ext) {
            case "java": return "java";
            case "py": return "python";
            case "js": case "ts": return ext;
            default: return ext.isEmpty() ? "unknown" : ext;
        }
    }

    private static String pretty(JsonElement e) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(e);
    }

    private static String text(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }

    private static int intOf(JsonObject o, String key, int fallback) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }
}
