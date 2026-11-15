package com.minion.core.agent;

import java.nio.file.*;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChildTaskIsolationFTest {
    @Test public void cleanRegisteredWorktreeCanBeRemovedWithoutTouchingParent()throws Exception{
        Path root=FGitFixture.create();FGitFixture.write(root.resolve("unrelated.txt"),"user");ChildTaskIsolation service=new ChildTaskIsolation(root);ChildTaskIsolation.Record r=service.create("task","step","clean",Collections.singletonList("a.txt"));assertEquals("ready",r.status);assertTrue(Files.exists(Paths.get(r.worktree).resolve("a.txt")));assertEquals(r.baseline.get("a.txt"),service.expectedTargetHash(r,"a.txt"));service.remove("clean");assertEquals("removed",service.load("clean").status);assertTrue(Files.exists(root.resolve("unrelated.txt")));assertFalse(Files.exists(Paths.get(r.worktree)));
    }
    @Test public void refusesDirtyScopeAndDoesNotCleanDirtyOrIgnoredWorktree()throws Exception{
        Path root=FGitFixture.create();FGitFixture.write(root.resolve("a.txt"),"user edit");ChildTaskIsolation service=new ChildTaskIsolation(root);
        try{service.create("task","step","blocked",Collections.singletonList("a.txt"));fail();}catch(java.io.IOException expected){}assertFalse(Files.exists(root.resolve(".minion/git-worktrees/blocked")));
        Path clean=FGitFixture.create();service=new ChildTaskIsolation(clean);ChildTaskIsolation.Record r=service.create("task","step","dirty",Collections.singletonList("a.txt"));FGitFixture.write(Paths.get(r.worktree).resolve("a.txt"),"child code");try{service.remove("dirty");fail();}catch(java.io.IOException expected){}assertTrue(Files.exists(Paths.get(r.worktree).resolve("a.txt")));
    }
    @Test public void baselineCannotBeWashedByFreshPreviewAndOutOfScopeIsRefused()throws Exception{
        Path root=FGitFixture.create();ChildTaskIsolation service=new ChildTaskIsolation(root);ChildTaskIsolation.Record r=service.create("task","step","baseline",Collections.singletonList("a.txt"));FGitFixture.write(root.resolve("a.txt"),"user newer");
        try{service.expectedTargetHash(r,"a.txt");fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("基线"));}
        try{service.expectedTargetHash(r,"other.txt");fail();}catch(java.io.IOException expected){}assertEquals("user newer",new String(Files.readAllBytes(root.resolve("a.txt")),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void headAdvanceInvalidatesBaselineAndIgnoredFilesPreventRemoval()throws Exception{
        Path root=FGitFixture.create();ChildTaskIsolation service=new ChildTaskIsolation(root);ChildTaskIsolation.Record r=service.create("task","step","head",Collections.singletonList("a.txt"));FGitFixture.advanceHead(root);try{service.expectedTargetHash(r,"a.txt");fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("HEAD"));}
        Path other=FGitFixture.create();ChildTaskIsolation second=new ChildTaskIsolation(other);ChildTaskIsolation.Record clean=second.create("task","step","ignored",Collections.singletonList("a.txt"));FGitFixture.write(other.resolve(".git/info/exclude"),"cache/\n");FGitFixture.write(Paths.get(clean.worktree).resolve("cache/user.txt"),"keep");try{second.remove("ignored");fail();}catch(java.io.IOException expected){}assertTrue(Files.exists(Paths.get(clean.worktree).resolve("cache/user.txt")));
    }
}
