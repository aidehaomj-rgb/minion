package com.minion.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** 项8 特征化测试：搬移 StopAdjudicator 前固化 stopSnapshot 的可观察行为
 *  （facts/receipts/blocking/proof）。搬移后同一组测试必须原样通过（A/B）。
 *  经私有 stopSnapshot 反射调用——委托保留同签名，搬移前后入口不变。 */
public class StopAdjudicatorCharacterizationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private AgentLoop loop;

    @Before
    public void setup() throws Exception {
        root = tmp.newFolder("ws").toPath();
        Workspace workspace = new Workspace(root.toString());
        loop = new AgentLoop(new FakeLlmClient(), new ToolRegistry(),
                new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(root), new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi() { }, null, workspace,
                Session.create(workspace.workDir(), "fake"));
    }

    @After
    public void cleanup() {
        loop.shutdown();
    }

    private StopGate.Snapshot snapshot(String candidate, List<StopGate.Receipt> current,
            int historicalCount, int fromIndex) throws Exception {
        Method m = AgentLoop.class.getDeclaredMethod("stopSnapshot",
                String.class, List.class, int.class, int.class);
        m.setAccessible(true);
        return (StopGate.Snapshot) m.invoke(loop, candidate, current, historicalCount, fromIndex);
    }

    private StopGate.Snapshot snapshot(String candidate) throws Exception {
        return snapshot(candidate, new ArrayList<StopGate.Receipt>(), 0, 0);
    }

    private JsonObject facts(StopGate.Snapshot s) {
        return JsonParser.parseString(s.input).getAsJsonObject().getAsJsonObject("facts");
    }

    private TaskState state() { return loop.session().taskState; }

    private Path file(String name, String content) throws Exception {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private TaskState.Evidence evidence(String tool, Path path, boolean mutation, long revision) throws Exception {
        TaskState.Evidence e = new TaskState.Evidence();
        e.taskId = state().id;
        e.scope = "root";
        e.tool = tool;
        e.success = true;
        e.result = "成功";
        e.mutation = mutation;
        e.revision = revision;
        e.file = FileEvidence.capture(path);
        return e;
    }

    @Test
    public void emptySession_noBlocking_noProof() throws Exception {
        StopGate.Snapshot s = snapshot("随便一段回复");
        assertEquals(state().id, s.id);
        assertEquals("", s.blocking);
        assertTrue(s.localProof.isEmpty());
        JsonObject f = facts(s);
        assertFalse(f.get("structured").getAsBoolean());
        assertFalse(f.get("toolsUsed").getAsBoolean());
        assertTrue(f.get("checkpoint").getAsString().contains("旧计划已失效或未登记"));
        assertEquals("passed", f.getAsJsonObject("acceptance").get("status").getAsString());
    }

    @Test
    public void pendingTodo_blocks() throws Exception {
        state().begin("生成报表", false);
        loop.session().todos.items.add(new TodoList.TodoItem("未完成事项", false));
        StopGate.Snapshot s = snapshot("做完了");
        assertEquals("主任务待办尚未完成", s.blocking);
        JsonObject f = facts(s);
        assertTrue(f.get("structured").getAsBoolean());
        assertTrue(f.get("todos").getAsString().contains("未完成事项"));
        assertTrue(s.localProof.isEmpty());
    }

    @Test
    public void unreviewedChild_blocks() throws Exception {
        state().begin("主任务", false);
        TaskState.Child c = new TaskState.Child();
        c.id = "c1"; c.taskId = state().id; c.status = "running"; c.assignment = "子任务";
        state().children.add(c);
        StopGate.Snapshot s = snapshot("做完了");
        assertTrue(s.blocking.contains("子Agent尚未核对或仍在运行/失败（c1，running）"));
        JsonObject f = facts(s);
        assertEquals(1, f.get("childCount").getAsInt());
        assertEquals(0, f.get("reviewedChildren").getAsInt());
        assertEquals(1, f.getAsJsonArray("pendingChildren").size());
    }

    @Test
    public void mergedChild_blocksWithValidateMergeHint() throws Exception {
        state().begin("主任务", false);
        TaskState.Child c = new TaskState.Child();
        c.id = "c2"; c.taskId = state().id; c.status = "merged_needs_validation";
        state().children.add(c);
        StopGate.Snapshot s = snapshot("做完了");
        assertTrue(s.blocking.contains("validate_merge(childId=c2"));
    }

    @Test
    public void reviewedChild_notBlocking() throws Exception {
        state().begin("主任务", false);
        TaskState.Child c = new TaskState.Child();
        c.id = "c3"; c.taskId = state().id; c.status = "reviewed";
        state().children.add(c);
        StopGate.Snapshot s = snapshot("做完了");
        assertEquals("", s.blocking);
        JsonObject f = facts(s);
        assertEquals(1, f.get("childCount").getAsInt());
        assertEquals(1, f.get("reviewedChildren").getAsInt());
        assertEquals(0, f.getAsJsonArray("pendingChildren").size());
    }

    @Test
    public void changedCheckpoint_blocks() throws Exception {
        state().begin("核验文件", false);
        Path p = file("check.txt", "原始内容");
        state().processedRange = "全部";
        state().nextStep = "";
        state().checkpointRevision = state().revision();
        state().checkpointFiles.add(FileEvidence.capture(p));
        Files.write(p, "被改过的内容".getBytes(StandardCharsets.UTF_8));
        StopGate.Snapshot s = snapshot("做完了");
        assertTrue(s.blocking.contains("断点证据文件已变更、缺失或无法核验"));
        // 探针 status 恒为 present，变更由 recorded/actual 哈希差异表达（unchanged=false）
        assertTrue(facts(s).get("checkpoint").getAsString().contains("[present]"));
        assertTrue(s.localProof.isEmpty());
    }

    @Test
    public void unchangedFinalCheckpoint_noBlocking() throws Exception {
        state().begin("核验文件", false);
        Path p = file("check.txt", "原始内容");
        state().processedRange = "全部";
        state().nextStep = "";
        state().checkpointRevision = state().revision();
        state().checkpointFiles.add(FileEvidence.capture(p));
        StopGate.Snapshot s = snapshot("做完了");
        assertEquals("", s.blocking);
        assertTrue(facts(s).get("checkpoint").getAsString().contains("[unchanged]"));
        assertTrue(s.localProof.isEmpty()); // 无 mutation 回执仍不放行
    }

    @Test
    public void failedAcceptanceRule_blocks() throws Exception {
        state().begin("生成文件", false);
        loop.reliability().configure(state().id,
                "[{\"type\":\"file_exists\",\"path\":\"missing.txt\"}]", false);
        StopGate.Snapshot s = snapshot("做完了");
        assertTrue(s.blocking.contains("用户登记的程序验收规则未通过或无法核验"));
        assertNotEquals("passed", facts(s).getAsJsonObject("acceptance").get("status").getAsString());
    }

    @Test
    public void unobservedStep_blocks() throws Exception {
        state().begin("执行命令", false);
        JsonObject args = new JsonObject();
        args.addProperty("command", "echo hi");
        // unresolved 只追踪 mutation 步骤；started 未 finish 即不可读取
        loop.reliability().start(state().id, "root", "Bash", args, true);
        StopGate.Snapshot s = snapshot("做完了");
        assertTrue(s.blocking.contains("有工具步骤执行结果不可读取"));
        assertTrue(s.blocking.contains("verify_step"));
        assertEquals(1, facts(s).getAsJsonArray("uncertainSteps").size());
    }

    @Test
    public void historicalEvidence_receiptsAndInternalOutputExclusion() throws Exception {
        state().begin("生成文件", false);
        long rev = state().revision();
        Path out = file("report.txt", "交付内容");
        Path internal = file(".session/tmp/s1/dump.txt", "内部落盘");
        state().evidence.add(evidence("Write", out, true, rev));
        state().evidence.add(evidence("Write", internal, true, rev));
        StopGate.Snapshot s = snapshot("做完了", new ArrayList<StopGate.Receipt>(), 2, 0);
        JsonObject f = facts(s);
        assertTrue(f.get("toolsUsed").getAsBoolean());
        JsonArray outputs = f.getAsJsonArray("outputFiles");
        assertEquals(1, outputs.size());
        assertTrue(outputs.get(0).getAsJsonObject().get("path").getAsString().endsWith("report.txt"));
        assertTrue(s.input.contains("evidence:0"));
        assertTrue(s.input.contains("evidence:1"));
    }

    @Test
    public void writeDelivery_proofEstablished() throws Exception {
        state().begin("生成 report.txt 文件", false);
        long rev = state().revision();
        Path out = file("report.txt", "交付内容");
        state().evidence.add(evidence("Write", out, true, rev));
        StopGate.Receipt receipt = new StopGate.Receipt("w1", "Write",
                "{\"path\":\"report.txt\",\"content\":\"交付内容\"}", ToolResult.success("写入成功"), true);
        receipt.revision = rev;
        String candidate = "report.txt 已生成并交付。\n" + AgentLoop.TASK_COMPLETE_MARKER;
        StopGate.Snapshot s = snapshot(candidate, Collections.singletonList(receipt), 0, 0);
        assertEquals("", s.blocking);
        assertEquals(Collections.singletonList("w1"), s.localProof);
    }

    @Test
    public void earlierDeliveredReplies_collected() throws Exception {
        state().begin("任务", false);
        loop.session().messages.add(Message.user("任务要求"));
        loop.session().messages.add(Message.assistant("先前交付的正文内容"));
        loop.session().messages.add(Message.assistant("当前候选回复"));
        StopGate.Snapshot s = snapshot("当前候选回复", new ArrayList<StopGate.Receipt>(), 0, 0);
        JsonArray prior = facts(s).getAsJsonArray("earlierDeliveredReplies");
        assertEquals(1, prior.size());
        assertEquals("先前交付的正文内容", prior.get(0).getAsJsonObject().get("text").getAsString());
        assertFalse(prior.get(0).getAsJsonObject().get("truncated").getAsBoolean());
    }
}