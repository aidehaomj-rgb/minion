package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.Session;
import com.minion.core.agent.TaskState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** §3.4 TaskV2 验收：创建/依赖拦截/状态机审计（completed 需真实回执不被绕过）/快照导出 */
public class TaskV2ToolsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private Session session() throws Exception {
        Session s = Session.create(tmp.newFolder().toPath().toString(), "m");
        s.taskState = new TaskState();
        return s;
    }

    @Test public void createGetListWithDependencies() throws Exception {
        Session s = session();
        TaskV2Tools tools = new TaskV2Tools(s);
        ToolResult a = tools.dispatch("create", args("{\"subject\":\"实现A\",\"description\":\"完成A 的验收\"}"));
        assertTrue(a.ok);
        String idA = a.output.split(" ")[1].replace(":","");
        ToolResult b = tools.dispatch("create", args(
                "{\"subject\":\"实现B\",\"blockedBy\":[\"" + idA + "\"]}"));
        assertTrue(b.ok);
        assertTrue(tools.dispatch("get", args("{\"task_id\":\"" + idA + "\"}")).ok);
        String list = tools.dispatch("list", new JsonObject()).output;
        assertTrue(list.contains("实现A"));
        assertTrue(list.contains("blockedBy: [" + idA + "]"));
        // 快照导出落盘
        Path snapshot = java.nio.file.Paths.get(s.workDir, ".minion", "tasks", s.id + ".json");
        assertTrue(Files.isRegularFile(snapshot));
        assertTrue(new String(Files.readAllBytes(snapshot), StandardCharsets.UTF_8).contains("实现A"));
    }

    /** 依赖解析：blockedBy 未完成不可 in_progress（transition 强制，V2 不绕过） */
    @Test public void dependencyBlocksInProgress() throws Exception {
        Session s = session();
        TaskV2Tools tools = new TaskV2Tools(s);
        String idA = tools.dispatch("create", args("{\"subject\":\"A\"}")).output.split(" ")[1].replace(":","");
        String idB = tools.dispatch("create", args(
                "{\"subject\":\"B\",\"blockedBy\":[\"" + idA + "\"]}")).output.split(" ")[1].replace(":","");
        ToolResult denied = tools.dispatch("update", args(
                "{\"task_id\":\"" + idB + "\",\"status\":\"in_progress\"}"));
        assertFalse(denied.ok);
        assertTrue(denied.output.contains("依赖") || denied.output.contains("前置"));
    }

    /** completed 需真实回执 + note（V68 验收门禁不被新状态机绕过） */
    @Test public void completedRequiresReceiptsAndNote() throws Exception {
        Session s = session();
        TaskV2Tools tools = new TaskV2Tools(s);
        String id = tools.dispatch("create", args("{\"subject\":\"A\"}")).output.split(" ")[1].replace(":","");
        // 未 start 直接 complete → 拒
        assertFalse(tools.dispatch("update", args(
                "{\"task_id\":\"" + id + "\",\"status\":\"completed\",\"note\":\"x\"}")).ok);
        // start → complete 缺回执 → 拒
        assertTrue(tools.dispatch("update", args(
                "{\"task_id\":\"" + id + "\",\"status\":\"in_progress\"}")).ok);
        ToolResult noReceipt = tools.dispatch("update", args(
                "{\"task_id\":\"" + id + "\",\"status\":\"completed\",\"note\":\"说明\"}"));
        assertFalse(noReceipt.ok);
        assertTrue(noReceipt.output.contains("证据") || noReceipt.output.contains("回执"));
        // deleted 需 note（cancel 审计）
        ToolResult noNote = tools.dispatch("update", args(
                "{\"task_id\":\"" + id + "\",\"status\":\"deleted\"}"));
        assertFalse(noNote.ok);
    }

    /** create 引用不存在的依赖 → 拒绝 */
    @Test public void createRejectsUnknownDependency() throws Exception {
        TaskV2Tools tools = new TaskV2Tools(session());
        ToolResult r = tools.dispatch("create", args(
                "{\"subject\":\"X\",\"blockedBy\":[\"ghost\"]}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("不存在"));
    }
}
