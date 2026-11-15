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
}
