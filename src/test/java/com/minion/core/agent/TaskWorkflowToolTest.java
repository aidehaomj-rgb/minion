package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.config.Config;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class TaskWorkflowToolTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private ToolCall call(String id,String name,JsonObject args){ToolCall c=new ToolCall();c.id=id;c.name=name;c.arguments=args.toString();return c;}
    private JsonObject args(String action){JsonObject o=new JsonObject();o.addProperty("action",action);return o;}

    @Test public void actualToolsMustProduceEvidenceBeforeStepAndTaskCanFinish()throws Exception {
        Path dir=tmp.newFolder("workflow-loop").toPath();Workspace workspace=new Workspace(dir.toString());Session session=Session.create(dir.toString(),"fake");
        FakeLlmClient llm=new FakeLlmClient(){@Override public void streamChat(List<Message> messages,List<JsonObject> schemas,StreamHandler handler)throws LlmException{
            if(requests.size()==3){String receipt=null;for(TaskState.Evidence evidence:session.taskState.evidence)if("Write".equals(evidence.tool)&&evidence.success)receipt=evidence.id;
                assertNotNull("必须先有实际Write成功证据",receipt);JsonObject complete=args("step_change");complete.addProperty("revision",1);complete.addProperty("stepId","deliver");complete.addProperty("stepAction","complete");complete.addProperty("receiptIds",receipt);complete.addProperty("files","proof.txt");complete.addProperty("note","已核对文件内容为ok");
                addTurnWithTools(Collections.singletonList(call("finish-step","AgentControl",complete)),null);addTurn("proof.txt已创建，内容为ok，任务完成。");}
            super.streamChat(messages,schemas,handler);
        }};
        JsonObject plan=args("plan_steps");plan.addProperty("revision",1);plan.add("steps",JsonParser.parseString("[{\"id\":\"deliver\",\"title\":\"创建文件\",\"scope\":\"proof.txt\",\"acceptance\":\"文件存在且内容ok\"}]"));
        JsonObject start=args("step_change");start.addProperty("revision",1);start.addProperty("stepId","deliver");start.addProperty("stepAction","start");
        JsonObject write=new JsonObject();write.addProperty("path","proof.txt");write.addProperty("content","ok");
        llm.addTurnWithTools(Collections.singletonList(call("plan","AgentControl",plan)),null);
        llm.addTurnWithTools(Collections.singletonList(call("start","AgentControl",start)),null);
        llm.addTurnWithTools(Collections.singletonList(call("write","Write",write)),null);llm.stopStatuses.add("completed");
        ToolRegistry registry=new ToolRegistry();registry.register(new WriteTool(workspace));
        AgentLoop loop=new AgentLoop(llm,registry,new SystemPromptBuilder(null),new ConfirmGate(Config.load(dir),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new RecordingUi(),null,workspace,session);
        loop.requestExecutionMode(ExecutionMode.GOAL);
        try {loop.runUserTurn("创建proof.txt文件，内容ok");assertTrue(loop.lastTurnSucceeded());assertEquals("completed",session.taskState.workflow.steps.get(0).status);
            assertEquals("ok",new String(Files.readAllBytes(dir.resolve("proof.txt")),"UTF-8"));
            assertTrue(llm.stopReviewRequests.get(0).contains("workflow"));assertEquals(1,session.taskState.workflow.steps.get(0).receipts.size());
        } finally {loop.shutdown();}
    }

    @Test public void oldRevisionIsRejectedWithoutChangingPlanAndEvidenceIsPaginated()throws Exception {
        Path dir=tmp.newFolder("workflow-tool").toPath();Workspace workspace=new Workspace(dir.toString());ToolRegistry registry=new ToolRegistry();Session s=Session.create(dir.toString(),"fake");
        AgentLoop loop=new AgentLoop(new FakeLlmClient(),registry,new SystemPromptBuilder(null),new ConfirmGate(Config.load(dir),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new RecordingUi(),null,workspace,s);
        try {s.taskState.begin("当前任务",true);Tool tool=registry.get("AgentControl");JsonObject plan=args("plan_steps");plan.addProperty("revision",1);plan.addProperty("steps","[{\"id\":\"one\",\"title\":\"核对\",\"scope\":\"one.txt\",\"acceptance\":\"核对内容\"}]");assertTrue(tool.execute(plan).ok);
            String before=new Gson().toJson(s.taskState.workflow);s.taskState.addGuidance("只检查，不修改");assertFalse(tool.execute(plan).ok);assertEquals(before,new Gson().toJson(s.taskState.workflow));
            for(int i=0;i<20;i++)s.taskState.recordEvidence("root","Read",true,"片段"+i,null);
            JsonObject query=args("evidence");query.addProperty("offset",2);query.addProperty("limit",3);JsonObject out=JsonParser.parseString(tool.execute(query).output).getAsJsonObject();
            assertEquals(3,out.getAsJsonArray("receipts").size());assertEquals(5,out.get("nextOffset").getAsInt());assertEquals(s.taskState.id,out.get("taskId").getAsString());
        } finally {loop.shutdown();}
    }

    @Test public void verifyStepReleasesUncertainJournalEntries()throws Exception {
        Path dir=tmp.newFolder("verify-step").toPath();Workspace workspace=new Workspace(dir.toString());ToolRegistry registry=new ToolRegistry();Session s=Session.create(dir.toString(),"fake");
        AgentLoop loop=new AgentLoop(new FakeLlmClient(),registry,new SystemPromptBuilder(null),new ConfirmGate(Config.load(dir),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),new RecordingUi(),null,workspace,s);
        try {s.taskState.begin("当前任务",true);
            Tool tool=registry.get("AgentControl");
            JsonObject started=new JsonObject();started.addProperty("command","python missing.py");
            ReliabilityService.Step stuck=loop.reliability().start(s.taskState.id,"root","Python",started,true);
            assertTrue(ReliabilityService.hasUnobservedOutcome(loop.reliability().unresolved(s.taskState.id)));
            JsonObject bashArgs=new JsonObject();bashArgs.addProperty("command","ping -n 1 example.invalid");
            ReliabilityService.Step uncertain=loop.reliability().start(s.taskState.id,"root","Bash",bashArgs,true);
            loop.reliability().finish(uncertain,ToolResult.error("exit 1，输出不可读"));
            assertEquals(2,loop.reliability().unresolved(s.taskState.id).size());
            JsonObject missing=args("verify_step");missing.addProperty("stepId",stuck.id);
            assertFalse(tool.execute(missing).ok);
            JsonObject v1=args("verify_step");v1.addProperty("stepId",stuck.id);v1.addProperty("note","本机未装解释器，命令未执行任何操作");
            assertTrue(tool.execute(v1).ok);
            JsonObject v2=args("verify_step");v2.addProperty("stepId",uncertain.id);v2.addProperty("note","已用只读命令核对无残留进程与文件变更");
            assertTrue(tool.execute(v2).ok);
            assertEquals(0,loop.reliability().unresolved(s.taskState.id).size());
            assertFalse(ReliabilityService.hasUnobservedOutcome(loop.reliability().unresolved(s.taskState.id)));
            assertFalse(tool.execute(v1).ok);
        } finally {loop.shutdown();}
    }
}
