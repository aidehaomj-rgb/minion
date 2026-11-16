package com.minion.core.context;

import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ContextManagerCalibrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ContextManager manager() {
        return new ContextManager(1000000, .9, 12, new FakeLlmClient(), 0);
    }

    private List<Message> messages() {
        return Collections.singletonList(Message.user("测试内容"));
    }

    private int local(List<Message> messages) {
        return TokenCounter.estimateMessages(messages);
    }

    @Test
    public void firstSample_direct_upwardImmediate() {
        ContextManager cm = manager();
        List<Message> ms = messages();
        int base = local(ms);
        cm.observeInputTokens(ms, base * 2);
        assertEquals(base * 2, cm.estimate(ms));
        cm.observeInputTokens(ms, base * 3); // 向上立即，不滞后
        assertEquals(base * 3, cm.estimate(ms));
    }

    @Test
    public void downwardSample_smoothed() {
        ContextManager cm = manager();
        List<Message> ms = messages();
        int base = local(ms);
        cm.observeInputTokens(ms, base * 2);           // scale=2
        cm.observeInputTokens(ms, base);               // 0.7*2+0.3*1=1.7
        assertEquals((int) Math.ceil(base * 1.7), cm.estimate(ms));
        cm.observeInputTokens(ms, base);               // 0.7*1.7+0.3=1.49
        assertEquals((int) Math.ceil(base * 1.49), cm.estimate(ms));
    }

    @Test
    public void floorNeverBelowOne() {
        ContextManager cm = manager();
        List<Message> ms = messages();
        int base = local(ms);
        for (int i = 0; i < 20; i++) cm.observeInputTokens(ms, Math.max(1, base / 10));
        assertTrue(cm.estimate(ms) >= base);
    }

    @Test
    public void persistedPrior_restoredAcrossManagers_andKeyIsolated() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("calibration.json");
        List<Message> ms = messages();
        int base = local(ms);
        TokenCalibrationStore store = new TokenCalibrationStore(file);
        ContextManager cm = manager();
        cm.enableCalibration("model-a", store);
        cm.observeInputTokens(ms, base * 2);
        store.flush();
        assertTrue(Files.exists(file));

        TokenCalibrationStore reloaded = new TokenCalibrationStore(file);
        ContextManager cm2 = manager();
        cm2.enableCalibration("model-a", reloaded);
        assertEquals(base * 2, cm2.estimate(ms));
        assertTrue(cm2.calibrationStatus().contains("先验"));

        cm2.enableCalibration("model-b", reloaded); // 换指纹不串校准
        assertEquals(base, cm2.estimate(ms));
    }

    @Test
    public void sameKeyReenable_keepsLiveLearning() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("calibration.json");
        List<Message> ms = messages();
        int base = local(ms);
        TokenCalibrationStore store = new TokenCalibrationStore(file);
        ContextManager cm = manager();
        cm.enableCalibration("model-a", store);
        cm.observeInputTokens(ms, base * 2);
        // 未 flush 时再次 enable（如改 profile）：保留会话内学习值，不被磁盘旧先验覆盖
        cm.enableCalibration("model-a", store);
        assertEquals(base * 2, cm.estimate(ms));
    }

    @Test
    public void modelSwitch_resetsThenLoadsNewPrior() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("calibration.json");
        List<Message> ms = messages();
        int base = local(ms);
        TokenCalibrationStore store = new TokenCalibrationStore(file);
        ContextManager cm = manager();
        cm.enableCalibration("model-a", store);
        cm.observeInputTokens(ms, base * 2);
        store.flush();

        cm.setLlm(new FakeLlmClient());
        assertEquals(base, cm.estimate(ms)); // 切换即重置
        cm.enableCalibration("model-a", store);
        assertEquals(base * 2, cm.estimate(ms)); // 重新载入先验
    }

    @Test
    public void requestObservation_persistsAndSmooths() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("calibration.json");
        TokenCalibrationStore store = new TokenCalibrationStore(file);
        ContextManager cm = manager();
        cm.enableCalibration("model-a", store);
        List<Message> history = messages();
        List<Message> request = new java.util.ArrayList<Message>();
        request.add(Message.system("系统"));
        request.addAll(history);
        int total = TokenCounter.estimateMessages(request) + 1000;
        cm.observeRequestTokens(request, history, 1000, total * 2);
        assertEquals(total * 2, cm.estimate(history));
        assertTrue(cm.calibrationStatus().contains("向上立即、向下平滑"));
        store.flush();
        assertNotNull(new TokenCalibrationStore(file).entry("model-a"));
    }

    @Test
    public void corruptStoreFile_silentlyRestarts() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("calibration.json");
        Files.write(file, "{corrupt".getBytes(StandardCharsets.UTF_8));
        TokenCalibrationStore store = new TokenCalibrationStore(file);
        assertNull(store.entry("model-a"));
        List<Message> ms = messages();
        int base = local(ms);
        ContextManager cm = manager();
        cm.enableCalibration("model-a", store);
        cm.observeInputTokens(ms, base * 2);
        assertEquals(base * 2, cm.estimate(ms));
    }
}