package com.minion.core.mcp;

import com.minion.core.security.ProjectTrustStore;
import com.minion.core.security.TrustGate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * §10.9 MCP stdio 持续信任验收：拒绝 → FAILED 且零子进程（marker 不出现）；
 * 批准 → 子进程可启动；未装配闸门（旧装配/单测）行为不变；非 stdio 不适用。
 */
public class McpManagerTrustTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Windows 下用 cmd 写 marker 验证子进程是否真的启动过 */
    private static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 建 marker 服务器（返回 store 供后续变更复用） */
    private McpStore markerStore(Path dir, Path marker) throws Exception {
        McpStore store = McpStore.load(dir);
        McpServer s = new McpServer();
        s.name = "marker";
        s.transport = "stdio";
        s.command = "cmd.exe";
        s.args = new ArrayList<String>();
        s.args.add("/c");
        s.args.add("echo hi > \"" + marker + "\"");
        s.enabled = true;
        store.list().add(s);
        return store;
    }

    private McpManager managerWithMarkerServer(Path dir, Path marker, TrustGate gate) throws Exception {
        McpStore store = markerStore(dir, marker);
        McpManager m = new McpManager(store);
        m.setTrustGate(gate);
        return m;
    }

    /** 拒绝：FAILED + 原因含「持续信任」+ 零子进程 */
    @Test public void denyMeansFailedAndZeroSubprocess() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows：cmd marker 用例跳过"); return; }
        Path dir = tmp.newFolder().toPath();
        Path marker = dir.resolve("spawned.txt");
        ProjectTrustStore trust = ProjectTrustStore.load(dir);
        TrustGate deny = new TrustGate(trust, true, TrustGate.DENY);
        McpManager m = managerWithMarkerServer(dir, marker, deny);
        try {
            m.ensureConnectedAsync("marker");
            long deadline = System.currentTimeMillis() + 10000;
            McpServer s = m.servers().get(0);
            while (s.state != McpServer.State.FAILED && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                s = m.servers().get(0);
            }
            assertEquals(McpServer.State.FAILED, s.state);
            assertNotNull(s.failReason);
            assertTrue("失败原因须指向持续信任复核: " + s.failReason, s.failReason.contains("持续信任"));
            assertFalse("拒绝时子进程数量必须为零（marker 不得出现）", Files.exists(marker));
        } finally {
            m.shutdown();
        }
    }

    /** 批准：子进程获准启动（marker 出现；握手失败属预期——cmd 不是 MCP server） */
    @Test public void approvalAllowsSubprocess() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows：cmd marker 用例跳过"); return; }
        Path dir = tmp.newFolder().toPath();
        Path marker = dir.resolve("approved.txt");
        ProjectTrustStore trust = ProjectTrustStore.load(dir);
        TrustGate approve = new TrustGate(trust, true, (spec, reason, summary) -> true);
        McpManager m = managerWithMarkerServer(dir, marker, approve);
        try {
            m.ensureConnectedAsync("marker");
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline && !Files.exists(marker)) Thread.sleep(100);
            assertTrue("批准后子进程应启动（marker 出现）", Files.exists(marker));
            assertTrue("批准须落盘（后续连接免提示）", trust.entries().containsKey("mcp"));
        } finally {
            m.shutdown();
        }
    }

    /** 命令变化（git pull mcp.json）→ 已有旧批准不复用 → 拒绝 */
    @Test public void changedCommandNotReused() throws Exception {
        if (!onWindows()) { System.out.println("[skip] 非 Windows：cmd marker 用例跳过"); return; }
        Path dir = tmp.newFolder().toPath();
        Path marker1 = dir.resolve("v1.txt");
        ProjectTrustStore trust = ProjectTrustStore.load(dir);
        // v1 批准并落盘
        McpStore store = markerStore(dir, marker1);
        McpManager v1 = new McpManager(store);
        v1.setTrustGate(new TrustGate(trust, true, (s, r, m) -> true));
        v1.ensureConnectedAsync("marker");
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && !Files.exists(marker1)) Thread.sleep(100);
        v1.shutdown();
        assertTrue(Files.exists(marker1));

        // v2：同一服务器名但参数变化（输出文件不同）→ DENY 闸门拒绝（旧指纹不匹配）
        Path marker2 = dir.resolve("v2.txt");
        store.list().get(0).args.set(1, "echo hi > \"" + marker2 + "\"");
        McpManager v2 = new McpManager(store);
        v2.setTrustGate(new TrustGate(trust, true, TrustGate.DENY));
        try {
            v2.ensureConnectedAsync("marker");
            deadline = System.currentTimeMillis() + 10000;
            McpServer s = v2.servers().get(0);
            while (s.state != McpServer.State.FAILED && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                s = v2.servers().get(0);
            }
            assertEquals(McpServer.State.FAILED, s.state);
            assertFalse("变化后的命令不得复用旧批准", Files.exists(marker2));
        } finally {
            v2.shutdown();
        }
    }

    /** 未装配闸门：行为与 V68 完全一致（直接连接，不复核） */
    @Test public void legacyNoGateUnchanged() throws Exception {
        Path dir = tmp.newFolder().toPath();
        McpStore store = McpStore.load(dir);
        McpServer s = new McpServer();
        s.name = "fake";
        s.transport = "stdio";
        s.command = System.getProperty("java.home") + "/bin/java";
        s.args = new ArrayList<String>();
        s.args.add("-cp");
        s.args.add(System.getProperty("java.class.path"));
        s.args.add(FakeMcpServer.class.getName());
        s.enabled = true;
        store.list().add(s);
        McpManager m = new McpManager(store);   // 无闸门
        try {
            m.ensureConnectedAsync("fake");
            long deadline = System.currentTimeMillis() + 15000;
            McpServer st = m.servers().get(0);
            while (st.state != McpServer.State.CONNECTED && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                st = m.servers().get(0);
            }
            assertEquals("无闸门时 FakeMcpServer 应正常连接（V68 不回归）",
                    McpServer.State.CONNECTED, st.state);
        } finally {
            m.shutdown();
        }
    }

    /** sse/streamable：无本地子进程，不触发信任复核（FAILED 原因为 URL 缺失而非信任） */
    @Test public void httpTransportsSkipTrustCheck() throws Exception {
        Path dir = tmp.newFolder().toPath();
        McpStore store = McpStore.load(dir);
        McpServer s = new McpServer();
        s.name = "remote";
        s.transport = "streamable";
        s.enabled = true;   // 无 URL → 连接失败（原因为 URL，不经信任闸门）
        store.list().add(s);
        McpManager m = new McpManager(store);
        m.setTrustGate(new TrustGate(ProjectTrustStore.load(dir), true, TrustGate.DENY));
        try {
            m.ensureConnectedAsync("remote");
            long deadline = System.currentTimeMillis() + 10000;
            McpServer st = m.servers().get(0);
            while (st.state != McpServer.State.FAILED && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                st = m.servers().get(0);
            }
            assertEquals(McpServer.State.FAILED, st.state);
            assertTrue(st.failReason.contains("URL"));
        } finally {
            m.shutdown();
        }
    }
}
