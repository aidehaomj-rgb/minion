package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ProjectMemoryStore;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.*;

public class AgentMemoryRecallTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();

    private AgentLoop loop(Path project,Path app,FakeLlmClient llm) {
        Workspace ws=new Workspace(project.toString());
        AgentLoop loop=new AgentLoop(llm,new ToolRegistry(),new SystemPromptBuilder(null),
                new ConfirmGate(Config.load(app),new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new AgentUi(){},null,ws,Session.create(ws.workDir(),"fake"));
        loop.setGlobalMemoryRoot(app);
        return loop;
    }

    @Test public void newConversationReceivesBoundedVerifiedIndexWithoutPrivateCrossProjectChat() throws Exception {
        Path app=tmp.newFolder("app").toPath();
        Path a=tmp.newFolder("project-a").toPath();Path b=tmp.newFolder("project-b").toPath();
        ProjectMemoryStore first=new ProjectMemoryStore(a);
        ProjectMemoryStore.Entry privateFact=first.remember("decision","项目一数据库仅在内网","已核验项目资料");
        first.setVerified(privateFact.id,true);
        ProjectMemoryStore global=ProjectMemoryStore.global(app);
        ProjectMemoryStore.Entry preference=global.remember("feedback","不要修改无关项目文件","用户显式共享");
        global.setVerified(preference.id,true);
        FakeLlmClient modelA=new FakeLlmClient();modelA.addTurn("收到");
        AgentLoop loopA=loop(a,app,modelA);
        try {
            loopA.runUserTurn("帮我写一个分析脚本");
            String request=content(modelA);
            assertTrue(request.contains("项目一数据库仅在内网"));
            assertTrue(request.contains("不要修改无关项目文件"));
            assertEquals(2,loopA.messages().size());
            assertEquals(Message.Role.USER,loopA.messages().get(0).role);
        } finally {loopA.shutdown();}
        FakeLlmClient modelB=new FakeLlmClient();modelB.addTurn("收到");
        AgentLoop loopB=loop(b,app,modelB);
        try {
            loopB.runUserTurn("帮我写一个分析脚本");
            String request=content(modelB);
            assertFalse(request.contains("项目一数据库仅在内网"));
            assertTrue(request.contains("不要修改无关项目文件"));
        } finally {loopB.shutdown();}
    }

    @Test public void optionalExtractionCreatesUnverifiedCandidateAfterSuccessfulTurn() throws Exception {
        Path app=tmp.newFolder("app2").toPath();Path project=tmp.newFolder("project").toPath();
        ProjectMemoryStore memory=new ProjectMemoryStore(project);memory.setAutoExtractEnabled(true);
        FakeLlmClient model=new FakeLlmClient();model.addTurn("收到");model.stopStatuses.add("completed");
        AgentLoop loop=loop(project,app,model);
        try {
            loop.runUserTurn("以后都先核对文件再修改");
            assertEquals(1,memory.list().size());
            assertFalse(memory.list().get(0).verified);
            assertTrue(memory.search("先核对文件",5).isEmpty());
        } finally {loop.shutdown();}
    }

    @Test public void globalIdentityIsVisibleAcrossProjectsOnlyAfterReviewAndWhileUseEnabled() throws Exception {
        Path app=tmp.newFolder("identity-app").toPath();
        Path project=tmp.newFolder("identity-project").toPath();
        ProjectMemoryStore profile=ProjectMemoryStore.global(app);
        profile.setGlobalGenerateEnabled(true);
        FakeLlmClient first=new FakeLlmClient();first.addTurn("收到");first.stopStatuses.add("completed");
        AgentLoop capture=loop(project,app,first);
        try { capture.runUserTurn("我是数据分析师"); }
        finally { capture.shutdown(); }
        assertEquals(1,profile.list().size());
        assertFalse(profile.list().get(0).verified);
        Path other=tmp.newFolder("other-project").toPath();
        FakeLlmClient before=new FakeLlmClient();before.addTurn("收到");
        AgentLoop noRecall=loop(other,app,before);
        try {noRecall.runUserTurn("做一个表格");assertFalse(content(before).contains("数据分析师"));}
        finally {noRecall.shutdown();}
        profile.setVerified(profile.list().get(0).id,true);
        profile.setGlobalUseEnabled(false);
        FakeLlmClient disabled=new FakeLlmClient();disabled.addTurn("收到");
        AgentLoop off=loop(other,app,disabled);
        try {off.runUserTurn("做一个表格");assertFalse(content(disabled).contains("数据分析师"));}
        finally {off.shutdown();}
        profile.setGlobalUseEnabled(true);
        FakeLlmClient enabled=new FakeLlmClient();enabled.addTurn("收到");
        AgentLoop on=loop(other,app,enabled);
        try {on.runUserTurn("做一个表格");assertTrue(content(enabled).contains("数据分析师"));}
        finally {on.shutdown();}
    }

    private static String content(FakeLlmClient client) {
        StringBuilder out=new StringBuilder();
        for(Message message:client.requests.get(0).messages)
            if(message.content!=null)out.append(message.content).append('\n');
        return out.toString();
    }
}
