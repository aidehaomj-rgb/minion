package com.minion.core.agent;

import com.minion.core.tools.Workspace;
import com.minion.core.checkpoint.CheckpointStore;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OutputMergeFTest {
    private static final class Fixture {
        Path root,output;TaskState state=new TaskState();String child;OutputMerge merge;
        Fixture()throws Exception{root=Files.createTempDirectory("minion-f-merge-");output=root.resolve(".minion/output");Files.createDirectories(output);FGitFixture.write(output.resolve("piece.txt"),"child");FGitFixture.write(root.resolve("report.txt"),"user before");state.begin("parent",true);TaskState.Child c=state.startChild("piece");child=c.id;state.isolateChild(child,output.toString());state.finishChild(child,"returned",true,"record");assertTrue(state.reviewChild(child));merge=new OutputMerge(new Workspace(root.toString()),new CheckpointStore(root,root.resolve(".minion/checkpoints")));}
        OutputMerge.Plan plan()throws Exception{return merge.inspect(state.child(child),"piece.txt","report.txt");}
    }
    @Test public void targetSourceAndOutputRootChangesInvalidatePreview()throws Exception{
        Fixture f=new Fixture();OutputMerge.Plan p=f.plan();FGitFixture.write(f.root.resolve("report.txt"),"user newer");try{f.merge.apply(p,f.state);fail();}catch(java.io.IOException expected){}assertEquals("user newer",new String(Files.readAllBytes(f.root.resolve("report.txt")),java.nio.charset.StandardCharsets.UTF_8));
        p=f.plan();FGitFixture.write(f.output.resolve("piece.txt"),"child newer");try{f.merge.apply(p,f.state);fail();}catch(java.io.IOException expected){}
        p=f.plan();f.state.isolateChild(f.child,f.root.resolve("other-output").toString());try{f.merge.apply(p,f.state);fail();}catch(java.io.IOException expected){}
    }
    @Test public void forgedModifiedAndConsumedPlansCannotWrite()throws Exception{
        Fixture f=new Fixture();try{f.merge.apply(new OutputMerge.Plan(),f.state);fail();}catch(java.io.IOException expected){}
        OutputMerge.Plan p=f.plan();p.target=f.root.resolve("other.txt");try{f.merge.apply(p,f.state);fail();}catch(java.io.IOException expected){}assertFalse(Files.exists(f.root.resolve("other.txt")));
        p=f.plan();assertTrue(f.merge.apply(p,f.state).contains("merged_needs_validation"));try{f.merge.apply(p,f.state);fail();}catch(java.io.IOException expected){}
        OutputMerge.Plan duplicate=f.plan();try{f.merge.apply(duplicate,f.state);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("重复"));}
    }
    @Test public void mergeNeedsFreshActualParentReceiptAndUnchangedFiles()throws Exception{
        Fixture f=new Fixture();f.state.recordEvidence("root","Build",true,"old test",null);String old=f.state.evidence.get(f.state.evidence.size()-1).id;Thread.sleep(5);f.merge.apply(f.plan(),f.state);assertEquals("merged_needs_validation",f.state.child(f.child).status);
        try{f.merge.validateFromParent(f.child,f.state,true,"tests pass",Collections.singletonList(old));fail();}catch(java.io.IOException expected){}
        f.state.recordEvidence("root","Build",true,"joint tests pass",null);String fresh=f.state.evidence.get(f.state.evidence.size()-1).id;assertEquals("validated",f.merge.validateFromParent(f.child,f.state,true,"joint tests pass",Collections.singletonList(fresh)).status);assertEquals("validated",f.state.child(f.child).status);
        Fixture changed=new Fixture();changed.merge.apply(changed.plan(),changed.state);FGitFixture.write(changed.root.resolve("report.txt"),"user after merge");changed.state.recordEvidence("root","Build",true,"pass",null);String receipt=changed.state.evidence.get(changed.state.evidence.size()-1).id;try{changed.merge.validateFromParent(changed.child,changed.state,true,"pass",Collections.singletonList(receipt));fail();}catch(java.io.IOException expected){}
    }
    @Test public void codeWorktreeMergeChecksDispatchBaselineEvenOnFreshPreview()throws Exception{
        Path root=FGitFixture.create();TaskState state=new TaskState();state.begin("code",true);TaskState.Child child=state.startChild("code");ChildTaskIsolation service=new ChildTaskIsolation(root);ChildTaskIsolation.Record isolated=service.create(state.id,"step","code",Collections.singletonList("a.txt"));state.isolateChild(child.id,isolated.worktree);state.finishChild(child.id,"returned",true,"record");state.reviewChild(child.id);FGitFixture.write(Paths.get(isolated.worktree).resolve("a.txt"),"child code");FGitFixture.write(root.resolve("a.txt"),"user code");OutputMerge merge=new OutputMerge(new Workspace(root.toString()),new CheckpointStore(root,root.resolve(".minion/checkpoints")));
        try{merge.inspect(state.child(child.id),"a.txt","a.txt");fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("基线"));}assertEquals("user code",new String(Files.readAllBytes(root.resolve("a.txt")),java.nio.charset.StandardCharsets.UTF_8));
    }
}
