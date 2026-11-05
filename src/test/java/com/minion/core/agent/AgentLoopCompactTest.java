package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;

import static org.junit.Assert.*;

public class AgentLoopCompactTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void autoCompress_triggersOverThreshold() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        // 小上下文上限，快速触发压缩（60×0.8=48 需 5 轮才够，50×0.8=40 在第 4 轮触发）
        ContextManager cm = new ContextManager(50, 0.8, 2, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        // 塞满历史：3 轮 user+assistant ≈ 每轮 12 token
        for (int i = 0; i < 3; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        // 第 4 轮触发压缩
        llm.addTurn("压缩后回复");
        loop.runUserTurn("触发压缩");
        boolean compressed = ui.warnings.stream().anyMatch(w -> w.contains("自动压缩"));
        assertTrue("应触发自动压缩", compressed);
        assertFalse(loop.messages().isEmpty()); // Tiny synthetic history may not yield net savings.
    }

    @Test
    public void compactNow_compressesImmediately() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】手动压缩";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(100000, 0.8, 1, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        assertFalse(loop.messages().get(0).summary); // 未触发
        loop.compactNow();
        assertTrue(loop.messages().get(0).summary);
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("已压缩")));
    }

    @Test
    public void compactNowArchivesRemovedMessagesBeforeReplacingContext() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】已完成第一步";
        Session session = Session.create(tmp.getRoot().getPath(), "test-model");
        SessionStore store = new SessionStore(tmp.newFolder("archive-sessions").toPath());
        AgentLoop loop = new AgentLoop(llm, new ToolRegistry(),
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(), new ContextManager(100000, 0.8, 1, llm, 0),
                new Workspace(tmp.getRoot().getPath()), session);
        loop.setSessionStore(store);
        llm.addTurn("第一步结果");
        loop.runUserTurn("核对海关证据后再继续");
        loop.compactNow();
        assertTrue(loop.messages().get(0).summary);
        assertFalse(store.searchHistory(session.id, "海关证据", 5).isEmpty());
    }

    @Test public void malformedCheckpointCannotReplaceOriginalHistory() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "一句话短摘要";
        llm.rawCompressResult = true;
        RecordingUi ui = new RecordingUi();
        Session session = Session.create(tmp.getRoot().getPath(), "test-model");
        AgentLoop loop = new AgentLoop(llm, new ToolRegistry(),
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                ui, new ContextManager(100000, 0.8, 1, llm, 0),
                new Workspace(tmp.getRoot().getPath()), session);
        llm.addTurn("已处理前半部分");
        loop.runUserTurn("新要求只处理B表");
        int originalCount = loop.messages().size();
        loop.compactNow();
        assertEquals(originalCount, loop.messages().size());
        assertFalse(loop.messages().get(0).summary);
        assertTrue(ui.warnings.toString(), ui.warnings.stream().anyMatch(w -> w.contains("压缩摘要缺少")));
    }

    @Test public void emergencyCompactionKeepsLatestUserInstructionVerbatim() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        Session session = Session.create(tmp.getRoot().getPath(), "test-model");
        AgentLoop loop = new AgentLoop(llm, new ToolRegistry(),
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(), new ContextManager(100000, 0.8, 0, llm, 0),
                new Workspace(tmp.getRoot().getPath()), session);
        llm.addTurn("先按A表检查");
        loop.runUserTurn("原任务先处理A表");
        llm.addTurn("收到新要求");
        loop.runUserTurn("改为只处理B表，A表停止");
        loop.compactNow();
        assertTrue(loop.messages().get(0).summary);
        assertTrue(loop.messages().stream().anyMatch(m -> m.role == Message.Role.USER
                && !m.summary && "改为只处理B表，A表停止".equals(m.content)));
    }

    @Test public void askingHowItWasDecidedRecallsSourcedOldEvidence() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        Session session = Session.create(tmp.getRoot().getPath(), "test-model");
        SessionStore store = new SessionStore(tmp.newFolder("decision-history").toPath());
        Message decision = Message.assistant("此前决定使用B表，A表不再处理");
        Message kept = Message.user("继续");
        store.archiveCompacted(session, Arrays.asList(decision, kept), java.util.Collections.singletonList(kept));
        AgentLoop loop = new AgentLoop(llm, new ToolRegistry(),
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(), new ContextManager(100000, 0.8, 1, llm, 0),
                new Workspace(tmp.getRoot().getPath()), session);
        loop.setSessionStore(store);
        llm.addTurn("核对后再答复");
        loop.runUserTurn("之前怎么定的？");
        assertTrue(llm.requests.stream().anyMatch(request -> request.messages.stream()
                .anyMatch(m -> m.content != null && m.content.contains("历史原文")
                        && m.content.contains("此前决定使用B表"))));
    }

    @Test public void changedInstructionAfterTwoHundredKTokensSurvivesCompaction() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        Session session = Session.create(tmp.getRoot().getPath(), "qwen");
        char[] payload = new char[22000];
        java.util.Arrays.fill(payload, '旧');
        for (int i = 0; i < 22; i++) {
            session.messages.add(Message.user("旧任务第" + i + "块 " + new String(payload)));
            session.messages.add(Message.assistant("旧分析第" + i + "块 " + new String(payload)));
        }
        Message newest = Message.user("最新修改：只处理B表，A表停止");
        session.messages.add(newest);
        ContextManager context = new ContextManager(900000, 0.95, 12, llm, 0);
        assertTrue("应模拟约20万 token 以上的历史", context.estimate(session.messages) >= 200000);
        SessionStore store = new SessionStore(tmp.newFolder("large-history").toPath());
        AgentLoop loop = new AgentLoop(llm, new ToolRegistry(),
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(), context, new Workspace(tmp.getRoot().getPath()), session);
        loop.setSessionStore(store);
        loop.compactNow();
        assertTrue(loop.messages().stream().anyMatch(m -> m.summary));
        assertTrue(loop.messages().stream().anyMatch(m -> newest == m));
        assertEquals(1, store.searchHistory(session.id, "旧任务第1块", 5).size());
        assertEquals(1, store.searchHistory(session.id, "最新修改：只处理B表", 5).size());
    }

    @Test
    public void failedArchiveDoesNotDiscardOriginalMessages() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】短摘要";
        RecordingUi ui = new RecordingUi();
        Session session = Session.create(tmp.getRoot().getPath(), "test-model");
        AgentLoop loop = new AgentLoop(llm, new ToolRegistry(),
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                ui, new ContextManager(100000, 0.8, 1, llm, 0),
                new Workspace(tmp.getRoot().getPath()), session);
        llm.addTurn("第一步结果");
        loop.runUserTurn("核对海关证据");
        int oldSize = loop.messages().size();
        java.nio.file.Path blocked = tmp.getRoot().toPath().resolve("not-a-directory");
        java.nio.file.Files.write(blocked, new byte[]{1});
        loop.setSessionStore(new SessionStore(blocked));
        loop.compactNow();
        assertEquals(oldSize, loop.messages().size());
        assertFalse(loop.messages().get(0).summary);
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("归档失败")));
    }

    /** 自动压缩：压缩前后发 onCompressingChanged，首事件 true、末事件 false */
    @Test
    public void autoCompress_firesCompressingEvents() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(50, 0.8, 2, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        // 每轮 ≈12 token（4+2 开销+文本），50×0.8=40 阈值：第 4 轮 user 入历史后触发
        for (int i = 0; i < 4; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        assertFalse("应触发自动压缩", ui.compressing.isEmpty());
        assertTrue("首事件=压缩开始", ui.compressing.get(0));
        assertFalse("末事件=压缩结束", ui.compressing.get(ui.compressing.size() - 1));
    }

    /** 手动 /compress：严格 true→false 成对 */
    @Test
    public void compactNow_firesCompressingEvents() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】手动压缩";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(100000, 0.8, 1, llm, 0); // 大阈值不自动压缩
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        ui.compressing.clear();
        loop.compactNow();
        assertEquals(Arrays.asList(true, false), ui.compressing);
    }

    /** 手动压缩：压缩完成后推送上下文统计（环形进度圈刷新依据） */
    @Test
    public void compactNow_pushesContextStats() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】手动压缩";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(100000, 0.8, 1, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        assertFalse("用户消息入历史即应推送一次", ui.ctxStats.isEmpty());
        int before = ui.ctxStats.size();
        loop.compactNow();
        assertTrue("压缩完成后应再推送", ui.ctxStats.size() > before);
        int[] last = ui.ctxStats.get(ui.ctxStats.size() - 1);
        assertTrue(last[0] >= 0);
        assertEquals(100000, last[1]);
    }

    /** 自动压缩：整个流程应有上下文统计推送 */
    @Test
    public void autoCompress_pushesContextStats() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(50, 0.8, 2, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        for (int i = 0; i < 3; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        llm.addTurn("压缩后回复");
        loop.runUserTurn("触发压缩");
        assertFalse("整个流程应有上下文统计推送", ui.ctxStats.isEmpty());
    }

    @Test
    public void observedPlanStallCompactsLongHistoryWithoutChangingConfiguredThreshold() throws Exception {
        FakeLlmClient llm=new FakeLlmClient();
        RecordingUi ui=new RecordingUi();
        Workspace ws=new Workspace(tmp.getRoot().getPath());
        Session session=Session.create(ws.workDir(),"test-model");
        char[] chars=new char[20000];java.util.Arrays.fill(chars,'x');String oldText=new String(chars);
        for(int i=0;i<22;i++) {
            session.messages.add(Message.user("旧任务"+i+oldText));
            session.messages.add(Message.assistant("旧回复"+i+oldText));
        }
        ContextManager cm=new ContextManager(900000,0.95,12,llm,0);
        assertTrue(cm.estimate(session.messages)>=131072);
        llm.stopStatuses.add("continue");llm.stopStatuses.add("continue");llm.stopStatuses.add("completed");
        llm.addTurn("我先检查，再处理。");llm.addTurn("我先核对，然后生成说明。");llm.addTurn("这是完整说明。");
        AgentLoop loop=new AgentLoop(llm,new ToolRegistry(),new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                ui,cm,ws,session);
        try {
            loop.runUserTurn("解释当前问题");
            assertTrue(loop.lastTurnSucceeded());
            assertTrue(ui.warnings.stream().anyMatch(w->w.contains("旧历史整理完成") || w.contains("自动压缩已完成")));
            assertEquals(900000,cm.maxTokens());
            assertEquals(0.95,cm.threshold(),0.001);
            assertTrue(loop.messages().stream().anyMatch(m->m.summary));
        } finally {loop.shutdown();}
    }
}
