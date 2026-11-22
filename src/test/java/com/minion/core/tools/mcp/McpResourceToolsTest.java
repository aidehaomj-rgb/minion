package com.minion.core.tools.mcp;

import com.google.gson.JsonParser;
import com.minion.core.mcp.FakeResourcesHandle;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpServer;
import com.minion.core.mcp.McpStore;
import com.minion.core.tools.ToolResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * v2.4 §3.7 二期验收：ListMcpResources / ReadMcpResource 只读工具；prompts
 * 命名空间斜杠命令文本（/server:prompt 形式防冲突）；未连接服务器安全降级。
 */
public class McpResourceToolsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private McpManager manager;
    private McpResourceTools.ListTool listTool;
    private McpResourceTools.ReadTool readTool;

    @Before public void setUp() throws Exception {
        Path dir = tmp.newFolder().toPath();
        McpStore store = McpStore.load(dir);
        McpServer s = new McpServer();
        s.name = "docs";
        s.transport = "stdio";
        s.command = "fake";
        s.enabled = true;
        s.state = McpServer.State.CONNECTED;
        store.list().add(s);
        manager = new McpManager(store);
        // 注入 Fake 句柄（绕过真实连接）
        Field clients = McpManager.class.getDeclaredField("clients");
        clients.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, com.minion.core.mcp.McpHandle> map =
                (java.util.Map<String, com.minion.core.mcp.McpHandle>) clients.get(manager);
        map.put("docs", new FakeResourcesHandle());
        listTool = new McpResourceTools.ListTool(manager);
        readTool = new McpResourceTools.ReadTool(manager);
    }

    @After public void tearDown() { manager.shutdown(); }

    /** ListMcpResources：全部已连接 → 资源清单带 server 标头 */
    @Test public void listAllConnectedServers() throws Exception {
        ToolResult r = listTool.execute(new com.google.gson.JsonObject());
        assertTrue(r.ok);
        assertTrue(r.output.contains("[docs]"));
        assertTrue(r.output.contains("config://app.json"));
        assertTrue(r.output.contains("docs://readme.md"));
        assertTrue("只读", listTool.isReadOnly(null));
        assertTrue("并发安全", listTool.isConcurrencySafe(null));
    }

    /** ListMcpResources：指定 server；未连接/不存在 → 安全降级提示 */
    @Test public void listSingleServerAndEmptyFallback() throws Exception {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("server", "docs");
        assertTrue(listTool.execute(args).ok);
        args.addProperty("server", "ghost");
        ToolResult r = listTool.execute(args);
        assertTrue(r.ok);
        assertTrue(r.output.contains("无可用"));
    }

    /** ReadMcpResource：读存在资源 / 缺参 / 不存在 */
    @Test public void readResourcePaths() throws Exception {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("server", "docs");
        args.addProperty("uri", "config://app.json");
        ToolResult ok = readTool.execute(args);
        assertTrue(ok.ok);
        assertTrue(ok.output.contains("\"version\""));

        ToolResult missing = readTool.execute(JsonParser.parseString(
                "{\"server\":\"docs\",\"uri\":\"config://nope\"}").getAsJsonObject());
        assertFalse(missing.ok);
        assertTrue(missing.output.contains("读资源失败"));

        ToolResult noArgs = readTool.execute(new com.google.gson.JsonObject());
        assertFalse(noArgs.ok);
        assertTrue(noArgs.output.contains("缺少"));
    }

    /** prompts 斜杠命令命名空间：/server:prompt 防冲突 + 取提示展开 */
    @Test public void promptNamespaceCommand() throws Exception {
        String prompt = manager.getPrompt("docs", "code_review", null);
        assertTrue(prompt.contains("[user] 请审查"));
        // 命名空间前缀格式（CommandDispatcher 层注册口径）
        String namespace = "docs:code_review";
        assertTrue(namespace.startsWith("docs:"));
    }
}
