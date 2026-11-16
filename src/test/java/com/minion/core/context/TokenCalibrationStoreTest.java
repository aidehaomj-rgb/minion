package com.minion.core.context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class TokenCalibrationStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void key_isStableHexAndIsolated() {
        String k = TokenCalibrationStore.key("qwen", "https://x/v1", "qwen3-max");
        assertEquals(64, k.length());
        assertEquals(k, TokenCalibrationStore.key("qwen", "https://x/v1", "qwen3-max"));
        assertNotEquals(k, TokenCalibrationStore.key("deepseek", "https://x/v1", "qwen3-max"));
        assertNotEquals(k, TokenCalibrationStore.key("qwen", "https://y/v1", "qwen3-max"));
        assertNotEquals(k, TokenCalibrationStore.key("qwen", "https://x/v1", "qwen-plus"));
        assertNotNull(TokenCalibrationStore.key(null, null, null));
    }

    @Test
    public void observe_throttled_flushWrites() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("calibration.json");
        TokenCalibrationStore store = new TokenCalibrationStore(file);
        store.observe("k", 2.0, 1); // 首次立即写
        String first = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(first.contains("\"scale\":2.0"));
        store.observe("k", 3.0, 2); // 30s 内节流
        assertEquals(first, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        store.flush();
        TokenCalibrationStore reloaded = new TokenCalibrationStore(file);
        TokenCalibrationStore.Entry e = reloaded.entry("k");
        assertNotNull(e);
        assertEquals(3.0, e.scale, 1e-9);
        assertEquals(2, e.samples);
    }

    @Test
    public void invalidScale_notStored() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("calibration.json");
        TokenCalibrationStore store = new TokenCalibrationStore(file);
        store.observe("k", 0.5, 1);
        store.observe("k", Double.NaN, 1);
        store.observe("k", Double.POSITIVE_INFINITY, 1);
        store.flush();
        assertNull(store.entry("k"));
        assertFalse(Files.exists(file));
    }

    @Test
    public void missingFile_emptyEntry() {
        TokenCalibrationStore store = new TokenCalibrationStore(
                tmp.getRoot().toPath().resolve("absent.json"));
        assertNull(store.entry("k"));
        assertNull(store.entry(null));
    }
}