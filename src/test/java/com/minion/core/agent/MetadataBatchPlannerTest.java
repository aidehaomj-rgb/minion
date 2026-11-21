package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * §2.2 元数据驱动批调度验收：连续并发安全合并成波、串行工具单独成批、批上限、
 * 判定异常 fail-closed 串行、legacy 模式不受影响。
 */
public class MetadataBatchPlannerTest {

    private static ToolCall call(String name) {
        ToolCall c = new ToolCall();
        c.id = "id-" + name + "-" + SEQ++;
        c.name = name;
        c.arguments = "{}";
        return c;
    }
    private static int SEQ = 0;

    private static final class Meta implements Tool {
        final String name;
        final boolean concurrencySafe;
        final boolean throwOnJudge;
        Meta(String name, boolean concurrencySafe, boolean throwOnJudge) {
            this.name = name;
            this.concurrencySafe = concurrencySafe;
            this.throwOnJudge = throwOnJudge;
        }
        public String name() { return name; }
        public String description() { return ""; }
        public JsonObject schema() { return new JsonObject(); }
        public ToolResult execute(JsonObject args) { return ToolResult.success("ok"); }
        @Override public boolean isConcurrencySafe(JsonObject args) {
            if (throwOnJudge) throw new IllegalStateException("判定炸了");
            return concurrencySafe;
        }
    }

    private static ToolRegistry registry(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }

    private static List<String> stageSummary(List<List<ToolCall>> stages) {
        List<String> out = new ArrayList<String>();
        for (List<ToolCall> s : stages) {
            StringBuilder b = new StringBuilder();
            for (ToolCall c : s) b.append(c.name);
            out.add(b.toString());
        }
        return out;
    }

    /** 规格用例：[Read,Read,Grep,Edit,Read] → [3并行][1串行][1]（tools.maxConcurrency≥3 档位） */
    @Test public void specSequenceSplitsIntoWaves() {
        ToolRegistry reg = registry(
                new Meta("Read", true, false), new Meta("Grep", true, false), new Meta("Edit", false, false));
        ToolBatchScheduler scheduler = new ToolBatchScheduler(new ToolSchedulingPolicy(4, 1000, 4), reg, true);
        List<ToolCall> calls = new ArrayList<ToolCall>();
        calls.add(call("Read")); calls.add(call("Read")); calls.add(call("Grep"));
        calls.add(call("Edit")); calls.add(call("Read"));
        List<String> stages = stageSummary(scheduler.plan(calls));
        assertEquals(3, stages.size());
        assertEquals("ReadReadGrep", stages.get(0));
        assertEquals("Edit", stages.get(1));
        assertEquals("Read", stages.get(2));
    }

    /** 两个 TodoWrite（串行工具）不得并行：各自单独成批 */
    @Test public void serialToolsNeverShareWave() {
        ToolRegistry reg = registry(new Meta("TodoWrite", false, false));
        ToolBatchScheduler scheduler = new ToolBatchScheduler(ToolSchedulingPolicy.conservative(), reg, true);
        List<ToolCall> calls = new ArrayList<ToolCall>();
        calls.add(call("TodoWrite")); calls.add(call("TodoWrite"));
        List<List<ToolCall>> stages = scheduler.plan(calls);
        assertEquals(2, stages.size());
        assertEquals(1, stages.get(0).size());
        assertEquals(1, stages.get(1).size());
    }

    /** 批大小上限：并发波按 policy.maxParallelReads 切分 */
    @Test public void waveSizeCappedByPolicy() {
        ToolRegistry reg = registry(new Meta("Read", true, false));
        ToolBatchScheduler policy = new ToolBatchScheduler(
                new ToolSchedulingPolicy(2, 100, 1), reg, true);
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (int i = 0; i < 5; i++) calls.add(call("Read"));
        List<List<ToolCall>> stages = policy.plan(calls);
        assertEquals(3, stages.size());
        assertEquals(2, stages.get(0).size());
        assertEquals(2, stages.get(1).size());
        assertEquals(1, stages.get(2).size());
    }

    /** 判定函数抛异常 → 串行（fail-closed） */
    @Test public void judgingExceptionFallsBackToSerial() {
        ToolRegistry reg = registry(new Meta("Flaky", true, true));
        ToolBatchScheduler scheduler = new ToolBatchScheduler(ToolSchedulingPolicy.conservative(), reg, true);
        List<ToolCall> calls = new ArrayList<ToolCall>();
        calls.add(call("Flaky")); calls.add(call("Flaky"));
        List<List<ToolCall>> stages = scheduler.plan(calls);
        assertEquals(2, stages.size());   // 每个单独成批
    }

    /** 未注册工具（名字不在 registry）→ 串行 */
    @Test public void unknownToolIsSerial() {
        ToolRegistry reg = registry(new Meta("Read", true, false));
        ToolBatchScheduler scheduler = new ToolBatchScheduler(ToolSchedulingPolicy.conservative(), reg, true);
        List<ToolCall> calls = new ArrayList<ToolCall>();
        calls.add(call("Read")); calls.add(call("Mystery")); calls.add(call("Read"));
        List<String> stages = stageSummary(scheduler.plan(calls));
        assertEquals("Read", stages.get(0));
        assertEquals("Mystery", stages.get(1));
        assertEquals("Read", stages.get(2));
    }

    /** legacy 模式（metadataDriven=false）：与既有按名判定完全一致（不受新标注影响） */
    @Test public void legacyModeUnchanged() {
        ToolRegistry reg = registry(new Meta("Read", true, false), new Meta("TodoWrite", false, false));
        ToolBatchScheduler legacy = new ToolBatchScheduler(ToolSchedulingPolicy.conservative(), reg, false);
        List<ToolCall> calls = new ArrayList<ToolCall>();
        calls.add(call("Read")); calls.add(call("TodoWrite"));
        // legacy 按名判定：Read 走 parallelReadOnly 名单、TodoWrite 串行
        List<List<ToolCall>> stages = legacy.plan(calls);
        ToolBatchScheduler old = new ToolBatchScheduler(ToolSchedulingPolicy.conservative());
        assertEquals(stageSummary(old.plan(calls)), stageSummary(stages));
    }
}
