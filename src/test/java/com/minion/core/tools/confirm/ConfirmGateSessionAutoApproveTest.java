package com.minion.core.tools.confirm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * H2 对话级权限选择：自动审批/完全授权后主/子agent（同一 ConfirmGate）不再弹确认；
 * deny 规则与 plan 只读拦截不放宽；会话态不落盘；G2 非交互默认拒绝保持。
 */
public class ConfirmGateSessionAutoApproveTest {

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

    private static JsonArray updates(String json) { return JsonParser.parseString(json).getAsJsonArray(); }

    @Test public void autoApprove_highRiskWrite_allows_uiZeroCalls() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        g.setSessionAutoApprove(true);
        assertTrue("自动审批：高危覆盖写直接放行", g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue("自动审批不得调用 ui", ui.asked.isEmpty());
    }

    @Test public void autoApprove_denyRule_stillDenies() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        g.applySessionPermissionUpdates(updates("[{\"behavior\":\"deny\",\"tool\":\"Write\"}]"));
        g.setSessionAutoApprove(true);
        assertFalse("deny 规则免疫自动审批", g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void autoApprove_planModeWrite_stillDenies() throws Exception {
        config.set("permission.mode", "read-only");
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        g.setSessionAutoApprove(true);
        assertFalse("plan 只读拦截不受自动审批影响", g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void fullTrust_bypassImmuneAskRule_autoApproves() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        g.applySessionPermissionUpdates(updates("[{\"behavior\":\"ask\",\"tool\":\"Write\"}]"));
        g.setSessionPermissionMode("full");
        g.setSessionAutoApprove(true);
        assertTrue("完全授权：免疫 bypass 的 ask 规则也自动放行", g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    /** 特征化：仅 bypass（旧 APPROVE_SESSION/full 语义）不跳过免疫 ask——只有新选择器显式 autoApprove 才免弹 */
    @Test public void bypassAlone_immuneAskRule_stillPrompts() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        g.applySessionPermissionUpdates(updates("[{\"behavior\":\"ask\",\"tool\":\"Write\"}]"));
        g.setSessionPermissionMode("full");
        assertFalse(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertEquals("旧语义保持：残留 ask 仍弹卡", 1, ui.asked.size());
    }

    @Test public void clearSessionMode_restoresGlobalDefault() {
        ConfirmGate g = gate(new FakeConfirmUi());
        g.setSessionPermissionMode("full");
        assertEquals(com.minion.core.security.permission.PermissionContext.Mode.BYPASS, g.effectiveMode());
        g.clearSessionPermissionMode();
        assertEquals(com.minion.core.security.permission.PermissionContext.mapLegacyMode(config.permissionMode()),
                g.effectiveMode());
    }

    @Test public void nonInteractive_withoutAuth_stillRejects() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertFalse("G2 保持：未授权的后台确认自动拒绝",
                ConfirmGate.nonInteractive(() -> g.check(writeTool(), args("{\"path\":\"a.txt\"}"))));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void nonInteractive_withAutoApprove_allows() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        g.setSessionAutoApprove(true);
        assertTrue("用户明示自动审批后，后台子agent高危操作放行（无人值守）",
                ConfirmGate.nonInteractive(() -> g.check(writeTool(), args("{\"path\":\"a.txt\"}"))));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void autoApprove_notPersistedAcrossInstances() {
        ConfirmGate g = gate(new FakeConfirmUi());
        g.setSessionAutoApprove(true);
        assertFalse("会话态不落盘：新实例回到默认", gate(new FakeConfirmUi()).sessionAutoApprove());
    }

    @Test public void autoApprove_legacyEngine_allows_uiZeroCalls() throws Exception {
        Files.write(Paths.get(config.externalFile().toString()),
                "permission.engine.v2=false\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        g.setSessionAutoApprove(true);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void autoApprove_outsideReadAndEscapeWrite_allow_uiZeroCalls() throws Exception {
        Files.write(Paths.get(config.externalFile().toString()),
                "\npaths.write.allowOutside=true\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        g.setSessionAutoApprove(true);
        assertTrue(g.checkReadOutside(readTool(), args("{\"path\":\"D:/x.txt\"}"), "D:/x.txt"));
        assertTrue(g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void autoApprove_hookAskAndHostTrust_allow_uiZeroCalls() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        g.setSessionAutoApprove(true);
        assertTrue(g.hookAsk(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(g.askHostKeyTrust("host", 22, "fp"));
        assertTrue(ui.asked.isEmpty());
    }

    @Test public void switchBackToDefault_promptsAgain() {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        g.setSessionAutoApprove(true);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        g.setSessionAutoApprove(false);
        g.clearSessionPermissionMode();
        assertFalse("切回默认恢复逐次确认", g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertEquals(1, ui.asked.size());
    }
}