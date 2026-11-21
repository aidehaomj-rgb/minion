package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** §3.2 验收：Bash 超时上限/sandbox 拒绝/run_in_background 委托、Sleep 可中断、Team 工具 */
public class BashBackgroundToolsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private Workspace ws() throws Exception { return new Workspace(tmp.newFolder().getAbsolutePath()); }

    // ==================== Bash 参数面 ====================

    @Test public void bashRejectsOverMaxTimeout() throws Exception {
        BashTool bash = new BashTool(ws(), null, null);
        ToolResult r = bash.execute(args("{\"command\":\"echo hi\",\"timeoutSeconds\":601}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("上限"));
        // 恰好上限允许（echo 秒回）
        assertTrue(bash.execute(args("{\"command\":\"echo ok\",\"timeoutSeconds\":600}")).ok);
    }

    /** sandbox=true 明确报错（不静默忽略）；省略按普通流程执行 */
    @Test public void bashSandboxExplicitlyRejected() throws Exception {
        BashTool bash = new BashTool(ws(), null, null);
        ToolResult rejected = bash.execute(args("{\"command\":\"echo hi\",\"sandbox\":true}"));
        assertFalse(rejected.ok);
        assertTrue(rejected.output.contains("不支持 OS 级沙箱"));
        assertTrue(bash.execute(args("{\"command\":\"echo normal\"}")).ok);
    }

    /** run_in_background 委托 ProcessTool；未接线时明确报错引导 */
    @Test public void bashRunInBackgroundDelegates() throws Exception {
        Workspace workspace = ws();
        BashTool unwired = new BashTool(workspace, null, null);
        ToolResult noRunner = unwired.execute(args("{\"command\":\"ping -n 2 127.0.0.1\",\"run_in_background\":true}"));
        assertFalse(noRunner.ok);
        assertTrue(noRunner.output.contains("Process"));

        BashTool wired = new BashTool(workspace, null, null);
        com.minion.core.tools.dev.ProcessTool process = new com.minion.core.tools.dev.ProcessTool(workspace);
        wired.setBackgroundRunner(process);
        ToolResult started = wired.execute(args(
                "{\"command\":\"echo bg-output\",\"run_in_background\":true,\"description\":\"测试后台\"}"));
        assertTrue(started.ok);
        assertTrue(started.output.contains("已启动后台进程"));
        // 等待完成并取输出（TaskOutput 通道）
        TaskOutputTool output = new TaskOutputTool(process);
        String jobId = extractJobId(started.output);
        ToolResult drained = output.execute(args("{\"task_id\":\"" + jobId + "\",\"block\":true,\"timeout\":15000}"));
        assertTrue(drained.output.contains("bg-output"));
        // 终止通道
        TaskStopTool stop = new TaskStopTool(process);
        ToolResult stopped = stop.execute(args("{\"task_id\":\"" + jobId + "\"}"));
        assertTrue(stopped.ok);
        process.shutdown();
    }

    private static String extractJobId(String text) {
        int at = text.indexOf("已启动后台进程 ") + "已启动后台进程 ".length();
        int end = text.indexOf('\n', at);
        return text.substring(at, end).trim();
    }

    // ==================== Sleep ====================

    @Test public void sleepWaitsAndIsInterruptible() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean(false);
        SleepTool sleep = new SleepTool(cancel::get);
        long t0 = System.currentTimeMillis();
        assertTrue(sleep.execute(args("{\"seconds\":0.3}")).ok);
        assertTrue(System.currentTimeMillis() - t0 >= 250);
        // 取消供应者 → 立即打断
        cancel.set(true);
        ToolResult interrupted = sleep.execute(args("{\"seconds\":5}"));
        assertFalse(interrupted.ok);
        assertTrue(System.currentTimeMillis() - t0 < 3000);
        // 参数校验
        assertFalse(sleep.execute(args("{\"seconds\":0}")).ok);
        assertFalse(sleep.execute(args("{\"seconds\":601}")).ok);
    }

    // ==================== Team 工具 ====================

    @Test public void teamToolsCreateSendDelete() throws Exception {
        String workDir = tmp.newFolder().toPath().toString();
        com.minion.core.team.TeamTools.TeamCreateTool create =
                new com.minion.core.team.TeamTools.TeamCreateTool(workDir);
        assertTrue(create.execute(args(
                "{\"team\":\"alpha\",\"members\":[\"lead\",\"dev1\"]}")).ok);
        // SendMessage 广播与点对点
        com.minion.core.team.TeamTools.SendMessageTool send =
                new com.minion.core.team.TeamTools.SendMessageTool(workDir);
        assertTrue(send.execute(args(
                "{\"team\":\"alpha\",\"from\":\"lead\",\"to\":\"dev1\",\"text\":\"开始\"}")).ok);
        assertTrue(send.execute(args(
                "{\"team\":\"alpha\",\"from\":\"dev1\",\"to\":\"*\",\"text\":\"站会\"}")).ok);
        // 非法目标
        assertFalse(send.execute(args(
                "{\"team\":\"alpha\",\"from\":\"lead\",\"to\":\"ghost\",\"text\":\"x\"}")).ok);
        // 删除后邮箱不可用
        com.minion.core.team.TeamTools.TeamDeleteTool delete =
                new com.minion.core.team.TeamTools.TeamDeleteTool(workDir);
        assertTrue(delete.execute(args("{\"team\":\"alpha\"}")).ok);
        assertFalse(send.execute(args(
                "{\"team\":\"alpha\",\"from\":\"lead\",\"to\":\"dev1\",\"text\":\"again\"}")).ok);
    }
}
