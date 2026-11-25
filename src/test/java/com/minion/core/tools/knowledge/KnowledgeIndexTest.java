package com.minion.core.tools.knowledge;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 第三批 B1：BM25 索引化检索。核心纪律：任何异常回退旧全量扫描且输出逐字一致；共享目录零写入。 */
public class KnowledgeIndexTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private KnowledgeTool tool(Path workspace, Config config) {
        return new KnowledgeTool(new Workspace(workspace.toString()), config);
    }

    private static JsonObject search(String query) {
        JsonObject o = new JsonObject();
        o.addProperty("action", "search");
        o.addProperty("query", query);
        return o;
    }

    private static Path note(Path root, String name, String content) throws Exception {
        Files.createDirectories(root);
        Path file = root.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Path kbRoot(Path workspace) {
        return workspace.resolve(".minion").resolve("knowledge");
    }

    /** 短文档整串精确命中（+20 bonus）排在长文档堆词之前 */
    @Test public void shortExactHitBeatsLongKeywordStuffing() throws Exception {
        Path workspace = tmp.newFolder("ws-short").toPath();
        Path root = kbRoot(workspace);
        note(root, "short.md", "# 折扣规则\n\n华东折扣上限是百分之十。\n");
        StringBuilder stuff = new StringBuilder("# 堆词长文\n\n");
        for (int i = 0; i < 200; i++) stuff.append("华东 区域 政策 折扣 无关 填充 文本 第").append(i).append("段\n");
        note(root, "long.md", stuff.toString());
        ToolResult result = tool(workspace, null).execute(search("华东折扣"));
        assertTrue(result.output, result.ok);
        int shortAt = result.output.indexOf("[short.md]");
        int longAt = result.output.indexOf("[long.md]");
        assertTrue("短文档应命中: " + result.output, shortAt >= 0);
        assertTrue("整串精确命中的短文档应排第一: " + result.output, shortAt < longAt || longAt < 0);
    }

    /** 中文 bigram 召回：查询与正文共享相邻双字即可命中 */
    @Test public void chineseBigramRecall() throws Exception {
        Path workspace = tmp.newFolder("ws-bigram").toPath();
        note(kbRoot(workspace), "tax.md", "进口税则第一版说明。");
        ToolResult result = tool(workspace, null).execute(search("税则"));
        assertTrue(result.output, result.output.contains("tax.md"));
        assertTrue(result.output.contains("第一版"));
    }

    /** mtime 变化 → 增量重读变更文件，新内容可召回 */
    @Test public void mtimeInvalidationRebuildsChangedDoc() throws Exception {
        Path workspace = tmp.newFolder("ws-mtime").toPath();
        Path file = note(kbRoot(workspace), "evolving.md", "进口税则第一版");
        KnowledgeTool tool = tool(workspace, null);
        assertTrue(tool.execute(search("税则")).output.contains("第一版"));
        Files.write(file, "进口税则第二版".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        ToolResult again = tool.execute(search("税则"));
        assertTrue(again.output, again.output.contains("第二版"));
        assertFalse(again.output.contains("第一版"));
    }

    /** 索引 JSON 损坏 → 本次回退旧路径且输出与旧实现逐字一致；损坏文件备份 .bak */
    @Test public void corruptIndexFallsBackVerbatim() throws Exception {
        Path workspace = tmp.newFolder("ws-corrupt").toPath();
        note(kbRoot(workspace), "a.md", "华东折扣规则一。");
        note(kbRoot(workspace), "b.md", "无关内容。");
        Config config = Config.load(tmp.newFolder("cfg-corrupt").toPath());
        KnowledgeTool indexed = tool(workspace, config);
        assertTrue(indexed.execute(search("华东折扣")).ok);   // 先建一份正常索引
        Path indexFile = workspace.resolve(".session").resolve("kb-index.json");
        assertTrue(Files.exists(indexFile));
        Files.write(indexFile, "{损坏的JSON!!!".getBytes(StandardCharsets.UTF_8));
        config.set("knowledge.search.index.enabled", "false");
        String legacy = tool(workspace, config).execute(search("华东折扣")).output;
        config.set("knowledge.search.index.enabled", "true");
        String fallback = tool(workspace, config).execute(search("华东折扣")).output;
        assertEquals("损坏回退输出必须与旧实现逐字一致", legacy, fallback);
        assertTrue("损坏索引应备份 .bak", Files.exists(indexFile.resolveSibling("kb-index.json.bak")));
    }

    /** 共享知识库只读：索引化检索绝不向共享目录写入任何文件 */
    @Test public void sharedDirectoryZeroWrites() throws Exception {
        Path shared = tmp.newFolder("shared-ro").toPath();
        note(shared.resolve("政策"), "shared-note.md", "共享税则条目。");
        Path workspace = tmp.newFolder("ws-shared").toPath();
        note(kbRoot(workspace), "local.md", "项目本地条目。");
        Config config = Config.load(tmp.newFolder("cfg-shared").toPath());
        config.set("knowledge.shared.dir", shared.toString());
        List<Path> before = walk(shared);
        KnowledgeTool tool = tool(workspace, config);
        assertTrue(tool.execute(search("税则")).output.contains("shared/政策/shared-note.md"));
        assertTrue(tool.execute(search("本地")).ok);
        assertEquals("共享目录文件清单必须零变化", before, walk(shared));
        assertTrue("索引文件只能在工作区内",
                Files.exists(workspace.resolve(".session").resolve("kb-index.json")));
    }

    private static List<Path> walk(Path root) throws Exception {
        List<Path> out = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            s.forEach(out::add);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 规模护栏：>5000 文件 → IndexTooLargeException → 回退旧扫描仍可用 */
    @Test public void tooManyFilesFallsBackToScan() throws Exception {
        Path workspace = tmp.newFolder("ws-huge").toPath();
        Path root = kbRoot(workspace);
        Files.createDirectories(root);
        for (int i = 0; i <= KnowledgeIndex.MAX_FILES; i++) {
            Files.write(root.resolve("n" + i + ".md"),
                    (i == 0 ? "唯一命中目标文档" : "填充").getBytes(StandardCharsets.UTF_8));
        }
        ToolResult result = tool(workspace, null).execute(search("唯一命中"));
        assertTrue(result.output, result.ok);
        assertTrue(result.output.contains("n0.md"));
        assertFalse("超限不得写索引", Files.exists(workspace.resolve(".session").resolve("kb-index.json")));
    }

    /** 索引持久化回环：二次加载条目数一致 */
    @Test public void indexPersistenceRoundtrip() throws Exception {
        Path workspace = tmp.newFolder("ws-persist").toPath();
        note(kbRoot(workspace), "p1.md", "持久化条目一。");
        note(kbRoot(workspace), "p2.md", "持久化条目二。");
        assertTrue(tool(workspace, null).execute(search("持久化")).ok);
        Path indexFile = workspace.resolve(".session").resolve("kb-index.json");
        KnowledgeIndex reloaded = KnowledgeIndex.load(indexFile);
        assertEquals(2, reloaded.docCount());
    }

    /** 大 KB 计时留证：索引化二次检索应不慢于全量扫描（宽松断言，计时打印供验收留证） */
    @Test public void largeKbTimingEvidence() throws Exception {
        Path workspace = tmp.newFolder("ws-timing").toPath();
        Path root = kbRoot(workspace);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) body.append("段落").append(i).append(" 业务 规则 说明 文本 内容。\n");
        for (int i = 0; i < 300; i++) {
            note(root, "doc" + i + ".md", "# 文档" + i + "\n\n" + body
                    + (i == 150 ? "特殊标记词唯一出现。\n" : ""));
        }
        Config config = Config.load(tmp.newFolder("cfg-timing").toPath());
        KnowledgeTool indexed = tool(workspace, config);
        indexed.execute(search("特殊标记词"));   // 首次建索引不计入对比
        long t0 = System.nanoTime();
        ToolResult viaIndex = indexed.execute(search("特殊标记词"));
        long indexNanos = System.nanoTime() - t0;
        config.set("knowledge.search.index.enabled", "false");
        KnowledgeTool legacy = tool(workspace, config);
        t0 = System.nanoTime();
        ToolResult viaScan = legacy.execute(search("特殊标记词"));
        long scanNanos = System.nanoTime() - t0;
        System.out.println("[B1 计时留证] 300 文档：索引检索=" + indexNanos / 1_000_000 + "ms 全量扫描="
                + scanNanos / 1_000_000 + "ms");
        assertTrue(viaIndex.output.contains("doc150.md"));
        assertTrue(viaScan.output.contains("doc150.md"));
        assertTrue("索引检索不应显著慢于全量扫描（2 倍容差）", indexNanos <= scanNanos * 2);
    }
}