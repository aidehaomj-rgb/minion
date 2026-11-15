package com.minion.core.agent;

import com.minion.core.tools.AtomicFiles;
import com.minion.core.tools.PathLocks;
import com.minion.core.tools.PathsGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Exact, sharded receipt history. A full hot cache never freezes useful progress. */
public final class ProgressReceiptLedger {
    private final Path root, directory;
    public ProgressReceiptLedger(Path workspace, String session, String task) throws IOException {
        if (session == null || task == null || !session.matches("[A-Za-z0-9_-]+") || !task.matches("[A-Za-z0-9_-]+"))
            throw new IOException("Invalid progress ledger scope");
        root = workspace.toAbsolutePath().normalize();
        directory = root.resolve(".minion/progress").resolve(session).resolve(task);
        guard(directory);
    }
    private void guard(Path path) throws IOException {
        if (!PathsGuard.inside(root.toString(), path)) throw new IOException("Progress ledger outside workspace");
    }
    /** Write before returning true. Corrupt/unwritable history must not grant a fresh budget. */
    public boolean add(String signature) throws IOException {
        if (signature == null || !signature.matches("[a-f0-9]{64}")) throw new IOException("Invalid progress signature");
        Path file = directory.resolve(signature.substring(0, 2) + ".txt");
        ReentrantLock lock = PathLocks.forPath(file); lock.lock();
        try {
            guard(file); Files.createDirectories(directory); guard(file);
            Set<String> values = new LinkedHashSet<String>();
            if (Files.exists(file)) {
                if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("Progress shard too large");
                for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                    if (!line.matches("[a-f0-9]{64}") || !line.startsWith(signature.substring(0, 2)))
                        throw new IOException("Corrupt progress receipt history");
                    values.add(line);
                }
            }
            if (!values.add(signature)) return false;
            AtomicFiles.writeText(file, String.join("\n", values) + "\n", StandardCharsets.US_ASCII);
            return true;
        } finally { lock.unlock(); }
    }
}
