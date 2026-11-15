package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.llm.*;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.*;
import com.minion.gui.session.EventList;
import com.minion.gui.session.SessionController;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 图片回答穿过主循环、请求投影、持久化和历史回放，仍属于同一个任务。 */
public class QuestionImageContinuationTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void imageAnswerFollowsAllPairedToolsAndSurvivesReloadWithoutNewTask() throws Exception {
        Path dir = tmp.newFolder("question-image").toPath();
        Files.write(dir.resolve("note.txt"), "已有依据".getBytes(StandardCharsets.UTF_8));
        Workspace workspace = new Workspace(dir.toString());
        ToolRegistry tools = new ToolRegistry(); tools.register(new ReadTool(workspace));
        FakeLlmClient llm = new FakeLlmClient();
        ToolCall ask = new ToolCall(); ask.id = "ask-pixels"; ask.name = "AskUserQuestion";
        ask.arguments = "{\"question\":\"请提供错误截图\"}";
        ToolCall read = new ToolCall(); read.id = "read-note"; read.name = "Read"; read.arguments = "{\"path\":\"note.txt\"}";
        llm.addTurnWithTools(Arrays.asList(ask, read), null); llm.addTurn("根据截图和已有依据，问题原因已说明。");
        llm.stopStatuses.add("completed");
        CountDownLatch waiting = new CountDownLatch(1);
        SessionController controller = new SessionController();
        controller.setAskStateListener(q -> { if (q != null) waiting.countDown(); });
        Session session = Session.create(dir.toString(), "fake");
        SessionStore store = new SessionStore(dir.resolve("sessions"));
        ConfirmGate gate = new ConfirmGate(Config.load(dir), new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        com.minion.core.context.ContextManager context = new com.minion.core.context.ContextManager(1000000, 0.9, 12, llm, 0);
        AgentLoop loop = new AgentLoop(llm, tools, new SystemPromptBuilder(null), gate, controller, context, workspace, session);
        loop.setSessionStore(store);
        Thread runner = new Thread(() -> loop.runUserTurn("这个错误是什么原因？"));
        runner.setDaemon(true); runner.start();
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            String taskId = session.taskState.id; long revision = session.taskState.revision();
            ImagePart image = AskUserImageAnswerTest.image("错误截图.png");
            assertTrue(loop.answerAskUser("看截图", Collections.singletonList(image)));
            runner.join(10000); assertFalse("回答后没有继续执行", runner.isAlive());
            assertEquals(taskId, session.taskState.id); assertEquals(revision, session.taskState.revision());
            assertEquals("这个错误是什么原因？", session.taskState.latestRequirement());
            assertTrue(llm.requests.size() >= 2);
            List<Message> request = llm.requests.get(1).messages;
            int imageIndex = -1, askIndex = -1, readIndex = -1;
            for (int i = 0; i < request.size(); i++) {
                Message message = request.get(i);
                if ("ask-pixels".equals(message.toolCallId)) askIndex = i;
                if ("read-note".equals(message.toolCallId)) readIndex = i;
                if ("ask-pixels".equals(message.replyToToolCallId)) imageIndex = i;
            }
            assertTrue(askIndex >= 0); assertTrue(readIndex >= 0);
            assertTrue("图片必须在全部工具回执之后", imageIndex > Math.max(askIndex, readIndex));
            Message pixels = request.get(imageIndex);
            assertTrue(pixels.toolImage); assertFalse(pixels.isUserInstruction());
            assertEquals(image.base64, pixels.images.get(0).base64);
            assertFalse(pixels.toApiJson().has("replyToToolCallId"));
            assertEquals("image_url", pixels.toApiJson().getAsJsonArray("content").get(1).getAsJsonObject().get("type").getAsString());

            Session restored = store.load(session.id);
            Message saved = restored.messages.stream().filter(m -> "ask-pixels".equals(m.replyToToolCallId)).findFirst().orElse(null);
            assertNotNull(saved); assertEquals(image.base64, saved.images.get(0).base64);
            assertEquals(taskId, saved.taskId); assertFalse(saved.isUserInstruction());
            SessionController replay = new SessionController(); replay.replayHistory(restored.messages);
            for (SessionController view : Arrays.asList(controller, replay)) {
                List<EventList.Ev> events = view.eventList().snapshot();
                assertEquals(1, events.stream().filter(e -> e.kind == EventList.Kind.USER_SUPPLEMENT && "图片：错误截图.png 看截图".equals(e.text)).count());
                assertEquals(1, events.stream().filter(e -> e.kind == EventList.Kind.USER_MESSAGE).count());
                assertFalse(events.stream().anyMatch(e -> e.text != null && e.text.contains(image.base64)));
            }
        } finally { loop.interrupt(); runner.interrupt(); runner.join(5000); loop.shutdown(); }
    }
}
