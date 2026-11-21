package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.LocalJobs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Cron 工具验收：登记（默认不启用）/列表/删除；非法 prompt 拒绝；账本持久化 */
public class CronToolTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private CronTool tool() throws Exception {
        Path work = tmp.newFolder().toPath();
        return new CronTool(new LocalJobs(new Workspace(work.toString()),
                work.resolve(".minion").resolve("reliability").resolve("s1")));
    }

    @Test public void addDefaultsDisabledListRemove() throws Exception {
        CronTool cron = tool();
        ToolResult added = cron.execute(args(
                "{\"action\":\"add\",\"title\":\"日报\",\"prompt\":\"汇总今日进展\",\"intervalMinutes\":30}"));
        assertTrue(added.output, added.ok);
        assertTrue(added.output.contains("默认未启用"));
        String listed = cron.execute(args("{\"action\":\"list\"}")).output;
        assertTrue(listed.contains("日报"));
        assertTrue(listed.contains("disabled"));   // 登记不自动启用（用户门控）
        // 删除往返（同一账本实例取 id）
        for (LocalJobs.Job j : new ArrayList<>(cron.jobs().snapshot()))
            assertTrue(cron.execute(args("{\"action\":\"remove\",\"id\":\"" + j.id + "\"}")).ok);
        assertTrue(cron.execute(args("{\"action\":\"list\"}")).output.contains("无定时任务"));
    }

    /** 非法输入：空 prompt / 非法间隔 / 未知 action */
    @Test public void invalidInputsRejected() throws Exception {
        CronTool cron = tool();
        assertFalse(cron.execute(args("{\"action\":\"add\",\"prompt\":\"\"}")).ok);
        assertFalse(cron.execute(args(
                "{\"action\":\"add\",\"prompt\":\"x\",\"intervalMinutes\":0}")).ok);
        assertFalse(cron.execute(args("{\"action\":\"pause\"}")).ok);
        assertFalse(cron.execute(args("{\"action\":\"remove\",\"id\":\"\"}")).ok);
    }
}
