package com.minion.core.tools;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.agent.AgentUi;
import com.minion.core.config.Config;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * task 工具的入口友好度（2026-11-23 内网弱模型修复）：参数必须带说明、类型如实声明；
 * 模型的常见变体写法自动纠正而不是拒绝。真实派发路径见 BackgroundChildAgentTest。
 */
public class TaskToolArgumentsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private TaskTool task;

    @Before public void setup() {
        AgentLoop loop = new AgentLoop(new com.minion.core.llm.FakeLlmClient(), new ToolRegistry(), new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi() {}, null, new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        task = new TaskTool(loop);
    }

    /** 弱模型靠参数说明才会用：每个参数都要有说明，布尔/整数/数组不能沿用全 string 的默认类型 */
    @Test public void schemaDescribesEveryParameterWithHonestTypes() {
        JsonObject schema = task.schema();
        assertEquals("object", schema.get("type").getAsString());
        JsonObject props = schema.getAsJsonObject("properties");
        assertFalse(props.entrySet().isEmpty());
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : props.entrySet())
            assertFalse(entry.getKey() + " 缺少参数说明",
                    entry.getValue().getAsJsonObject().get("description").getAsString().trim().isEmpty());
        assertEquals("boolean", props.getAsJsonObject("run_in_background").get("type").getAsString());
        assertEquals("boolean", props.getAsJsonObject("isolatedOutput").get("type").getAsString());
        assertEquals("boolean", props.getAsJsonObject("requiresMerge").get("type").getAsString());
        assertEquals("integer", props.getAsJsonObject("timeoutMillis").get("type").getAsString());
        assertEquals("integer", props.getAsJsonObject("revision").get("type").getAsString());
        for (String key : new String[]{"scope", "dependsOn"}) {
            JsonObject prop = props.getAsJsonObject(key);
            assertEquals(key, "array", prop.get("type").getAsString());
            assertEquals(key + " 的元素类型", "string", prop.getAsJsonObject("items").get("type").getAsString());
        }
        // 简单用法只需要 description
        assertEquals(1, schema.getAsJsonArray("required").size());
        assertEquals("description", schema.getAsJsonArray("required").get(0).getAsString());
        assertTrue(task.description().contains("run_in_background=true"));
        assertTrue(task.description().contains("mode=\"explore\""));
    }

    @Test public void modeSynonymsNormalizeToCanonicalModes() {
        for (String read : new String[]{"read", "Read-Only", "readonly", "research", "调查", "只读", "查询"})
            assertEquals(read, "explore", TaskTool.normalizeMode(read));
        for (String write : new String[]{"write", "implement", "coding", "general-purpose", "执行", "写入"})
            assertEquals(write, "worker", TaskTool.normalizeMode(write));
        for (String plan : new String[]{"planner", "规划", "计划"}) assertEquals(plan, "plan", TaskTool.normalizeMode(plan));
        assertEquals("explore", TaskTool.normalizeMode(" Explore "));
        assertEquals("worker", TaskTool.normalizeMode("worker"));
        assertNull("无法识别的 mode 保持原值，由调用方提示", TaskTool.normalizeMode("admin"));
        assertNull(TaskTool.normalizeMode(null));
        assertNull(TaskTool.normalizeMode("  "));
    }

    /** 任务说明写在 prompt/task/instructions 等字段里：当作 description，且不再重复追加 */
    @Test public void descriptionFallsBackToAlternateFields() {
        JsonObject call = TaskTool.normalize(parse("{\"prompt\":\"调研并返回要点\",\"mode\":\"read\"}"));
        assertEquals("调研并返回要点", call.get("description").getAsString());
        assertFalse(call.has("prompt"));
        assertEquals("explore", call.get("mode").getAsString());

        call = TaskTool.normalize(parse("{\"task\":\"整理目录\"}"));
        assertEquals("整理目录", call.get("description").getAsString());

        JsonObject explicit = TaskTool.normalize(parse("{\"description\":\"正式说明\",\"prompt\":\"补充要求\"}"));
        assertEquals("正式说明", explicit.get("description").getAsString());
        assertEquals("已有 description 时 prompt 保留为补充要求", "补充要求", explicit.get("prompt").getAsString());
    }

    @Test public void stringScopeAndDependsOnBecomeArrays() {
        JsonObject call = TaskTool.normalize(parse("{\"description\":\"x\",\"scope\":\"src/\",\"dependsOn\":\"a, b；c\"}"));
        assertEquals(1, call.getAsJsonArray("scope").size());
        assertEquals("src/", call.getAsJsonArray("scope").get(0).getAsString());
        com.google.gson.JsonArray deps = call.getAsJsonArray("dependsOn");
        assertEquals(3, deps.size());
        assertEquals("b", deps.get(1).getAsString());
    }

    @Test public void structuredEntryIsDetectedByAnyStructuredKey() {
        assertFalse(TaskTool.isStructured(parse("{\"description\":\"x\",\"run_in_background\":true}")));
        for (String key : new String[]{"stepId", "scope", "dependsOn", "revision", "requiresMerge"}) {
            JsonObject call = parse("{\"description\":\"x\"}");
            call.addProperty(key, 1);
            assertTrue(key, TaskTool.isStructured(call));
        }
    }

    /** stepId 只是标签：中文、空格等非法字符替换掉，超长截断，规范化后为空视为没给 */
    @Test public void stepIdIsSanitizedToCoordinatorCharset() {
        assertEquals("extract-1", TaskTool.sanitizeStepId("extract 1"));
        assertTrue(TaskTool.sanitizeStepId("数据清洗").matches("[A-Za-z0-9_.-]+"));
        assertEquals(128, TaskTool.sanitizeStepId(new String(new char[200]).replace('\0', 'a')).length());
        assertNull(TaskTool.sanitizeStepId("   "));
        assertNull(TaskTool.sanitizeStepId(null));
    }

    /** 无法识别的 mode：原样报错并给出三个合法值（不静默当 worker） */
    @Test public void unknownModeIsRejectedWithTheValidValues() {
        ToolResult result = task.execute(parse("{\"description\":\"x\",\"mode\":\"admin\"}"));
        assertFalse(result.ok);
        assertTrue(result.output, result.output.contains("mode只支持worker/explore/plan") && result.output.contains("admin"));
    }

    private static JsonObject parse(String json) { return com.google.gson.JsonParser.parseString(json).getAsJsonObject(); }
}
