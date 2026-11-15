package com.minion.core.agent;

import com.google.gson.Gson;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SteeringTrackerTest {
    private Session session(){Session s=Session.create(".","fake");s.taskState.begin("任务",true);return s;}
    private Message inserted(Session s,String id,String text) {
        SteeringTracker.create(s,id,text,s.taskState.id,s.taskState.revision());
        Message m=Message.realUser(text);m.supplement=true;m.steeringId=id;m.taskId=s.taskState.id;m.taskRevision=s.taskState.revision();
        s.messages.add(m);SteeringTracker.inserted(s,m);return m;
    }
    @Test public void nullableFieldsDoNotChangeLegacyJsonAndSnapshotsAreDeepBounded() {
        Gson gson=new Gson();Session s=gson.fromJson("{}",Session.class);
        assertNull(s.steeringReceipts);assertNull(s.pendingSteeringIds);assertTrue(s.steeringSnapshot().isEmpty());
        assertFalse(gson.toJson(s).contains("steeringReceipts"));assertFalse(gson.toJson(s).contains("pendingSteeringIds"));
        Message m=gson.fromJson("{\"role\":\"USER\",\"content\":\"old\"}",Message.class);
        String old=gson.toJson(m);assertNull(m.steeringId);assertFalse(old.contains("steeringId"));
        s=session();for(int i=0;i<105;i++)SteeringTracker.create(s,"id"+i,"原文",s.taskState.id,1L);
        assertEquals(100,s.steeringSnapshot().size());List<SteeringReceipt> copy=s.steeringSnapshot();copy.get(0).state="read";copy.clear();
        assertEquals("unread",s.steeringSnapshot().get(0).state);
    }
    @Test public void requestSnapshotDoesNotConfirmMidstreamNewOrSameTextReceipt() {
        Session s=session();Message a=inserted(s,"one","同文");
        SteeringTracker.Request request=SteeringTracker.snapshot(s,Collections.singletonList(a),1);
        SteeringTracker.sending(s,request);inserted(s,"two","同文");
        SteeringTracker.feedback(s,request);
        assertTrue(s.steeringSnapshot().get(0).isRead());assertEquals("inserted",s.steeringSnapshot().get(1).state);
        assertEquals(1,request.ids.size());assertEquals("one",request.ids.get(0));
    }
    @Test public void failedAttemptRetryAndOldAttemptAreDistinctAndReadNeverDowngrades() {
        Session s=session();Message m=inserted(s,"one","要求");
        SteeringTracker.Request a=SteeringTracker.snapshot(s,Collections.singletonList(m),1);SteeringTracker.sending(s,a);SteeringTracker.failed(s,a);
        assertEquals("unconfirmed",s.steeringSnapshot().get(0).state);
        SteeringTracker.Request b=SteeringTracker.snapshot(s,Collections.singletonList(m),1);SteeringTracker.sending(s,b);
        assertTrue(SteeringTracker.feedback(s,a).isEmpty());assertEquals("sending",s.steeringSnapshot().get(0).state);
        SteeringTracker.feedback(s,b);long readAt=s.steeringSnapshot().get(0).readAt;
        assertTrue(SteeringTracker.failed(s,b).isEmpty());assertEquals(readAt,s.steeringSnapshot().get(0).readAt);
    }
    @Test public void recoveryDoesNotInventReadAndLegacyPendingAlignmentPreservesText() {
        Session s=session();Message m=inserted(s,"one","要求");
        SteeringTracker.Request r=SteeringTracker.snapshot(s,Collections.singletonList(m),1);SteeringTracker.sending(s,r);
        SteeringTracker.recover(s);assertEquals("unconfirmed",s.steeringSnapshot().get(0).state);
        s.pendingSupplements.add("完整旧文本");SteeringTracker.alignPending(s);
        assertEquals(Collections.singletonList("完整旧文本"),s.pendingSupplements);assertEquals(1,s.pendingSteeringIds.size());assertNull(s.pendingSteeringIds.get(0));
    }
    @Test public void compactProtectsOnlyCurrentTaskAndKeepsFullLatestOriginalAndProjectionId() {
        Session s=session();Message old=inserted(s,"old",String.join("",Collections.nCopies(200000,"旧")));
        s.taskState.begin("新任务",true);String full=String.join("",Collections.nCopies(15000,"新"));
        Message current=inserted(s,"current",full);
        Message summary=Message.user("summary");summary.summary=true;
        List<Message> protectedMessages=SteeringTracker.protect(s,Arrays.asList(old,current),Collections.singletonList(summary));
        assertFalse(protectedMessages.contains(old));assertTrue(protectedMessages.contains(current));assertEquals(full,current.content);
        assertTrue(s.steeringSnapshot().get(1).text.contains("显示节选，原文保留"));assertTrue(s.steeringSnapshot().get(1).text.length()<=2000);
        ContextManager cm=new ContextManager(1000000,.9,12,new FakeLlmClient(),0);
        assertEquals("current",cm.prepareForRequest(Collections.singletonList(current)).get(0).steeringId);
        assertEquals(full,cm.prepareForRequest(Collections.singletonList(current)).get(0).content);
    }

    /** A checkpoint holds only the latest 100 receipts, while immutable history retains every ID. */
    private Session readCheckpoint(int historyCount) {
        Session s=session();s.steeringReceipts=new ArrayList<SteeringReceipt>();
        for(int i=1;i<=historyCount;i++) {
            Message m=Message.realUser("instruction-"+i);m.supplement=true;m.steeringId="id-"+i;
            m.taskId=s.taskState.id;m.taskRevision=1L;m.ts=1000L+i;s.messages.add(m);
            if(i>historyCount-100) {
                SteeringReceipt r=new SteeringReceipt();r.id=m.steeringId;r.text=m.content;r.state="read";
                r.eventId=m.eventId;r.requestId="request-"+i;r.taskId=m.taskId;r.revision=1L;
                r.createdAt=m.ts;r.updatedAt=100000L+i;r.readAt=r.updatedAt;s.steeringReceipts.add(r);
            }
        }
        Gson gson=new Gson();return gson.fromJson(gson.toJson(s),Session.class);
    }
    private void assertOverflowPreservesRead(int count) {
        Session s=readCheckpoint(count);Gson gson=new Gson();
        String originalMessages=gson.toJson(s.messages),originalReceipts=gson.toJson(s.steeringSnapshot());
        assertTrue(SteeringTracker.recover(s).isEmpty());
        assertEquals(100,s.steeringSnapshot().size());assertTrue(s.steeringSnapshot().stream().allMatch(SteeringReceipt::isRead));
        assertEquals(originalReceipts,gson.toJson(s.steeringSnapshot()));
        assertEquals(originalMessages,gson.toJson(s.messages));
        assertTrue(SteeringTracker.recover(s).isEmpty());
        assertEquals(originalReceipts,gson.toJson(s.steeringSnapshot()));
        assertEquals(originalMessages,gson.toJson(s.messages));
    }
    @Test public void recoveryExactly100ControlPreservesReadAndTimes(){assertOverflowPreservesRead(100);}
    @Test public void recovery101HistoryPreservesLast100ReadAndTimes(){assertOverflowPreservesRead(101);}
    @Test public void recovery140HistoryPreservesLast100ReadAndTimes(){assertOverflowPreservesRead(140);}

    @Test public void newJournalOrphanAfterCheckpointSurvivesCutAndRepeatedRecoverIsIdempotent() {
        Session s=readCheckpoint(140);Gson gson=new Gson();
        Message orphan=Message.realUser("journal tail full instruction");orphan.supplement=true;orphan.steeringId="tail";
        orphan.taskId=s.taskState.id;orphan.taskRevision=2L;orphan.ts=1141;s.messages.add(orphan);
        String originalMessages=gson.toJson(s.messages);
        List<SteeringReceipt> changed=SteeringTracker.recover(s);
        assertEquals(1,changed.size());SteeringReceipt restored=changed.get(0);
        assertEquals("tail",restored.id);assertEquals("inserted",restored.state);assertEquals(orphan.eventId,restored.eventId);
        assertEquals(orphan.ts,restored.createdAt);assertEquals(orphan.ts,restored.updatedAt);assertEquals(0,restored.readAt);
        assertEquals(100,s.steeringSnapshot().size());assertEquals(99,s.steeringSnapshot().stream().filter(SteeringReceipt::isRead).count());
        assertEquals("id-42",s.steeringSnapshot().get(0).id);
        String first=gson.toJson(s.steeringSnapshot());
        assertTrue(SteeringTracker.recover(s).isEmpty());assertEquals(first,gson.toJson(s.steeringSnapshot()));
        assertEquals(originalMessages,gson.toJson(s.messages));
    }

    @Test public void zeroTimestampOrphansUseHistoricalOrderNotCurrentTime() {
        Session s=readCheckpoint(140);Gson gson=new Gson();
        // No historical timestamp is available: checkpoint receipt times anchor the ordering.
        for(Message m:s.messages)m.ts=0;
        Message tail=Message.realUser("legacy tail");tail.supplement=true;tail.steeringId="legacy-tail";
        tail.taskId=s.taskState.id;tail.taskRevision=1L;tail.ts=0;s.messages.add(tail);
        String originalMessages=gson.toJson(s.messages);
        List<SteeringReceipt> changed=SteeringTracker.recover(s);
        assertEquals(1,changed.size());assertEquals("legacy-tail",changed.get(0).id);
        assertEquals(0,changed.get(0).createdAt);assertEquals(0,changed.get(0).updatedAt);
        assertEquals(99,s.steeringSnapshot().stream().filter(SteeringReceipt::isRead).count());
        String first=gson.toJson(s.steeringSnapshot());assertTrue(SteeringTracker.recover(s).isEmpty());
        assertEquals(first,gson.toJson(s.steeringSnapshot()));assertEquals(originalMessages,gson.toJson(s.messages));
    }

    @Test public void recoveryPreservesExistingAuthorityAndSendingTimesAndDoesNotReviveOtherTasks() {
        Session s=session();Message sending=inserted(s,"sending","old instruction");sending.ts=100;
        SteeringReceipt stored=s.steeringReceipts.get(0);stored.state="sending";stored.createdAt=100;stored.updatedAt=200;
        stored.requestId="inflight";stored.eventId="existing-event";stored.revision=7L;
        s.taskState.begin("new task",true);
        Message oldOrphan=Message.realUser("old orphan");oldOrphan.steeringId="old-orphan";oldOrphan.taskId=sending.taskId;oldOrphan.ts=101;s.messages.add(oldOrphan);
        Message current=Message.realUser("current");current.steeringId="current";current.taskId=s.taskState.id;current.ts=300;s.messages.add(current);
        Gson gson=new Gson();String originalMessages=gson.toJson(s.messages);
        List<SteeringReceipt> changed=SteeringTracker.recover(s);assertEquals(3,changed.size());
        SteeringReceipt recovered=s.steeringSnapshot().stream().filter(r->"sending".equals(r.id)).findFirst().get();
        assertEquals("unconfirmed",recovered.state);assertEquals(100,recovered.createdAt);assertEquals(200,recovered.updatedAt);
        assertEquals("inflight",recovered.requestId);assertEquals("existing-event",recovered.eventId);assertEquals(Long.valueOf(7),recovered.revision);
        Message summary=Message.user("summary");summary.summary=true;
        List<Message> protectedMessages=SteeringTracker.protect(s,s.messages,Collections.singletonList(summary));
        assertFalse(protectedMessages.contains(sending));assertFalse(protectedMessages.contains(oldOrphan));assertTrue(protectedMessages.contains(current));
        String first=gson.toJson(s.steeringSnapshot());assertTrue(SteeringTracker.recover(s).isEmpty());
        assertEquals(first,gson.toJson(s.steeringSnapshot()));assertEquals(originalMessages,gson.toJson(s.messages));
    }
}
