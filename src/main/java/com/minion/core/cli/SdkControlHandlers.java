package com.minion.core.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.config.Config;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpServer;
import com.minion.core.security.ProjectTrustStore;
import com.minion.core.security.ReadStateStore;
import com.minion.core.security.TrustGate;
import com.minion.core.security.TrustedExecutable;
import com.minion.core.storage.RewindService;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §12.2 SDK 二期控制请求装配：mcp_status / mcp_message / mcp_set_servers（§10.9 信任
 * 校验）/ hook_callback / rewind_files（RewindService 预恢复点与冲突保护）/ seed_read_state
 * （ReadStateStore 同步）/ get_context_usage / get_settings（敏感键脱敏）/ reload_plugins /
 * stop_task（幂等）。全部有界（SdkDuplex 统一 30s 超时），不绕过权限管线（mcp_message 经
 * 宿主已授权语义——由宿主发起即宿主意志；mcp_set_servers 新命令未经信任不自动连接）。
 * 子系统不可用（headless 未装配）返回明确 unavailable 错误而非静默。
 */
public final class SdkControlHandlers {

    private SdkControlHandlers() { }

    public static Map<String, SdkRequestHandler> build(
            final Config config, final Path jarDir, final Path workDir,
            final McpManager mcp, final ProjectTrustStore trust,
            final AgentLoop loop, final RewindService rewind,
            final ReadStateStore readState, final Session session) {
        Map<String, SdkRequestHandler> out = new LinkedHashMap<String, SdkRequestHandler>();

        // ===== MCP 控制 =====
        out.put("mcp_status", body -> {
            if (mcp == null) throw new SdkRequestHandler.SdkRequestError("unavailable", "headless 未装配 MCP");
            JsonArray servers = new JsonArray();
            for (McpServer s : mcp.servers()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", s.name);
                o.addProperty("transport", McpServer.normalizedTransport(s.transport));
                o.addProperty("enabled", s.enabled);
                o.addProperty("state", String.valueOf(s.state));
                if (s.failReason != null) o.addProperty("failReason", s.failReason);
                o.addProperty("tools", s.tools == null ? 0 : s.tools.size());
                servers.add(o);
            }
            JsonObject result = new JsonObject();
            result.add("servers", servers);
            return result;
        });
        out.put("mcp_message", body -> {
            if (mcp == null) throw new SdkRequestHandler.SdkRequestError("unavailable", "headless 未装配 MCP");
            String server = str(body, "server"), tool = str(body, "tool");
            if (server == null || tool == null)
                throw new SdkRequestHandler.SdkRequestError("invalid_request", "缺少 server/tool");
            JsonObject arguments = body.has("arguments") && body.get("arguments").isJsonObject()
                    ? body.getAsJsonObject("arguments") : new JsonObject();
            try {
                String output = mcp.call(server, tool, arguments);
                JsonObject result = new JsonObject();
                result.addProperty("output", output == null ? "" : output);
                return result;
            } catch (Exception e) {
                throw new SdkRequestHandler.SdkRequestError("call_failed",
                        server + "/" + tool + ": " + e.getMessage());
            }
        });
        out.put("mcp_set_servers", body -> {
            if (mcp == null) throw new SdkRequestHandler.SdkRequestError("unavailable", "headless 未装配 MCP");
            if (!body.has("servers") || !body.get("servers").isJsonArray())
                throw new SdkRequestHandler.SdkRequestError("invalid_request", "缺少 servers 数组");
            TrustGate gate = trust == null ? null
                    : new TrustGate(trust, true, TrustGate.DENY);   // headless 无 UI：未批准即不连
            JsonArray status = new JsonArray();
            mcp.servers().clear();
            for (com.google.gson.JsonElement element : body.getAsJsonArray("servers")) {
                if (!element.isJsonObject()) continue;
                JsonObject spec = element.getAsJsonObject();
                McpServer s = new McpServer();
                s.name = str(spec, "name");
                s.transport = str(spec, "transport") == null ? "stdio" : str(spec, "transport");
                s.command = str(spec, "command") == null ? "" : str(spec, "command");
                s.url = str(spec, "url");
                s.enabled = true;
                if (spec.has("args") && spec.get("args").isJsonArray()) {
                    s.args = new java.util.ArrayList<String>();
                    for (com.google.gson.JsonElement a : spec.getAsJsonArray("args"))
                        if (a.isJsonPrimitive()) s.args.add(a.getAsString());
                }
                if (spec.has("env") && spec.get("env").isJsonObject())
                    for (Map.Entry<String, com.google.gson.JsonElement> e : spec.getAsJsonObject("env").entrySet())
                        if (e.getValue().isJsonPrimitive()) s.env.put(e.getKey(), e.getValue().getAsString());
                mcp.servers().add(s);
                JsonObject row = new JsonObject();
                row.addProperty("name", s.name);
                if (gate != null && "stdio".equals(McpServer.normalizedTransport(s.transport))) {
                    TrustedExecutable exe = TrustedExecutable.of(
                            TrustedExecutable.TYPE_MCP, s.name, "user", s.command, s.args, s.env,
                            jarDir == null ? "" : jarDir.toString(),
                            TrustedExecutable.referencedScripts(
                                    s.command + " " + String.join(" ", s.args), jarDir));
                    row.addProperty("trusted", gate.ensureTrusted(exe));
                    row.addProperty("connected", false);   // 未过 §10.9 不自动连接
                } else {
                    row.addProperty("trusted", true);
                    row.addProperty("connected", false);   // 配置保存即成功，连接惰性
                }
                status.add(row);
            }
            mcp.save();
            JsonObject result = new JsonObject();
            result.add("installed", status);
            return result;
        });

        // ===== Hook 与文件回退 =====
        out.put("hook_callback", body -> {
            // 远程 hook 的事件转发由 Minion→宿主 control_request(hook_callback) 往返承载
            // （SdkHostBridge）；宿主→Minion 方向仅支持查询当前注册状态（注册走 initialize）
            JsonObject result = new JsonObject();
            result.addProperty("registered",
                    loop != null && loop.ccHookSystem() != null && loop.ccHookSystem().enabled());
            result.addProperty("note", "远程 hook 事件经 control_request(hook_callback) 由宿主应答；注册经 initialize");
            return result;
        });
        out.put("rewind_files", body -> {
            if (rewind == null)
                throw new SdkRequestHandler.SdkRequestError("no_history", "会话无文件历史（未恢复持久会话）");
            String messageId = str(body, "message_id");
            String mode = str(body, "mode") == null ? "code" : str(body, "mode");
            if (messageId == null)
                throw new SdkRequestHandler.SdkRequestError("invalid_request", "缺少 message_id");
            try {
                RewindService.Result r = rewind.rewind(session, messageId, mode, null);
                JsonObject result = new JsonObject();
                result.addProperty("ok", r.ok);
                result.addProperty("mode", r.mode);
                result.addProperty("filesRestored", r.filesRestored);
                result.addProperty("filesUnchanged", r.filesUnchanged);
                result.addProperty("filesDeleted", r.filesDeleted);
                result.addProperty("messagesBefore", r.messagesBefore);
                result.addProperty("messagesAfter", r.messagesAfter);
                if (!r.conflicts.isEmpty()) {
                    JsonArray conflicts = new JsonArray();
                    for (com.minion.core.storage.MessageFileHistory.RestoreItem c : r.conflicts) {
                        JsonObject row = new JsonObject();
                        row.addProperty("path", c.path);
                        conflicts.add(row);
                    }
                    result.add("conflicts", conflicts);
                }
                return result;
            } catch (Exception e) {
                throw new SdkRequestHandler.SdkRequestError("rewind_failed", e.getMessage());
            }
        });

        // ===== 状态与运维 =====
        out.put("seed_read_state", body -> {
            if (readState == null)
                throw new SdkRequestHandler.SdkRequestError("unavailable", "ReadStateStore 未装配");
            if (!body.has("entries") || !body.get("entries").isJsonArray())
                throw new SdkRequestHandler.SdkRequestError("invalid_request", "缺少 entries 数组");
            int seeded = 0;
            for (com.google.gson.JsonElement element : body.getAsJsonArray("entries")) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String path = str(entry, "path");
                if (path == null || path.trim().isEmpty()) continue;
                readState.record(path, null, str(entry, "contentHash"), null,
                        entry.has("mtime") ? entry.get("mtime").getAsLong() : 0L,
                        0, 0, true, false);
                seeded++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("seeded", seeded);
            return result;
        });
        out.put("get_context_usage", body -> {
            JsonObject result = new JsonObject();
            com.minion.core.context.ContextManager cm = loop == null ? null : loop.contextManager();
            if (cm != null) {
                result.addProperty("configuredMaxTokens", cm.configuredMaxTokens());
                result.addProperty("maxTokens", cm.maxTokens());
                result.addProperty("autoCompactAtTokens", cm.autoCompressAtTokens());
            }
            int chars = 0;
            if (session != null) synchronized (session.messages) {
                for (com.minion.core.llm.Message m : session.messages)
                    chars += m.content == null ? 0 : m.content.length();
            }
            result.addProperty("estimatedChars", chars);
            return result;
        });
        out.put("get_settings", body -> {
            JsonObject result = new JsonObject();
            JsonObject props = new JsonObject();
            for (String key : config.keysWithPrefix("")) {
                String value = config.get(key, "");
                if (key.toLowerCase().matches(".*(key|token|secret|password).*") && !value.isEmpty())
                    value = "***";
                props.addProperty(key, value);
            }
            result.add("settings", props);
            if (config.settingsChain() != null)
                result.addProperty("chain", config.settingsChain().describe());
            return result;
        });
        out.put("reload_plugins", body -> {
            com.minion.core.plugins.PluginMarketplace marketplace =
                    new com.minion.core.plugins.PluginMarketplace(jarDir);
            com.minion.core.plugins.PluginInstaller installer =
                    new com.minion.core.plugins.PluginInstaller(jarDir, marketplace);
            com.minion.core.plugins.PluginLoader loader = new com.minion.core.plugins.PluginLoader(
                    installer, trust);
            com.minion.core.plugins.PluginLoader.Runtime runtime = loader.loadEnabled();
            JsonObject result = new JsonObject();
            result.addProperty("skills", runtime.skills.size());
            result.addProperty("commands", runtime.commands.size());
            result.addProperty("pendingTrust", runtime.pendingTrust.size());
            return result;
        });
        out.put("stop_task", body -> {
            if (loop != null) loop.interrupt();   // 幂等：空闲时无副作用
            return new JsonObject();
        });
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsString() : null;
    }
}
