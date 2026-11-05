package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.RecordingUi;
import com.minion.core.llm.ImagePart;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 回答提问的图片快照、编号含义、取消与共享等待；不依赖真实模型或 JavaFX。 */
public class AskUserImageAnswerTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/M0AAAAASUVORK5CYII=";

    public static ImagePart image(String name) {
        ImagePart image = new ImagePart();
        image.mime = "image/png"; image.base64 = PNG; image.name = name;
        return image;
    }

    private static final class Waiting implements AutoCloseable {
        final CountDownLatch ready = new CountDownLatch(1);
        final RecordingUi ui = new RecordingUi() {
            @Override public void onAskUserStart(String q) { super.onAskUserStart(q); ready.countDown(); }
        };
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        final CompletableFuture<ToolResult> result = new CompletableFuture<ToolResult>();
        final Thread worker;

        Waiting(boolean choices) throws Exception {
            JsonObject args = JsonParser.parseString(choices
                    ? "{\"question\":\"选哪个方案？\",\"options\":[{\"label\":\"只排查\"},{\"label\":\"修复错误\",\"description\":\"根据截图修复\"}]}"
                    : "{\"question\":\"能提供错误截图吗？\"}").getAsJsonObject();
            worker = new Thread(() -> {
                try { result.complete(tool.execute(args)); }
                catch (Exception e) { result.completeExceptionally(e); }
            }, "ask-image-test");
            worker.setDaemon(true); worker.start();
            assertTrue("没有进入提问等待", ready.await(5, TimeUnit.SECONDS));
        }
        ToolResult get() throws Exception { return result.get(5, TimeUnit.SECONDS); }
        @Override public void close() throws Exception { worker.interrupt(); worker.join(5000); }
    }

    @Test public void textAndImageReturnMetadataAndActualPixels() throws Exception {
        try (Waiting wait = new Waiting(false)) {
            assertTrue(wait.tool.complete("截图中有错误", Collections.singletonList(image("错误.png"))));
            ToolResult result = wait.get();
            assertTrue(result.ok);
            assertEquals(PNG, result.images.get(0).base64);
            JsonObject answer = JsonParser.parseString(result.output).getAsJsonObject();
            assertEquals("截图中有错误", answer.get("answer").getAsString());
            assertEquals("能提供错误截图吗？", answer.get("question").getAsString());
            assertEquals("answered_with_images", answer.get("answerStatus").getAsString());
            assertFalse("工具正文不能重复存像素", result.output.contains(PNG));
            assertEquals("图片：错误.png 截图中有错误", wait.ui.asksDone.get(0));
            assertEquals(wait.ui.asksDone.get(0), AskUserQuestionTool.answerForDisplay(result.output));
        }
    }

    @Test public void imageOnlyIsAnAnswerNotAnEmptySubmission() throws Exception {
        try (Waiting wait = new Waiting(false)) {
            assertTrue(wait.tool.complete(null, Collections.singletonList(image("截图.png"))));
            ToolResult result = wait.get();
            assertEquals("", JsonParser.parseString(result.output).getAsJsonObject().get("answer").getAsString());
            assertEquals("图片：截图.png", AskUserQuestionTool.answerForDisplay(result.output));
            assertEquals(1, wait.ui.asksDone.size());
        }
    }

    @Test public void numericChoiceWithImageKeepsOptionMeaning() throws Exception {
        try (Waiting wait = new Waiting(true)) {
            assertTrue(wait.tool.complete("2", Collections.singletonList(image("依据.png"))));
            JsonObject answer = JsonParser.parseString(wait.get().output).getAsJsonObject();
            assertEquals("2", answer.get("answer").getAsString());
            assertEquals("修复错误", answer.getAsJsonArray("selectedOptions").get(0).getAsJsonObject().get("label").getAsString());
            assertEquals(1, answer.getAsJsonArray("imageAttachments").size());
            assertEquals("图片：依据.png 2", wait.ui.asksDone.get(0));
        }
    }

    @Test public void attachmentsAreCopiedBeforeCompletingFuture() throws Exception {
        try (Waiting wait = new Waiting(false)) {
            ImagePart first = image("一.png"), second = image("二.png");
            List<ImagePart> draft = new ArrayList<ImagePart>(Arrays.asList(first, second));
            assertTrue(wait.tool.complete("两张图", draft));
            first.name = "被修改"; first.base64 = ""; draft.clear();
            ToolResult result = wait.get();
            assertEquals(2, result.images.size());
            assertEquals("一.png", result.images.get(0).name);
            assertEquals(PNG, result.images.get(0).base64);
        }
    }

    @Test public void invalidAttachmentsDoNotConsumeThePendingQuestion() throws Exception {
        try (Waiting wait = new Waiting(false)) {
            assertFalse(wait.tool.complete(" ", Collections.<ImagePart>emptyList()));
            assertFalse(wait.tool.complete("", Arrays.asList(image("1"), image("2"), image("3"), image("4"))));
            assertFalse(wait.tool.complete("不要丢失", Collections.<ImagePart>singletonList(null)));
            ImagePart invalid = image("坏图"); invalid.base64 = "%%%";
            assertFalse(wait.tool.complete("", Collections.singletonList(invalid)));
            invalid = image("坏类型"); invalid.mime = "text/html";
            assertFalse(wait.tool.complete("", Collections.singletonList(invalid)));
            assertFalse(wait.result.isDone());
            assertTrue(wait.tool.complete("最终回答", Collections.singletonList(image("有效.png"))));
            assertEquals("最终回答", JsonParser.parseString(wait.get().output).getAsJsonObject().get("answer").getAsString());
        }
    }

    @Test public void absentCancelledAndRepeatedAnswersAreRejected() throws Exception {
        assertFalse(new AskUserQuestionTool(new RecordingUi()).complete("", Collections.singletonList(image("图.png"))));
        try (Waiting wait = new Waiting(false)) {
            wait.worker.interrupt(); wait.worker.join(5000);
            assertFalse(wait.worker.isAlive());
            assertFalse(wait.tool.complete("迟到", Collections.singletonList(image("图.png"))));
            assertTrue(wait.ui.asksDone.isEmpty());
        }
        try (Waiting wait = new Waiting(false)) {
            assertTrue(wait.tool.complete("第一份", Collections.singletonList(image("原图.png"))));
            assertFalse(wait.tool.complete("第二份", Collections.singletonList(image("新图.png"))));
            assertEquals("原图.png", wait.get().images.get(0).name);
        }
    }

    @Test public void simultaneousQuestionsReturnPixelsAndUiAnswerOnlyOnce() throws Exception {
        try (Waiting wait = new Waiting(false)) {
            CompletableFuture<ToolResult> second = new CompletableFuture<ToolResult>();
            Thread follower = new Thread(() -> {
                try { second.complete(wait.tool.execute(JsonParser.parseString("{\"question\":\"另一个提问\"}").getAsJsonObject())); }
                catch (Exception e) { second.completeExceptionally(e); }
            });
            follower.setDaemon(true); follower.start();
            try {
                long end = System.currentTimeMillis() + 5000;
                while (follower.getState() != Thread.State.WAITING && System.currentTimeMillis() < end) Thread.sleep(5);
                assertEquals(Thread.State.WAITING, follower.getState());
                assertTrue(wait.tool.complete("共用回答", Collections.singletonList(image("图.png"))));
                ToolResult owner = wait.get(), duplicate = second.get(5, TimeUnit.SECONDS);
                assertEquals(owner.output, duplicate.output);
                assertNotNull(owner.images); assertNull(duplicate.images);
                assertEquals(1, wait.ui.asksStarted.size()); assertEquals(1, wait.ui.asksDone.size());
            } finally { follower.interrupt(); follower.join(5000); }
        }
    }

    @Test public void plainTextPreservesExactOriginalAndLegacyOutput() throws Exception {
        try (Waiting wait = new Waiting(true)) {
            assertTrue(wait.tool.complete(" 2 "));
            ToolResult result = wait.get();
            assertNull(result.images);
            assertEquals(" 2 ", wait.ui.asksDone.get(0));
            assertEquals(" 2 ", AskUserQuestionTool.answerForDisplay(result.output));
            assertFalse(result.output.contains("imageAttachments"));
        }
    }

    @Test public void cancelledQuestionIdentityCannotAnswerTheNextQuestion() throws Exception {
        try (Waiting wait = new Waiting(false)) {
            String oldId = wait.tool.pendingQuestionId(); assertNotNull(oldId);
            wait.worker.interrupt(); wait.worker.join(5000);
            CompletableFuture<ToolResult> next = new CompletableFuture<ToolResult>();
            Thread nextWorker = new Thread(() -> {
                try { next.complete(wait.tool.execute(JsonParser.parseString("{\"question\":\"新的问题\"}").getAsJsonObject())); }
                catch (Exception e) { next.completeExceptionally(e); }
            });
            nextWorker.setDaemon(true); nextWorker.start();
            try {
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    synchronized (wait.ui) { if (wait.ui.asksStarted.size() == 2) break; }
                    Thread.sleep(5);
                }
                String nextId = wait.tool.pendingQuestionId();
                assertNotNull(nextId); assertNotEquals(oldId, nextId);
                assertFalse(wait.tool.complete("旧草稿", Collections.singletonList(image("旧图.png")), oldId));
                assertFalse(wait.tool.complete("", Collections.singletonList(image("空身份.png")), null));
                assertFalse(next.isDone());
                assertTrue(wait.tool.complete("新回答", Collections.singletonList(image("新图.png")), nextId));
                assertEquals("新图.png", next.get(5, TimeUnit.SECONDS).images.get(0).name);
            } finally { nextWorker.interrupt(); nextWorker.join(5000); }
        }
    }
}
