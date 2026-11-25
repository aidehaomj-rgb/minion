package com.minion.core.tools.computeruse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.tools.PermissionCheck;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.ValidationResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.*;

/**
 * 三批 C5：computeruse 审计 + 可选动作上限。默认 0=不限（旧行为不变）；
 * 设限第 N+1 次拒绝且不触达底层工具；审计条目（tool/action/参数/前台标题）可捕获；
 * 分类/校验/权限元数据全量委托（ConfirmGate 看到的仍是原工具）。
 */
public class ComputerUseAuditTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /**  distinctive 元数据的 fake 底层工具：验证包装器全量委托 */
    private static final class FakeTool implements Tool {
        int calls;
        RuntimeException toThrow;
        ToolResult result = ToolResult.success("ok");
        final JsonObject schema = new JsonObject();

        @Override public String name() { return "FakeAudited"; }
        @Override public String description() { return "fake"; }
        @Override public JsonObject schema() { return schema; }
        @Override public ToolResult execute(JsonObject args) {
            calls++;
            if (toThrow != null) throw toThrow;
            return result;
        }
        @Override public boolean isHighRisk(JsonObject args) { return true; }
        @Override public boolean isReadOnly(JsonObject args) { return true; }
        @Override public boolean isConcurrencySafe(JsonObject args) { return true; }
        @Override public boolean isDestructive(JsonObject args) { return false; }
        @Override public boolean isEnabled() { return false; }
        @Override public ValidationResult validateInput(JsonObject args) { return ValidationResult.invalid("fake-invalid"); }
        @Override public PermissionCheck checkPermissions(JsonObject args,
                com.minion.core.security.permission.PermissionContext ctx) { return PermissionCheck.deny("fake-deny"); }
        @Override public int maxResultSizeChars() { return 123; }
    }

    private static JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    /** 默认 0=不限：多少次都放行，底层逐次执行（旧行为不变） */
    @Test
    public void defaultZeroUnlimited() throws Exception {
        ComputerUseConfig config = new ComputerUseConfig();
        assertEquals(0, config.maxActionsPerSessionSafe());
        FakeTool fake = new FakeTool();
        ComputerUseAuditor auditor = new ComputerUseAuditor(fake, config);
        for (int i = 0; i < 50; i++) assertTrue(auditor.execute(args("{\"action\":\"click\"}")).ok);
        assertEquals(50, fake.calls);
        config.maxActionsPerSession = -3;
        assertEquals("负数按不限处理", 0, config.maxActionsPerSessionSafe());
        assertTrue(auditor.execute(args("{\"action\":\"click\"}")).ok);
    }

    /** 设限 N：第 N+1 次可操作拒绝，且不触达底层工具 */
    @Test
    public void limitRejectsNPlus1WithoutExecuting() throws Exception {
        ComputerUseConfig config = new ComputerUseConfig();
        config.maxActionsPerSession = 2;
        FakeTool fake = new FakeTool();
        ComputerUseAuditor auditor = new ComputerUseAuditor(fake, config);
        assertTrue(auditor.execute(args("{\"action\":\"click\"}")).ok);
        assertTrue(auditor.execute(args("{\"action\":\"type\"}")).ok);
        ToolResult third = auditor.execute(args("{\"action\":\"click\"}"));
        assertFalse(third.ok);
        assertTrue("拒绝应可操作: " + third.output, third.output.contains("maxActionsPerSession"));
        assertTrue(third.output.contains("tools.json"));
        assertEquals("超限动作不得执行", 2, fake.calls);
    }

    /** 计数跨工具共享（Screen/Mouse/Keyboard… 合计） */
    @Test
    public void counterSharedAcrossTools() throws Exception {
        ComputerUseConfig config = new ComputerUseConfig();
        config.maxActionsPerSession = 3;
        FakeTool a = new FakeTool(), b = new FakeTool();
        ComputerUseAuditor wa = new ComputerUseAuditor(a, config);
        ComputerUseAuditor wb = new ComputerUseAuditor(b, config);
        assertTrue(wa.execute(args("{\"action\":\"screenshot\"}")).ok);
        assertTrue(wa.execute(args("{\"action\":\"diff\"}")).ok);
        assertTrue(wb.execute(args("{\"action\":\"click\"}")).ok);
        assertFalse("合计第 4 次应拒绝", wb.execute(args("{\"action\":\"type\"}")).ok);
        assertEquals(2, a.calls);
        assertEquals(1, b.calls);
    }

    /** 元数据全量委托：门禁/调度/截断层看到的仍是原工具 */
    @Test
    public void allMetadataDelegated() {
        FakeTool fake = new FakeTool();
        ComputerUseAuditor auditor = new ComputerUseAuditor(fake, new ComputerUseConfig());
        JsonObject a = args("{\"action\":\"click\"}");
        assertEquals("FakeAudited", auditor.name());
        assertEquals(fake.description(), auditor.description());
        assertSame(fake.schema(), auditor.schema());
        assertTrue(auditor.isHighRisk(a));
        assertTrue(auditor.isReadOnly(a));
        assertTrue(auditor.isConcurrencySafe(a));
        assertFalse(auditor.isDestructive(a));
        assertFalse(auditor.isEnabled());
        assertFalse(auditor.validateInput(a).ok);
        assertEquals(PermissionCheck.Outcome.DENY, auditor.checkPermissions(a, null).outcome);
        assertEquals(123, auditor.maxResultSizeChars());
    }

    /** 审计条目捕获：tool/action/参数摘要/前台标题 */
    @Test
    public void auditEntryCaptured() throws Exception {
        DiagnosticLog.initialize(tmp.getRoot().toPath());
        FakeTool fake = new FakeTool();
        new ComputerUseAuditor(fake, new ComputerUseConfig())
                .execute(args("{\"action\":\"click\",\"x\":5,\"y\":6}"));
        List<String> tail = DiagnosticLog.tail(50);
        boolean found = false;
        for (String line : tail) {
            if (line.contains("FakeAudited") && line.contains("action=click")
                    && line.contains("\"x\":5") && line.contains("前台=")) { found = true; break; }
        }
        assertTrue("审计行应含 tool/action/参数/前台: " + tail, found);
    }

    /** 底层异常：照常上抛且记失败审计（不吞异常） */
    @Test
    public void exceptionRethrownAndAudited() throws Exception {
        DiagnosticLog.initialize(tmp.getRoot().toPath());
        FakeTool fake = new FakeTool();
        fake.toThrow = new IllegalStateException("boom");
        try {
            new ComputerUseAuditor(fake, new ComputerUseConfig()).execute(args("{\"action\":\"pressKey\"}"));
            fail("底层异常应上抛");
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
        boolean found = false;
        for (String line : DiagnosticLog.tail(50)) {
            if (line.contains("FakeAudited") && line.contains("exception=IllegalStateException")) { found = true; break; }
        }
        assertTrue("异常应留审计", found);
    }

    /** 超限拒绝也留审计；前台标题接口永不返回 null（非 Windows 为空串） */
    @Test
    public void rejectionAuditedAndForegroundTitleNeverNull() throws Exception {
        DiagnosticLog.initialize(tmp.getRoot().toPath());
        ComputerUseConfig config = new ComputerUseConfig();
        config.maxActionsPerSession = 1;
        ComputerUseAuditor auditor = new ComputerUseAuditor(new FakeTool(), config);
        auditor.execute(args("{\"action\":\"click\"}"));
        auditor.execute(args("{\"action\":\"click\"}"));
        boolean found = false;
        for (String line : DiagnosticLog.tail(50)) {
            if (line.contains("rejected=action_limit(1)")) { found = true; break; }
        }
        assertTrue("拒绝应留审计", found);
        assertNotNull(Win32Gui.foregroundTitle());
    }

    /** Gson 回环：transient 计数器不落盘，反序列化后惰性重建可用 */
    @Test
    public void gsonRoundtripCounterRebuilt() {
        ComputerUseConfig config = new ComputerUseConfig();
        config.maxActionsPerSession = 5;
        config.actionCounter().incrementAndGet();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        ComputerUseConfig back = gson.fromJson(gson.toJson(config), ComputerUseConfig.class);
        assertNull("transient 不应落盘", back.actionCount);
        assertEquals(5, back.maxActionsPerSession);
        assertEquals("惰性重建从 0 计", 1, back.actionCounter().incrementAndGet());
    }
}