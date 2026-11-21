package com.minion.core.tools.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * §3.1 LSP 工具验收：Content-Length 帧编解码（协议正确性）、未配置语言拒绝、isEnabled 语义、
 * publishDiagnostics 被动缓存分发。真实子进程往返属 Win7 实机总验收。
 */
public class LspToolTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    /** Content-Length 帧解码（反射访问私有方法）：合法帧/多帧序列 */
    @Test public void frameDecodingProtocolCorrect() throws Exception {
        Method readFrame = LspTool.class.getDeclaredMethod("readFrame", BufferedReader.class, long.class);
        readFrame.setAccessible(true);
        String f1 = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"a\":1}}";
        String f2 = "{\"method\":\"textDocument/publishDiagnostics\"}";
        String frame1 = "Content-Length: " + f1.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + f1;
        String frame2 = "Content-Length: " + f2.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + f2;
        BufferedReader in = new BufferedReader(new StringReader(frame1 + frame2));
        long deadline = System.currentTimeMillis() + 5000;
        JsonObject first = (JsonObject) readFrame.invoke(null, in, deadline);
        assertNotNull(first);
        assertTrue(first.has("id"));
        assertEquals(1, first.get("id").getAsInt());
        JsonObject second = (JsonObject) readFrame.invoke(null, in, deadline);
        assertNotNull(second);
        assertTrue(second.has("method"));
        assertEquals("textDocument/publishDiagnostics", second.get("method").getAsString());
    }

    /** 未配置语言：拒绝 + isEnabled=false */
    @Test public void unconfiguredLanguageRejected() throws Exception {
        Path work = tmp.newFolder().toPath();
        Path file = work.resolve("a.py");
        Files.write(file, "x = 1".getBytes(StandardCharsets.UTF_8));
        LspTool empty = new LspTool(work.toString(), Collections.<String, String>emptyMap());
        assertFalse(empty.isEnabled());
        com.minion.core.tools.ToolResult r = empty.execute(args(
                "{\"operation\":\"hover\",\"filePath\":\"" + file.toString().replace("\\", "\\\\") + "\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("未配置"));
    }

    /** 配置了语言但文件语言无 server：同样拒绝 */
    @Test public void wrongLanguageServerRejected() throws Exception {
        Path work = tmp.newFolder().toPath();
        Path javaFile = work.resolve("Demo.java");
        Files.write(javaFile, "public class D {}".getBytes(StandardCharsets.UTF_8));
        Map<String, String> onlyPython = new HashMap<String, String>();
        onlyPython.put("python", "some-server");
        LspTool lsp = new LspTool(work.toString(), onlyPython);
        assertTrue(lsp.isEnabled());
        com.minion.core.tools.ToolResult r = lsp.execute(args(
                "{\"operation\":\"hover\",\"filePath\":\"" + javaFile.toString().replace("\\", "\\\\") + "\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("未配置 LSP server"));
    }

    /** 未知 operation 拒绝（fail-closed） */
    @Test public void unknownOperationRejected() throws Exception {
        Path work = tmp.newFolder().toPath();
        Path file = work.resolve("a.py");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
        Map<String, String> servers = new HashMap<String, String>();
        servers.put("python", "echo");
        LspTool lsp = new LspTool(work.toString(), servers);
        com.minion.core.tools.ToolResult r = lsp.execute(args(
                "{\"operation\":\"badOp\",\"filePath\":\"" + file.toString().replace("\\", "\\\\") + "\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("未知 operation"));
    }
}
