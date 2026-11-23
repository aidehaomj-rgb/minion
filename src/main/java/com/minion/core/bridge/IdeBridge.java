package com.minion.core.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.cli.SdkFrameIo;
import com.minion.core.security.ProjectTrustStore;
import com.minion.core.security.TrustedExecutable;
import com.minion.core.tools.PathsGuard;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 IDE 双向桥接服务（v2.4 §12.3，路径 A 实施）。
 *
 * <p>安全边界：
 * <ul>
 *   <li>仅监听回环地址 {@code 127.0.0.1}，accept 后二次校验 {@code isLoopbackAddress()}</li>
 *   <li>32 字节随机会话令牌，常量时间比对（{@link MessageDigest#isEqual}）</li>
 *   <li>严格 {@code hello} 握手（5 秒超时）：校验 {@code protocolVersion == 1}、令牌、
 *       {@code workspace} realpath 一致性，以及可选 {@link ProjectTrustStore} 插件指纹</li>
 *   <li>文件打开 / diff 预览 / 诊断均经 {@link PathsGuard#inside} 校验，拒绝越界路径</li>
 *   <li>{@link #canUseTool} fail-closed：未连接、断线、超时或响应异常一律返回拒绝</li>
 *   <li>断线立即作废全部在途请求，重连绝不重放旧请求；入站请求 ID 有界 LRU 去重</li>
 * </ul>
 */
public final class IdeBridge implements Closeable {

    public static final int PROTOCOL_VERSION = 1;
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;
    private static final int MAX_PENDING_REQUESTS = 64;
    private static final int MAX_SEEN_INBOUND_IDS = 256;
    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final SecureRandom RNG = new SecureRandom();

    /** 插件首次连接或版本/哈希更新时的交互审批回调（null 时走 fail-closed：仅已信任放行）。 */
    public interface PluginTrustApprover {
        boolean approve(TrustedExecutable spec, ProjectTrustStore.CheckResult check);
    }

    /** 当前连接到的 IDE 客户端元信息。 */
    public static final class ClientInfo {
        public final String ideName;
        public final String ideVersion;
        public final String pluginId;
        public final String pluginVersion;
        public final String pluginHash;
        public final String connectedAt;

        ClientInfo(String ideName, String ideVersion, String pluginId,
                   String pluginVersion, String pluginHash, String connectedAt) {
            this.ideName = ideName;
            this.ideVersion = ideVersion;
            this.pluginId = pluginId;
            this.pluginVersion = pluginVersion;
            this.pluginHash = pluginHash;
            this.connectedAt = connectedAt;
        }
    }

    /** IDE 当前编辑器选区快照。 */
    public static final class Selection {
        public final String file;
        public final int startLine;
        public final int startColumn;
        public final int endLine;
        public final int endColumn;
        public final String text;

        public Selection(String file, int startLine, int startColumn,
                         int endLine, int endColumn, String text) {
            this.file = file == null ? "" : file;
            this.startLine = startLine;
            this.startColumn = startColumn;
            this.endLine = endLine;
            this.endColumn = endColumn;
            this.text = text == null ? "" : text;
        }

        public boolean isEmpty() {
            return file.isEmpty() && text.isEmpty();
        }
    }

    /** IDE 诊断条目（错误/警告/提示）。 */
    public static final class DiagnosticItem {
        public final String file;
        public final int line;
        public final int column;
        public final String severity;
        public final String message;
        public final String source;

        public DiagnosticItem(String file, int line, int column,
                              String severity, String message, String source) {
            this.file = file == null ? "" : file;
            this.line = line;
            this.column = column;
            this.severity = severity == null ? "info" : severity;
            this.message = message == null ? "" : message;
            this.source = source == null ? "" : source;
        }
    }

    /** IDE 侧工具权限回调结果（fail-closed：仅明确 allowed=true 放行）。 */
    public static final class ToolApproval {
        public final boolean allowed;
        public final String reason;
        /** true=通道故障（未连接、超时、断线、畸形响应、被中断），不是 IDE 那边用户的拒绝；结果同样是拒绝 */
        public final boolean failed;

        private ToolApproval(boolean allowed, String reason, boolean failed) {
            this.allowed = allowed;
            this.reason = reason == null ? "" : reason;
            this.failed = failed;
        }

        public static ToolApproval allow(String reason) {
            return new ToolApproval(true, reason, false);
        }

        public static ToolApproval deny(String reason) {
            return new ToolApproval(false, reason, false);
        }

        /** 通道故障导致的拒绝（调用方据此决定是回落本地确认，而不是当作用户已拒绝） */
        public static ToolApproval failed(String reason) {
            return new ToolApproval(false, reason, true);
        }
    }

    private final Path workspaceRealPath;
    private final Path lockDir;
    private final ProjectTrustStore trustStore;
    private final PluginTrustApprover trustApprover;
    private final String token;
    private final byte[] tokenBytes;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong reqSeq = new AtomicLong(0);
    private final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pending =
            new ConcurrentHashMap<String, CompletableFuture<JsonObject>>();
    private final Map<String, Boolean> seenInboundIds = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_SEEN_INBOUND_IDS;
                }
            });

    private ServerSocket serverSocket;
    private Path lockFile;
    private Thread acceptThread;
    private volatile Connection activeConnection;
    private volatile ClientInfo connectedClient;
    private volatile String lastError = "";
    private volatile String startedAt = "";

    public IdeBridge(Path workspace) throws IOException {
        this(workspace, defaultLockDir(), null, null);
    }

    public IdeBridge(Path workspace, Path lockDir,
                     ProjectTrustStore trustStore, PluginTrustApprover trustApprover) throws IOException {
        if (workspace == null) throw new IllegalArgumentException("workspace 不能为空");
        this.workspaceRealPath = canonicalWorkspace(workspace);
        this.lockDir = lockDir == null ? defaultLockDir() : lockDir;
        this.trustStore = trustStore;
        this.trustApprover = trustApprover;
        this.token = randomHexToken(32);
        this.tokenBytes = this.token.getBytes(StandardCharsets.UTF_8);
    }

    public static Path defaultLockDir() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".minion", "ide");
    }

    /** 启动回环监听并写入发现文件 {@code minion-ide-<port>.lock}。 */
    public synchronized IdeBridge start() throws IOException {
        if (closed.get()) throw new IllegalStateException("IdeBridge 已关闭");
        if (serverSocket != null) return this;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket ss = new ServerSocket(0, 8, loopback);
        this.serverSocket = ss;
        this.startedAt = Instant.now().toString();
        writeLockFile(ss.getLocalPort());
        Thread t = new Thread(this::acceptLoop, "minion-ide-bridge-" + ss.getLocalPort());
        t.setDaemon(true);
        this.acceptThread = t;
        t.start();
        return this;
    }

    public boolean isRunning() {
        return !closed.get() && serverSocket != null && !serverSocket.isClosed();
    }

    public boolean isConnected() {
        Connection c = activeConnection;
        return c != null && !c.closed.get();
    }

    public int port() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public String token() { return token; }

    public Path workspaceRealPath() { return workspaceRealPath; }

    public Path lockFile() { return lockFile; }

    public ClientInfo connectedClient() { return connectedClient; }

    public String lastError() { return lastError; }

    // ==================== IDE 双向操作 ====================

    public Selection getSelection() throws IOException {
        return getSelection(DEFAULT_TIMEOUT_MS);
    }

    public Selection getSelection(long timeoutMs) throws IOException {
        JsonObject resp = sendRequest("get_selection", new JsonObject(), timeoutMs);
        JsonObject data = resp.has("data") && resp.get("data").isJsonObject()
                ? resp.getAsJsonObject("data") : resp;
        return new Selection(
                str(data, "file"),
                intOf(data, "startLine", 0),
                intOf(data, "startColumn", 0),
                intOf(data, "endLine", 0),
                intOf(data, "endColumn", 0),
                str(data, "text"));
    }

    public void openFile(Path file, int line, int column) throws IOException {
        openFile(file, line, column, DEFAULT_TIMEOUT_MS);
    }

    public void openFile(Path file, int line, int column, long timeoutMs) throws IOException {
        Path resolved = requireInsideWorkspace(file);
        JsonObject payload = new JsonObject();
        payload.addProperty("file", resolved.toString());
        if (line > 0) payload.addProperty("line", line);
        if (column > 0) payload.addProperty("column", column);
        sendRequest("open_file", payload, timeoutMs);
    }

    public void openDiff(Path file, String proposedContent, String title) throws IOException {
        openDiff(file, proposedContent, title, DEFAULT_TIMEOUT_MS);
    }

    public void openDiff(Path file, String proposedContent, String title, long timeoutMs) throws IOException {
        Path resolved = requireInsideWorkspace(file);
        JsonObject payload = new JsonObject();
        payload.addProperty("file", resolved.toString());
        payload.addProperty("proposedContent", proposedContent == null ? "" : proposedContent);
        payload.addProperty("title", title == null || title.isEmpty()
                ? "Minion Diff: " + resolved.getFileName() : title);
        sendRequest("open_diff", payload, timeoutMs);
    }

    public List<DiagnosticItem> getDiagnostics(Path optionalFile) throws IOException {
        return getDiagnostics(optionalFile, DEFAULT_TIMEOUT_MS);
    }

    public List<DiagnosticItem> getDiagnostics(Path optionalFile, long timeoutMs) throws IOException {
        JsonObject payload = new JsonObject();
        if (optionalFile != null) {
            Path resolved = requireInsideWorkspace(optionalFile);
            payload.addProperty("file", resolved.toString());
        }
        JsonObject resp = sendRequest("get_diagnostics", payload, timeoutMs);
        List<DiagnosticItem> items = new ArrayList<DiagnosticItem>();
        JsonElement arrEl = resp.has("items") ? resp.get("items")
                : (resp.has("data") && resp.get("data").isJsonObject()
                ? resp.getAsJsonObject("data").get("items") : null);
        if (arrEl != null && arrEl.isJsonArray()) {
            JsonArray arr = arrEl.getAsJsonArray();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                items.add(new DiagnosticItem(
                        str(o, "file"),
                        intOf(o, "line", 0),
                        intOf(o, "column", 0),
                        str(o, "severity"),
                        str(o, "message"),
                        str(o, "source")));
            }
        }
        return items;
    }

    /**
     * 向 IDE 请求工具执行确认（fail-closed：未连接、超时、断线或畸形响应均返回拒绝）。
     * 通道故障的拒绝带 failed=true，与 IDE 用户的明确拒绝区分（GUI 确认通道 {@link IdeConfirmUi} 据此回落本地确认框）。
     */
    public ToolApproval canUseTool(String toolName, JsonObject input, String summary, long timeoutMs) {
        if (!isConnected()) {
            return ToolApproval.failed("IDE 未连接（fail-closed 拒绝）");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("tool", toolName == null ? "" : toolName);
        if (input != null) payload.add("input", input);
        payload.addProperty("summary", summary == null ? "" : summary);
        try {
            JsonObject resp = sendRequest("can_use_tool", payload, timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS);
            JsonObject data = resp.has("data") && resp.get("data").isJsonObject()
                    ? resp.getAsJsonObject("data") : resp;
            // allowed 必须是明确的布尔值（或 "true"/"false" 文本）：畸形响应是通道故障，不能当成用户拒绝
            JsonElement verdict = data.get("allowed");
            String answer = verdict != null && verdict.isJsonPrimitive() ? verdict.getAsString().trim() : "";
            if (verdict == null || !verdict.isJsonPrimitive()
                    || !(verdict.getAsJsonPrimitive().isBoolean() || "true".equalsIgnoreCase(answer) || "false".equalsIgnoreCase(answer)))
                return ToolApproval.failed("IDE 权限回调响应畸形（fail-closed 拒绝）");
            boolean allowed = verdict.getAsBoolean();
            String reason = str(data, "reason");
            return allowed
                    ? ToolApproval.allow(reason.isEmpty() ? "IDE 已批准" : reason)
                    : ToolApproval.deny(reason.isEmpty() ? "IDE 拒绝执行" : reason);
        } catch (Exception e) {
            return ToolApproval.failed("IDE 权限回调失败（fail-closed 拒绝）: " + e.getMessage());
        }
    }

    /** 供 `/ide` 与 `/doctor` 展示的中文状态摘要。 */
    public String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("本地 IDE 桥接（§12.3）\n");
        sb.append("  状态       : ").append(isRunning() ? "监听中" : "未启动").append('\n');
        if (isRunning()) {
            sb.append("  回环地址   : 127.0.0.1:").append(port()).append('\n');
            sb.append("  发现文件   : ").append(lockFile == null ? "-" : lockFile).append('\n');
        }
        sb.append("  工作区     : ").append(workspaceRealPath).append('\n');
        ClientInfo ci = connectedClient;
        if (isConnected() && ci != null) {
            sb.append("  已连接 IDE : ").append(ci.ideName);
            if (!ci.ideVersion.isEmpty()) sb.append(' ').append(ci.ideVersion);
            if (!ci.pluginId.isEmpty()) {
                sb.append(" (插件 ").append(ci.pluginId);
                if (!ci.pluginVersion.isEmpty()) sb.append('@').append(ci.pluginVersion);
                sb.append(')');
            }
            sb.append('\n');
        } else {
            sb.append("  已连接 IDE : 未连接（在 VS Code 1.70+ 安装离线插件 minion-bridge 后自动发现）\n");
        }
        if (lastError != null && !lastError.isEmpty()) {
            sb.append("  最近错误   : ").append(lastError).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Connection c = activeConnection;
        activeConnection = null;
        connectedClient = null;
        if (c != null) c.close("IDE bridge closed");
        failAllPending("IDE bridge closed");
        ServerSocket ss = this.serverSocket;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { }
        }
        deleteLockFile();
    }

    // ==================== 内部网络与协议处理 ====================

    private void acceptLoop() {
        while (!closed.get()) {
            Socket sock;
            try {
                sock = serverSocket.accept();
            } catch (SocketException e) {
                if (closed.get()) break;
                lastError = "accept 异常: " + e.getMessage();
                continue;
            } catch (IOException e) {
                if (closed.get()) break;
                lastError = "accept 异常: " + e.getMessage();
                continue;
            }
            if (sock.getInetAddress() == null || !sock.getInetAddress().isLoopbackAddress()) {
                lastError = "拒绝非回环地址连接: " + sock.getInetAddress();
                quietClose(sock);
                continue;
            }
            Thread handler = new Thread(() -> handleClient(sock), "minion-ide-client");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handleClient(Socket sock) {
        Connection conn = null;
        try {
            sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());

            SdkFrameIo.Read first = SdkFrameIo.readFrame(in);
            if (first.overrun) {
                sendHelloReject(out, "frame_too_large", "hello 帧超过 1MiB 上限");
                quietClose(sock);
                return;
            }
            if (first.line == null) {
                lastError = "握手失败：客户端在发送 hello 前断开";
                quietClose(sock);
                return;
            }
            JsonObject hello = SdkFrameIo.parse(first.line);
            if (hello == null) {
                sendHelloReject(out, "invalid_json", "hello 帧不是合法 JSON");
                quietClose(sock);
                return;
            }
            if (!"hello".equals(str(hello, "type"))) {
                sendHelloReject(out, "expected_hello", "首帧必须为 hello");
                quietClose(sock);
                return;
            }
            if (!SdkFrameIo.versionCompatible(hello, PROTOCOL_VERSION)) {
                sendHelloReject(out, "version_mismatch",
                        "协议版本不兼容：期望 " + PROTOCOL_VERSION + "，收到 " + intOf(hello, "protocolVersion", -1));
                quietClose(sock);
                return;
            }
            String peerToken = str(hello, "token");
            if (!constantTimeEquals(peerToken)) {
                sendHelloReject(out, "invalid_token", "会话令牌校验失败");
                quietClose(sock);
                return;
            }
            String peerWs = str(hello, "workspace");
            if (!matchesWorkspaceRealPath(peerWs)) {
                sendHelloReject(out, "workspace_mismatch",
                        "工作区 realpath 不匹配：" + peerWs);
                quietClose(sock);
                return;
            }
            String ideName = str(hello, "ideName");
            if (ideName.isEmpty()) ideName = "IDE";
            String ideVersion = str(hello, "ideVersion");
            String pluginId = str(hello, "pluginId");
            String pluginVersion = str(hello, "pluginVersion");
            String pluginHash = str(hello, "pluginHash");

            if (trustStore != null) {
                String effectiveId = pluginId.isEmpty() ? ideName : pluginId;
                TrustedExecutable spec = TrustedExecutable.of(
                        TrustedExecutable.TYPE_PLUGIN,
                        "ide-bridge:" + effectiveId,
                        "user",
                        "ide-plugin:" + effectiveId + "@" + pluginVersion,
                        Collections.singletonList(pluginHash),
                        null,
                        workspaceRealPath.toString(),
                        Collections.<Path>emptyList());
                ProjectTrustStore.CheckResult check = trustStore.check(spec);
                if (!check.trusted) {
                    boolean approved = trustApprover != null && trustApprover.approve(spec, check);
                    if (!approved) {
                        sendHelloReject(out, "plugin_untrusted",
                                "IDE 插件未获信任或已更新（" + check.reason + "）");
                        quietClose(sock);
                        return;
                    }
                    trustStore.approve(spec);
                }
            }

            // 握手通过：先关闭旧连接并作废其全部在途请求，确保重连绝不重放
            Connection prev = activeConnection;
            if (prev != null) prev.close("replaced by new IDE connection");
            failAllPending("IDE reconnected");

            sock.setSoTimeout(0);
            conn = new Connection(sock, out);
            ClientInfo info = new ClientInfo(
                    ideName, ideVersion, pluginId, pluginVersion, pluginHash, Instant.now().toString());
            activeConnection = conn;
            connectedClient = info;
            lastError = "";

            JsonObject ack = new JsonObject();
            ack.addProperty("type", "hello_ack");
            ack.addProperty("ok", true);
            ack.addProperty("protocolVersion", PROTOCOL_VERSION);
            ack.addProperty("workspace", workspaceRealPath.toString());
            conn.sendFrame(ack);

            readLoop(conn, in);
        } catch (IOException e) {
            if (!closed.get()) {
                lastError = "IDE 连接中断: " + e.getMessage();
            }
        } finally {
            if (conn != null) {
                conn.close("connection ended");
                if (activeConnection == conn) {
                    activeConnection = null;
                    connectedClient = null;
                    failAllPending("IDE bridge disconnected");
                }
            } else {
                quietClose(sock);
            }
        }
    }

    private void readLoop(Connection conn, BufferedReader in) throws IOException {
        while (!closed.get() && !conn.closed.get()) {
            SdkFrameIo.Read r = SdkFrameIo.readFrame(in);
            if (r.overrun) continue;
            if (r.line == null) break;
            JsonObject frame = SdkFrameIo.parse(r.line);
            if (frame == null) continue;
            String type = str(frame, "type");
            if ("response".equals(type)) {
                String id = str(frame, "id");
                CompletableFuture<JsonObject> fut = pending.remove(id);
                if (fut != null) {
                    boolean ok = !frame.has("ok") || frame.get("ok").getAsBoolean();
                    if (ok) {
                        fut.complete(frame);
                    } else {
                        String err = str(frame, "error");
                        fut.completeExceptionally(new IOException(err.isEmpty() ? "IDE 返回错误" : err));
                    }
                }
                continue;
            }
            String inboundId = str(frame, "id");
            if (!inboundId.isEmpty()) {
                synchronized (seenInboundIds) {
                    if (seenInboundIds.containsKey(inboundId)) continue;
                    seenInboundIds.put(inboundId, Boolean.TRUE);
                }
            }
            if ("ping".equals(type)) {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                if (!inboundId.isEmpty()) pong.addProperty("id", inboundId);
                conn.sendFrame(pong);
            }
        }
    }

    private JsonObject sendRequest(String type, JsonObject payload, long timeoutMs) throws IOException {
        Connection conn = activeConnection;
        if (conn == null || conn.closed.get()) {
            throw new IOException("IDE 未连接");
        }
        if (pending.size() >= MAX_PENDING_REQUESTS) {
            throw new IOException("IDE 桥接在途请求已达上限");
        }
        String id = "req-" + reqSeq.incrementAndGet();
        JsonObject frame = payload == null ? new JsonObject() : payload;
        frame.addProperty("type", type);
        frame.addProperty("id", id);

        CompletableFuture<JsonObject> fut = new CompletableFuture<JsonObject>();
        pending.put(id, fut);
        try {
            conn.sendFrame(frame);
        } catch (IOException sendErr) {
            pending.remove(id);
            throw sendErr;
        }
        try {
            return fut.get(timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            pending.remove(id);
            throw new IOException("IDE 请求超时 (" + type + ")");
        } catch (InterruptedException ie) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("IDE 请求被中断 (" + type + ")");
        } catch (ExecutionException ee) {
            pending.remove(id);
            Throwable cause = ee.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException(cause == null ? "IDE 请求失败" : cause.getMessage(), cause);
        }
    }

    private void failAllPending(String reason) {
        IOException ex = new IOException(reason);
        for (String id : new ArrayList<String>(pending.keySet())) {
            CompletableFuture<JsonObject> fut = pending.remove(id);
            if (fut != null) fut.completeExceptionally(ex);
        }
    }

    private void sendHelloReject(OutputStream out, String code, String error) {
        lastError = error;
        JsonObject ack = new JsonObject();
        ack.addProperty("type", "hello_ack");
        ack.addProperty("ok", false);
        ack.addProperty("protocolVersion", PROTOCOL_VERSION);
        ack.addProperty("code", code);
        ack.addProperty("error", error);
        try {
            byte[] bytes = (GSON.toJson(ack) + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) { }
    }

    private boolean constantTimeEquals(String peerToken) {
        if (peerToken == null || peerToken.isEmpty()) return false;
        byte[] peerBytes = peerToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(tokenBytes, peerBytes);
    }

    private boolean matchesWorkspaceRealPath(String peerWorkspace) {
        if (peerWorkspace == null || peerWorkspace.trim().isEmpty()) return false;
        try {
            Path peerReal = Paths.get(peerWorkspace.trim()).toRealPath();
            return workspaceRealPath.equals(peerReal);
        } catch (Exception e) {
            return false;
        }
    }

    private Path requireInsideWorkspace(Path file) {
        if (file == null) throw new SecurityException("文件路径为空，已拒绝");
        Path resolved = file.isAbsolute()
                ? file.normalize()
                : workspaceRealPath.resolve(file).normalize();
        if (!PathsGuard.inside(workspaceRealPath.toString(), resolved)) {
            throw new SecurityException("路径在工作路径之外，已拒绝: " + file);
        }
        return resolved;
    }

    private void writeLockFile(int port) throws IOException {
        Files.createDirectories(lockDir);
        Path target = lockDir.resolve("minion-ide-" + port + ".lock");
        JsonObject doc = new JsonObject();
        doc.addProperty("port", port);
        doc.addProperty("host", "127.0.0.1");
        doc.addProperty("token", token);
        doc.addProperty("workspace", workspaceRealPath.toString());
        doc.addProperty("protocolVersion", PROTOCOL_VERSION);
        doc.addProperty("startedAt", startedAt);
        Files.write(target, GSON.toJson(doc).getBytes(StandardCharsets.UTF_8));
        this.lockFile = target;
    }

    private void deleteLockFile() {
        Path f = this.lockFile;
        if (f != null) {
            try { Files.deleteIfExists(f); } catch (IOException ignored) { }
        }
    }

    private static Path canonicalWorkspace(Path workspace) throws IOException {
        if (Files.exists(workspace)) {
            return workspace.toRealPath();
        }
        return workspace.toAbsolutePath().normalize();
    }

    private static String randomHexToken(int numBytes) {
        byte[] buf = new byte[numBytes];
        RNG.nextBytes(buf);
        StringBuilder sb = new StringBuilder(numBytes * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        try { return obj.get(key).getAsString(); }
        catch (Exception e) { return ""; }
    }

    private static int intOf(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try { return obj.get(key).getAsInt(); }
        catch (Exception e) { return def; }
    }

    private static void quietClose(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static final class Connection {
        final Socket socket;
        final OutputStream out;
        final AtomicBoolean closed = new AtomicBoolean(false);

        Connection(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        synchronized void sendFrame(JsonObject frame) throws IOException {
            if (closed.get()) throw new IOException("IDE connection closed");
            byte[] bytes = (GSON.toJson(frame) + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(bytes);
            out.flush();
        }

        void close(String reason) {
            if (closed.compareAndSet(false, true)) {
                quietClose(socket);
            }
        }
    }
}
