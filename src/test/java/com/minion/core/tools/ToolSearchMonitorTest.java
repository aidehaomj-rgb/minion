package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import com.minion.core.tools.dev.ProcessTool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** §5.4 ToolSearch 工具 + §3.2 Monitor 验收 */
public class ToolSearchMonitorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test public void toolSearchSelectsAndScores() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool(new Workspace(".")));
        registry.register(new GlobTool(new Workspace(".")));
        registry.register(new TodoWriteTool(new com.minion.core.agent.TodoList()));
        ToolSearchTool search = new ToolSearchTool(registry);
        // select 直选
        ToolResult direct = search.execute(args("{\"query\":\"select:Read,Glob\"}"));
        assertTrue(direct.ok);
        assertTrue(direct.output.contains("== Read =="));
        assertTrue(direct.output.contains("== Glob =="));
        assertFalse(direct.output.contains("TodoWrite"));
        // 关键词命中（描述含"文件"）
        ToolResult byWord = search.execute(args("{\"query\":\"文件\"}"));
        assertTrue(byWord.ok);
        assertTrue(byWord.output.contains("Read") || byWord.output.contains("Glob"));
        // 无命中不报错
        assertTrue(search.execute(args("{\"query\":\"zzz不存在的词\"}")).ok);
        // 空查询拒绝
        assertFalse(search.execute(args("{\"query\":\"\"}")).ok);
    }

    /** Monitor：后台命令输出按批注入 session.messages；TaskStop 可终止 */
    @Test public void monitorInjectsEventsInBatches() throws Exception {
        com.minion.core.tools.Workspace workspace = new com.minion.core.tools.Workspace(tmp.newFolder().getAbsolutePath());
        Session session = Session.create(workspace.workDir(), "m");
        ProcessTool process = new ProcessTool(workspace);
        MonitorTool monitor = new MonitorTool(process, session);
        ToolResult started = monitor.execute(args(
                "{\"command\":\"echo line1 && echo line2 && echo line3\",\"description\":\"测试监控\"}"));
        assertTrue(started.ok);
        String jobId = started.output.split("\n", 2)[0].replace("监控已启动 ", "").split("（")[0].trim();
        // 等监听线程收割（进程快退；轮询 ≤10s）
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && session.messages.stream().noneMatch(m -> m.content != null && m.content.contains("[监控结束]")))
            Thread.sleep(200);
        boolean ended = session.messages.stream()
                .anyMatch(m -> m.content != null && m.content.contains("[监控结束]"));
        assertTrue("监控应注入结束事件", ended);
        boolean hasEvent = session.messages.stream()
                .anyMatch(m -> m.content != null && m.content.contains("line1"));
        assertTrue("监控事件行应注入会话", hasEvent);
        // TaskStop 终止通道（已退出的进程按结算处理）
        TaskStopTool stop = new TaskStopTool(process);
        assertTrue(stop.execute(args("{\"task_id\":\"" + jobId + "\"}")).ok);
        process.shutdown();
    }
}
