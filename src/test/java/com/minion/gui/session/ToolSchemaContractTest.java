package com.minion.gui.session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.skills.Skill;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.plugin.ToolPlugin;
import com.minion.core.tools.plugin.ToolPluginManager;
import com.minion.core.tools.plugin.ToolStore;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 工具 schema 契约（2026-11-23）：schema 是直接发给模型的协议，按会话实际装配的全部工具校验。
 * <ul>
 * <li>畸形 schema 会让 OpenAI 兼容网关整轮 400（此前 TaskOutput.block 的 schema 值是裸字符串）；</li>
 * <li>“参数一律 string”会让弱模型把对象/数组/布尔当字符串传——StructuredOutput.data、TodoWrite、
 *     TaskOutput.block 三次同根故障，都出在这里。</li>
 * </ul>
 */
public class ToolSchemaContractTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final ConfirmUi FAKE_UI = new ConfirmUi() {
        @Override public Decision ask(String message) { return Decision.APPROVE; }
    };
    private static final List<String> TYPES = Arrays.asList(
            "string", "integer", "number", "boolean", "object", "array", "null");

    @Test public void everySessionToolSchemaIsWellFormedAndHonestlyTyped() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        ToolPluginManager plugins = new ToolPluginManager(ToolStore.load(jar));
        SessionManager m = new SessionManager(FAKE_UI, Config.load(jar), jar, WorkspaceManager.load(jar),
                ModelManager.load(jar), new ArrayList<Skill>(), plugins, null);
        try {
            SessionHandle h = m.createSession(null);
            ToolRegistry registry = h.loop.registry();
            for (ToolPlugin plugin : plugins.plugins()) plugins.setEnabled(plugin.id(), true);   // 插件工具一并覆盖
            List<String> problems = new ArrayList<String>();
            assertTrue("会话工具面异常稀少: " + registry.schemas().size(), registry.schemas().size() > 30);
            assertTrue(registry.get("StructuredOutput") != null);
            assertTrue(registry.get("task") != null);
            for (JsonObject schema : registry.schemas()) {
                String tool = schema.getAsJsonObject("function").get("name").getAsString();
                check(tool, schema.getAsJsonObject("function").getAsJsonObject("parameters"), problems);
            }
            assertTrue(String.join("\n", problems), problems.isEmpty());
        } finally {
            m.shutdown();
            plugins.shutdown();
        }
    }

    private static void check(String tool, JsonObject schema, List<String> problems) {
        String where = tool + ": ";
        if (!"object".equals(text(schema, "type"))) problems.add(where + "根 type 不是 object: " + schema);
        if (text(schema, "description").trim().isEmpty()) problems.add(where + "缺少工具描述");
        JsonObject props = schema.getAsJsonObject("properties");
        if (props == null) { problems.add(where + "缺少 properties"); return; }
        for (java.util.Map.Entry<String, JsonElement> entry : props.entrySet()) {
            String name = where + "参数 " + entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                problems.add(name + " 不是 JSON 对象（裸值无法发给模型）: " + entry.getValue());
                continue;
            }
            JsonObject prop = entry.getValue().getAsJsonObject();
            boolean described = prop.has("enum") || prop.has("anyOf") || prop.has("oneOf");
            String type = text(prop, "type");
            if (!described && !TYPES.contains(type)) problems.add(name + " type 非法: " + type);
            if ("array".equals(type) && !prop.has("items"))
                problems.add(name + " 是数组但没有 items");
            if ("object".equals(type) && prop.has("properties") && !prop.get("properties").isJsonObject())
                problems.add(name + " 的 properties 不是对象");
            if (prop.has("additionalProperties") && !prop.get("additionalProperties").isJsonPrimitive())
                problems.add(name + " 的 additionalProperties 不是布尔值");
        }
        if (schema.has("required")) {
            if (!schema.get("required").isJsonArray()) problems.add(where + "required 不是数组");
            else for (JsonElement required : schema.getAsJsonArray("required"))
                if (!required.isJsonPrimitive() || !props.has(required.getAsString()))
                    problems.add(where + "required 引用了未声明的参数: " + required);
        }
    }

    private static String text(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
