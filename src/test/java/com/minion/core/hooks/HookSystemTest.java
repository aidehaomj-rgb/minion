package com.minion.core.hooks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * §4.7 Hook 系统验收：matcher 三态、快照批准、退出码 2 阻断、stdout JSON 决策
 * （block/ask/updatedInput）、超时 fail-ask、多 hook 最严格合并、未批准零执行。
 */
public class HookSystemTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;

    private HookSystem systemWith(String json) throws Exception {
        root = tmp.newFolder().toPath();
        Files.createDirectories(root.resolve(".minion"));
        Files.write(root.resolve(".minion").resolve("cc-hooks.json"), json.getBytes(StandardCharsets.UTF_8));
        return new HookSystem(root);
    }

    private static void approve(HookSystem hs) throws Exception {
        String preview = hs.configurationPreview();
        String hash = sha256(preview);
        hs.approve(hash);
    }

    private static String sha256(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 255));
        return sb.toString();
    }

    /** 配置模板：matcher + command（command 内引号/反斜杠按 JSON 转义） */
    private static String config(String event, String matcher, String command) {
        String escaped = command.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"hooks\":{\"" + event + "\":[{\"matcher\":\"" + matcher
                + "\",\"hooks\":[{\"type\":\"command\",\"command\":\"" + escaped + "\"}]}]}}";
    }

    // ==================== matcher ====================

    @Test public void matcherThreeForms() {
        assertTrue(HookSystem.matcherMatches("", "Bash"));
        assertTrue(HookSystem.matcherMatches("*", "Bash"));
        assertTrue(HookSystem.matcherMatches("Bash|Write", "Write"));
        assertFalse(HookSystem.matcherMatches("Bash|Write", "Read"));
        assertTrue(HookSystem.matcherMatches("Ba.*", "Bash"));            // 正则形态
        assertFalse(HookSystem.matcherMatches("Ba[", "Bash"));            // 非法正则 fail-closed 不执行
        assertTrue(HookSystem.matcherMatches("", null));                   // target null 按空串
    }

    // ==================== 批准与配置校验 ====================

    @Test public void approvalEnablesAndChangeInvalidates() throws Exception {
        HookSystem hs = systemWith(config(HookSystem.PRE_TOOL_USE, "Bash", "exit /b 0"));
        assertFalse(hs.enabled());
        assertEquals(HookSystem.Outcome.PASS.executed, hs.run(HookSystem.PRE_TOOL_USE, "Bash", null).executed);
        approve(hs);
        assertTrue(hs.enabled());
        assertTrue(hs.matches(HookSystem.PRE_TOOL_USE, "Bash"));
        assertFalse(hs.matches(HookSystem.PRE_TOOL_USE, "Read"));
        // 批准后配置改变：用旧哈希再批准必须失败（需重新预览）；disable 后零执行
        String oldHash = sha256(hs.configurationPreview());
        Files.write(root.resolve(".minion").resolve("cc-hooks.json"),
                config(HookSystem.PRE_TOOL_USE, "*", "exit /b 0").getBytes(StandardCharsets.UTF_8));
        try {
            hs.approve(oldHash);
            fail("配置已变，批准必须失败");
        } catch (Exception expected) { }
        hs.disable();
        assertFalse(hs.enabled());
    }

    @Test public void invalidConfigRejected() throws Exception {
        for (String bad : new String[]{
                "{\"hooks\":{\"NoSuchEvent\":[{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\",\"command\":\"exit /b 0\"}]}]}}",
                "{}",
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"\",\"hooks\":[{\"type\":\"exec\",\"command\":\"x\"}]}]}}",
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\",\"command\":\"x\",\"timeout\":5}]}]}}"})
            try {
                HookSystem hs = systemWith(bad);
                approve(hs);
                fail("非法配置必须被拒绝: " + bad);
            } catch (Exception expected) { }
    }

    // ==================== 执行语义 ====================

    /** 退出码 2 = 阻断，stderr 反馈模型 */
    @Test public void exitCodeTwoBlocks() throws Exception {
        HookSystem hs = systemWith(config(HookSystem.PRE_TOOL_USE, "Write",
                "echo hook-says-no 1>&2 & exit /b 2"));
        approve(hs);
        HookSystem.Outcome o = hs.run(HookSystem.PRE_TOOL_USE, "Write", new JsonObject());
        assertTrue(o.blocked);
        assertTrue(o.reason.contains("hook-says-no"));
    }

    /** stdout JSON：decision=block 阻断；permissionDecision=ask 转 ask；updatedInput 透传 */
    @Test public void stdoutJsonDecisions() throws Exception {
        HookSystem block = systemWith(config(HookSystem.PRE_TOOL_USE, "Bash",
                "echo {\"decision\":\"block\",\"reason\":\"policy\"}"));
        approve(block);
        HookSystem.Outcome b = block.run(HookSystem.PRE_TOOL_USE, "Bash", new JsonObject());
        assertTrue(b.blocked);
        assertEquals("policy", b.reason);

        HookSystem ask = systemWith(config(HookSystem.PRE_TOOL_USE, "Bash",
                "echo {\"permissionDecision\":\"ask\",\"reason\":\"confirm\",\"updatedInput\":{\"command\":\"dir\"}}"));
        approve(ask);
        HookSystem.Outcome a = ask.run(HookSystem.PRE_TOOL_USE, "Bash", new JsonObject());
        assertFalse(a.blocked);
        assertTrue(a.ask);
        assertEquals("confirm", a.reason);
        assertEquals("dir", a.updatedInput.get("command").getAsString());
    }

    /** continue=false 阻断；非 JSON stdout fail-ask（不放行） */
    @Test public void continueFalseBlocksAndMalformedFailsAsk() throws Exception {
        HookSystem noGo = systemWith(config(HookSystem.STOP, "", "echo {\"continue\":false}"));
        approve(noGo);
        HookSystem.Outcome o = noGo.run(HookSystem.STOP, "", new JsonObject());
        assertTrue(o.blocked);

        HookSystem garbage = systemWith(config(HookSystem.STOP, "", "echo not-json"));
        approve(garbage);
        HookSystem.Outcome g = garbage.run(HookSystem.STOP, "", new JsonObject());
        assertFalse(g.blocked);
        assertTrue(g.ask);
    }

    /** 超时：杀进程树 + ask（无 continue 分支） */
    @Test public void timeoutKillsAndAsks() throws Exception {
        HookSystem hs = systemWith("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"\",\"hooks\":"
                + "[{\"type\":\"command\",\"command\":\"ping -n 10 127.0.0.1 > nul\",\"timeout\":400}]}]}}");
        approve(hs);
        long t0 = System.currentTimeMillis();
        HookSystem.Outcome o = hs.run(HookSystem.PRE_TOOL_USE, "Bash", new JsonObject());
        assertTrue(o.ask);
        assertFalse(o.blocked);
        assertTrue("400ms 超时应快速返回，实耗 " + (System.currentTimeMillis() - t0) + "ms",
                System.currentTimeMillis() - t0 < 5000);
    }

    /** 多 hook 并行 + 最严格合并：一个 allow 一个 deny → blocked */
    @Test public void multipleHooksMergeStrictest() throws Exception {
        String json = "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":["
                + "{\"type\":\"command\",\"command\":\"exit /b 0\"},"
                + "{\"type\":\"command\",\"command\":\"echo second-deny 1>&2 & exit /b 2\"}]}]}}";
        HookSystem hs = systemWith(json);
        approve(hs);
        HookSystem.Outcome o = hs.run(HookSystem.PRE_TOOL_USE, "Bash", new JsonObject());
        assertTrue(o.blocked);
        assertTrue(o.reason.contains("second-deny"));
        assertEquals(2, o.executed);
    }

    /** stdin JSON 契约：hook 能读到 session 元数据与 tool_input；找不到关键字则阻断（证明往返） */
    @Test public void stdinContractDelivered() throws Exception {
        HookSystem hs = systemWith(config(HookSystem.PRE_TOOL_USE, "Bash",
                "findstr /C:\"tool_name\" > nul || (echo no-stdin 1>&2 & exit /b 2)"));
        approve(hs);
        JsonObject payload = new JsonObject();
        payload.addProperty("session_id", "s-1");
        payload.addProperty("tool_name", "Bash");
        JsonObject input = new JsonObject();
        input.addProperty("command", "dir");
        payload.add("tool_input", input);
        HookSystem.Outcome o = hs.run(HookSystem.PRE_TOOL_USE, "Bash", payload);
        // stdin 未送达（findstr 读不到 tool_name）会 exit 2 阻断；放行即证明 JSON 已进 stdin
        assertFalse("stdin JSON 未送达 hook", o.blocked);
        assertFalse(o.ask);
    }
}
