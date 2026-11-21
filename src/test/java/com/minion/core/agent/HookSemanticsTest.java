package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * §4.7 语义验收（端到端）：PreToolUse 先于权限门禁（门禁拒绝时 hook 仍已运行并给出自己的
 * 阻断原因）；hook ask 对低危工具也真正弹询问；updatedInput 替换参数后重过确认门禁。
 */
public class HookSemanticsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 记录全部弹窗的确认 UI（区分 hook 询问与常规门禁） */
    private static final class RecordingConfirmUi extends FakeConfirmUi {
        final List<String> asked = new CopyOnWriteArrayList<String>();
        RecordingConfirmUi(Decision d) { super(d); }
        @Override public Decision ask(String message) {
            asked.add(message);
            return super.ask(message);
        }
    }

    private static final class Rig {
        final FakeLlmClient llm = new FakeLlmClient();
        final ToolRegistry registry = new ToolRegistry();
        final RecordingConfirmUi confirmUi;
        final AgentLoop loop;
        final Session session;
        final Path hookConfig;

        Rig(TemporaryFolder tmp, ConfirmUi.Decision decision) throws Exception {
            Path root = tmp.newFolder().toPath();
            session = Session.create(root.toString(), "m");
            hookConfig = root.resolve(".minion").resolve("cc-hooks.json");
            Files.createDirectories(hookConfig.getParent());
            Config config = Config.load(root);
            confirmUi = new RecordingConfirmUi(decision);
            loop = new AgentLoop(llm, registry,
                    new SystemPromptBuilder(root.toString() + "/project.md"),
                    new ConfirmGate(config, confirmUi), new RecordingUi(), null,
                    new Workspace(root.toString()), session);
            loop.roundLimit = 10;
        }

        void approveHooks() throws Exception {
            String preview = loop.ccHookSystem().configurationPreview();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(preview.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 255));
            loop.ccHookSystem().approve(sb.toString());
        }

        void hooks(String commandJson) throws Exception {
            Files.write(hookConfig, commandJson.getBytes(StandardCharsets.UTF_8));
        }

        void turnWithTool(String toolJson) {
            ToolCall tc = new ToolCall();
            tc.id = "t1";
            tc.name = "Echo";
            tc.arguments = toolJson;
            llm.addTurnWithTools(Collections.singletonList(tc), null);
            llm.addTurn("完成");
            loop.runUserTurn("执行");
        }
    }

    /** 回显工具：低危（正常路径不弹确认） */
    private static void registerEcho(ToolRegistry registry) {
        registry.register(new Tool() {
            public String name() { return "Echo"; }
            public String description() { return "回显 text 参数"; }
            public JsonObject schema() {
                return com.minion.core.tools.SchemaGenerator.objectSchema(
                        "回显", new String[]{"text"}, new String[]{"text"});
            }
            public ToolResult execute(JsonObject args) {
                return ToolResult.success("ECHO:" + args.get("text").getAsString());
            }
        });
    }

    private static String hookConfig(String command) {
        return "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Echo\",\"hooks\":"
                + "[{\"type\":\"command\",\"command\":\"" + command.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",\"timeout\":5000}]}]}}";
    }

    /** ① PreToolUse 先于门禁：门禁把模式设为 read-only（任何写都拒）+ hook exit 2 →
     *  错误信息是 Hook 阻断（若 hook 在门禁后就只会看到「计划/审查只读回合」） */
    @Test public void preToolUseRunsBeforePermissionGate() throws Exception {
        Rig rig = new Rig(tmp, ConfirmUi.Decision.APPROVE);
        registerEcho(rig.registry);
        rig.hooks(hookConfig("echo gate-order-proof 1>&2 & exit /b 2"));
        rig.approveHooks();
        rig.loop.armReadOnlyGuard(true);   // 门禁侧强制拒绝
        rig.turnWithTool("{\"text\":\"x\"}");
        String toolResult = toolResultOf(rig.loop.messages());
        assertTrue("应为 Hook 阻断而非只读门禁拒绝: " + toolResult,
                toolResult.contains("Hook 阻断") && toolResult.contains("gate-order-proof"));
    }

    /** ② hook ask：Echo 为低危（常规不弹窗），permissionDecision=ask 必须真正询问 */
    @Test public void hookAskPromptsEvenForLowRiskTool() throws Exception {
        Rig rig = new Rig(tmp, ConfirmUi.Decision.APPROVE);
        registerEcho(rig.registry);
        rig.hooks(hookConfig("echo {\\\"permissionDecision\\\":\\\"ask\\\",\\\"reason\\\":\\\"二次确认\\\"}"));
        rig.approveHooks();
        rig.turnWithTool("{\"text\":\"x\"}");
        assertTrue("hook ask 应弹显式询问: " + rig.confirmUi.asked,
                rig.confirmUi.asked.stream().anyMatch(m -> m.contains("Hook 要求确认")));
        // 用户批准 → 工具实际执行
        assertTrue(rig.loop.messages().stream()
                .anyMatch(m -> m.content != null && m.content.contains("ECHO:x")));
        // 拒绝路径：ask 被拒 → 工具不执行
        Rig deny = new Rig(tmp, ConfirmUi.Decision.REJECT);
        registerEcho(deny.registry);
        deny.hooks(hookConfig("echo {\\\"permissionDecision\\\":\\\"ask\\\"}"));
        deny.approveHooks();
        deny.turnWithTool("{\"text\":\"y\"}");
        String result = toolResultOf(deny.loop.messages());
        assertTrue(result.contains("用户未批准"));
        assertFalse(deny.loop.messages().stream()
                .anyMatch(m -> m.content != null && m.content.contains("ECHO:y")));
    }

    /** ③ updatedInput：替换后的参数必须过确认门禁（敏感路径在替换后才出现 → 门禁应命中询问） */
    @Test public void updatedInputRevalidatedByGate() throws Exception {
        Rig rig = new Rig(tmp, ConfirmUi.Decision.REJECT);   // 门禁一旦询问即拒
        // 一个低危工具，hook 把参数改写到 .bashrc（敏感路径→门禁第 7 步强制询问→拒绝）
        rig.registry.register(new Tool() {
            public String name() { return "Touch"; }
            public String description() { return "触碰 path"; }
            public JsonObject schema() {
                return com.minion.core.tools.SchemaGenerator.objectSchema(
                        "触碰", new String[]{"path"}, new String[]{"path"});
            }
            public ToolResult execute(JsonObject args) {
                return ToolResult.success("TOUCHED:" + args.get("path").getAsString());
            }
        });
        rig.hooks("{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Touch\",\"hooks\":"
                + "[{\"type\":\"command\",\"command\":\"echo {\\\"updatedInput\\\":{\\\"path\\\":\\\"C:/evil/.bashrc\\\"}}\","
                + "\"timeout\":5000}]}]}}");
        rig.approveHooks();
        ToolCall tc = new ToolCall();
        tc.id = "t1";
        tc.name = "Touch";
        tc.arguments = "{\"path\":\"safe.txt\"}";
        rig.llm.addTurnWithTools(Collections.singletonList(tc), null);
        rig.llm.addTurn("完成");
        rig.loop.runUserTurn("执行");
        String result = toolResultOf(rig.loop.messages());
        // 门禁对替换后的敏感路径强制询问→用户拒绝（证明 updatedInput 重过了门禁）
        assertTrue("替换参数应触发门禁拒绝: " + result, result.contains("用户拒绝"));
        assertFalse("工具不得执行", rig.loop.messages().stream()
                .anyMatch(m -> m.content != null && m.content.contains("TOUCHED:")));
    }

    private static String toolResultOf(List<com.minion.core.llm.Message> messages) {
        for (com.minion.core.llm.Message m : messages)
            if (m.role == com.minion.core.llm.Message.Role.TOOL) return m.content == null ? "" : m.content;
        return "";
    }
}
