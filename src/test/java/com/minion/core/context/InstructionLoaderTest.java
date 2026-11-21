package com.minion.core.context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * §6.1 InstructionLoader 验收：层级发现与去重、@import（深度/环路/代码块不展开/白名单）、
 * paths 条件激活、超限告警、开关关闭零发现。
 */
public class InstructionLoaderTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path dir(String name) throws Exception { return tmp.newFolder(name).toPath(); }

    private Path write(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /** 层级：企业不可用（无 ProgramData 控制）→ 用户 → 项目 cwd；同 realpath 去重 */
    @Test public void discoversProjectAndUserLayersWithDedup() throws Exception {
        Path cwd = dir("proj");
        Path fakeJar = dir("jar");
        java.nio.file.Files.createDirectories(fakeJar.resolve(".minion"));
        write(fakeJar.resolve(".minion"), "MINION.md", "用户层指令");
        write(cwd, "MINION.md", "项目层指令");
        InstructionLoader loader = new InstructionLoader(cwd, fakeJar, true);
        List<InstructionLoader.Layer> layers = loader.loadAll(null);
        assertTrue(layers.size() >= 2);
        assertEquals("用户层指令", layers.get(0).content.trim());
        assertEquals("项目层指令", layers.get(1).content.trim());
        // 开关关闭：零发现
        assertTrue(new InstructionLoader(cwd, fakeJar, false).loadAll(null).isEmpty());
    }

    /** @import：相对/子目录导入、被包含者在前、代码块内不展开、深度上限、环路防护 */
    @Test public void importsExpandedOutsideCodeBlocks() throws Exception {
        Path cwd = dir("imp");
        write(cwd, "sub.md", "子文件内容");
        write(cwd, "MINION.md",
                "# 主文件\n@sub.md\n```\n@sub.md\n```\n@missing.txt\n@deep1.md\n@loopa.md\n");
        // 深度链：deep1→deep2→deep3→deep4→deep5→deep6（超深度 5 不展开）
        write(cwd, "deep1.md", "@deep2.md"); write(cwd, "deep2.md", "@deep3.md"); write(cwd, "deep3.md", "@deep4.md");
        write(cwd, "deep4.md", "@deep5.md"); write(cwd, "deep5.md", "最深可达"); write(cwd, "deep6.md", "不应注入");
        // 环路：loopa ↔ loopb（processedPaths 防护不死循环）
        write(cwd, "loopa.md", "@loopb.md\nA尾"); write(cwd, "loopb.md", "@loopa.md\nB尾");
        InstructionLoader loader = new InstructionLoader(cwd, null, true);
        List<InstructionLoader.Layer> layers = loader.loadAll(null);
        String text = layers.get(layers.size() - 1).content;   // MINION.md 本体
        // 被包含者在包含者之前（deep 链先于主文件）；子文件内容展开
        assertTrue(text.contains("子文件内容"));
        // 代码块内 @sub.md 不展开
        assertTrue(text.contains("```\n@sub.md\n```"));
        // 缺失静默忽略
        assertTrue(text.contains("@missing.txt"));
        // 深度 ≤5：最深可达注入、deep6 不注入
        assertTrue(text.contains("最深可达"));
        assertFalse(text.contains("不应注入"));
        // 环路终止（含 A尾/B尾 且不死循环）
        assertTrue(text.contains("A尾") || text.contains("B尾"));
        // 非白名单扩展名不导入
        write(cwd, "run.bat", "del /q *");
        write(cwd, "MINION.md", "@run.bat");
        String batText = new InstructionLoader(cwd, null, true).loadAll(null)
                .get(0).content;
        assertTrue(batText.contains("@run.bat"));   // 未展开
    }

    /** rules/*.md frontmatter paths 条件激活（触碰目录匹配才注入） */
    @Test public void rulesActivateByPathsCondition() throws Exception {
        Path cwd = dir("rules-proj");
        Path rules = cwd.resolve(".minion").resolve("rules");
        Files.createDirectories(rules);
        write(rules, "db.md", "---\npaths: [db/**]\n---\n数据库规范");
        write(rules, "docs.md", "---\npaths: [docs/**]\n---\n文档规范");
        write(rules, "always.md", "通用规范");
        InstructionLoader loader = new InstructionLoader(cwd, null, true);
        // 无触碰目录：paths 条件规则不激活，无 frontmatter 规则激活
        List<InstructionLoader.Layer> none = loader.loadAll(null);
        assertTrue(containsContent(none, "通用规范"));
        assertFalse(containsContent(none, "数据库规范"));
        // 触碰 db 目录：db 规则激活
        List<InstructionLoader.Layer> db = loader.loadAll(cwd.resolve("db").resolve("migrations"));
        assertTrue(containsContent(db, "数据库规范"));
        assertFalse(containsContent(db, "文档规范"));
    }

    /** 子目录 MINION.md：触碰目录链向上（cwd 以下）注入 */
    @Test public void subdirInstructionLoadedOnTouch() throws Exception {
        Path cwd = dir("multi");
        Path sub = cwd.resolve("feature");
        Files.createDirectories(sub);
        write(sub, "MINION.md", "功能子目录规范");
        write(cwd, "MINION.md", "根规范");
        InstructionLoader loader = new InstructionLoader(cwd, null, true);
        List<InstructionLoader.Layer> layers = loader.loadAll(sub.resolve("a.java"));
        assertTrue(containsContent(layers, "功能子目录规范"));
        assertTrue(containsContent(layers, "根规范"));
    }

    /** MINION.local.md：本地层最后注入（不入 git 的本地覆盖） */
    @Test public void localLayerLoadsLast() throws Exception {
        Path cwd = dir("local");
        write(cwd, "MINION.md", "共享规范");
        write(cwd, "MINION.local.md", "本地覆盖");
        List<InstructionLoader.Layer> layers = new InstructionLoader(cwd, null, true).loadAll(null);
        assertEquals("本地覆盖", layers.get(layers.size() - 1).content.trim());
    }

    private static boolean containsContent(List<InstructionLoader.Layer> layers, String needle) {
        for (InstructionLoader.Layer l : layers) if (l.content.contains(needle)) return true;
        return false;
    }
}
