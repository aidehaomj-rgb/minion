package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * §1.3 模型降级链验收：连续 3 次过载触发切换（消息无孤儿 tool_use、系统提示已注入、
 * 本会话 override 生效）；链空时行为=明确报错不降级（与 legacy 一致的停止语义）。
 */
public class ModelFallbackTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final LlmException OVERLOAD = new LlmException(
            LlmException.Type.OTHER, "API 错误(529): server overloaded_error", true, 529, "overloaded_error", 0);

    private AgentLoop loopWith(FakeLlmClient llm, Session s) {
        Config config = Config.load(tmp.getRoot().toPath());
        ToolRegistry registry = new ToolRegistry();
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(), null,
                new Workspace(tmp.getRoot().getPath()), s);
        loop.roundLimit = 10;
        loop.retryPolicy = new RetryPolicy(1, 0, 2, 60_000, 1.0, 0);   // 1ms 线性：测试不等待
        return loop;
    }

    /** 3 次过载 → 降级切换 → 备用模型完成任务 */
    @Test public void threeOverloadsSwitchToFallback() throws Exception {
        FakeLlmClient main = new FakeLlmClient();
        main.addTurnThrow(OVERLOAD);
        main.addTurnThrow(OVERLOAD);
        main.addTurnThrow(OVERLOAD);
        FakeLlmClient backup = new FakeLlmClient();
        backup.addTurn("备用模型完成");
        Session s = Session.create(tmp.getRoot().getPath(), "main-model");
        AgentLoop loop = loopWith(main, s);
        AtomicInteger switched = new AtomicInteger();
        loop.setModelFallback("backup-model", next -> {
            switched.incrementAndGet();
            loop.setLlm(backup);
            return next;
        });
        loop.runUserTurn("干活");
        // 切换恰好一次，且发生在第 3 次过载后
        assertEquals(1, switched.get());
        assertEquals(3, main.requests.size());
        assertEquals(1, backup.requests.size());          // 降级后整请求重发（新客户端一次成功）
        assertEquals("backup-model", s.modelOverride);
        // 系统提示已注入（含模型名）
        boolean hasNote = false;
        for (Message m : loop.messages())
            if (m.content != null && m.content.contains("已自动降级切换到 backup-model")) hasNote = true;
        assertTrue(hasNote);
        // 无孤儿 tool_use：全部消息 toolCalls 均为 null/空（失败请求从未入历史）
        for (Message m : loop.messages())
            assertTrue(m.toolCalls == null || m.toolCalls.isEmpty());
        // 备用模型响应进入历史
        assertTrue(loop.messages().stream().anyMatch(m -> "备用模型完成".equals(m.content)));
    }

    /** 链空（默认）：连续 3 次过载明确报错停止，不降级、不无限重试 */
    @Test public void noChainStopsWithExplicitError() throws Exception {
        FakeLlmClient main = new FakeLlmClient();
        for (int i = 0; i < 5; i++) main.addTurnThrow(OVERLOAD);
        Session s = Session.create(tmp.getRoot().getPath(), "main-model");
        AgentLoop loop = loopWith(main, s);
        loop.runUserTurn("干活");
        assertNull(s.modelOverride);
        // 第 3 次过载后明确停止（不会耗尽 5 个脚本回合）
        assertTrue(main.requests.size() <= 4);
    }

    /** 非过载瞬时错误不触发降级（既有长重试语义不变） */
    @Test public void nonOverloadTransientDoesNotTriggerFallback() throws Exception {
        FakeLlmClient main = new FakeLlmClient();
        LlmException err500 = new LlmException(LlmException.Type.OTHER, "API 错误(500)", true, 500, "", 0);
        for (int i = 0; i < 4; i++) main.addTurnThrow(err500);
        main.addTurn("恢复正常");
        Session s = Session.create(tmp.getRoot().getPath(), "main-model");
        AgentLoop loop = loopWith(main, s);
        AtomicInteger switched = new AtomicInteger();
        loop.setModelFallback("backup-model", next -> { switched.incrementAndGet(); return next; });
        loop.runUserTurn("干活");
        assertEquals(0, switched.get());
        assertNull(s.modelOverride);
    }
}
