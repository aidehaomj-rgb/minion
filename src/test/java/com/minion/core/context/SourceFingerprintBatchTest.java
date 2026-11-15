package com.minion.core.context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class SourceFingerprintBatchTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void normalizedAliasesShareOneHashButTheNextBatchHashesAgain() throws Exception {
        Path root = tmp.newFolder("project").toPath();
        Path file = write(root.resolve("规则.txt"), "AAAA");
        Files.createDirectories(root.resolve("nested"));
        AtomicInteger hashes = new AtomicInteger();
        SourceFingerprintBatch.Hasher counted = counted(hashes);
        SourceFingerprintBatch batch = new SourceFingerprintBatch(root, counted);
        SourceFingerprintBatch.Snapshot first = batch.read(file);
        assertSame(first, batch.read(root.resolve("nested/../规则.txt")));
        assertEquals(1, hashes.get());
        FileTime at = Files.getLastModifiedTime(file);
        write(file, "BBBB"); Files.setLastModifiedTime(file, at);
        SourceFingerprintBatch.Snapshot next = new SourceFingerprintBatch(root, counted).read(file);
        assertNotEquals(first.sha256, next.sha256);
        assertEquals(2, hashes.get());
    }

    @Test public void detectedVersionChangeAndExplicitInvalidationNeverUseAnOldDigest() throws Exception {
        Path root = tmp.newFolder("project").toPath(); Path file = write(root.resolve("source.txt"), "AAAA");
        AtomicInteger hashes = new AtomicInteger();
        SourceFingerprintBatch batch = new SourceFingerprintBatch(root, counted(hashes));
        SourceFingerprintBatch.Snapshot first = batch.read(file);
        write(file, "BBBB"); Files.setLastModifiedTime(file, FileTime.fromMillis(first.modifiedAt + 2000));
        SourceFingerprintBatch.Snapshot next = batch.read(file);
        assertNotEquals(first.sha256, next.sha256); assertEquals(2, hashes.get());
        FileTime at = Files.getLastModifiedTime(file);
        write(file, "CCCC"); Files.setLastModifiedTime(file, at);
        batch.invalidate(file);
        assertNotEquals(next.sha256, batch.read(file).sha256); assertEquals(3, hashes.get());
    }

    @Test public void replacingFileWithSameTimestampAndLengthChecksFileIdentity() throws Exception {
        Path root = tmp.newFolder("project").toPath(); Path file = write(root.resolve("source.txt"), "AAAA");
        AtomicInteger hashes = new AtomicInteger();
        SourceFingerprintBatch batch = new SourceFingerprintBatch(root, counted(hashes));
        SourceFingerprintBatch.Snapshot first = batch.read(file);
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
        Path replacement = write(root.resolve("replacement.txt"), "BBBB");
        Files.setLastModifiedTime(replacement, before.lastModifiedTime());
        Object nextKey = Files.readAttributes(replacement, BasicFileAttributes.class).fileKey();
        assumeTrue("filesystem must expose distinct file identities", before.fileKey() != null && !before.fileKey().equals(nextKey));
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        assertNotEquals(first.sha256, batch.read(file).sha256); assertEquals(2, hashes.get());
    }

    @Test public void sourceChangingDuringHashIsRejectedAndNotCached() throws Exception {
        Path root = tmp.newFolder("project").toPath(); final Path file = write(root.resolve("source.txt"), "AAAA");
        AtomicInteger hashes = new AtomicInteger();
        SourceFingerprintBatch batch = new SourceFingerprintBatch(root, path -> {
            String digest = SourceFingerprintBatch.SHA256.sha256(path);
            if (hashes.incrementAndGet() == 1) {
                write(path, "different length");
            }
            return digest;
        });
        try { batch.read(file); fail("must fail closed while changing"); } catch (IOException expected) { }
        assertEquals(SourceFingerprintBatch.SHA256.sha256(file), batch.read(file).sha256);
        assertEquals(2, hashes.get());
    }

    @Test public void hashFailureAndMissingFileFailClosedWithoutCachingFailure() throws Exception {
        Path root = tmp.newFolder("project").toPath(); Path file = write(root.resolve("source.txt"), "AAAA");
        AtomicInteger hashes = new AtomicInteger();
        SourceFingerprintBatch batch = new SourceFingerprintBatch(root, path -> {
            if (hashes.incrementAndGet() == 1) throw new IOException("unreadable");
            return SourceFingerprintBatch.SHA256.sha256(path);
        });
        try { batch.read(file); fail("unreadable"); } catch (IOException expected) { }
        assertNotNull(batch.read(file).sha256); assertEquals(2, hashes.get());
        Files.delete(file);
        try { batch.read(file); fail("deleted"); } catch (IOException expected) { }
    }

    @Test public void cachedSourceCannotAuthorizeAnotherProject() throws Exception {
        Path one = tmp.newFolder("one").toPath(), two = tmp.newFolder("two").toPath();
        Path a = write(one.resolve("source.txt"), "AAAA"), b = write(two.resolve("source.txt"), "AAAA");
        AtomicInteger hashes = new AtomicInteger(); SourceFingerprintBatch batch = new SourceFingerprintBatch(one, counted(hashes));
        batch.read(a);
        try { batch.read(b); fail("cross project"); } catch (IOException expected) { }
        assertEquals(1, hashes.get());
    }

    private static SourceFingerprintBatch.Hasher counted(AtomicInteger hashes) {
        return path -> { hashes.incrementAndGet(); return SourceFingerprintBatch.SHA256.sha256(path); };
    }

    private static Path write(Path file, String text) throws IOException {
        return Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
