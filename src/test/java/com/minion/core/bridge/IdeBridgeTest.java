package com.minion.core.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.cli.SdkFrameIo;
import com.minion.core.security.ProjectTrustStore;
import com.minion.gui.command.CommandDispatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * §12.3 本地 IDE 桥接测试：握手安全（令牌/版本/workspace realpath/插件信任）、
 * 选区/打开文件/diff/诊断/权限回调（含越界拒绝与 fail-closed）、断线作废与重连不重放。
 */
public class IdeBridgeTest {

    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void lockFileCreatedOnStartAndDeletedOnClose() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path ws = Files.createDirectories(root.resolve("ws"));
        Path lockDir = root.resolve("locks");
        Path lockFile;
        try (IdeBridge bridge = new IdeBridge(ws, lockDir, null, null).start()) {
            assertTrue(bridge.isRunning());
            assertTrue(bridge.port() > 0);
            lockFile = bridge.lockFile();
            assertNotNull(lockFile);
            assertTrue(Files.exists(lockFile));
            JsonObject lock = GSON.fromJson(
                    new String(Files.readAllBytes(lockFile), StandardCharsets.UTF_8), JsonObject.class);
            assertEquals(bridge.port(), lock.get("port").getAsInt());
            assertEquals("127.0.0.1", lock.get("host").getAsString());
            assertEquals(bridge.token(), lock.get("token").getAsString());
            assertEquals(IdeBridge.PROTOCOL_VERSION, lock.get("protocolVersion").getAsInt());
            assertEquals(ws.toRealPath().toString(), lock.get("workspace").getAsString());
        }
        assertFalse("close 后锁文件必须清理", Files.exists(lockFile));
    }

    @Test
    public void handshakeRejectsWrongTokenVersionAndWorkspace() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path ws = Files.createDirectories(root.resolve("ws"));
        Path otherWs = Files.createDirectories(root.resolve("other"));
        Path lockDir = root.resolve("locks");

        try (IdeBridge bridge = new IdeBridge(ws, lockDir, null, null).start()) {
            // 1. 错误令牌
            JsonObject badToken = helloFrame(IdeBridge.PROTOCOL_VERSION, "wrong-token", ws.toString(), "hash1");
            JsonObject ack1 = sendHelloAndReadAck(bridge.port(), badToken);
            assertFalse(ack1.get("ok").getAsBoolean());
            assertEquals("invalid_token", ack1.get("code").getAsString());

            // 2. 不兼容协议版本
            JsonObject badVer = helloFrame(99, bridge.token(), ws.toString(), "hash1");
            JsonObject ack2 = sendHelloAndReadAck(bridge.port(), badVer);
            assertFalse(ack2.get("ok").getAsBoolean());
            assertEquals("version_mismatch", ack2.get("code").getAsString());

            // 3. 工作区 realpath 不匹配
            JsonObject badWs = helloFrame(IdeBridge.PROTOCOL_VERSION, bridge.token(), otherWs.toString(), "hash1");
            JsonObject ack3 = sendHelloAndReadAck(bridge.port(), badWs);
            assertFalse(ack3.get("ok").getAsBoolean());
            assertEquals("workspace_mismatch", ack3.get("code").getAsString());

            assertFalse(bridge.isConnected());
        }
    }

    @Test
    public void pluginUpdateRequiresReapprovalInProjectTrustStore() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path ws = Files.createDirectories(root.resolve("ws"));
        Path trustRoot = Files.createDirectories(root.resolve("trust"));
        Path lockDir = root.resolve("locks");
        ProjectTrustStore store = ProjectTrustStore.load(trustRoot);

        AtomicInteger prompts = new AtomicInteger(0);
        IdeBridge.PluginTrustApprover approver = (spec, check) -> {
            int n = prompts.incrementAndGet();
            // 首次批准，第二次（插件哈希升级后）拒绝以验证重新复核生效
            return n == 1;
        };

        try (IdeBridge bridge = new IdeBridge(ws, lockDir, store, approver).start()) {
            // 首次连接 hash-v1 → 弹窗批准
            try (Socket s1 = new Socket("127.0.0.1", bridge.port())) {
                writeFrame(s1.getOutputStream(),
                        helloFrame(IdeBridge.PROTOCOL_VERSION, bridge.token(), ws.toString(), "hash-v1"));
                JsonObject ack = readFrame(readerOf(s1));
                assertTrue(ack.get("ok").getAsBoolean());
                assertEquals(1, prompts.get());
            }
            // 同哈希重连 hash-v1 → 命中 trust.json，不再弹窗
            try (Socket s2 = new Socket("127.0.0.1", bridge.port())) {
                writeFrame(s2.getOutputStream(),
                        helloFrame(IdeBridge.PROTOCOL_VERSION, bridge.token(), ws.toString(), "hash-v1"));
                JsonObject ack = readFrame(readerOf(s2));
                assertTrue(ack.get("ok").getAsBoolean());
                assertEquals("相同插件指纹不应重复弹窗", 1, prompts.get());
            }
            // 插件更新为 hash-v2 → 指纹失效，重新弹窗（本次回调返回 false 拒绝）
            JsonObject ack3 = sendHelloAndReadAck(
                    bridge.port(),
                    helloFrame(IdeBridge.PROTOCOL_VERSION, bridge.token(), ws.toString(), "hash-v2"));
            assertFalse(ack3.get("ok").getAsBoolean());
            assertEquals("plugin_untrusted", ack3.get("code").getAsString());
            assertEquals("插件哈希变化必须重新走 ProjectTrustStore 审批", 2, prompts.get());
        }
    }

    @Test
    public void bidirectionalOperationsAndPathGuardAndFailClosed() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path ws = Files.createDirectories(root.resolve("ws"));
        Path insideFile = Files.write(ws.resolve("Main.java"), "class Main {}\n".getBytes(StandardCharsets.UTF_8));
        Path outsideFile = Files.write(root.resolve("secret.txt"), "top-secret".getBytes(StandardCharsets.UTF_8));
        Path lockDir = root.resolve("locks");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (IdeBridge bridge = new IdeBridge(ws, lockDir, null, null).start()) {
            // 未连接时 canUseTool 必须 fail-closed 拒绝
            assertFalse(bridge.canUseTool("Bash", new JsonObject(), "echo hi", 500).allowed);

            CountDownLatch connected = new CountDownLatch(1);
            List<String> receivedTypes = new CopyOnWriteArrayList<String>();
            Future<?> clientTask = pool.submit(() -> {
                try (Socket sock = new Socket("127.0.0.1", bridge.port())) {
                    BufferedReader in = readerOf(sock);
                    OutputStream out = sock.getOutputStream();
                    writeFrame(out,
                            helloFrame(IdeBridge.PROTOCOL_VERSION, bridge.token(), ws.toString(), "hash-ok"));
                    JsonObject ack = readFrame(in);
                    assertTrue(ack.get("ok").getAsBoolean());
                    connected.countDown();

                    SdkFrameIo.Read r;
                    while ((r = SdkFrameIo.readFrame(in)).line != null) {
                        JsonObject req = SdkFrameIo.parse(r.line);
                        String type = req.get("type").getAsString();
                        String id = req.get("id").getAsString();
                        receivedTypes.add(type);

                        JsonObject resp = new JsonObject();
                        resp.addProperty("type", "response");
                        resp.addProperty("id", id);
                        resp.addProperty("ok", true);
                        JsonObject data = new JsonObject();
                        if ("get_selection".equals(type)) {
                            data.addProperty("file", insideFile.toString());
                            data.addProperty("startLine", 1);
                            data.addProperty("startColumn", 1);
                            data.addProperty("endLine", 1);
                            data.addProperty("endColumn", 10);
                            data.addProperty("text", "class Main");
                        } else if ("get_diagnostics".equals(type)) {
                            JsonArray arr = new JsonArray();
                            JsonObject d = new JsonObject();
                            d.addProperty("file", insideFile.toString());
                            d.addProperty("line", 1);
                            d.addProperty("column", 7);
                            d.addProperty("severity", "warning");
                            d.addProperty("message", "unused class");
                            d.addProperty("source", "javac");
                            arr.add(d);
                            data.add("items", arr);
                        } else if ("can_use_tool".equals(type)) {
                            String tool = req.get("tool").getAsString();
                            if ("SlowTool".equals(tool)) {
                                // 故意不回复以验证超时 fail-closed
                                continue;
                            }
                            data.addProperty("allowed", "AllowTool".equals(tool));
                            data.addProperty("reason", "AllowTool".equals(tool) ? "user ok" : "user denied");
                        }
                        resp.add("data", data);
                        writeFrame(out, resp);
                    }
                } catch (Exception ignored) { }
            });

            assertTrue(connected.await(3, TimeUnit.SECONDS));
            waitUntil(bridge::isConnected, 2000);
            assertTrue(bridge.statusText().contains("Visual Studio Code 1.70.2"));

            // 1. getSelection
            IdeBridge.Selection sel = bridge.getSelection();
            assertEquals("class Main", sel.text);
            assertEquals(1, sel.startLine);

            // 2. openFile（工作区内放行，工作区外 PathsGuard 拒绝）
            bridge.openFile(insideFile, 1, 1);
            try {
                bridge.openFile(outsideFile, 1, 1);
                fail("越界 openFile 必须抛 SecurityException");
            } catch (SecurityException expected) {
                // ok
            }

            // 3. openDiff（工作区内放行，工作区外 PathsGuard 拒绝）
            bridge.openDiff(insideFile, "class Main { int x; }\n", "Diff Preview");
            try {
                bridge.openDiff(outsideFile, "hack", "bad");
                fail("越界 openDiff 必须抛 SecurityException");
            } catch (SecurityException expected) {
                // ok
            }

            // 4. getDiagnostics
            List<IdeBridge.DiagnosticItem> diags = bridge.getDiagnostics(insideFile);
            assertEquals(1, diags.size());
            assertEquals("warning", diags.get(0).severity);
            assertEquals("unused class", diags.get(0).message);

            // 5. canUseTool：允许 / 拒绝 / 超时 fail-closed
            assertTrue(bridge.canUseTool("AllowTool", new JsonObject(), "ok", 2000).allowed);
            assertFalse(bridge.canUseTool("DenyTool", new JsonObject(), "no", 2000).allowed);
            assertFalse(bridge.canUseTool("SlowTool", new JsonObject(), "timeout", 250).allowed);

            assertTrue(receivedTypes.contains("open_file"));
            assertTrue(receivedTypes.contains("open_diff"));
            clientTask.cancel(true);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void disconnectCancelsInFlightRequestAndReconnectNeverReplays() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path ws = Files.createDirectories(root.resolve("ws"));
        Path lockDir = root.resolve("locks");

        ExecutorService pool = Executors.newCachedThreadPool();
        try (IdeBridge bridge = new IdeBridge(ws, lockDir, null, null).start()) {
            // 第一次连接：收到 get_selection 后直接断开不回复
            Socket s1 = new Socket("127.0.0.1", bridge.port());
            BufferedReader r1 = readerOf(s1);
            writeFrame(s1.getOutputStream(),
                    helloFrame(IdeBridge.PROTOCOL_VERSION, bridge.token(), ws.toString(), "hash-1"));
            assertTrue(readFrame(r1).get("ok").getAsBoolean());
            waitUntil(bridge::isConnected, 2000);

            Future<IdeBridge.Selection> inFlight = pool.submit(() -> bridge.getSelection(5000));
            JsonObject req1 = readFrame(r1);
            assertEquals("get_selection", req1.get("type").getAsString());
            s1.close(); // 模拟 IDE 意外断线

            try {
                inFlight.get(3, TimeUnit.SECONDS);
                fail("断线应立即结束在途请求");
            } catch (ExecutionException expected) {
                assertTrue("断线应立即以 IOException 结束在途请求",
                        expected.getCause() instanceof IOException);
            }

            // 第二次重连：确认不会收到旧请求重放，只收到新请求
            try (Socket s2 = new Socket("127.0.0.1", bridge.port())) {
                BufferedReader r2 = readerOf(s2);
                writeFrame(s2.getOutputStream(),
                        helloFrame(IdeBridge.PROTOCOL_VERSION, bridge.token(), ws.toString(), "hash-1"));
                assertTrue(readFrame(r2).get("ok").getAsBoolean());
                waitUntil(bridge::isConnected, 2000);

                Future<List<IdeBridge.DiagnosticItem>> diagFut = pool.submit(() -> bridge.getDiagnostics(null, 3000));
                JsonObject firstOnNewConn = readFrame(r2);
                assertEquals("重连后首帧应是新请求，绝不能重放断线前的 get_selection",
                        "get_diagnostics", firstOnNewConn.get("type").getAsString());
                JsonObject resp = new JsonObject();
                resp.addProperty("type", "response");
                resp.addProperty("id", firstOnNewConn.get("id").getAsString());
                resp.addProperty("ok", true);
                resp.add("data", new JsonObject());
                writeFrame(s2.getOutputStream(), resp);
                assertTrue(diagFut.get(3, TimeUnit.SECONDS).isEmpty());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void commandDispatcherRoutesIdeToHandler() {
        CommandDispatcher d = new CommandDispatcher()
                .ide((h, parts) -> "ide:" + (parts.length > 1 ? parts[1] : "status"));
        assertEquals("ide:status", d.dispatch(null, "/ide"));
        assertEquals("ide:start", d.dispatch(null, "/ide start"));
    }

    private static JsonObject helloFrame(int version, String token, String workspace, String pluginHash) {
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocolVersion", version);
        hello.addProperty("token", token);
        hello.addProperty("workspace", workspace);
        hello.addProperty("ideName", "Visual Studio Code");
        hello.addProperty("ideVersion", "1.70.2");
        hello.addProperty("pluginId", "minion.minion-ide-bridge");
        hello.addProperty("pluginVersion", "0.1.0");
        hello.addProperty("pluginHash", pluginHash);
        return hello;
    }

    private static JsonObject sendHelloAndReadAck(int port, JsonObject hello) throws IOException {
        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(3000);
            writeFrame(sock.getOutputStream(), hello);
            return readFrame(readerOf(sock));
        }
    }

    private static BufferedReader readerOf(Socket sock) throws IOException {
        return new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
    }

    private static void writeFrame(OutputStream out, JsonObject obj) throws IOException {
        out.write((GSON.toJson(obj) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static JsonObject readFrame(BufferedReader in) throws IOException {
        SdkFrameIo.Read r = SdkFrameIo.readFrame(in);
        assertFalse("帧不应超限", r.overrun);
        assertNotNull("期望收到 JSON 帧", r.line);
        JsonObject parsed = SdkFrameIo.parse(r.line);
        assertNotNull("帧应为合法 JSON", parsed);
        return parsed;
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue("等待条件超时", cond.getAsBoolean());
    }
}
