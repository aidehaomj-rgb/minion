package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * §2.1 工具元数据验收：接口 default 全 fail-closed；标注表抽查（Read/Glob/Grep/CodeIndex
 * 只读并行、TodoWrite 串行、Bash 按分析、Edit/Write 默认保守）；校验/权限四态类型。
 */
public class ToolMetadataTest {

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** 未标注的匿名工具：全部保守默认（非只读/串行/可能破坏） */
    @Test public void defaultsAreFailClosed() {
        Tool anon = new Tool() {
            public String name() { return "anon"; }
            public String description() { return ""; }
            public JsonObject schema() { return new JsonObject(); }
            public ToolResult execute(JsonObject args) { return ToolResult.success("ok"); }
        };
        assertFalse(anon.isReadOnly(new JsonObject()));
        assertFalse(anon.isConcurrencySafe(new JsonObject()));
        assertTrue(anon.isDestructive(new JsonObject()));
        assertTrue(anon.isEnabled());
        assertTrue(anon.validateInput(new JsonObject()).ok);
        assertEquals(PermissionCheck.Outcome.PASSTHROUGH, anon.checkPermissions(new JsonObject(), null).outcome);
        assertEquals(30000, anon.maxResultSizeChars());
        assertFalse(anon.isHighRisk(new JsonObject()));
    }

    /** §2.1 标注表：只读并发安全工具 */
    @Test public void annotatedReadOnlyTools() {
        Workspace ws = new Workspace(".");
        assertTrue(new ReadTool(ws).isReadOnly(new JsonObject()));
        assertTrue(new ReadTool(ws).isConcurrencySafe(new JsonObject()));
        assertTrue(new GlobTool(ws).isReadOnly(new JsonObject()));
        assertTrue(new GlobTool(ws).isConcurrencySafe(new JsonObject()));
        assertTrue(new GrepTool(ws).isConcurrencySafe(new JsonObject()));
        assertTrue(new com.minion.core.tools.dev.CodeIndexTool(ws, () -> false).isConcurrencySafe(new JsonObject()));
    }

    /** TodoWrite：全量替换，显式串行 */
    @Test public void todoWriteIsSerial() {
        assertFalse(new TodoWriteTool(new com.minion.core.agent.TodoList()).isConcurrencySafe(new JsonObject()));
    }

    /** Bash：可证明只读命令并行（ls/grep）；写入/未知一律 false */
    @Test public void bashReadOnlyByAnalysis() {
        Config config = Config.load(java.nio.file.Paths.get("target/test-tmp-abs"));
        BashTool bash = new BashTool(new Workspace("."), java.nio.file.Paths.get("target/test-tmp-abs"), config);
        assertTrue(bash.isReadOnly(args("{\"command\":\"ls -la\"}")));
        assertTrue(bash.isConcurrencySafe(args("{\"command\":\"grep -rn foo src/\"}")));
        assertFalse(bash.isReadOnly(args("{\"command\":\"rm -rf build\"}")));
        assertFalse(bash.isConcurrencySafe(args("{\"command\":\"echo hi > out.txt\"}")));
        assertFalse(bash.isConcurrencySafe(args("{\"command\":\"some-unknown --flag\"}")));
        // 缺 command 参数 → false（fail-closed）
        assertFalse(bash.isReadOnly(new JsonObject()));
    }

    /** Edit/Write：默认保守（非只读、串行）；Write 覆盖已有文件为破坏性 */
    @Test public void editWriteConservativeDefaults() {
        WriteTool write = new WriteTool(new Workspace("."));
        assertFalse(write.isReadOnly(args("{\"path\":\"a.txt\",\"content\":\"x\"}")));
        assertFalse(write.isConcurrencySafe(args("{\"path\":\"a.txt\",\"content\":\"x\"}")));
        assertTrue(write.isDestructive(args("{\"path\":\"a.txt\",\"content\":\"x\"}")));
    }

    /** ValidationResult / PermissionCheck 工厂与四态 */
    @Test public void validationAndPermissionTypes() {
        assertTrue(ValidationResult.ok().ok);
        ValidationResult bad = ValidationResult.invalid("缺少 path", "missing_param");
        assertFalse(bad.ok);
        assertEquals("missing_param", bad.errorCode);
        assertEquals(PermissionCheck.Outcome.ALLOW, PermissionCheck.allow().outcome);
        assertEquals(PermissionCheck.Outcome.DENY, PermissionCheck.deny("危险").outcome);
        assertEquals(PermissionCheck.Outcome.ASK, PermissionCheck.ask("确认").outcome);
        assertEquals(PermissionCheck.Outcome.PASSTHROUGH, PermissionCheck.passthrough().outcome);
    }
}
