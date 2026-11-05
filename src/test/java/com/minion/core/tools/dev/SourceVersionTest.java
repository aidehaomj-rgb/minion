package com.minion.core.tools.dev;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.*;

public class SourceVersionTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void contentChangeEvenWithSameSizeAndTimestampInvalidates() throws Exception {
        Path root = tmp.newFolder("中文 空格").toPath(), src = root.resolve("main.py");
        write(src, "x=1\n");
        FileTime time = Files.getLastModifiedTime(src);
        String digest = SourceVersion.capture(root).digest;
        assertTrue(SourceVersion.verify(root, digest));
        write(src, "x=2\n"); Files.setLastModifiedTime(src, time);
        assertFalse(SourceVersion.verify(root, digest));
    }

    @Test public void additionsDeletionsAndRenamesInvalidate() throws Exception {
        Path root = tmp.newFolder().toPath();
        write(root.resolve("a.py"), "x=1\n");
        String first = SourceVersion.capture(root).digest;
        write(root.resolve("b.py"), "x=1\n");
        String second = SourceVersion.capture(root).digest;
        assertNotEquals(first, second);
        Files.delete(root.resolve("b.py"));
        assertTrue(SourceVersion.verify(root, first));
        Files.move(root.resolve("a.py"), root.resolve("b.py"));
        assertFalse(SourceVersion.verify(root, first));
    }

    @Test public void outputsIgnoredButExplicitValidationConfigurationIsBound() throws Exception {
        Path root = tmp.newFolder().toPath();
        write(root.resolve("main.py"), "print('hello')\n");
        String digest = SourceVersion.capture(root).digest;
        for (String dir : new String[]{"target", "build", "dist", "node_modules", "__pycache__", ".git", ".minion/validation-runs/old"})
            write(root.resolve(dir).resolve("output.py"), "generated");
        assertTrue(SourceVersion.verify(root, digest));
        write(root.resolve(".minion/validation.json"), "{\"version\":1}");
        assertFalse(SourceVersion.verify(root, digest));
        String withConfig = SourceVersion.capture(root).digest;
        write(root.resolve(".minion/validation.json"), "{\"version\":2}");
        assertFalse(SourceVersion.verify(root, withConfig));
    }

    @Test public void invalidOrMissingEvidenceFailsClosed() throws Exception {
        Path root = tmp.newFolder().toPath();
        assertFalse(SourceVersion.verify(root, null));
        assertFalse(SourceVersion.verify(root, ""));
        assertFalse(SourceVersion.verify(root.resolve("missing"), SourceVersion.capture(root).digest));
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent()); Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
