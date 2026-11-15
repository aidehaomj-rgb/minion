package com.minion.core.agent;

import com.google.gson.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class TaskWorkflowTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private TaskState state(){TaskState s=new TaskState();s.begin("开发并测试当前项目",true);return s;}
    private JsonArray definitions(){return JsonParser.parseString("[{\"id\":\"code\",\"title\":\"修改代码\",\"scope\":\"src\",\"acceptance\":\"实际修改并核对\"},{\"id\":\"test\",\"title\":\"运行测试\",\"scope\":\"tests\",\"acceptance\":\"实际断言通过\",\"dependsOn\":[\"code\"]}]").getAsJsonArray();}
    private void change(TaskState s,String id,String action,String receipt)throws Exception{s.workflow.transition(s,id,action,receipt==null?Collections.emptyList():Collections.singletonList(receipt),Collections.emptyList(),"核对实际依据","");}
    private String receipt(TaskState s,boolean ok,String tool){s.recordEvidence("root",tool,ok,"执行结果",null,true);return s.evidence.get(s.evidence.size()-1).id;}
    private void rejects(Runnable action){try{action.run();fail("必须拒绝");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    @Test public void dependenciesRequireCompletedEvidenceAndKeepTaskId()throws Exception{
        TaskState s=state();String task=s.id;s.workflow=TaskWorkflow.configure(s,definitions(),1);
        rejects(()->s.workflow.transition(s,"test","start",Collections.emptyList(),Collections.emptyList(),"",""));
        change(s,"code","start",null);String evidence=receipt(s,true,"Write");change(s,"code","complete",evidence);
        change(s,"test","start",null);String tested=receipt(s,true,"Build");change(s,"test","complete",tested);
        assertEquals("",s.workflow.blocking(s,p->true));assertEquals(task,s.id);
    }
    @Test public void forgedFailedControlAndPreStartReceiptsCannotComplete()throws Exception{
        TaskState s=state();s.workflow=TaskWorkflow.configure(s,definitions(),1);String old=receipt(s,true,"Write");change(s,"code","start",null);
        for(String ref:Arrays.asList(old,"made-up",receipt(s,false,"Write"),receipt(s,true,"TodoWrite")))
            rejects(()->s.workflow.transition(s,"code","complete",Collections.singletonList(ref),Collections.emptyList(),"声称完成",""));
        assertEquals("running",s.workflow.steps.get(0).status);
    }
    @Test public void cyclesMissingDependenciesAndDuplicateIdsAreRejected(){
        TaskState s=state();JsonArray a=definitions();a.get(0).getAsJsonObject().add("dependsOn",JsonParser.parseString("[\"test\"]"));rejects(()->TaskWorkflow.configure(s,a,1));
        JsonArray b=definitions();b.get(1).getAsJsonObject().add("dependsOn",JsonParser.parseString("[\"missing\"]"));rejects(()->TaskWorkflow.configure(s,b,1));
        JsonArray c=definitions();c.get(1).getAsJsonObject().addProperty("id","code");rejects(()->TaskWorkflow.configure(s,c,1));assertNull(s.workflow);
    }
    @Test public void userRevisionInvalidatesOldPlanUntilExplicitRebind()throws Exception{
        TaskState s=state();s.workflow=TaskWorkflow.configure(s,definitions(),1);s.addGuidance("不要开发了，改为只核对已有结果");
        assertTrue(s.workflow.blocking(s,p->true).contains("过期"));
        rejects(()->s.workflow.transition(s,"code","start",Collections.emptyList(),Collections.emptyList(),"",""));
        s.workflow=TaskWorkflow.configure(s,definitions(),s.revision());assertEquals(2,s.workflow.revision);
        TaskWorkflow.Change last=s.workflow.changes.get(s.workflow.changes.size()-1);assertEquals(s.latestRequirement(),last.userRequirement);
    }
    @Test public void updatesCannotEraseOrRewriteExistingSteps(){
        TaskState s=state();s.workflow=TaskWorkflow.configure(s,definitions(),1);JsonArray drop=definitions();drop.remove(0);rejects(()->TaskWorkflow.configure(s,drop,1));
        JsonArray rewritten=definitions();rewritten.get(0).getAsJsonObject().addProperty("title","伪称完成");rejects(()->TaskWorkflow.configure(s,rewritten,1));
        assertEquals("修改代码",s.workflow.steps.get(0).title);
    }
    @Test public void cancelledStepCannotServeAsCompletedDependency()throws Exception{
        TaskState s=state();s.workflow=TaskWorkflow.configure(s,definitions(),1);s.workflow.transition(s,"code","cancel",Collections.emptyList(),Collections.emptyList(),"用户取消开发","");
        rejects(()->s.workflow.transition(s,"test","start",Collections.emptyList(),Collections.emptyList(),"",""));
        assertEquals("cancelled",s.workflow.steps.get(0).status);assertEquals("cancel",s.workflow.changes.get(1).kind);
    }
    @Test public void changedOrUnauthorizedFileInvalidatesStepCompletion()throws Exception{
        TaskState s=state();s.workflow=TaskWorkflow.configure(s,definitions(),1);change(s,"code","start",null);String ref=receipt(s,true,"Write");
        Path file=tmp.newFile("actual.txt").toPath();Files.write(file,new byte[]{1});
        s.workflow.transition(s,"code","complete",Collections.singletonList(ref),Collections.singletonList(FileEvidence.capture(file)),"内容已核对","");
        s.workflow.transition(s,"test","cancel",Collections.emptyList(),Collections.emptyList(),"用户只要求代码","");
        assertEquals("",s.workflow.blocking(s,p->true));assertFalse(s.workflow.blocking(s,p->false).isEmpty());
        Files.write(file,new byte[]{2});assertTrue(s.workflow.blocking(s,p->true).contains("改变"));
    }
    @Test public void restartBlocksRunningStepAndPreservesCompletedFacts()throws Exception{
        TaskState s=state();s.workflow=TaskWorkflow.configure(s,definitions(),1);change(s,"code","start",null);
        TaskState loaded=new Gson().fromJson(new Gson().toJson(s),TaskState.class);loaded.recoverAfterRestart();
        assertEquals("blocked",loaded.workflow.steps.get(0).status);assertEquals("restart",loaded.pauseCode);
        assertTrue(loaded.compactionHandoff(new TodoList()).contains("工作流"));assertFalse(loaded.canAutomaticallyContinue());
    }
    @Test public void newTaskCannotReuseOldWorkflow(){TaskState s=state();s.workflow=TaskWorkflow.configure(s,definitions(),1);s.begin("另起任务，只回答问题",true);assertNull(s.workflow);}
    @Test public void longPlansPrioritizeUnfinishedStepsAndDoNotDuplicateHugeOriginalInEachChange(){TaskState s=state();String original=new String(new char[30000]).replace('\0','原');s.begin(original,true);JsonArray all=new JsonArray();for(int i=0;i<38;i++){JsonObject o=new JsonObject();o.addProperty("id","part"+i);o.addProperty("title",new String(new char[500]).replace('\0','步'));o.addProperty("scope","file"+i);o.addProperty("acceptance","核对文件");all.add(o);}s.workflow=TaskWorkflow.configure(s,all,1);for(int i=0;i<37;i++)s.workflow.steps.get(i).status="completed";s.workflow.steps.get(37).status="running";String render=s.workflow.render();assertTrue(render.indexOf("part37 [running]")<render.indexOf("已完成ID"));assertTrue(s.compactionHandoff(new TodoList()).contains("part37 [running]"));assertEquals(original,s.objective);assertTrue(s.workflow.changes.get(0).userRequirement.length()<=2000);assertEquals(StopGate.digest(original),s.workflow.changes.get(0).userRequirementHash);}
}
