package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * §1.4 max_tokens 升档续写 + §1.5 token 预算验收：默认档截断→升档重发；显式大档不升；
 * 预算未满 nudge 续写、收益递减停止；无预算输入零影响（V68 不回归）。
 */
public class LengthEscalationBudgetTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private AgentLoop loopWith(FakeLlmClient llm, Session s, Integer modelMaxOutputTokens) {
        Config config = Config.load(tmp.getRoot().toPath());
        if (modelMaxOutputTokens != null) {
            ModelConfig mc = new ModelConfig();
            mc.displayName = "test";
            mc.modelName = "test-model";
            mc.maxOutputTokens = modelMaxOutputTokens;
            s.runtimeModelConfig = mc;
        }
        AgentLoop loop = new AgentLoop(llm, new ToolRegistry(),
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(), null,
                new Workspace(tmp.getRoot().getPath()), s);
        loop.roundLimit = 30;
        return loop;
    }

    private static void length(FakeLlmClient llm, String content) {
        llm.addTurnLength(null, content, null);   // finish=length
    }

    /** 默认档（≤8192）首次截断：升档 max_tokens=64000 并按续接协议重发 */
    @Test public void defaultCapEscalatesOnce() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        length(llm, "前半段");
        length(llm, "后半段");
        llm.addTurn("完整交付");
        Session s = Session.create(tmp.getRoot().getPath(), "m");
        AgentLoop loop = loopWith(llm, s, 8192);
        loop.runUserTurn("写长文");
        assertEquals(64000, llm.lastMaxTokensOverride);
        assertEquals(3, llm.requests.size());   // 截断→升档重发（仍截断）→常规续写→成功
    }

    /** 用户显式配大档（>8192）：尊重选择不升档，走既有 12 次续写协议 */
    @Test public void explicitLargeCapDoesNotEscalate() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        length(llm, "前半段");
        llm.addTurn("续写完成");
        Session s = Session.create(tmp.getRoot().getPath(), "m");
        AgentLoop loop = loopWith(llm, s, 32000);
        loop.runUserTurn("写长文");
        assertEquals(-1, llm.lastMaxTokensOverride);   // 从未升档
        assertEquals(2, llm.requests.size());
    }

    /** 升档只做一次：第二次截断不再升（复用既有续写，不无限放大） */
    @Test public void escalationHappensOnlyOnce() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        length(llm, "a");
        length(llm, "b");
        length(llm, "c");
        llm.addTurn("完成");
        Session s = Session.create(tmp.getRoot().getPath(), "m");
        AgentLoop loop = loopWith(llm, s, 8192);
        loop.runUserTurn("写长文");
        assertEquals(64000, llm.lastMaxTokensOverride);
        assertEquals(4, llm.requests.size());   // 3 次截断 + 1 次成功，升档仅首次
    }

    /** §1.5 预算 nudge：+1k 预算、每轮 15 token → nudge 注入 meta 提醒；收益递减（两轮 <500）后停止 */
    @Test public void budgetNudgeThenDiminishingStop() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.addTurn("第一部分完成");
        llm.addTurn("第二部分完成");
        llm.addTurn("第三部分完成");
        llm.addTurn("第四部分完成");
        llm.addTurn("第五部分完成");   // 递减停止应发生在第 3 次 nudge 后（第 4 次自然停止）
        Session s = Session.create(tmp.getRoot().getPath(), "m");
        AgentLoop loop = loopWith(llm, s, 32000);
        loop.runUserTurn("+1k\n做大规模数据分析");
        // 每轮 usage=15 token：nudge 3 次后（continuations=3、两轮增量 15/15<500）按收益递减停止
        assertEquals(4, llm.requests.size());
        long nudges = 0;
        for (Message m : loop.messages())
            if (m.content != null && m.content.contains("[预算提醒]")) nudges++;
        assertEquals(3, nudges);
    }

    /** 无预算输入：自然停止一次即结束（V68 行为零变化） */
    @Test public void noBudgetStopsNaturally() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.addTurn("回答完毕");
        llm.addTurn("不应发生的第二轮");
        Session s = Session.create(tmp.getRoot().getPath(), "m");
        AgentLoop loop = loopWith(llm, s, 32000);
        loop.runUserTurn("普通问题");
        assertEquals(1, llm.requests.size());
    }

    /** budget.enabled=false：显式预算输入也被忽略 */
    @Test public void budgetDisabledIgnoresMarks() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.addTurn("回答完毕");
        llm.addTurn("不应发生的第二轮");
        Session s = Session.create(tmp.getRoot().getPath(), "m");
        AgentLoop loop = loopWith(llm, s, 32000);
        loop.budgetEnabled = false;
        loop.runUserTurn("+1k\n做分析");
        assertEquals(1, llm.requests.size());
    }
}
