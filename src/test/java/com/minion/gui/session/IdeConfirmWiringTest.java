package com.minion.gui.session;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.minion.core.bridge.IdeBridge;
import com.minion.core.cli.SdkFrameIo;
import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.config.ModelConfig;
import com.minion.core.skills.Skill;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * §12.3 IDE 桥接接入 GUI 确认链路（2026-11-23）：会话的 ConfirmGate 经 IdeConfirmUi 提问——
 * 工作区有已连接的 IDE 时，高危操作的确认发给 IDE；没有 IDE 时与接线前完全一致。
 */
public class IdeConfirmWiringTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final Gson GSON = new Gson();

    private static class SpyManager extends SessionManager {
        final List<FakeLlmClient> created = new ArrayList<FakeLlmClient>();

        SpyManager(ConfirmUi ui, Config config, Path jar) {
            super(ui, config, jar, WorkspaceManager.load(jar), ModelManager.load(jar),
                    new ArrayList<Skill>(), null, null);
        }

        @Override public LlmClient newLlm(ModelConfig model) {
            FakeLlmClient client = new FakeLlmClient();
            created.add(client);
            return client;
        }
    }

    @Test public void sessionConfirmationsGoToTheConnectedIdeInsteadOfTheLocalDialog() throws Exception {
        AtomicInteger localAsks = new AtomicInteger();
        Path jar = tmp.newFolder("jar").toPath();
        SpyManager m = new SpyManager(message -> {
            localAsks.incrementAndGet();
            return ConfirmUi.Decision.APPROVE;   // 本地若被问到就会放行并真的执行 rm
        }, Config.load(jar), jar);
        try {
            SessionHandle h = m.createSession("IDE 确认");
            Path workspace = Paths.get(h.loop.workspace().workDir()).toRealPath();
            List<JsonObject> ideRequests = new CopyOnWriteArrayList<JsonObject>();
            ExecutorService pool = Executors.newCachedThreadPool();
            try (IdeBridge bridge = new IdeBridge(workspace, tmp.newFolder("locks").toPath(), null, null).start()) {
                bridgesOf(m).put(workspace.toString(), bridge);
                Future<?> ide = pool.submit(() -> fakeIde(bridge, workspace, ideRequests));
                waitUntil(bridge::isConnected, 10000);

                runDangerousCommand(m, h);
                JsonObject confirmation = firstOf(ideRequests, "can_use_tool");
                assertNotNull("高危操作的确认应发给 IDE", confirmation);
                assertEquals(1, count(ideRequests, "can_use_tool"));
                assertTrue("请求里要带可读的确认文案: " + confirmation,
                        confirmation.get("summary").getAsString().contains("rm -rf build"));
                assertEquals("IDE 已连接时不再弹本地确认框", 0, localAsks.get());
                assertTrue("IDE 拒绝即拒绝: " + toolResult(h), toolResult(h).contains("用户拒绝了该操作"));
                ide.cancel(true);
            } finally {
                pool.shutdownNow();
            }
        } finally {
            m.shutdown();
        }
    }

    /** 没有 IDE（绝大多数内网机器）：确认照常问本地，接线不改变原有行为 */
    @Test public void withoutIdeTheLocalDialogIsAskedExactlyAsBefore() throws Exception {
        AtomicInteger localAsks = new AtomicInteger();
        Path jar = tmp.newFolder("jar").toPath();
        SpyManager m = new SpyManager(message -> {
            localAsks.incrementAndGet();
            return ConfirmUi.Decision.REJECT;
        }, Config.load(jar), jar);
        try {
            SessionHandle h = m.createSession("本地确认");
            runDangerousCommand(m, h);
            assertEquals(1, localAsks.get());
            assertTrue(toolResult(h).contains("用户拒绝了该操作"));
        } finally {
            m.shutdown();
        }
    }

    /** 启动了桥接但 IDE 没连上：同样回落本地确认框 */
    @Test public void startedButUnconnectedBridgeStillUsesTheLocalDialog() throws Exception {
        AtomicInteger localAsks = new AtomicInteger();
        Path jar = tmp.newFolder("jar").toPath();
        SpyManager m = new SpyManager(message -> {
            localAsks.incrementAndGet();
            return ConfirmUi.Decision.REJECT;
        }, Config.load(jar), jar);
        try {
            SessionHandle h = m.createSession("未连接");
            Path workspace = Paths.get(h.loop.workspace().workDir()).toRealPath();
            try (IdeBridge bridge = new IdeBridge(workspace, tmp.newFolder("locks").toPath(), null, null).start()) {
                bridgesOf(m).put(workspace.toString(), bridge);
                runDangerousCommand(m, h);
                assertEquals(1, localAsks.get());
                assertTrue(toolResult(h).contains("用户拒绝了该操作"));
            }
        } finally {
            m.shutdown();
        }
    }

    private static void runDangerousCommand(SpyManager m, SessionHandle h) throws Exception {
        FakeLlmClient llm = m.created.get(0);
        ToolCall bash = new ToolCall();
        bash.id = "dangerous";
        bash.name = "Bash";
        bash.arguments = "{\"command\":\"rm -rf build\"}";
        llm.stopKind = "task";
        llm.addTurnWithTools(Collections.singletonList(bash), null);
        llm.addTurn("已处理。");
        m.send(h, "清理 build 目录");
        h.pool.submit(() -> { }).get(60, TimeUnit.SECONDS);
    }

    private static String toolResult(SessionHandle h) {
        for (Message message : h.session.messages)
            if (message.role == Message.Role.TOOL && "Bash".equals(message.name)) return String.valueOf(message.content);
        return "(没有 Bash 工具结果) requests=" + h.session.messages;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, IdeBridge> bridgesOf(SessionManager m) throws Exception {
        Field field = SessionManager.class.getDeclaredField("ideBridges");
        field.setAccessible(true);
        return (Map<String, IdeBridge>) field.get(m);
    }

    private static int count(List<JsonObject> requests, String type) {
        int count = 0;
        for (JsonObject request : requests) if (type.equals(request.get("type").getAsString())) count++;
        return count;
    }

    private static JsonObject firstOf(List<JsonObject> requests, String type) {
        for (JsonObject request : requests) if (type.equals(request.get("type").getAsString())) return request;
        return null;
    }

    /** 假 IDE：握手后对所有请求应答；can_use_tool 一律拒绝（验证拒绝链路，不真的执行 rm） */
    private static void fakeIde(IdeBridge bridge, Path workspace, List<JsonObject> requests) {
        try (Socket socket = new Socket("127.0.0.1", bridge.port())) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("protocolVersion", IdeBridge.PROTOCOL_VERSION);
            hello.addProperty("token", bridge.token());
            hello.addProperty("workspace", workspace.toString());
            hello.addProperty("ideName", "Visual Studio Code");
            hello.addProperty("ideVersion", "1.70.2");
            hello.addProperty("pluginId", "minion.minion-ide-bridge");
            hello.addProperty("pluginVersion", "0.1.0");
            hello.addProperty("pluginHash", "hash-ok");
            write(out, hello);
            assertTrue(read(in).get("ok").getAsBoolean());
            while (true) {
                SdkFrameIo.Read frame = SdkFrameIo.readFrame(in);
                if (frame.line == null) return;
                JsonObject request = SdkFrameIo.parse(frame.line);
                requests.add(request);
                JsonObject response = new JsonObject();
                response.addProperty("type", "response");
                response.addProperty("id", request.get("id").getAsString());
                response.addProperty("ok", true);
                JsonObject data = new JsonObject();
                if ("can_use_tool".equals(request.get("type").getAsString())) {
                    data.addProperty("allowed", false);
                    data.addProperty("reason", "用户在 VS Code 中拒绝或取消");
                }
                response.add("data", data);
                write(out, response);
            }
        } catch (Exception disconnected) {
            // 桥接关闭即退出
        }
    }

    private static void write(OutputStream out, JsonObject frame) throws Exception {
        out.write((GSON.toJson(frame) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static JsonObject read(BufferedReader in) throws Exception {
        SdkFrameIo.Read frame = SdkFrameIo.readFrame(in);
        assertNotNull("期望收到 JSON 帧", frame.line);
        return SdkFrameIo.parse(frame.line);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("等待 IDE 连接超时");
    }
}
