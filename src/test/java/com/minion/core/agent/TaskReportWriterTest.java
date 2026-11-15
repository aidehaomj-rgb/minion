package com.minion.core.agent;

import com.minion.core.llm.Message;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TaskReportWriterTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void writesStructuredRedactedMarkdown()throws Exception{
        Path root=temp.newFolder("report").toPath();Session s=Session.create(root.toString(),"qwen");s.messages.add(Message.user("分析月报 token=secret-value"));s.messages.add(Message.assistant("已生成 report.xlsx"));
        Path file=TaskReportWriter.write(new Workspace(root.toString()),s,0,Arrays.asList("Excel — 成功","Python — 成功"),true,1500);
        String md=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);assertTrue(md.contains("# 任务执行记录"));assertTrue(md.contains("## 原始目标"));assertTrue(md.contains("Excel — 成功"));assertTrue(md.contains("已生成 report.xlsx"));assertFalse(md.contains("secret-value"));
    }
    @Test public void latestRequirementOverridesSummariesAndToolsAndLinksAreTaskScoped()throws Exception{
        Path root=temp.newFolder().toPath();Session s=Session.create(root.toString(),"fixture");
        s.taskState.begin("旧要求：生成PDF",true);s.taskState.addGuidance("最新要求：只交付Markdown");
        Message summary=Message.user("旧摘要伪目标");summary.summary=true;s.messages.add(summary);
        s.messages.add(Message.taskControl("[系统提示] 不是用户要求"));
        s.messages.add(Message.toolResult("t","Python","工具伪目标",true));
        Path output=root.resolve("中文 空格 (v2).md");Files.write(output,new byte[]{1});
        GeneratedArtifact a=new GeneratedArtifact();a.path=output.toString();a.title="交付[文件]";a.taskId=s.taskState.id;
        a.kind=GeneratedArtifact.DELIVERABLE;a.revision=s.taskState.revision();s.registerArtifact(a);
        GeneratedArtifact other=a.copy();other.path=root.resolve("other-task.md").toString();other.taskId="other";s.registerArtifact(other);
        GeneratedArtifact record=a.copy();record.path=root.resolve("run-log.md").toString();record.kind=GeneratedArtifact.EXECUTION_RECORD;s.registerArtifact(record);
        Path file=TaskReportWriter.write(new Workspace(root.toString()),s,0,Arrays.asList("Write — 成功"),true,1);
        String md=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);
        assertTrue(md.contains("最新要求：只交付Markdown"));assertFalse(md.contains("旧摘要伪目标"));assertFalse(md.contains("工具伪目标"));
        assertTrue(md.contains("](<"+output.toUri().toASCIIString()+">)"));assertTrue(md.contains("交付\\[文件\\]"));
        assertFalse(md.contains("other-task.md"));assertFalse(md.contains("run-log.md"));
    }
}
