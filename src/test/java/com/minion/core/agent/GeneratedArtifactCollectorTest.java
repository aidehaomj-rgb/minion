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
}
