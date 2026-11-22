package com.minion.core.mcp;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** §3.7 二期测试桩：内存资源/提示句柄（不走真实子进程） */
public class FakeResourcesHandle implements McpHandle {

    public final List<String> resources = new ArrayList<String>(Arrays.asList(
            "{\"uri\":\"config://app.json\",\"name\":\"app 配置\",\"mimeType\":\"application/json\"}",
            "{\"uri\":\"docs://readme.md\",\"name\":\"README\",\"mimeType\":\"text/markdown\"}"));
    public final Map<String, String> resourceContents = new HashMap<String, String>();
    public final List<String> prompts = new ArrayList<String>(Arrays.asList(
            "{\"name\":\"code_review\",\"description\":\"审查代码\"}"));
    public final Map<String, String> promptMessages = new HashMap<String, String>();

    public FakeResourcesHandle() {
        resourceContents.put("config://app.json", "{\"version\":\"1.0\"}");
        promptMessages.put("code_review", "[user] 请审查以下代码变更");
    }

    @Override public void connect() { }
    @Override public List<McpToolInfo> listTools() { return new ArrayList<McpToolInfo>(); }
    @Override public String callTool(String name, JsonObject args) { return ""; }

    @Override public List<String> listResources() { return resources; }

    @Override public String readResource(String uri) throws McpException {
        String content = resourceContents.get(uri);
        if (content == null) throw new McpException("资源不存在: " + uri);
        return content;
    }

    @Override public List<String> listPrompts() { return prompts; }

    @Override public String getPrompt(String name, Map<String, Object> arguments) throws McpException {
        String message = promptMessages.get(name);
        if (message == null) throw new McpException("提示不存在: " + name);
        return message;
    }

    @Override public void close() { }

    @Override public McpProgressAndCancel.Capabilities capabilities() {
        return McpProgressAndCancel.Capabilities.none();
    }

    @Override public void sendCancelled(String requestId, String reason) { }
}
