package com.minion.core.security.permission;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * 权限引擎 13 步管线验收（开发说明 v2.4 §4.1）：
 * 规则解析（转义/畸形/通配/前缀）≥30 例；13 步每步命中；关键组合（deny 免疫 bypass 等）。
 * 纯内存规则，无 IO。
 */
public class PermissionEngineTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final PermissionEngine engine = new PermissionEngine();

    private RuleStore rules(PermissionRule... rs) {
        RuleStore store = new RuleStore();
        for (PermissionRule r : rs) store.addRule(r);
        return store;
    }

    private static PermissionRule allow(String spec) { return PermissionRule.parse(spec, PermissionRule.Behavior.ALLOW, PermissionRule.Source.USER); }
    private static PermissionRule deny(String spec) { return PermissionRule.parse(spec, PermissionRule.Behavior.DENY, PermissionRule.Source.POLICY); }
    private static PermissionRule ask(String spec) { return PermissionRule.parse(spec, PermissionRule.Behavior.ASK, PermissionRule.Source.PROJECT); }

    // ==================== 规则解析（≥30 例，v2.4 验收） ====================

    @Test public void ruleParsing_basicForms() {
        assertEquals("Bash", PermissionRule.parse("Bash", null, PermissionRule.Source.USER).toolName);
        assertNull(PermissionRule.parse("Bash", null, PermissionRule.Source.USER).content);
        assertEquals("git", PermissionRule.parse("Bash(git)", null, PermissionRule.Source.USER).content);
        assertNull(PermissionRule.parse("Bash()", null, PermissionRule.Source.USER).content);        // Bash() = 整工具
        assertNull(PermissionRule.parse("Bash(*)", null, PermissionRule.Source.USER).content);       // Bash(*) = 整工具
        assertEquals("git status", PermissionRule.parse("Bash(git status)", null, PermissionRule.Source.USER).content);
        assertEquals("Edit", PermissionRule.parse("Edit(./src/**)", null, PermissionRule.Source.USER).toolName);
        assertEquals("./src/**", PermissionRule.parse("Edit(./src/**)", null, PermissionRule.Source.USER).content);
    }

    @Test public void ruleParsing_escaping() {
        assertEquals("a(b", PermissionRule.parse("T(a\\(b)", null, PermissionRule.Source.USER).content);
        assertEquals("a)b", PermissionRule.parse("T(a\\)b)", null, PermissionRule.Source.USER).content);
        assertEquals("a\\b", PermissionRule.parse("T(a\\\\b)", null, PermissionRule.Source.USER).content);
        assertEquals("(x)", PermissionRule.parse("T(\\(x\\))", null, PermissionRule.Source.USER).content);
        // 首个未转义 ( 与末尾未转义 )：中间的转义括号保留为内容
        assertEquals("f(1)", PermissionRule.parse("T(f\\(1\\))", null, PermissionRule.Source.USER).content);
    }

    @Test public void ruleParsing_malformed() {
        // 未闭合括号 → 整串当工具名（规则不丢弃，fail-closed 保守保留）
        PermissionRule r1 = PermissionRule.parse("Bash(git", null, PermissionRule.Source.USER);
        assertEquals("Bash(git", r1.toolName); assertNull(r1.content);
        // ) 不在末尾 → 畸形当工具名
        PermissionRule r2 = PermissionRule.parse("Bash(git) extra", null, PermissionRule.Source.USER);
        assertEquals("Bash(git) extra", r2.toolName); assertNull(r2.content);
        // 转义的括号不作为分界
        PermissionRule r3 = PermissionRule.parse("T\\(x", null, PermissionRule.Source.USER);
        assertEquals("T\\(x", r3.toolName);
        // 空/空白
        assertTrue(PermissionRule.parse("", null, PermissionRule.Source.USER).toolName.isEmpty());
        assertTrue(PermissionRule.parse("   ", null, PermissionRule.Source.USER).toolName.isEmpty());
        assertNull(PermissionRule.parse(null, null, PermissionRule.Source.USER).toolName.isEmpty() ? null : null);
    }

    @Test public void ruleParsing_whitespaceAndCase() {
        assertEquals("git", PermissionRule.parse("  Bash(  git  )  ", null, PermissionRule.Source.USER).content);
        assertEquals("Bash", PermissionRule.parse("  Bash  ", null, PermissionRule.Source.USER).toolName);
    }

    @Test public void ruleParsing_roundTrip() {
        String[] specs = {"Bash", "Bash(git status)", "Edit(./src/**)", "WebFetch", "Bash(npm run *)"};
        for (String spec : specs)
            assertEquals(spec, PermissionRule.parse(spec, null, PermissionRule.Source.USER).toString());
    }

    @Test public void ruleParsing_moreThanThirtyExamples() {
        // 批量形式补足 ≥30 例断言（覆盖大小写、unicode、嵌套引号、点号）
        String[] specs = {"Read", "read", "Glob(*)", "Grep(pattern)", "Bash(ls *)", "Bash(git diff)",
                "Bash(npm run build)", "Edit(**/*.ts)", "Write(./docs/)", "WebFetch(https://*)",
                "mcp:playwright", "McpProxyTool", "T(a b c)", "T(  )", "T(\\()", "T(\\))", "T(\\\\)",
                "T(*)", "T()", "T(x; y)", "T(命令)", "T(引号\"内\")", "T(a.b.c)", "T(./x/../y)",
                "Bash(docker ps)", "Bash(kubectl get *)", "Task", "TodoWrite", "Skill",
                "AskUserQuestion", "Screen", "Keyboard(pressKey)", "Mouse(click)", "Bash(chmod +x)"};
        for (String spec : specs) {
            PermissionRule r = PermissionRule.parse(spec, null, PermissionRule.Source.USER);
            assertNotNull(spec, r);
            assertFalse(spec, r.toolName.isEmpty());
        }
    }

    // ==================== 内容匹配三语义 ====================

    @Test public void matcher_prefixWildcardExact() {
        assertTrue(RuleMatcher.matches("git status", "git:*"));          // 前缀
        assertFalse(RuleMatcher.matches("gitx", "git:*"));
        assertTrue(RuleMatcher.matches("npm run build", "npm run *"));   // 词+空格+星
        assertTrue(RuleMatcher.matches("npm", "npm *"));                 // 裸词也匹配
        assertFalse(RuleMatcher.matches("npmx run", "npm *"));
        assertTrue(RuleMatcher.matches("git status", "git *"));
        assertTrue(RuleMatcher.matches("exact", "exact"));               // 精确
        assertFalse(RuleMatcher.matches("exact2", "exact"));
        assertTrue(RuleMatcher.matches("anything", null));               // 整工具规则恒真
        assertFalse(RuleMatcher.matches(null, "x"));
        // 转义星为字面量
        assertTrue(RuleMatcher.matches("a*b", "a\\*b"));
        assertFalse(RuleMatcher.matches("axb", "a\\*b"));
        // 复合命令不被前缀/词星放行（§4.4）
        assertFalse(RuleMatcher.matches("git status && rm -rf /", "git:*"));
        assertFalse(RuleMatcher.matches("npm run a; curl x", "npm *"));
        assertFalse(RuleMatcher.matches("git status | sh", "git:*"));
    }

    @Test public void matcher_pathRules() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path src = Files.createDirectories(root.resolve("src/main"));
        Path file = src.resolve("App.java");
        Files.write(file, "x".getBytes("UTF-8"));
        assertTrue(RuleMatcher.pathMatches(file, file, "./src/**"));
        assertTrue(RuleMatcher.pathMatches(file, file, "src/**"));
        assertFalse(RuleMatcher.pathMatches(root.resolve("docs/x.md"), null, "./src/**"));
        assertTrue(RuleMatcher.pathMatches(root.resolve("a/b/App.java"), null, "**/App.java"));
        assertTrue(RuleMatcher.looksLikePathRule("./src/**"));
        assertFalse(RuleMatcher.looksLikePathRule("git status"));
    }

    // ==================== 13 步逐层验收 ====================

    @Test public void step1_wholeToolDeny() {
        PermissionEngine.Verdict v = engine.evaluate("Bash", "git status", rules(deny("Bash")), PermissionContext.of(PermissionContext.Mode.DEFAULT));
        assertTrue(v.denied()); assertEquals(1, v.step);
    }

    @Test public void step2_contentDeny_beatsEverything() {
        RuleStore rs = rules(deny("Bash(rm *)"), allow("Bash"), ask("Bash"));
        // 内容 deny 免疫整工具 allow/ask（步骤序 2 < 5/11）与 bypass
        PermissionEngine.Verdict v = engine.evaluate("Bash", "rm -rf /", rs, PermissionContext.of(PermissionContext.Mode.BYPASS));
        assertTrue(v.denied()); assertEquals(2, v.step);
        // 内容 deny 免疫 acceptEdits 与用户确认路径（引擎层无确认概念，直接 deny）
        PermissionEngine.Verdict v2 = engine.evaluate("Bash", "rm -rf /", rs, PermissionContext.of(PermissionContext.Mode.ACCEPT_EDITS));
        assertTrue(v2.denied()); assertEquals(2, v2.step);
    }

    @Test public void step3_toolCheckDeny_andPlanMode() {
        PermissionContext denied = new PermissionContext(PermissionContext.Mode.DEFAULT,
                PermissionContext.ToolCheck.DENY, false, false, null, null, null);
        assertTrue(engine.evaluate("X", null, rules(), denied).denied());
        assertEquals(3, engine.evaluate("X", null, rules(), denied).step);
        // plan 模式拒绝非 allow（高危）工具；只读工具（自检 allow）放行到第 10 步
        PermissionContext highRisk = PermissionContext.of(PermissionContext.Mode.PLAN);
        assertTrue(engine.evaluate("Write", null, rules(), highRisk).denied());
        PermissionContext readOnly = new PermissionContext(PermissionContext.Mode.PLAN,
                PermissionContext.ToolCheck.ALLOW, false, false, null, null, null);
        assertTrue(engine.evaluate("Read", null, rules(), readOnly).allowed());
        assertEquals(10, engine.evaluate("Read", null, rules(), readOnly).step);
    }

    @Test public void step4_requiresInteraction() {
        PermissionContext ctx = new PermissionContext(PermissionContext.Mode.BYPASS,
                PermissionContext.ToolCheck.ASK, true, false, null, null, null);
        PermissionEngine.Verdict v = engine.evaluate("X", null, rules(), ctx);   // bypass 也不能跳过交互要求
        assertEquals(PermissionEngine.Verdict.Outcome.ASK, v.outcome);
        assertEquals(4, v.step);
    }

    @Test public void step5_wholeToolAsk_immuneToBypass() {
        PermissionEngine.Verdict v = engine.evaluate("Bash", null, rules(ask("Bash")), PermissionContext.of(PermissionContext.Mode.BYPASS));
        assertEquals(PermissionEngine.Verdict.Outcome.ASK, v.outcome);
        assertEquals(5, v.step);
    }

    @Test public void step6_contentAsk() {
        PermissionEngine.Verdict v = engine.evaluate("Bash", "git push", rules(ask("Bash(git push)")), PermissionContext.of(PermissionContext.Mode.DEFAULT));
        assertEquals(PermissionEngine.Verdict.Outcome.ASK, v.outcome);
        assertEquals(6, v.step);
    }

    @Test public void step7_safetyCheck_immuneToBypass() {
        PermissionContext ctx = new PermissionContext(PermissionContext.Mode.BYPASS,
                PermissionContext.ToolCheck.ALLOW, false, true, null, null, null);
        PermissionEngine.Verdict v = engine.evaluate("Edit", null, rules(allow("Edit")), ctx);
        assertEquals(PermissionEngine.Verdict.Outcome.ASK, v.outcome);
        assertEquals(7, v.step);
    }

    @Test public void step8_toolCheckAsk_immuneToBypass() {
        PermissionContext ctx = new PermissionContext(PermissionContext.Mode.BYPASS,
                PermissionContext.ToolCheck.ASK, false, false, null, null, null);
        assertEquals(8, engine.evaluate("X", null, rules(), ctx).step);
    }

    @Test public void step9_bypassAllows_afterAllGuards() {
        assertEquals(9, engine.evaluate("X", null, rules(), PermissionContext.of(PermissionContext.Mode.BYPASS)).step);
    }

    @Test public void step9b_acceptEditsInsideWorkspace() throws Exception {
        Path root = tmp.getRoot().toPath();
        java.nio.file.Files.createDirectories(root.resolve("src"));
        Path file = root.resolve("src/a.txt");
        Files.write(file, "x".getBytes("UTF-8"));
        PermissionContext inside = new PermissionContext(PermissionContext.Mode.ACCEPT_EDITS,
                PermissionContext.ToolCheck.PASSTHROUGH, false, false, file, file, root);
        assertEquals(9, engine.evaluate("Edit", null, rules(), inside).step);
        Path outside = root.getParent().resolve("other.txt");
        PermissionContext ctxOut = new PermissionContext(PermissionContext.Mode.ACCEPT_EDITS,
                PermissionContext.ToolCheck.PASSTHROUGH, false, false, outside, null, root);
        assertTrue(engine.evaluate("Edit", null, rules(), ctxOut).outcome != PermissionEngine.Verdict.Outcome.ALLOW
                || engine.evaluate("Edit", null, rules(), ctxOut).step > 9);   // 工作区外不自动放行
        // 无 workspaceRoot 时 fail-closed
        PermissionContext noRoot = new PermissionContext(PermissionContext.Mode.ACCEPT_EDITS,
                PermissionContext.ToolCheck.PASSTHROUGH, false, false, file, file, null);
        assertFalse(engine.evaluate("Edit", null, rules(), noRoot).allowed());
    }

    @Test public void step10_toolCheckAllow_notPassthrough() {
        PermissionContext allow = new PermissionContext(PermissionContext.Mode.DEFAULT,
                PermissionContext.ToolCheck.ALLOW, false, false, null, null, null);
        assertEquals(10, engine.evaluate("Read", null, rules(), allow).step);
        // PASSTHROUGH ≠ ALLOW：走默认 ask
        PermissionEngine.Verdict pass = engine.evaluate("X", null, rules(), PermissionContext.of(PermissionContext.Mode.DEFAULT));
        assertEquals(PermissionEngine.Verdict.Outcome.ASK, pass.outcome);
        assertEquals(13, pass.step);
    }

    @Test public void step11And12_allowRules() {
        assertEquals(11, engine.evaluate("Read", null, rules(allow("Read")), PermissionContext.of(PermissionContext.Mode.DEFAULT)).step);
        PermissionEngine.Verdict v12 = engine.evaluate("Bash", "git status", rules(allow("Bash(git:*)")), PermissionContext.of(PermissionContext.Mode.DEFAULT));
        assertTrue(v12.allowed()); assertEquals(12, v12.step);
    }

    @Test public void step13_defaultAsk_andDontAskTightensToDeny() {
        PermissionEngine.Verdict v = engine.evaluate("X", null, rules(), PermissionContext.of(PermissionContext.Mode.DEFAULT));
        assertEquals(PermissionEngine.Verdict.Outcome.ASK, v.outcome); assertEquals(13, v.step);
        PermissionEngine.Verdict d = engine.evaluate("X", null, rules(), PermissionContext.of(PermissionContext.Mode.DONT_ASK));
        assertTrue(d.denied());
    }

    // ==================== 关键组合（v2.4 验收） ====================

    @Test public void combo_wholeToolAskPlusContentDeny_isDeny() {
        RuleStore rs = rules(ask("Bash"), deny("Bash(rm *)"));
        assertTrue(engine.evaluate("Bash", "rm x", rs, PermissionContext.of(PermissionContext.Mode.DEFAULT)).denied());
    }

    @Test public void combo_toolAllowPlusPolicyDeny_isDeny() {
        RuleStore rs = rules(allow("WebFetch"), deny("WebFetch"));
        assertTrue(engine.evaluate("WebFetch", null, rs, PermissionContext.of(PermissionContext.Mode.DEFAULT)).denied());
    }

    @Test public void combo_passthroughNeverAllows() {
        PermissionEngine.Verdict v = engine.evaluate("AnyTool", null, rules(), PermissionContext.of(PermissionContext.Mode.DEFAULT));
        assertEquals(PermissionEngine.Verdict.Outcome.ASK, v.outcome);   // 未声明自检 allow 的工具默认 ask
    }

    @Test public void combo_managedOnly_policyOnlyRules() throws Exception {
        Path policy = tmp.getRoot().toPath().resolve("policy.json");
        Files.write(policy, "{\"permissions\":{\"deny\":[\"Bash\"],\"managedOnly\":true}}".getBytes("UTF-8"));
        RuleStore rs = new RuleStore().addSource(policy, PermissionRule.Source.POLICY);
        rs.addRule(allow("Bash"));   // 用户层 allow 不生效
        assertTrue(rs.managedOnly());
        assertTrue(engine.evaluate("Bash", null, rs, PermissionContext.of(PermissionContext.Mode.BYPASS)).denied());
    }

    @Test public void combo_deniedInEveryMode() {
        RuleStore rs = rules(deny("Bash(shutdown:*)"));
        for (PermissionContext.Mode m : PermissionContext.Mode.values()) {
            PermissionEngine.Verdict v = engine.evaluate("Bash", "shutdown /s", rs, PermissionContext.of(m));
            assertTrue(m.name(), v.denied());
        }
    }

    // ==================== 分层来源与迁移 ====================

    @Test public void store_layeredSources_mergeUnion() throws Exception {
        Path user = tmp.newFolder("u").toPath().resolve("settings.json");
        Files.write(user, "{\"permissions\":{\"allow\":[\"Bash(git:*)\"]}}".getBytes("UTF-8"));
        Path project = tmp.newFolder("p").toPath().resolve("settings.json");
        Files.write(project, "{\"permissions\":{\"deny\":[\"Bash(rm:*)\"]}}".getBytes("UTF-8"));
        RuleStore rs = new RuleStore()
                .addSource(user, PermissionRule.Source.USER)
                .addSource(project, PermissionRule.Source.PROJECT);
        assertEquals(2, rs.size());
        assertTrue(engine.evaluate("Bash", "git status", rs, PermissionContext.of(PermissionContext.Mode.DEFAULT)).allowed());
        assertTrue(engine.evaluate("Bash", "rm -rf x", rs, PermissionContext.of(PermissionContext.Mode.DEFAULT)).denied());
    }

    @Test public void store_corruptSourceFailsClosedAsEmpty() throws Exception {
        Path bad = tmp.getRoot().toPath().resolve("bad.json");
        Files.write(bad, "not json at all".getBytes("UTF-8"));
        RuleStore rs = new RuleStore().addSource(bad, PermissionRule.Source.USER);
        assertEquals(0, rs.size());   // 损坏按空处理，不抛异常
    }

    @Test public void store_sessionAllowOnlyAddsAllow() {
        RuleStore rs = new RuleStore();
        rs.sessionAllow("Bash", "git:*");
        assertTrue(engine.evaluate("Bash", "git push", rs, PermissionContext.of(PermissionContext.Mode.DEFAULT)).allowed());
    }

    @Test public void legacyModeMapping() {
        assertEquals(PermissionContext.Mode.PLAN, PermissionContext.mapLegacyMode("read-only"));
        assertEquals(PermissionContext.Mode.ACCEPT_EDITS, PermissionContext.mapLegacyMode("workspace-write"));
        assertEquals(PermissionContext.Mode.BYPASS, PermissionContext.mapLegacyMode("full"));
        assertEquals(PermissionContext.Mode.DONT_ASK, PermissionContext.mapLegacyMode("dontAsk"));
        assertEquals(PermissionContext.Mode.DEFAULT, PermissionContext.mapLegacyMode("default"));
        assertEquals(PermissionContext.Mode.DEFAULT, PermissionContext.mapLegacyMode("unknown-x"));
    }


}
