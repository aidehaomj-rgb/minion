package com.minion.core.checkpoint;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class CheckpointStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void restoresExistingAndOriginallyMissingFile() throws Exception {
        Path work = tmp.newFolder("work").toPath();
        CheckpointStore store = new CheckpointStore(work, work.resolve(".minion/checkpoints/s1"));
        Path existing = work.resolve("a.txt");
        Files.write(existing, "old".getBytes(StandardCharsets.UTF_8));
        CheckpointStore.Entry first = store.create(existing, "Edit");
        Files.write(existing, "new".getBytes(StandardCharsets.UTF_8));
        store.restore(first.id);
        assertEquals("old", new String(Files.readAllBytes(existing), StandardCharsets.UTF_8));

        Path created = work.resolve("new.txt");
        CheckpointStore.Entry second = store.create(created, "Write");
        Files.write(created, "x".getBytes(StandardCharsets.UTF_8));
        store.restore(second.id);
        assertFalse(Files.exists(created));
        assertEquals(2, store.list(10).size());
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsOutsideWorkspace() throws Exception {
        Path work = tmp.newFolder("work2").toPath();
        CheckpointStore store = new CheckpointStore(work, work.resolve(".minion/checkpoints/s1"));
        store.create(tmp.newFile("outside.txt").toPath(), "Write");
    }
}
