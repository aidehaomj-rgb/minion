package com.minion.core.agent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** §7.3 自定义子代理定义验收：frontmatter 解析、层级就近优先、非法跳过、find 匹配 */
public class AgentDefinitionsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private void agent(Path dir, String name, String frontmatter) throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve(name + ".md"),
                ("---\n" + frontmatter + "\n---\n\n正文指导").getBytes(StandardCharsets.UTF_8));
    }

    @Test public void parsesFrontmatterAndFinds() throws Exception {
        Path root = tmp.newFolder().toPath();
        agent(root.resolve(".minion").resolve("agents"), "code-reviewer",
                "name: code-reviewer\ndescription: 代码审查\ntools: Read,Grep,Glob,CodeIndex\nmodel: deepseek");
        AgentDefinitions.Definition d = AgentDefinitions.find(root.toString(), "code-reviewer");
        assertNotNull(d);
        assertEquals("代码审查", d.description);
        assertEquals("Read,Grep,Glob,CodeIndex", d.tools);
        assertEquals("deepseek", d.model);
        // 大小写不敏感
        assertNotNull(AgentDefinitions.find(root.toString(), "Code-Reviewer"));
        assertNull(AgentDefinitions.find(root.toString(), "ghost"));
    }

    /** 层级就近优先：子目录同名定义覆盖父目录 */
    @Test public void nearestDefinitionWins() throws Exception {
        Path root = tmp.newFolder().toPath();
        Path sub = root.resolve("feature");
        Files.createDirectories(sub);
        agent(root.resolve(".minion").resolve("agents"), "tester", "name: tester\ndescription: 根级");
        agent(sub.resolve(".minion").resolve("agents"), "tester", "name: tester\ndescription: 子级");
        AgentDefinitions.Definition d = AgentDefinitions.find(sub.toString(), "tester");
        assertEquals("子级", d.description);
    }

    /** 缺 name / 非法 name / 无 frontmatter：跳过不炸 */
    @Test public void invalidDefinitionsSkipped() throws Exception {
        Path root = tmp.newFolder().toPath();
        Path agents = root.resolve(".minion").resolve("agents");
        Files.createDirectories(agents);
        Files.write(agents.resolve("noname.md"), "---\ndescription: 缺名\n---\n".getBytes(StandardCharsets.UTF_8));
        Files.write(agents.resolve("bad@name.md"), "---\nname: bad@name\n---\n".getBytes(StandardCharsets.UTF_8));
        Files.write(agents.resolve("nofm.md"), "没有 frontmatter".getBytes(StandardCharsets.UTF_8));
        assertTrue(AgentDefinitions.loadAll(root.toString()).isEmpty());
    }
}
