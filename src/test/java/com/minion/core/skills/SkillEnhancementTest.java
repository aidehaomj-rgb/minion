package com.minion.core.skills;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * v2.4 §7.1 Skills 增强验收（解析/展开/注入门控/路径激活；渐进加载与优先级由既有
 * SkillSet/SkillResidency 测试覆盖，fork 隔离由 AgentLoop 级测试覆盖）。
 */
public class SkillEnhancementTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** frontmatter 全字段解析（§7.1 验收第 1 项） */
    @Test public void frontmatterFullFieldParsing() throws Exception {
        Path dir = tmp.newFolder("pdf-report").toPath();
        Path md = dir.resolve("SKILL.md");
        Files.write(md, ("---\n"
                + "name: pdf-report\ndescription: 生成 PDF 报告\nwhen_to_use: 用户要 PDF 交付时\n"
                + "allowed-tools:\n  - Read\n  - Write\nargument-hint: \"[主题] 输出路径\"\n"
                + "arguments:\n  topic: 报告主题\n  style: 风格\nmodel: inherit\nversion: '1.2'\n"
                + "disable-model-invocation: true\nuser-invocable: false\n"
                + "context: fork\nagent: reporter\npaths:\n  - '**/*.pdf'\n  - 'docs/**'\n"
                + "---\n正文方法说明").getBytes(StandardCharsets.UTF_8));
        Skill s = SkillManager.parse(md, "fallback", Skill.SOURCE_PROJECT);
        assertEquals("pdf-report", s.name);
        assertEquals("生成 PDF 报告", s.description);
        assertEquals("用户要 PDF 交付时", s.whenToUse);
        assertEquals(Arrays.asList("Read", "Write"), s.allowedTools);
        assertEquals("[主题] 输出路径", s.argumentHint);
        assertEquals("报告主题", s.arguments.get("topic"));
        assertEquals("风格", s.arguments.get("style"));
        assertEquals("inherit", s.model);
        assertEquals("1.2", s.version);
        assertTrue(s.disableModelInvocation);
        assertFalse(s.userInvocable);
        assertTrue(s.isFork());
        assertEquals("reporter", s.agent);
        assertEquals(2, s.paths.size());
        assertTrue(s.isConditional());
        // hint 含 when_to_use（一级清单 token 估算口径）
        assertTrue(s.hint().contains("用户要 PDF 交付时"));
    }

    /** 缺省 frontmatter：V68 兼容（无扩展语义，inline/可调用/无条件） */
    @Test public void minimalFrontmatterKeepsV68Defaults() throws Exception {
        Path dir = tmp.newFolder("plain").toPath();
        Path md = dir.resolve("SKILL.md");
        Files.write(md, "---\nname: plain\ndescription: 简单技能\n---\n内容".getBytes(StandardCharsets.UTF_8));
        Skill s = SkillManager.parse(md, "plain", Skill.SOURCE_GLOBAL);
        assertFalse(s.isFork());
        assertFalse(s.isConditional());
        assertTrue(s.userInvocable);
        assertFalse(s.disableModelInvocation);
        assertTrue(s.allowedTools.isEmpty());
        assertTrue(s.shellInjectionAllowed());
    }

    /** $ARGUMENTS/命名参数/${SKILL_DIR}/${SESSION_ID} 替换（§7.1 验收第 4 项） */
    @Test public void variableExpansion() {
        Path skillDir = tmp.getRoot().toPath().resolve("my-skill");
        Map<String, String> named = new LinkedHashMap<String, String>();
        named.put("topic", "季度汇总");
        String body = "主题: $ARGUMENTS\n命名: ${topic}\n基目录: ${SKILL_DIR}\n会话: ${SESSION_ID}\n命令: !`git log -1`";
        String expanded = SkillExpansion.expand(body, "写一份", named, skillDir, "sess-42");
        assertEquals("主题: 写一份\n命名: 季度汇总\n基目录: " + skillDir + "\n会话: sess-42\n命令: !`git log -1`",
                expanded);   // 内联 shell 标记原样保留（提取阶段统一处理）
    }

    /** 内联 shell：提取/回填 + 门控语义（插件来源禁、未装配拒） */
    @Test public void shellInjectionExtractionAndGating() throws Exception {
        String body = "版本: !`git rev-parse --short HEAD` 然后用户: !`whoami`";
        List<String> commands = SkillExpansion.extractShellInjections(body);
        assertEquals(Arrays.asList("git rev-parse --short HEAD", "whoami"), commands);
        String substituted = SkillExpansion.substituteShellOutputs(body,
                Arrays.asList("abc1234", "corpus"));
        assertEquals("版本: abc1234 然后用户: corpus", substituted);
        // 无注入 → 原样
        assertTrue(SkillExpansion.extractShellInjections("普通正文").isEmpty());
        assertEquals("普通正文", SkillExpansion.substituteShellOutputs("普通正文",
                Arrays.asList("x")));
        // 插件来源技能：shellInjectionAllowed=false（SkillTool 侧拒绝展开）
        Skill pluginSkill = new Skill("p", "d", "b", "f", Skill.SOURCE_PLUGIN);
        assertFalse(pluginSkill.shellInjectionAllowed());
    }

    /** paths 条件激活（§7.1 验收第 3 项）：触碰匹配文件激活、未匹配不激活、幂等 */
    @Test public void pathsConditionalActivation() {
        Skill pdfSkill = new Skill("pdf", "PDF 处理", "方法", "f", Skill.SOURCE_PROJECT,
                null, null, null, null, null, null, false, true, "inline", null,
                Arrays.asList("**/*.pdf", "docs/**"));
        Skill other = new Skill("plain", "普通", "m", "f", Skill.SOURCE_GLOBAL);
        SkillPathActivation activation = new SkillPathActivation(Arrays.asList(pdfSkill, other));

        assertTrue("首次触碰激活", activation.onFileTouched("D:/ws/report/年报.PDF".replace("PDF", "pdf"))
                .size() == 1);
        assertTrue("已激活技能进动态表", activation.dynamicSkills().stream()
                .anyMatch(s -> s.name.equals("pdf")));
        assertTrue("重复触碰幂等（不重复激活）", activation.onFileTouched("D:/ws/report/x.pdf").isEmpty());

        assertTrue("docs/ 前缀激活", new SkillPathActivation(Arrays.asList(pdfSkill))
                .onFileTouched("D:/ws/docs/deep/guide.md").size() == 1);
        assertTrue("无关文件不激活", new SkillPathActivation(Arrays.asList(pdfSkill))
                .onFileTouched("D:/ws/src/Main.java").isEmpty());
        // 裸名 glob：匹配任意目录段
        Skill nameSkill = new Skill("n", "d", "m", "f", Skill.SOURCE_PROJECT,
                null, null, null, null, null, null, false, true, "inline", null,
                Arrays.asList("dockerfile*"));
        SkillPathActivation byName = new SkillPathActivation(Arrays.asList(nameSkill));
        assertEquals(1, byName.onFileTouched("D:/ws/deep/build/Dockerfile.prod").size());
    }

    /** 嵌套发现：被操作文件目录向上的 .minion/skills（gitignored 目录停止） */
    @Test public void nestedSkillsDiscovery() throws Exception {
        Path root = tmp.newFolder().toPath();
        Path nested = root.resolve("app/pkg/.minion/skills/deep-skill");
        Files.createDirectories(nested);
        Files.write(nested.resolve("SKILL.md"),
                "---\nname: deep-skill\ndescription: 嵌套技能\n---\n内容".getBytes(StandardCharsets.UTF_8));
        List<Skill> found = SkillPathActivation.discoverNested(root.resolve("app/pkg/src/main.py"));
        assertEquals(1, found.size());
        assertEquals("deep-skill", found.get(0).name);
        // target/ 下（gitignored 类）停止向上发现
        assertTrue(SkillPathActivation.discoverNested(root.resolve("app/target/gen/x.java")).isEmpty());
    }
}
