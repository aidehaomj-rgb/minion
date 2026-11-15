package com.minion.core.tools.context;

import com.google.gson.JsonObject;
import com.minion.core.agent.Session;
import com.minion.core.llm.Message;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.Workspace;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class MemoryToolHistoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void searchesArchivedOriginalsAndTypedMemories() throws Exception {
        Workspace workspace = new Workspace(temp.getRoot().getPath());
        Session session = Session.create(workspace.workDir(), "test");
        SessionStore sessions = new SessionStore(temp.newFolder("sessions").toPath());
        Message old = Message.user("原始证据位于第四批明细表");
        Message current = Message.user("继续");
        sessions.archiveCompacted(session, Arrays.asList(old, current), Collections.singletonList(current));
        MemoryTool tool = new MemoryTool(workspace, null, sessions, session.id);
        JsonObject search = new JsonObject();
        search.addProperty("action", "search_history");
        search.addProperty("query", "第四批明细表");
        assertTrue(tool.execute(search).output.contains("原始证据"));

        JsonObject remember = new JsonObject();
        remember.addProperty("action", "remember");
        remember.addProperty("type", "feedback");
        remember.addProperty("content", "压缩后先核对当前任务");
        remember.addProperty("source", "用户明确要求");
        assertTrue(tool.isHighRisk(remember));
        assertTrue(tool.execute(remember).ok);
        JsonObject searchMemory = new JsonObject();
        searchMemory.addProperty("action", "search");
        searchMemory.addProperty("query", "当前任务");
        assertEquals("未找到", tool.execute(searchMemory).output);
        JsonObject verify = new JsonObject();
        verify.addProperty("action", "verify");
        verify.addProperty("id", new com.minion.core.context.ProjectMemoryStore(temp.getRoot().toPath()).list().get(0).id);
        assertTrue(tool.isHighRisk(verify));
        assertTrue(tool.execute(verify).ok);
        assertTrue(tool.execute(searchMemory).output.contains("压缩后先核对"));
    }
}
