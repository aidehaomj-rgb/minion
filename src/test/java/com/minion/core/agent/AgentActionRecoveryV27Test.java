package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.*;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static org.junit.Assert.*;

/** V27：口头循环必须升级真实工具请求，不能因 300K 上下文强制压缩或误报完成。 */
public class AgentActionRecoveryV27Test {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private FakeLlmClient llm;
    private AgentLoop loop;
    private List<String> notices;
    @Before public void setup() {
        llm=new FakeLlmClient(); notices=new ArrayList<String>();
        Workspace ws=new Workspace(tmp.getRoot().getPath());
        ToolRegistry tools=new ToolRegistry(); tools.register(new com.minion.core.tools.example.ExampleTool());
        loop=new AgentLoop(llm,tools,new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(tmp.getRoot().toPath()),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi(){ public void onWarning(String s){notices.add(s);} public void onError(String s){notices.add(s);}},
                new ContextManager(900000,.9,50,llm,0),ws,Session.create(ws.workDir(),"deepseek-v4-flash-0731"));
    }
    @After public void cleanup(){loop.shutdown();}
    private void scan(String id) {
        ToolCall c=new ToolCall(); c.id=id;c.name="example";c.arguments="{\"text\":\"核验完成\"}";
        llm.addTurnWithTools(Collections.singletonList(c),null);
    }
    @Test public void numericAnswerMeaningReachesNextModelRequestWithPairedToolId() throws Exception {
        ToolCall question=new ToolCall();question.id="choice-3";question.name="AskUserQuestion";
        question.arguments="{\"question\":\"排查方式？\",\"options\":[{\"label\":\"日志\"},{\"label\":\"计划任务\"},{\"label\":\"两者都做\",\"description\":\"检查日志和计划任务\"}]}";
        llm.addTurnWithTools(Collections.singletonList(question),null);
        llm.addTurn("按第三项处理完毕。\n[MINION_TASK_COMPLETE]");
        Thread worker=new Thread(()->loop.runUserTurn("按我选择的方式排查"));worker.setDaemon(true);worker.start();
        try {
            long deadline=System.currentTimeMillis()+5000;
            boolean accepted=false;
            while(System.currentTimeMillis()<deadline && !(accepted=loop.answerAskUser("3"))) Thread.sleep(10);
            assertTrue("回答必须送达挂起的工具",accepted);
            worker.join(10000);assertFalse(worker.isAlive());
            List<Message> request=llm.requests.get(1).messages;
            Message result=request.get(request.size()-1);
            assertEquals(Message.Role.TOOL,result.role);
            assertEquals("choice-3",result.toolCallId);
            assertTrue(result.content.contains("两者都做"));
            assertTrue(result.content.contains("检查日志和计划任务"));
            assertEquals("3",com.google.gson.JsonParser.parseString(result.content).getAsJsonObject().get("answer").getAsString());
            Message invocation=request.get(request.size()-2);
            assertEquals(Message.Role.ASSISTANT,invocation.role);
            assertEquals("choice-3",invocation.toolCalls.get(0).id);
        } finally {loop.interrupt();worker.join(5000);}
    }
    @Test public void screenshotTailPromisingBashIsNotACompletedReply() {
        assertTrue(AgentLoop.looksLikeInterimAnswer("先调用Bash构建补全清单并分块。"));
        assertTrue(AgentLoop.looksLikeInterimAnswer("我现在实际调用Bash执行构建补全清单脚本。执行工具调用。现在。"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("可以使用Bash复核；本次已交付完整结果。"+AgentLoop.TASK_COMPLETE_MARKER));
        assertFalse(AgentLoop.looksLikeInterimAnswer("我使用Bash查看了目录，结果有3个文件。"));
        assertFalse(AgentLoop.looksLikeInterimAnswer("如需复核，可以调用Bash查看文件。"));
    }
    @Test public void twoPlansEscalateToActionRequestThenResumeNormalModel() {
        scan("start");
        llm.addTurn("我先检查353行的实际证据。");
        llm.addTurn("先调用Bash构建补全清单并分块。");
        scan("actual");llm.addTurn("核验完成。"+AgentLoop.TASK_COMPLETE_MARKER);
        loop.runUserTurn("补全353行原文与分析并交付");
        assertEquals(5,llm.requests.size()); assertEquals(1,llm.actionRecoveryCalls);
        assertTrue(loop.lastTurnSucceeded()); assertTrue(llm.completeChatRequests.isEmpty());
        assertEquals(900000,loop.contextManager().maxTokens());
        assertTrue(notices.toString().contains("工具动作恢复"));
    }
    @Test public void repeatedPlansEndWithSpecificErrorNotSilentSuccess() {
        scan("start");llm.addTurn("我先检查353行的实际证据。");
        loop.runUserTurn("补全所有行");
        assertTrue(llm.actionRecoveryCalls>0); assertFalse(loop.lastTurnSucceeded());
        assertEquals("paused",loop.session().taskState.status);
        assertTrue(notices.toString().contains("未标记为完成"));
        assertTrue(llm.completeChatRequests.isEmpty());
    }
    @Test public void ordinaryQuestionNeverRequiresAction() {
        llm.addTurn("Bash是命令行工具。");loop.runUserTurn("Bash是什么？");
        assertEquals(1,llm.requests.size()); assertEquals(0,llm.actionRecoveryCalls);
        assertTrue(loop.lastTurnSucceeded());
    }
    @Test public void streamingLoopHasFiniteBudgetAndNeverReportsCompletion() {
        llm.addTurn(com.minion.core.llm.RepetitionGuardTest.loopText());
        loop.runUserTurn("校验result_1并交付");
        assertEquals(4,llm.requests.size());assertEquals(3,llm.actionRecoveryCalls);
        assertFalse(loop.lastTurnSucceeded());assertEquals("paused",loop.session().taskState.status);
        assertTrue(notices.toString().contains("恢复3次仍失败"));
        assertFalse(loop.messages().stream().anyMatch(m->m.content!=null&&m.content.contains("good")));
    }
    @Test public void longScreenshotLoopAlsoEscalatesInsteadOfOnlyRepeatingCompletionHint() {
        StringBuilder repeat=new StringBuilder();
        for(int i=0;i<40;i++) repeat.append("我现在实际调用Bash执行构建补全清单脚本。执行工具调用。现在。\n");
        repeat.append("先调用Bash构建补全清单并分块。");assertTrue(repeat.length()>1000);
        scan("start");llm.addTurn(repeat.toString());llm.addTurn(repeat.toString());
        scan("actual");llm.addTurn("全部交付。"+AgentLoop.TASK_COMPLETE_MARKER);
        loop.runUserTurn("补全353行");assertTrue(loop.lastTurnSucceeded());assertEquals(2,llm.actionRecoveryCalls);
        assertTrue(llm.completeChatRequests.isEmpty());
        assertFalse(loop.messages().stream().anyMatch(m -> repeat.toString().equals(m.content)));
    }
}
