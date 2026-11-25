package com.minion.gui.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class WorkspaceFilesPaneTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void onlyListsSelectedFolderAndHidesInternalState()throws Exception{
        Path root=temp.newFolder().toPath();
        Path child=Files.createDirectory(root.resolve("原始资料"));
        Files.createFile(child.resolve("nested.docx"));
        Files.createFile(root.resolve("报告.md"));
        Files.createDirectory(root.resolve(".minion"));
        Files.createDirectory(root.resolve(".git"));
        WorkspaceFilesPane.Scan view=WorkspaceFilesPane.list(root,root);
        assertEquals(Arrays.asList("原始资料","报告.md"),view.rows.stream()
                .map(r->r.path.getFileName().toString()).collect(Collectors.toList()));
        assertTrue(view.rows.get(0).directory);
        assertEquals("nested.docx",WorkspaceFilesPane.list(root,child).rows.get(0).path.getFileName().toString());
        try{WorkspaceFilesPane.list(root,temp.newFolder().toPath());fail("outside");}catch(IOException expected){assertTrue(expected.getMessage().contains("工作区之外"));}
    }

    @Test public void directoryWorkIsBounded()throws Exception{
        Path root=temp.newFolder().toPath();
        for(int i=0;i<405;i++)Files.createFile(root.resolve(i+".txt"));
        WorkspaceFilesPane.Scan view=WorkspaceFilesPane.list(root,root);
        assertEquals(400,view.rows.size());assertTrue(view.limited);
    }

    // ---- F4 手动扫描（additive，旧用例上方逐字保留） ----

    /** 递归命中交付扩展名；排除 .git/.minion/.session 与非交付文件 */
    @Test public void scanFindsDeliverablesAndExcludesInternal()throws Exception{
        Path root=temp.newFolder().toPath();
        Files.createFile(root.resolve("报告.docx"));
        Path sub=Files.createDirectories(root.resolve("sub"));
        Files.createFile(sub.resolve("数据.xlsx"));
        Files.createFile(sub.resolve("笔记.txt"));
        Files.createFile(sub.resolve("脚本.py"));
        Path deep=Files.createDirectories(sub.resolve("deep"));
        Files.createFile(deep.resolve("图.png"));
        for(String internal:new String[]{".git",".minion",".session"}){
            Path d=Files.createDirectory(root.resolve(internal));
            Files.createFile(d.resolve("隐藏.docx"));
        }
        List<String> names=WorkspaceFilesPane.scanDeliverables(root).stream()
                .map(p->p.getFileName().toString()).collect(Collectors.toList());
        assertEquals(Arrays.asList("图.png","报告.docx","数据.xlsx"),names);
    }

    /** 深度护栏 ≤3 层：第 4 层目录内文件不扫 */
    @Test public void scanDepthCappedAtThree()throws Exception{
        Path root=temp.newFolder().toPath();
        Path d3=Files.createDirectories(root.resolve("a").resolve("b").resolve("c"));
        Files.createFile(d3.resolve("三层.docx"));
        Path d4=Files.createDirectories(d3.resolve("d"));
        Files.createFile(d4.resolve("四层.docx"));
        List<String> names=WorkspaceFilesPane.scanDeliverables(root).stream()
                .map(p->p.getFileName().toString()).collect(Collectors.toList());
        assertEquals(Collections.singletonList("三层.docx"),names);
    }

    /** 数量护栏：交付文件收集 ≤400 */
    @Test public void scanCollectCappedAt400()throws Exception{
        Path root=temp.newFolder().toPath();
        for(int i=0;i<405;i++)Files.createFile(root.resolve(i+".docx"));
        assertEquals(400,WorkspaceFilesPane.scanDeliverables(root).size());
    }
}
