package com.minion.core.agent;

import com.google.gson.Gson;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.Assert.*;

public class SessionArtifactsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private GeneratedArtifact artifact(String path, String kind) {
        GeneratedArtifact a=new GeneratedArtifact();a.path=path;a.kind=kind;a.taskId="task";a.revision=1L;a.updatedAt=1;return a;
    }

    @Test public void oldMissingOrNullFieldStaysNullEvenAfterSnapshotAndJournalReplay() throws Exception {
        Gson gson=new Gson();
        for(String json:new String[]{"{}","{\"artifacts\":null}"}) {
            Session s=gson.fromJson(json,Session.class);assertNull(s.artifacts);assertTrue(s.artifactsSnapshot().isEmpty());
            assertNull(s.artifacts);assertFalse(gson.toJson(s).contains("\"artifacts\""));
        }
        Path root=temp.newFolder().toPath();SessionStore store=new SessionStore(root.resolve("sessions"));
        Session old=Session.create(root.toString(),"fixture");old.taskState.begin("原用户任务",true);
        old.messages.add(Message.realUser("原用户任务"));store.save(old);
        // Replay a real checksummed event newer than the checkpoint, with no new artifact field.
        store.appendMessage(old,Message.realUser("补充用户要求"));
        Session restored=new SessionStore(root.resolve("sessions")).load(old.id);
        assertEquals(2,restored.messages.size());assertNull(restored.artifacts);assertTrue(restored.artifactsSnapshot().isEmpty());
    }

    @Test public void duplicateUpdatesAreDetachedBoundedAndReferencesDoNotDowngradeDeliverables() {
        Session s=new Session();s.registerArtifact(artifact("same",GeneratedArtifact.DELIVERABLE));
        long before=s.artifactsSnapshot().get(0).updatedAt;
        GeneratedArtifact update=artifact("same",GeneratedArtifact.DELIVERABLE);update.size=55;s.registerArtifact(update);
        assertEquals(1,s.artifacts.size());assertTrue(s.artifacts.get(0).updatedAt>before);assertEquals(55,s.artifacts.get(0).size);
        update.path="mutated input";List<GeneratedArtifact> snapshot=s.artifactsSnapshot();snapshot.get(0).path="mutated copy";snapshot.clear();
        assertEquals("same",s.artifacts.get(0).path);
        assertNull(s.registerArtifact(artifact("same",GeneratedArtifact.REFERENCE)));
        assertEquals(GeneratedArtifact.DELIVERABLE,s.artifacts.get(0).kind);
        for(int i=0;i<110;i++)s.registerArtifact(artifact("file"+i,GeneratedArtifact.DELIVERABLE));
        assertEquals(100,s.artifacts.size());assertEquals(100,s.artifactsSnapshot().size());
        assertEquals("file10",s.artifactsSnapshot().get(0).path);
    }

    @Test public void receiptsSurviveAtomicSaveAndTailEventReplayWithNullableLegacyFields() throws Exception {
        Path root=temp.newFolder().toPath();Session s=Session.create(root.toString(),"fixture");
        s.taskState.begin("task",true);s.messages.add(Message.realUser("task"));
        s.registerArtifact(artifact("deliverable",GeneratedArtifact.DELIVERABLE));
        s.registerArtifact(artifact("record",GeneratedArtifact.EXECUTION_RECORD));
        GeneratedArtifact old=new Gson().fromJson("{\"path\":\"old\",\"kind\":\"reference\"}",GeneratedArtifact.class);
        assertNull(old.revision);assertNull(old.sourceTool);s.registerArtifact(old);
        SessionStore store=new SessionStore(root.resolve("sessions"));store.save(s);store.appendMessage(s,Message.assistant("后续事件"));
        Session restored=new SessionStore(root.resolve("sessions")).load(s.id);
        assertEquals(2,restored.messages.size());assertEquals(3,restored.artifactsSnapshot().size());
        assertEquals(GeneratedArtifact.EXECUTION_RECORD,restored.artifactsSnapshot().get(1).kind);
        assertNull(restored.artifactsSnapshot().get(2).revision);
    }
    @Test public void deliverableUpdatesKeepARealPreviousFileSnapshot() throws Exception {
        Path root=temp.newFolder().toPath(),file=root.resolve("报告.md");
        Session s=Session.create(root.toString(),"fixture");
        Files.write(file,"v1".getBytes(StandardCharsets.UTF_8));
        s.registerArtifact(artifact(file.toString(),GeneratedArtifact.DELIVERABLE));
        Files.write(file,"v2 review".getBytes(StandardCharsets.UTF_8));
        GeneratedArtifact update=artifact(file.toString(),GeneratedArtifact.DELIVERABLE);
        update.revision=2L;update.updatedAt=2;
        s.registerArtifact(update);
        GeneratedArtifact current=s.artifactsSnapshot().get(0);
        assertEquals(1,current.history.size());
        assertEquals("v1",new String(Files.readAllBytes(Paths.get(current.history.get(0).snapshotPath)),StandardCharsets.UTF_8));
        assertEquals("v2 review",new String(Files.readAllBytes(Paths.get(current.snapshotPath)),StandardCharsets.UTF_8));
        assertNotEquals(current.snapshotPath,current.history.get(0).snapshotPath);
    }
}
