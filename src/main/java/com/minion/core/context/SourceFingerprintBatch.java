package com.minion.core.context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Operation-local source snapshots. Never store this object in a field/static cache
 * or reuse it across requests: equal size/mtime is NOT proof of equal contents.
 * Canonical paths and file identities are checked on every lookup; only the digest
 * is shared among references in this one retrieval/verification batch.
 */
final class SourceFingerprintBatch {
    static final long MAX_SOURCE_BYTES = 50L * 1024 * 1024;

    interface Hasher { String sha256(Path file) throws IOException; }
    static final Hasher SHA256 = SourceFingerprintBatch::hash;

    static final class Snapshot {
        final Path realPath;
        final String sha256;
        final long size, modifiedAt;
        private final BasicFileAttributes attributes;

        private Snapshot(Path path, String digest, BasicFileAttributes attrs) {
            realPath = path; sha256 = digest; size = attrs.size();
            modifiedAt = attrs.lastModifiedTime().toMillis(); attributes = attrs;
        }
    }

    private final Path root;
    private final Hasher hasher;
    private final Map<Path, Snapshot> snapshots = new HashMap<Path, Snapshot>();

    SourceFingerprintBatch(Path projectRoot, Hasher hasher) {
        this.root = projectRoot.toAbsolutePath().normalize();
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    Snapshot read(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path realRoot = root.toRealPath();
        if (!normalized.startsWith(root) && !normalized.startsWith(realRoot)) throw new IOException("来源文件不能跨项目");
        Path real = normalized.toRealPath();
        if (!real.startsWith(realRoot)) throw new IOException("来源文件不能跨项目");
        try {
            BasicFileAttributes before = attributes(real);
            Snapshot prior = snapshots.get(real);
            if (prior != null && sameIdentityAndVersion(prior.attributes, before)) return prior;
            snapshots.remove(real);
            String digest = hasher.sha256(real);
            BasicFileAttributes after = attributes(real);
            if (!sameIdentityAndVersion(before, after) || !real.equals(normalized.toRealPath()))
                throw new IOException("来源文件在核验期间发生变化");
            Snapshot current = new Snapshot(real, digest, after);
            snapshots.put(real, current);
            return current;
        } catch (IOException | RuntimeException failed) {
            snapshots.remove(real);
            throw failed;
        }
    }

    /** For a known in-batch write/notification. No watcher, background thread or permanent metadata allowlist. */
    void invalidate(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        snapshots.remove(normalized);
        if (Files.exists(normalized)) snapshots.remove(normalized.toRealPath());
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.size() > MAX_SOURCE_BYTES)
            throw new IOException("来源不是普通文件或超过50MiB");
        return attrs;
    }

    private static boolean sameIdentityAndVersion(BasicFileAttributes a, BasicFileAttributes b) {
        return a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && a.creationTime().equals(b.creationTime()) && Objects.equals(a.fileKey(), b.fileKey());
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int n; long bytes = 0;
                while ((n = in.read(buffer)) != -1) {
                    bytes += n;
                    if (bytes > MAX_SOURCE_BYTES) throw new IOException("来源文件超过50MiB");
                    md.update(buffer, 0, n);
                }
            }
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder out = new StringBuilder(64);
            for (byte b : md.digest()) out.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
