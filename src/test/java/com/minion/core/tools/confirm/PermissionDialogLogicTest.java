package com.minion.core.tools.confirm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.security.permission.RuleWriter;
import com.minion.core.tools.Tool;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.WriteTool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * v2.4 §10.3 权限确认对话框族验收（逻辑层）：判定来源/风险标注/规则建议/diff 数据；
 * RuleWriter 按作用域落盘（原子、去重、保留他键、拒畸形、拒损坏覆盖）；
 * ConfirmGate→askDetailed→「允许并写规则」端到端（会话规则立即生效+落盘重读免二次询问）。
 * FX 富卡片渲染属人工验收批（方向稿：UI 逻辑层 headless 可测、FX 薄壳）。
 */
public class PermissionDialogLogicTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** 造一个 ask 判定（Write 高危无 allow 规则 → ask） */
    private com.minion.core.security.permission.PermissionEngine.Verdict askVerdict(
            Tool tool, JsonObject a) {
        com.minion.core.security.permission.PermissionContext ctx =
                new com.minion.core.security.permission.PermissionContext(
                        com.minion.core.security.permission.PermissionContext.Mode.DEFAULT,
                        com.minion.core.security.permission.PermissionContext.ToolCheck.PASSTHROUGH,
                        false, false, null, null, null);
        return new com.minion.core.security.permission.PermissionEngine()
                .evaluate(tool.name(), null, new com.minion.core.security.permission.RuleStore(), ctx);
    }

    /** Bash：风险标注来自分析器；规则建议=首 token 前缀 */
    @Test public void bashRiskAndPrefixRule() {
        com.minion.core.tools.BashTool bash =
                new com.minion.core.tools.BashTool(new Workspace(tmp.getRoot().getPath()));
        JsonObject a = args("{\"command\":\"git push origin main\"}");
        PermissionExplainer.Explanation ex =
                PermissionExplainer.explain(askVerdict(bash, a), bash, a, "git push origin main");
        assertEquals("Bash", ex.tool);
        assertTrue(ex.decisionSource.contains("PermissionEngine 第"));
        assertNotNull(ex.riskDetail);
        assertEquals("Bash(git:*)", ex.suggestedRule);
        assertEquals("project", ex.ruleScope);
        assertNull(ex.diffNew);   // 非 Write/Edit 无 diff
    }

    /** Edit：diff 数据（路径/旧/新）+ 目录前缀规则建议 */
    @Test public void editDiffAndDirPrefixRule() {
        com.minion.core.tools.EditTool edit =
                new com.minion.core.tools.EditTool(new Workspace(tmp.getRoot().getPath()));
        JsonObject a = args("{\"path\":\"docs/guide.md\",\"old_string\":\"旧\",\"new_string\":\"新\"}");
        PermissionExplainer.Explanation ex =
                PermissionExplainer.explain(askVerdict(edit, a), edit, a, null);
        assertEquals("docs/guide.md", ex.diffPath);
        assertEquals("旧", ex.diffOld);
        assertEquals("新", ex.diffNew);
        assertEquals("Edit(docs/*)", ex.suggestedRule);
    }

    /** RuleWriter：落盘/去重/保留他键/作用域路径 */
    @Test public void ruleWriterPersistsAndDedups() throws Exception {
        Path ws = tmp.newFolder("ws").toPath();
        Path settings = RuleWriter.scopeFile(ws, tmp.getRoot().toPath(), "project");
        assertEquals(ws.resolve(".minion/settings.json"), settings);
        assertEquals(ws.resolve(".minion/settings.local.json"),
                RuleWriter.scopeFile(ws, tmp.getRoot().toPath(), "local"));   // local=工作空间本地层
        assertEquals(tmp.getRoot().toPath().resolve(".minion/settings.json"),
                RuleWriter.scopeFile(ws, tmp.getRoot().toPath(), "user"));    // user=jarDir 层
        Files.createDirectories(settings.getParent());
        Files.write(settings, "{\"model\":{\"name\":\"x\"}}".getBytes(StandardCharsets.UTF_8));
        RuleWriter.writeAllow(settings, "Bash(git:*)");
        RuleWriter.writeAllow(settings, "Bash(git:*)");   // 去重
        RuleWriter.writeAllow(settings, "Edit(docs/*)");
        JsonObject root = JsonParser.parseString(
                new String(Files.readAllBytes(settings), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("x", root.getAsJsonObject("model").get("name").getAsString());   // 他键保留
        assertEquals(2, root.getAsJsonObject("permissions").getAsJsonArray("allow").size());
    }

    /** RuleWriter 防线：畸形规则拒绝；损坏 settings.json 拒绝覆盖 */
    @Test public void ruleWriterRejectsMalformed() throws Exception {
        Path settings = tmp.newFolder("w2").toPath().resolve(".minion/settings.json");
        try {
            RuleWriter.writeAllow(settings, "");
            fail("空规则须拒绝");
        } catch (IllegalArgumentException expected) { }
        Files.createDirectories(settings.getParent());
        Files.write(settings, "{{{ broken".getBytes(StandardCharsets.UTF_8));
        try {
            RuleWriter.writeAllow(settings, "Bash(git:*)");
            fail("损坏文件须拒绝覆盖");
        } catch (java.io.IOException expected) { }
    }

    /** 端到端：askDetailed 富信息 → 「允许并写规则」（编辑文本优先）→ 落盘 + 二次免询问 */
    @Test public void gateWhitelistWritesRuleAndReasksFree() throws Exception {
        Path ws = tmp.newFolder("space").toPath();
        Path jar = tmp.newFolder("jar").toPath();
        Files.createDirectories(ws.resolve("docs"));
        Config config = Config.load(jar);
        final Path outside = tmp.newFolder("outside").toPath();   // 工作区外目录：workspace-write 下越界写必 ask
        final String dir = outside.toString().replace('\\', '/') + "/";
        final PermissionExplainer.Explanation[] seen = new PermissionExplainer.Explanation[1];
        final int[] asks = {0};
        ConfirmUi ui = new ConfirmUi() {
            @Override public Decision ask(String message) { return Decision.REJECT; }
            @Override public Decision askDetailed(
                    PermissionExplainer.Explanation ex, String message) {
                seen[0] = ex;
                asks[0]++;
                return Decision.APPROVE_WHITELIST;   // 允许并写规则
            }
            @Override public String editedRule() { return "Write(" + dir + "*)"; }   // 用户编辑后的规则
        };
        ConfirmGate gate = new ConfirmGate(config, ui);
        gate.setProjectSettings(ws.resolve(".minion/settings.json"),
                ws.resolve(".minion/settings.local.json"));
        WriteTool write = new WriteTool(new Workspace(ws.toString()));
        Files.write(java.nio.file.Paths.get(dir + "new-file.md"),
                "旧内容".getBytes(StandardCharsets.UTF_8));   // 预存在 → 覆盖写=高危 → ask
        JsonObject a = args("{\"path\":\"" + (dir + "new-file.md").replace("/", "\\\\") + "\",\"content\":\"内容\"}");
        assertTrue("批准后当次放行", gate.check(write, a));
        assertEquals(1, asks[0]);
        assertNotNull("UI 收到结构化解释", seen[0]);
        assertEquals("Write(" + dir + "*)", seen[0].suggestedRule);
        // 落盘：项目 settings.json permissions.allow 含编辑后的规则
        JsonObject saved = JsonParser.parseString(new String(
                Files.readAllBytes(ws.resolve(".minion/settings.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(saved.getAsJsonObject("permissions").getAsJsonArray("allow")
                .toString().contains("Write(" + dir + "*)"));
        // 二次同类操作：规则重读命中 → 免询问直接放行
        Files.write(java.nio.file.Paths.get(dir + "another.md"),
                "旧内容2".getBytes(StandardCharsets.UTF_8));   // 同为覆盖写（高危）但规则命中免询问
        JsonObject again = args("{\"path\":\"" + (dir + "another.md").replace("/", "\\\\") + "\",\"content\":\"更多\"}");
        assertTrue(gate.check(write, again));
        assertEquals("规则生效后不再询问", 1, asks[0]);
    }
}
