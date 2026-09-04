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
}
