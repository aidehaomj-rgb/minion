package com.minion.core.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.Session;
import com.minion.core.config.Config;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpStore;
import com.minion.core.security.ProjectTrustStore;
import com.minion.core.security.ReadStateStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * v2.4 §12.2 SDK 二期控制请求验收：逐项成功与失败路径；mcp_set_servers 经 §10.9
 * 信任校验（未批准不连）；get_settings 敏感键脱敏；子系统不可用明确 unavailable；
 * SdkDuplex 扩展分发（成功/未知请求）。不绕过权限管线由 mcp 信任门与 evaluatePolicy
 * 链路保证（CanUseToolCallbackTest 已锁）。
 */
public class SdkControlHandlersTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Map<String, SdkRequestHandler> handlers(McpManager mcp,
            com.minion.core.storage.RewindService rewind, ReadStateStore readState) throws Exception {
        Path jarDir = tmp.newFolder().toPath();
        Config config = Config.load(jarDir);
        config.set("model.key", "sk-secret-value");
        Session session = Session.create(tmp.newFolder().getPath(), "deepseek-v4-flash");
        return SdkControlHandlers.build(config, jarDir, jarDir, mcp,
                ProjectTrustStore.load(jarDir), null, rewind, readState, session);
    }

    private static JsonObject ok(SdkRequestHandler h, String bodyJson) throws Exception {
        return h.handle(JsonParser.parseString(bodyJson).getAsJsonObject());
    }

    private static SdkRequestHandler.SdkRequestError err(SdkRequestHandler h, String bodyJson) {
        try {
            h.handle(bodyJson == null ? new JsonObject()
                    : JsonParser.parseString(bodyJson).getAsJsonObject());
            fail("应抛 SdkRequestError");
            return null;
        } catch (SdkRequestHandler.SdkRequestError expected) {
            return expected;
        } catch (Exception other) {
            throw new AssertionError("非预期异常: " + other);
        }
    }

    /** mcp_set_servers：全量替换保存 + stdio 未过信任不自动连；mcp_status 反映 */
    @Test public void mcpSetServersUntrustedNotConnected() throws Exception {
        Path jarDir = tmp.newFolder("mcpjar").toPath();
        McpManager mcp = new McpManager(McpStore.load(jarDir));
        Map<String, SdkRequestHandler> hs = handlers(mcp, null, null);
        JsonObject result = ok(hs.get("mcp_set_servers"),
                "{\"servers\":[{\"name\":\"demo\",\"command\":\"run-mcp\",\"args\":[\"--x\"]}]}");
        assertEquals(1, result.getAsJsonArray("installed").size());
        assertFalse("未过 §10.9 信任不得标记 trusted",
                result.getAsJsonArray("installed").get(0).getAsJsonObject().get("trusted").getAsBoolean());
        assertEquals(1, mcp.servers().size());
        assertEquals("demo", mcp.servers().get(0).name);

        JsonObject status = ok(hs.get("mcp_status"), "{}");
        assertEquals(1, status.getAsJsonArray("servers").size());
        assertEquals("demo", status.getAsJsonArray("servers").get(0).getAsJsonObject().get("name").getAsString());
        // 重复调用幂等（全量替换语义）
        ok(hs.get("mcp_set_servers"),
                "{\"servers\":[{\"name\":\"demo\",\"command\":\"run-mcp\",\"args\":[\"--x\"]}]}");
        assertEquals(1, mcp.servers().size());
        mcp.shutdown();
    }

    /** mcp_message：不存在的 server → call_failed；mcp 未装配 → unavailable */
    @Test public void mcpMessageFailurePaths() throws Exception {
        Map<String, SdkRequestHandler> hs = handlers(
                new McpManager(McpStore.load(tmp.newFolder("m2").toPath())), null, null);
        assertEquals("call_failed", err(hs.get("mcp_message"),
                "{\"server\":\"ghost\",\"tool\":\"t\"}").code);
        Map<String, SdkRequestHandler> none = handlers(null, null, null);
        assertEquals("unavailable", err(none.get("mcp_status"), "{}").code);
        assertEquals("unavailable", err(none.get("mcp_message"), "{}").code);
        assertEquals("unavailable", err(none.get("mcp_set_servers"), "{\"servers\":[]}").code);
    }

    /** rewind_files：无快照失败路径（ok=false 或 rewind_failed）；未装配 → no_history */
    @Test public void rewindFilesFailurePaths() throws Exception {
        Path ws = tmp.newFolder().toPath();
        com.minion.core.storage.RewindService rewind =
                new com.minion.core.storage.RewindService(
                        new com.minion.core.storage.MessageFileHistory(ws), ws);
        Map<String, SdkRequestHandler> hs = handlers(null, rewind, null);
        SdkRequestHandler rewindHandler = hs.get("rewind_files");
        boolean failureReported;
        try {
            JsonObject r = rewindHandler.handle(JsonParser.parseString(
                    "{\"message_id\":\"m1\",\"mode\":\"code\"}").getAsJsonObject());
            failureReported = !r.get("ok").getAsBoolean();   // 成功响应携带 ok=false
        } catch (SdkRequestHandler.SdkRequestError expected) {
            failureReported = "rewind_failed".equals(expected.code);
        }
        assertTrue("无快照必须以失败上报（不伪造成功）", failureReported);
        assertEquals("no_history", err(handlers(null, null, null)
                .get("rewind_files"), "{\"message_id\":\"m\"}").code);
        assertEquals("invalid_request", err(hs.get("rewind_files"), "{\"mode\":\"code\"}").code);
    }

    /** seed_read_state：条目入 ReadStateStore；重复 seed 幂等 */
    @Test public void seedReadStateEntries() throws Exception {
        ReadStateStore store = new ReadStateStore();
        Map<String, SdkRequestHandler> hs = handlers(null, null, store);
        JsonObject r1 = ok(hs.get("seed_read_state"),
                "{\"entries\":[{\"path\":\"a.txt\",\"contentHash\":\"h1\",\"mtime\":123},"
                        + "{\"path\":\"\",\"contentHash\":\"x\"}]}");
        assertEquals("空 path 跳过", 1, r1.get("seeded").getAsInt());
        JsonObject r2 = ok(hs.get("seed_read_state"),
                "{\"entries\":[{\"path\":\"a.txt\",\"contentHash\":\"h1\",\"mtime\":123}]}");
        assertEquals("幂等", 1, r2.get("seeded").getAsInt());
        assertEquals("invalid_request", err(hs.get("seed_read_state"), "{}").code);
    }

    /** get_settings：敏感键脱敏；普通键明文 */
    @Test public void getSettingsMasksSecrets() throws Exception {
        Map<String, SdkRequestHandler> hs = handlers(null, null, null);
        JsonObject settings = ok(hs.get("get_settings"), "{}");
        String props = settings.getAsJsonObject("settings").toString();
        assertTrue("model.key 须脱敏", props.contains("sk-***") || props.contains("\"***\""));
        assertFalse("密钥明文不得出现", props.contains("sk-secret-value"));
    }

    /** get_context_usage：无 ContextManager 走估算；reload_plugins 空载；stop_task 幂等 */
    @Test public void contextPluginsStop() throws Exception {
        Map<String, SdkRequestHandler> hs = handlers(null, null, null);
        JsonObject usage = ok(hs.get("get_context_usage"), "{}");
        assertTrue(usage.has("estimatedChars"));
        JsonObject plugins = ok(hs.get("reload_plugins"), "{}");
        assertEquals(0, plugins.get("skills").getAsInt());
        JsonObject stop = ok(hs.get("stop_task"), "{}");   // loop null 容错
        assertNotNull(stop);
        ok(hs.get("stop_task"), "{}");                     // 幂等重复
        JsonObject hook = ok(hs.get("hook_callback"), "{}");
        assertFalse(hook.get("registered").getAsBoolean());
    }

    /** SdkDuplex 集成：扩展请求成功响应（request_id 关联）+ 未知请求报错 */
    @Test public void duplexExtendedDispatch() throws Exception {
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        StreamJsonProtocol protocol = new StreamJsonProtocol(
                new java.io.PrintStream(captured, true, "UTF-8"), true);
        SdkDuplexTest.ScriptedStdin in = new SdkDuplexTest.ScriptedStdin();
        SdkDuplexTest.ScriptedEngine engine = new SdkDuplexTest.ScriptedEngine();
        SdkHostBridge bridge = new SdkHostBridge(protocol, 1000);
        Map<String, SdkRequestHandler> handlers = new java.util.LinkedHashMap<String, SdkRequestHandler>();
        handlers.put("get_settings", body -> {
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            return r;
        });
        SdkDuplex duplex = new SdkDuplex(protocol, engine, bridge, handlers);
        Thread driver = new Thread(() -> {
            try {
                in.send("{\"type\":\"initialize\",\"protocolVersion\":1}");
                Thread.sleep(200);
                in.send("{\"type\":\"control_request\",\"request_id\":\"x1\",\"subtype\":\"get_settings\"}");
                in.send("{\"type\":\"control_request\",\"request_id\":\"x2\",\"subtype\":\"no_such_thing\"}");
                Thread.sleep(300);
                in.finish();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        driver.start();
        int exit = duplex.run(new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)));
        driver.join(5000);
        assertEquals(0, exit);
        String out = captured.toString();
        assertTrue("扩展请求成功响应", out.contains("\"request_id\":\"x1\""));
        assertTrue("未知请求报错", out.contains("unknown_request"));
    }
}
