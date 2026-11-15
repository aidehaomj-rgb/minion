package com.minion.gui.session;
import com.minion.core.agent.GeneratedArtifact;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SessionArtifactEventsTest {
    @Test public void orphanGuidanceIsRecoveredBeforeHistoryCreatesItsSingleRow() {
        com.minion.core.agent.Session s=com.minion.core.agent.Session.create(".","test");s.taskState.id="task";
        com.minion.core.llm.Message m=com.minion.core.llm.Message.realUser("这是原始完整引导");m.steeringId="orphan";m.eventId="event-orphan";m.taskId="task";m.taskRevision=1L;s.messages.add(m);
        String original=new com.google.gson.Gson().toJson(m);s.recoverSteeringReceipts();s.recoverSteeringReceipts();
        SessionController controller=new SessionController();controller.replayHistory(s.messages,s.steeringSnapshot());
        assertEquals(1,controller.eventList().snapshot().stream().filter(e->e.kind==EventList.Kind.USER_SUPPLEMENT).count());
        assertEquals(1,controller.eventList().size());assertEquals("这是原始完整引导",controller.eventList().snapshot().get(0).text);
        assertEquals(original,new com.google.gson.Gson().toJson(m));
    }
    @Test public void createdFilePublishesUiLinkBeforeListenerWithoutFakeUserMessage() {
        SessionController controller=new SessionController();List<String> notifications=new ArrayList<String>();
        controller.setArtifactListener(path->{assertEquals(EventList.Kind.ARTIFACT,controller.eventList().snapshot().get(0).kind);notifications.add(path);});
        controller.onArtifactCreated("D:/out/report.docx");
        assertEquals(Collections.singletonList("D:/out/report.docx"),notifications);
        assertEquals(1,controller.eventList().size());assertEquals("D:/out/report.docx",controller.eventList().snapshot().get(0).text);
        controller.onArtifactCreated(" ");assertEquals(1,controller.eventList().size());
    }
    @Test public void replaySummarizesHistoricalFilesWithoutAutopreviewOrPushingLatestReplyAway() {
        SessionController controller=new SessionController();controller.setArtifactListener(p->{throw new AssertionError("history must not auto-open");});
        GeneratedArtifact a=new GeneratedArtifact();a.path="D:/历史/报告.md";
        GeneratedArtifact b=new GeneratedArtifact();b.path="D:/历史/附件.txt";
        GeneratedArtifact record=new GeneratedArtifact();record.path="D:/历史/任务执行记录.md";record.kind=GeneratedArtifact.EXECUTION_RECORD;
        controller.replayArtifacts(Arrays.asList(a,null,b,record));
        assertEquals(EventList.Kind.ARTIFACT_ARCHIVE,controller.eventList().snapshot().get(0).kind);
        assertEquals("2",controller.eventList().snapshot().get(0).text);
        assertEquals(1,controller.eventList().pageBefore(1,150).events.size());
    }
}
