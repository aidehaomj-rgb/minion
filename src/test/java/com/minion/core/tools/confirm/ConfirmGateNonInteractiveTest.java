package com.minion.core.tools.confirm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * G2：后台子 agent 非交互作用域——作用域内一切交互确认自动拒绝且 ui 零调用；
 * 作用域外前台行为逐字不变；嵌套/异常/跨线程不泄漏。
 */
public class ConfirmGateNonInteractiveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private com.minion.core.config.Config config;

    @Before
    public void setup() throws Exception {
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        Files.write(tmp.getRoot().toPath().resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
    }

    private ConfirmGate gate(ConfirmUi ui) { return new ConfirmGate(config, ui); }

    private com.minion.core.tools.Tool writeTool() {
        return new com.minion.core.tools.WriteTool(
                new com.minion.core.tools.Workspace(tmp.getRoot().getAbsolutePath()));
    }

    private com.minion.core.tools.Tool readTool() {
        return new com.minion.core.tools.ReadTool(
                new com.minion.core.tools.Workspace(tmp.getRoot().getAbsolutePath()));
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private void legacyEngine() throws Exception {
        Files.write(Paths.get(config.externalFile().toString()),
                "permission.engine.v2=false\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
    }

    @Test public void v2HighRiskWrite_autoRejects_uiZeroCalls() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        Boolean allowed = ConfirmGate.nonInteractive(() -> g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertFalse("作用域内高危确认自动拒绝", allowed);
        assertTrue("作用域内不得调用 ui", ui.asked.isEmpty());
    }

    @Test public void outsideScope_foregroundBehaviorUnchanged() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertTrue("作用域外前台确认照常询问并放行", g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertEquals(1, ui.asked.size());
        assertTrue(ui.asked.get(0).startsWith("! 高危操作 "));
    }

    @Test public void legacyHighRiskWrite_autoRejects_uiZeroCalls() throws Exception {
        legacyEngine();
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertFalse(ConfirmGate.nonInteractive(() -> g.check(writeTool(), args("{\"path\":\"a.txt\"}"))));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void outsideReadAndWrite_autoReject_uiZeroCalls() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        ConfirmGate.nonInteractive(() -> {
            assertFalse(g.checkReadOutside(readTool(), args("{\"path\":\"D:/x.txt\"}"), "D:/x.txt"));
            assertFalse(g.checkWriteOutside(writeTool(), args("{\"path\":\"D:/x.png\"}"), "D:/x.png"));
            return null;
        });
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void escapeWrite_switchOn_autoRejects_uiZeroCalls() throws Exception {
        Files.write(Paths.get(config.externalFile().toString()),
                "\npaths.write.allowOutside=true\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertFalse(ConfirmGate.nonInteractive(() -> g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void hookAsk_autoRejects_uiZeroCalls() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertFalse(ConfirmGate.nonInteractive(() -> g.hookAsk(writeTool(), args("{\"path\":\"a.txt\"}"))));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void hostKeyTrust_autoRejects_uiZeroCalls() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertFalse(ConfirmGate.nonInteractive(() -> g.askHostKeyTrust("host", 22, "fp")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void ruleAllow_stillWorksInScope() {
        config.appendWhitelist("confirm.whitelist.tools", "write");
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        assertTrue("规则放行不受非交互作用域影响（只豁免交互，不收紧裁决）",
                ConfirmGate.nonInteractive(() -> g.check(writeTool(), args("{\"path\":\"a.txt\"}"))));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void exceptionPath_cleansUpScope() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        try {
            ConfirmGate.nonInteractive(() -> { throw new IllegalStateException("boom"); });
            fail("应抛出异常");
        } catch (IllegalStateException expected) { }
        assertFalse("异常后作用域须清理", ConfirmGate.isNonInteractive());
        assertTrue("同线程后续前台确认不受污染", g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertEquals(1, ui.asked.size());
    }

    @Test public void nestedScopes_restoreOuter() {
        assertTrue(ConfirmGate.nonInteractive(() -> {
            ConfirmGate.nonInteractive(() -> null);
            return ConfirmGate.isNonInteractive();
        }));
        assertFalse(ConfirmGate.isNonInteractive());
    }

    @Test public void otherThread_notAffected() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        AtomicBoolean otherThreadDenied = new AtomicBoolean();
        Thread t = new Thread(() -> otherThreadDenied.set(
                !ConfirmGate.nonInteractive(() -> g.check(writeTool(), args("{\"path\":\"a.txt\"}")))));
        ConfirmGate.nonInteractive(() -> {
            try { t.start(); t.join(5000); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return null;
        });
        assertTrue("子线程作用域内自动拒绝", otherThreadDenied.get());
        assertTrue("主线程前台确认不受子线程作用域影响", g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertEquals(1, ui.asked.size());
    }

    /** G2 核心回归：后台作用域内的确认不得持锁等待——前台确认必须能及时到达 ui（旧 bug：后台持
     *  synchronized 监视器无限等 ui，前台被堵死）。ui 延迟应答下统计调用次数与来源线程。 */
    @Test public void backgroundScopeDoesNotBlockForegroundConfirm() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> askThreads = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        ConfirmUi slowUi = new ConfirmUi() {
            @Override public Decision ask(String message) {
                calls.incrementAndGet();
                askThreads.add(Thread.currentThread().getName());
                entered.countDown();
                try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return Decision.REJECT; }
                return Decision.APPROVE;
            }
        };
        ConfirmGate g = gate(slowUi);
        AtomicBoolean bgAllDenied = new AtomicBoolean(true);
        Thread bg = new Thread(() -> {
            for (int i = 0; i < 20; i++)
                if (ConfirmGate.nonInteractive(() -> g.check(writeTool(), args("{\"path\":\"a.txt\"}"))))
                    bgAllDenied.set(false);
        }, "bg-child");
        AtomicBoolean fgAllowed = new AtomicBoolean();
        Thread fg = new Thread(() -> fgAllowed.set(g.check(writeTool(), args("{\"path\":\"a.txt\"}"))), "fg-main");
        bg.start();
        fg.start();
        assertTrue("前台确认应在 5s 内到达 ui（不被后台作用域堵锁）",
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        release.countDown();
        bg.join(10000);
        fg.join(10000);
        assertFalse(bg.isAlive());
        assertFalse(fg.isAlive());
        assertTrue("后台作用域内全部自动拒绝", bgAllDenied.get());
        assertTrue("前台确认照常放行", fgAllowed.get());
        assertEquals("ui 只应被前台调用一次（后台零调用）", 1, calls.get());
        assertEquals("fg-main", askThreads.get(0));
    }
}