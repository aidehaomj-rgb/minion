package com.minion.core.security;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** §10.9 TrustGate 闸门：已信任不弹窗、拒绝不落盘且调用方不得启动子进程、批准落盘续用、应急开关放行 */
public class TrustGateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static TrustedExecutable spec(String command) {
        return TrustedExecutable.of(TrustedExecutable.TYPE_MCP, "srv", "user", command,
                Collections.<String>emptyList(), Collections.<String, String>emptyMap(), "",
                Collections.<Path>emptyList());
    }

    /** 已信任：不弹窗直接放行 */
    @Test public void trustedSkipsPrompt() throws Exception {
        ProjectTrustStore store = ProjectTrustStore.load(tmp.newFolder().toPath());
        store.approve(spec("run.exe"));
        AtomicInteger prompts = new AtomicInteger();
        TrustGate gate = new TrustGate(store, true, (s, r, m) -> {
            prompts.incrementAndGet();
            return true;
        });
        assertTrue(gate.ensureTrusted(spec("run.exe")));
        assertEquals(0, prompts.get());
    }

    /** 拒绝：返回 false（不启动子进程）且不落盘指纹 */
    @Test public void denyDoesNotPersist() throws Exception {
        ProjectTrustStore store = ProjectTrustStore.load(tmp.newFolder().toPath());
        TrustGate gate = new TrustGate(store, true, TrustGate.DENY);
        assertFalse(gate.ensureTrusted(spec("run.exe")));
        assertFalse(store.isTrusted(spec("run.exe")));
        assertTrue(store.entries().isEmpty());
    }

    /** 批准：落盘后同指纹续用（第二次不再弹窗） */
    @Test public void approvalPersistsAndReuses() throws Exception {
        ProjectTrustStore store = ProjectTrustStore.load(tmp.newFolder().toPath());
        AtomicInteger prompts = new AtomicInteger();
        TrustGate gate = new TrustGate(store, true, (s, r, m) -> prompts.incrementAndGet() == 1);
        assertTrue(gate.ensureTrusted(spec("run.exe")));
        assertTrue(gate.ensureTrusted(spec("run.exe")));   // 已落盘 → 不再问
        assertEquals(1, prompts.get());
        assertTrue(store.isTrusted(spec("run.exe")));
    }

    /** 内容变化后再问：原因含「变化」，新批准覆盖旧指纹 */
    @Test public void changeRePromptsAndUpdates() throws Exception {
        ProjectTrustStore store = ProjectTrustStore.load(tmp.newFolder().toPath());
        store.approve(spec("run.exe v1"));
        final String[] seenReason = new String[1];
        TrustGate gate = new TrustGate(store, true, (s, r, m) -> {
            seenReason[0] = r;
            return true;
        });
        assertTrue(gate.ensureTrusted(spec("run.exe v2")));
        assertTrue(seenReason[0].contains("变化"));
        assertTrue(store.isTrusted(spec("run.exe v2")));
        assertFalse(store.isTrusted(spec("run.exe v1")));
    }

    /** 应急开关（trust.store.v2=false）：全放行不弹窗 */
    @Test public void disabledGatePassesAll() throws Exception {
        ProjectTrustStore store = ProjectTrustStore.load(tmp.newFolder().toPath());
        AtomicInteger prompts = new AtomicInteger();
        TrustGate gate = new TrustGate(store, false, (s, r, m) -> {
            prompts.incrementAndGet();
            return false;
        });
        assertTrue(gate.ensureTrusted(spec("anything")));
        assertEquals(0, prompts.get());
    }
}
