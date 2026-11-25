package com.minion.core.agent;

import com.minion.core.llm.ToolCall;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class GeneratedArtifactCollectorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private ToolCall call(String name, String args) {
        ToolCall c = new ToolCall(); c.id = "tool"; c.name = name; c.arguments = args; return c;
    }
    private Path file(Path root, String name) throws Exception {
        Path p = root.resolve(name); Files.write(p, "real file".getBytes(StandardCharsets.UTF_8)); return p;
    }
    private GeneratedArtifactCollector collector(Path root) {
        return new GeneratedArtifactCollector(p -> PathsGuard.inside(root.toString(), p));
    }
    private List<GeneratedArtifact> collect(GeneratedArtifactCollector c, Path cwd, String tool, String args, ToolResult r) {
        return c.fromTool(call(tool, args), r, cwd, "task", 3);
    }

    @Test public void successfulKnownOutputsOnlyAndNoReadOrDirectoryMisclassification() throws Exception {
        Path root = temp.newFolder().toPath(), p = file(root, "result.txt");
        GeneratedArtifactCollector c = collector(root);
        for (String tool : new String[]{"Write", "Edit"}) {
            List<GeneratedArtifact> a = collect(c, root, tool, "{\"path\":\"result.txt\"}", ToolResult.success("ok"));
            assertEquals(1, a.size()); assertEquals(p.toRealPath().toString(), a.get(0).path);
            assertEquals(GeneratedArtifact.DELIVERABLE, a.get(0).kind); assertEquals(tool, a.get(0).sourceTool);
            assertEquals(Long.valueOf(3), a.get(0).revision); assertEquals("task", a.get(0).taskId);
            assertEquals(Files.size(p), a.get(0).size);
            assertTrue(collect(c, root, tool, "{\"path\":\"result.txt\"}", ToolResult.error("failed")).isEmpty());
        }
        for (String[] spec : new String[][]{{"Document","docx_report"},{"Excel","write"},{"Artwork","render"},{"Database","export"}}) {
            assertEquals(1, collect(c, root, spec[0], "{\"action\":\""+spec[1]+"\",\"outputPath\":\"result.txt\"}", ToolResult.success("ok")).size());
        }
        assertEquals(1, collect(c, root, "Artwork", "{\"action\":\"template\",\"path\":\"result.txt\"}", ToolResult.success("ok")).size());
        assertTrue(collect(c, root, "Read", "{\"path\":\"result.txt\"}", ToolResult.success("ok")).isEmpty());
        assertTrue(collect(c, root, "Excel", "{\"action\":\"read\",\"outputPath\":\"result.txt\"}", ToolResult.success("ok")).isEmpty());
        assertTrue(collect(c, root, "Document", "{\"action\":\"pdf_split\",\"outputPath\":\".\"}", ToolResult.success("ok")).isEmpty());
        assertTrue(collect(c, root, "Write", "{\"path\":\"missing.txt\"}", ToolResult.success("ok")).isEmpty());
        assertTrue(collect(c, root, "Write", "{\"path\":{}}", ToolResult.success("ok")).isEmpty());
    }

    @Test public void traversalSymlinkAndExtraAuthorizationUseRealFiles() throws Exception {
        Path root = temp.newFolder("root").toPath(), outside = temp.newFolder("outside").toPath();
        Path p = file(outside, "outside.txt"); GeneratedArtifactCollector c = collector(root);
        assertTrue(collect(c, root, "Write", "{\"path\":\"../outside/outside.txt\"}", ToolResult.success("ok")).isEmpty());
        assertTrue(c.references("[outside](<"+p.toUri()+">)", root, "task", 1).isEmpty());
        Workspace ws = new Workspace(root.toString()); ws.setExtraAllowedDirs(java.util.Collections.singletonList(outside.toString()));
        GeneratedArtifactCollector extra = new GeneratedArtifactCollector(x -> PathsGuard.inside(ws.workDir(),x)||PathsGuard.insideExtra(ws,x));
        assertEquals(1, extra.references("[allowed](<"+p.toUri()+">)", root, "task", 1).size());
        try { Files.createSymbolicLink(root.resolve("escape"), outside); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) { return; }
        assertTrue(collect(c, root, "Write", "{\"path\":\"escape/outside.txt\"}", ToolResult.success("ok")).isEmpty());
    }

    @Test public void shellReceiptsRequireSuccessfulStructuredArtifactsOrCompleteMarkdown() throws Exception {
        Path root = temp.newFolder().toPath(); file(root,"a (final).txt"); file(root,"b.txt");
        Path report = file(root,"最终报告 v2.docx");
        String reportPath = report.toRealPath().toString();
        String savedPath = report.toAbsolutePath().normalize().toString();
        GeneratedArtifactCollector c = collector(root);
        String stdout = "saved "+savedPath+" paragraphs 199 tables 10\n"
                +"progress\n{\"artifacts\":[{\"path\":\"b.txt\",\"title\":\"数据\"},\"missing.txt\"]}\n"
                +"[成品](a (final).txt)\n[同一文件](b.txt)\n[不存在](missing.txt)\n[网页](https://example.com/b.txt)\n"
                +"[未结束](b.txt\n[被截断](b.txt";
        for (String tool : new String[]{"Python","Shell","Bash"}) {
            assertEquals("saved receipt: "+savedPath,1,
                    collect(c, root, tool, "{}", ToolResult.success("saved "+savedPath+" paragraphs 199")).size());
            List<GeneratedArtifact> a = collect(c, root, tool, "{\"outputPath\":\"b.txt\"}", ToolResult.success(stdout));
            assertEquals("stdout="+stdout+" paths="+a.stream().map(x->x.path).collect(java.util.stream.Collectors.toList()),
                    3,a.size()); assertTrue(a.stream().allMatch(x -> GeneratedArtifact.DELIVERABLE.equals(x.kind)));
            assertTrue(a.stream().anyMatch(x -> reportPath.equals(x.path)));
            assertTrue(collect(c,root,tool,"{}",ToolResult.error(stdout)).isEmpty());
            assertTrue(collect(c,root,tool,"{\"outputPath\":\"b.txt\",\"code\":\"b.txt\"}",ToolResult.success("b.txt")).isEmpty());
            assertTrue(collect(c,root,tool,"{}",ToolResult.success("{\"path\":\"b.txt\"}")).isEmpty());
            assertTrue(collect(c,root,tool,"{}",ToolResult.success("[bad](b.txt")).isEmpty());
        }
    }

    @Test public void uriLinksAreReferencesNotGeneratedAndCollectorIsBounded() throws Exception {
        Path root = temp.newFolder().toPath(), p = file(root,"中文 空格 (v1).txt");
        GeneratedArtifactCollector c = collector(root);
        List<GeneratedArtifact> a = c.references("[成品](<"+p.toUri().toASCIIString()+">)",root,"task",9);
        assertEquals(1,a.size()); assertEquals(p.toRealPath().toString(),a.get(0).path);
        assertEquals(GeneratedArtifact.REFERENCE,a.get(0).kind); assertEquals("assistant",a.get(0).sourceTool);
        assertEquals(1,c.references("[带\\[方括号\\]](<"+p.toUri().toASCIIString()+"> \"文件标题\")",root,"task",9).size());
        assertEquals(1,c.references("[文件](<"+p.toUri().toASCIIString()+"> '文件标题')",root,"task",9).size());
        StringBuilder json = new StringBuilder("{\"artifacts\":[");
        for (int i=0;i<105;i++) { file(root,"f"+i); if(i>0)json.append(','); json.append('"').append("f").append(i).append('"'); }
        json.append("]}");
        assertEquals(100,collect(c,root,"Python","{}",ToolResult.success(json.toString())).size());
        assertTrue(collect(c,root,"Python","{}",ToolResult.success(String.join("",java.util.Collections.nCopies(262145,"x"))+"\n[late](f0)")).isEmpty());
    }

    @Test public void chineseAndColonSavedReceiptsAreCollectedForPptxAndDocs() throws Exception {
        Path root = temp.newFolder().toPath();
        Path pptx = file(root, "工作汇报.pptx");
        String abs = pptx.toAbsolutePath().normalize().toString();
        GeneratedArtifactCollector c = collector(root);
        String[] receipts = {
                "已保存: " + abs,
                "已生成：" + abs + "（共16页）",
                "Saved: \"" + abs + "\"",
                "输出文件 -> " + abs,
                "生成成功: 工作汇报.pptx"
        };
        for (String out : receipts) {
            List<GeneratedArtifact> found = collect(c, root, "Python", "{}", ToolResult.success(out));
            assertEquals("应识别中文/冒号保存回执: " + out, 1, found.size());
            assertEquals(pptx.toRealPath().toString(), found.get(0).path);
            assertEquals(GeneratedArtifact.DELIVERABLE, found.get(0).kind);
        }
    }

    // ---- F1 措辞容错 + 路径兜底（additive，旧用例上方逐字保留） ----

    /** 行中中文回执（SAVED_RECEIPT 行首锚定漏掉）：用 .txt 隔离 EXTENDED（兜底不含 .txt） */
    @Test public void extendedMidLineChineseReceipt() throws Exception {
        Path root = temp.newFolder().toPath(); Path p = file(root, "笔记.txt");
        String abs = p.toAbsolutePath().normalize().toString();
        GeneratedArtifactCollector c = collector(root);
        for (String out : new String[]{"脚本完成，已生成 " + abs, "文件：" + abs, "产物: " + abs}) {
            List<GeneratedArtifact> a = collect(c, root, "Bash", "{}", ToolResult.success(out));
            assertEquals("行中措辞应识别: " + out, 1, a.size());
            assertEquals(p.toRealPath().toString(), a.get(0).path);
        }
    }

    /** 兜底扫描：无回执措辞的裸相对路径 / 引号含空格路径 / 后缀形式，存在即登记 */
    @Test public void fallbackScanRegistersExistingDeliverables() throws Exception {
        Path root = temp.newFolder().toPath();
        Path bare = file(root, "report.docx");
        Path spaced = file(root, "my report v2.docx");
        String spacedAbs = spaced.toAbsolutePath().normalize().toString();
        GeneratedArtifactCollector c = collector(root);
        assertEquals("裸相对路径", 1, collect(c, root, "Python", "{}", ToolResult.success("report.docx")).size());
        assertEquals("引号含空格绝对路径", 1,
                collect(c, root, "Bash", "{}", ToolResult.success("\"" + spacedAbs + "\"")).size());
        List<GeneratedArtifact> suffix = collect(c, root, "Shell", "{}",
                ToolResult.success(bare.toAbsolutePath().normalize() + "（已生成）"));
        assertEquals("后缀形式", 1, suffix.size());
        assertEquals(bare.toRealPath().toString(), suffix.get(0).path);
    }

    /** 兜底只认盘上真实存在文件：不存在 / 白名单外一律不登记（误报=0） */
    @Test public void fallbackRejectsMissingAndOutside() throws Exception {
        Path root = temp.newFolder("root").toPath(), outside = temp.newFolder("outside").toPath();
        Path out = file(outside, "secret.docx");
        GeneratedArtifactCollector c = collector(root);
        assertTrue("不存在文件不登记", collect(c, root, "Bash", "{}", ToolResult.success("ghost.docx")).isEmpty());
        assertTrue("白名单外不登记",
                collect(c, root, "Bash", "{}", ToolResult.success(out.toAbsolutePath().normalize().toString())).isEmpty());
    }

    /** 兜底每次工具结果 ≤10 条（防 stdout 刷路径轰炸） */
    @Test public void fallbackCappedAtTen() throws Exception {
        Path root = temp.newFolder().toPath();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) { file(root, "f" + i + ".docx"); sb.append("f").append(i).append(".docx "); }
        GeneratedArtifactCollector c = collector(root);
        assertEquals(10, collect(c, root, "Python", "{}", ToolResult.success(sb.toString())).size());
    }

    /** ok==false 零登记（特征化：兜底/扩展均不改失败语义） */
    @Test public void failedResultNeverCollects() throws Exception {
        Path root = temp.newFolder().toPath(); file(root, "report.docx");
        GeneratedArtifactCollector c = collector(root);
        assertTrue(collect(c, root, "Bash", "{}", ToolResult.error("report.docx 已生成")).isEmpty());
    }

    /** F6 诊断：accept/drop 落 artifact-collect 标签（未初始化时安全 no-op，不影响上方用例） */
    @Test public void diagnosticsCaptureAcceptAndDrop() throws Exception {
        Path logDir = temp.newFolder("log").toPath();
        com.minion.core.diagnostics.DiagnosticLog.initialize(logDir);
        Path root = temp.newFolder("root").toPath(), outside = temp.newFolder("outside").toPath();
        file(root, "ok.docx"); Path out = file(outside, "no.docx");
        GeneratedArtifactCollector c = collector(root);
        collect(c, root, "Bash", "{}", ToolResult.success("ok.docx"));
        collect(c, root, "Bash", "{}", ToolResult.success(out.toAbsolutePath().normalize().toString()));
        List<String> tail = com.minion.core.diagnostics.DiagnosticLog.tail(100);
        assertTrue("应有 accept 记录", tail.stream().anyMatch(l -> l.contains("artifact-collect") && l.contains("accept=")));
        assertTrue("应有 not-allowed drop 记录", tail.stream().anyMatch(l -> l.contains("artifact-collect") && l.contains("drop=not-allowed")));
        com.minion.core.diagnostics.DiagnosticLog.clear();
    }
}
