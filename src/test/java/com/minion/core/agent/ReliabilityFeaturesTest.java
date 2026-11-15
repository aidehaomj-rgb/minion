package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.checkpoint.CheckpointStore;
import com.minion.core.tools.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ReliabilityFeaturesTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private Workspace workspace()throws Exception{return new Workspace(tmp.newFolder("project").getAbsolutePath());}
    @Test public void acceptanceChecksRealJsonCsvAndXlsxAndStaleFiles()throws Exception {
        Workspace w=workspace();Path root=Paths.get(w.workDir());Files.write(root.resolve("rows.json"),"[{\"x\":1},{\"x\":2}]".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("rows.csv"),"code,name\n001,A\n002,B\n".getBytes(StandardCharsets.UTF_8));
        Path x=root.resolve("rows.xlsx");try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(x))){
            zip(z,"xl/workbook.xml","<workbook><sheets><sheet name='Data' r:id='rId1'/></sheets></workbook>");
            zip(z,"xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='rId1' Target='worksheets/sheet1.xml'/></Relationships>");
            zip(z,"xl/worksheets/sheet1.xml","<worksheet><sheetData><row><c r='A1' t='inlineStr'><is><t>code</t></is></c><c r='B1' t='inlineStr'><is><t>name</t></is></c></row><row><c r='A2' t='inlineStr'><is><t>001</t></is></c><c r='B2' t='inlineStr'><is><t>A</t></is></c></row><row><c r='A3' t='inlineStr'><is><t>002</t></is></c><c r='B3' t='inlineStr'><is><t>B</t></is></c></row></sheetData></worksheet>");}
        JsonArray rules=JsonParser.parseString("[{\"type\":\"json_count\",\"path\":\"rows.json\",\"count\":2},{\"type\":\"table_rows\",\"path\":\"rows.csv\",\"count\":2},{\"type\":\"table_columns\",\"path\":\"rows.xlsx\",\"sheet\":\"Data\",\"columns\":[\"code\",\"name\"]},{\"type\":\"unique_column\",\"path\":\"rows.xlsx\",\"sheet\":\"Data\",\"column\":\"code\"}]").getAsJsonArray();
        AcceptanceRules.validate(rules);TaskState state=new TaskState();state.begin("目标",true);JsonObject result=AcceptanceRules.evaluate(rules,w,state,p->PathsGuard.inside(w.workDir(),p));assertEquals("passed",result.get("status").getAsString());
        Files.write(root.resolve("rows.csv"),"code,name\n001,A\n".getBytes(StandardCharsets.UTF_8));assertEquals("failed",AcceptanceRules.evaluate(rules,w,state,p->true).get("status").getAsString());
        assertEquals("unknown",AcceptanceRules.evaluate(rules,w,state,p->false).get("status").getAsString());
    }
    private static void zip(ZipOutputStream z,String name,String content)throws Exception {z.putNextEntry(new ZipEntry(name));z.write(content.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    @Test public void journalPreventsBlindRetryAndRecordsManualReview()throws Exception {
        Workspace w=workspace();ReliabilityService r=new ReliabilityService(w,"safe-1");JsonObject args=new JsonObject();args.addProperty("path","result.txt");
        ReliabilityService.Step start=r.start("task-a","root","Write",args,true);assertEquals(1,r.unresolved("task-a").size());
        try{r.start("task-a","root","Write",args,true);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("禁止盲目重试"));}
        r.acknowledge(start.id,"文件未发生改变，手工检查后重试");assertEquals(0,r.unresolved("task-a").size());
        ReliabilityService.Step again=r.start("task-a","root","Write",args,true);r.finish(again,ToolResult.success("done"));assertEquals(0,r.unresolved("task-a").size());
        assertEquals(2,new ReliabilityService(w,"safe-1").steps().size());
    }
    @Test public void definiteFailureCanContinueButUnknownOutcomeStillRequiresReview()throws Exception {
        Workspace w=workspace();ReliabilityService r=new ReliabilityService(w,"safe-2");JsonObject args=new JsonObject();args.addProperty("command","python -c broken");
        ReliabilityService.Step rejected=r.start("task-b","root","Bash",args,true);
        r.finish(rejected,ToolResult.errorWithoutSideEffects("参数无效，命令未运行"));
        assertEquals("failed_no_effect",r.steps().get(0).state);
        assertEquals(0,r.unresolved("task-b").size());
        ReliabilityService.Step failed=r.start("task-b","root","Bash",args,true);
        r.finish(failed,ToolResult.error("exit code 1"));
        JsonArray unresolved=r.unresolved("task-b");assertEquals(1,unresolved.size());
        assertFalse(ReliabilityService.hasUnobservedOutcome(unresolved));
        assertTrue(ReliabilityService.unresolvedSummary(unresolved).contains(failed.id));
        try{r.start("task-b","root","Bash",args,true);fail("partial command must not be blindly retried");}
        catch(IOException expected){assertTrue(expected.getMessage().contains(failed.id));}
        JsonObject other=new JsonObject();other.addProperty("command","another command");
        ReliabilityService.Step interrupted=r.start("task-b","root","Bash",other,true);
        assertTrue(ReliabilityService.hasUnobservedOutcome(r.unresolved("task-b")));
        assertTrue(ReliabilityService.unresolvedSummary(r.unresolved("task-b")).contains(interrupted.id));
    }
    @Test public void shellReadClassificationRejectsMetacharactersAndInterpreters()throws Exception {
        assertTrue(BashTool.provablyReadOnly("ls -la"));
        assertTrue(BashTool.provablyReadOnly("grep brand result.txt"));
        assertFalse(BashTool.provablyReadOnly("ls && rm result.txt"));
        assertFalse(BashTool.provablyReadOnly("echo $(touch result.txt)"));
        assertFalse(BashTool.provablyReadOnly("rg --pre command result.txt"));
        assertFalse(BashTool.provablyReadOnly("python -c print(1)"));
        JsonObject args=new JsonObject();args.addProperty("command","ls -la");assertTrue(ToolExecutionGuard.readOnly("Bash",args));
        JsonObject invalid=new JsonObject();invalid.addProperty("command","python -c broken");invalid.addProperty("timeoutSeconds",-1);
        ToolResult rejected=new BashTool(workspace()).execute(invalid);
        assertFalse(rejected.ok);assertTrue(rejected.noSideEffect);
    }
    @Test public void groupedRestoreRejectsConflictAndBacksUpCurrent()throws Exception {
        Workspace w=workspace();Path file=Paths.get(w.workDir(),"report.txt"),root=Paths.get(w.workDir(),".minion","checkpoints","safe-1");Files.write(file,"before".getBytes(StandardCharsets.UTF_8));CheckpointStore store=new CheckpointStore(Paths.get(w.workDir()),root);
        CheckpointStore.Entry e=store.create(file,"Edit");Files.write(file,"changed".getBytes(StandardCharsets.UTF_8));store.seal(e);
        Files.write(file,"user-change".getBytes(StandardCharsets.UTF_8));assertTrue(store.preflight(e.id).contains("conflict"));try{store.restoreGroup(e.id);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("预检失败"));}assertEquals("user-change",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        Files.write(file,"changed".getBytes(StandardCharsets.UTF_8));assertTrue(store.restoreGroup(e.id).contains("已恢复"));assertEquals("before",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));assertTrue(store.list(10).size()>=2);
    }
    @Test public void timerAndFileJobsDoNotCatchUpOrOverlap()throws Exception {
        Workspace w=workspace();Path root=Paths.get(w.workDir(),".minion","reliability","safe-1");LocalJobs jobs=new LocalJobs(w,root);
        jobs.configure("[{\"id\":\"timer\",\"kind\":\"timer\",\"intervalMinutes\":1,\"prompt\":\"run\"}]","model-a");jobs.enable(true);
        assertNull(jobs.tick(1_000_000,false,false,"model-a"));assertNull(jobs.tick(1_030_000,false,false,"model-a"));
        assertEquals("timer",jobs.tick(1_060_001,false,false,"model-a").id);assertNull(jobs.tick(1_130_000,true,false,"model-a"));
        jobs.tick(1_130_000,false,false,"model-a");assertTrue(jobs.json().contains("paused: 上次运行未完成"));assertFalse(jobs.json().contains("\"enabled\": true"));
        jobs.configure("[{\"id\":\"watch\",\"kind\":\"file\",\"watchDir\":\".\",\"prompt\":\"review\"}]","model-a");jobs.enable(true);assertNull(jobs.tick(2_000_000,false,false,"model-a"));
        Files.write(Paths.get(w.workDir(),"new.md"),"data".getBytes(StandardCharsets.UTF_8));assertNull(jobs.tick(2_010_000,false,false,"model-a"));assertNull(jobs.tick(2_025_000,false,false,"model-a"));assertEquals("watch",jobs.tick(2_045_000,false,false,"model-a").id);
        LocalJobs restored=new LocalJobs(w,root);assertTrue(restored.json().contains("上次执行状态未知"));
    }
    @Test public void visualEditorPreservesOtherJobsAndRequiresExplicitEnable()throws Exception {
        Workspace w=workspace();Path root=Paths.get(w.workDir(),".minion","reliability","visual-editor");
        LocalJobs jobs=new LocalJobs(w,root);
        jobs.configure("[{\"id\":\"legacy\",\"prompt\":\"已有计划\",\"intervalMinutes\":1}]","model-a");jobs.enable(true);
        LocalJobs.Job draft=new LocalJobs.Job();draft.title="资料巡检";draft.prompt="只读汇总";draft.intervalMinutes=1;
        jobs.saveJob(null,draft,"model-a");
        LocalJobs.Job created=jobs.snapshot().get(1);assertFalse(created.enabled);assertEquals("资料巡检",created.title);
        created.prompt="外部修改";assertEquals("只读汇总",jobs.snapshot().get(1).prompt);
        assertTrue(jobs.snapshot().get(0).enabled);
        jobs.setEnabled(created.id,true,"model-b");
        assertEquals("model-b",jobs.snapshot().get(1).modelFingerprint);
        jobs.deleteJob(created.id);
        assertEquals(1,new LocalJobs(w,root).snapshot().size());
        assertTrue(jobs.snapshot().get(0).enabled);
    }
    @Test public void pausingActiveJobKeepsRecoveryMarkerAndEditsCannotReplaceIt()throws Exception {
        Workspace w=workspace();Path root=Paths.get(w.workDir(),".minion","reliability","active-editor");
        LocalJobs jobs=new LocalJobs(w,root);
        jobs.configure("[{\"id\":\"timer\",\"prompt\":\"执行巡检\",\"intervalMinutes\":1}]","model-a");jobs.enable(true);
        assertNull(jobs.tick(1000,false,false,"model-a"));
        assertEquals("timer",jobs.tick(62000,false,false,"model-a").id);
        jobs.setEnabled("timer",false,"model-a");
        assertEquals("dispatched",jobs.snapshot().get(0).status);
        try{jobs.deleteJob("timer");fail();}catch(IllegalStateException expected){}
        try{jobs.saveJob("timer",jobs.snapshot().get(0),"model-a");fail();}catch(IllegalStateException expected){}
        jobs.finish("unrelated",true);assertEquals("dispatched",jobs.snapshot().get(0).status);
        assertTrue(new LocalJobs(w,root).snapshot().get(0).status.contains("状态未知"));
        jobs.finish("timer",true);assertFalse(jobs.snapshot().get(0).enabled);
        assertEquals("disabled",jobs.snapshot().get(0).status);
    }
    @Test public void corruptJobLedgerDoesNotStartOrOverwriteJobs()throws Exception {
        Workspace w=workspace();Path root=Paths.get(w.workDir(),".minion","reliability","safe-1");Files.createDirectories(root);
        Path file=root.resolve("jobs.json");String corrupt="[{\"id\":\"one\",\"enabled\":true}]";
        Files.write(file,corrupt.getBytes(StandardCharsets.UTF_8));
        try{new LocalJobs(w,root);fail("malformed job must never be scheduled");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("记录损坏"));}
        assertEquals(corrupt,new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        Files.write(file,"null".getBytes(StandardCharsets.UTF_8));
        try{new LocalJobs(w,root);fail("null is not an empty job list");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("记录损坏"));}
    }
    @Test public void scheduledTurnAllowsOnlyConfiguredReadActions()throws Exception {
        Workspace w=workspace();ToolExecutionGuard guard=new ToolExecutionGuard(w,false);AtomicInteger calls=new AtomicInteger();
        Tool write=new Tool(){public String name(){return "Write";}public String description(){return "test";}
            public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject args){calls.incrementAndGet();return ToolResult.success("ok");}};
        Tool read=new Tool(){public String name(){return "Read";}public String description(){return "test";}
            public JsonObject schema(){return new JsonObject();}public ToolResult execute(JsonObject args){calls.incrementAndGet();return ToolResult.success("ok");}};
        guard.scheduledReadOnly(new HashSet<String>(Arrays.asList("Read")));
        assertFalse(guard.execute(write,new JsonObject()).ok);
        assertTrue(guard.execute(read,new JsonObject()).ok);
        assertEquals(1,calls.get());
        guard.clearScheduled();assertTrue(guard.execute(write,new JsonObject()).ok);assertEquals(2,calls.get());
        LocalJobs jobs=new LocalJobs(w,Paths.get(w.workDir(),".minion/reliability/safe-1"));
        try{jobs.configure("[{\"kind\":\"timer\",\"prompt\":\"x\",\"allowedTools\":[\"Bash\"]}]","model");fail("code execution cannot be scheduled");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("只读工具"));}
    }
    @Test public void manualBusinessReviewStaysBoundToTaskAndIsNotInvented()throws Exception {
        Workspace w=workspace();ReliabilityService reliability=new ReliabilityService(w,"safe-1");Session session=Session.create(w.workDir(),"test");
        session.taskState.begin("核对100条",true);String id=session.taskState.id;session.taskState.finish(false);
        JsonObject before=TaskEvaluation.current(session,reliability);
        assertEquals("not_reviewed",before.get("manualBusinessReviewStatus").getAsString());
        String review="{\"accepted\":false,\"expectedItems\":100,\"omittedItems\":2,\"duplicateItems\":1,\"incorrectItems\":3,\"note\":\"已比对原始表\"}";
        TaskEvaluation.recordReview(reliability,session.taskState,id,review);
        JsonObject after=TaskEvaluation.current(session,reliability);
        assertEquals(2,after.getAsJsonObject("manualBusinessReview").get("omittedItems").getAsInt());
        assertTrue(after.get("activeMillis").getAsLong()>=0);
        session.taskState.begin("另一项任务",true);
        try{TaskEvaluation.recordReview(reliability,session.taskState,id,review);fail("stale review must not attach to a new task");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("已切换"));}
        assertEquals("not_reviewed",TaskEvaluation.current(session,reliability).get("manualBusinessReviewStatus").getAsString());
    }
    @Test public void isolatedChildPolicyAndMergeRejectStaleTarget()throws Exception {
        Workspace w=workspace();Path root=Paths.get(w.workDir()),dir=root.resolve(".minion/child-output/safe-1/child-1");Files.createDirectories(dir);
        AgentToolPolicy policy=new AgentToolPolicy(false,"",dir.toString());JsonObject write=new JsonObject();write.addProperty("path",dir.resolve("piece.txt").toString());assertTrue(policy.permits("Write",write));
        write.addProperty("path",root.resolve("report.txt").toString());assertFalse(policy.permits("Write",write));assertFalse(policy.visible("Bash"));
        TaskState state=new TaskState();state.begin("写报告",true);TaskState.Child child=state.startChild("一段");state.isolateChild(child.id,dir.toString());state.finishChild(child.id,"done",true,"record");assertTrue(state.reviewChild(child.id));
        Files.write(dir.resolve("piece.txt"),"child".getBytes(StandardCharsets.UTF_8));Files.write(root.resolve("report.txt"),"before".getBytes(StandardCharsets.UTF_8));
        OutputMerge merger=new OutputMerge(w,new CheckpointStore(root,root.resolve(".minion/checkpoints/safe-1")));
        OutputMerge.Plan plan=merger.inspect(state.child(child.id),"piece.txt","report.txt");Files.write(root.resolve("report.txt"),"user-edit".getBytes(StandardCharsets.UTF_8));
        try{merger.apply(plan,state);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("已修改"));}assertEquals("user-edit",new String(Files.readAllBytes(root.resolve("report.txt")),StandardCharsets.UTF_8));
        OutputMerge.Plan fresh=merger.inspect(state.child(child.id),"piece.txt","report.txt");assertTrue(merger.apply(fresh,state).contains("已合并"));assertEquals("child",new String(Files.readAllBytes(root.resolve("report.txt")),StandardCharsets.UTF_8));
    }
}
