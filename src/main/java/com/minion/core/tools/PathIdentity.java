package com.minion.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Canonical path identity for containment and deduplication; display paths stay logical. */
public final class PathIdentity {
    private PathIdentity() { }

    /** Resolve the nearest existing ancestor and append the still-missing suffix. */
    public static Path canonical(Path path) throws IOException {
        if (path == null) throw new IOException("path is null");
        Path probe = path.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<Path>();
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            Path name = probe.getFileName();
            if (name != null) missing.add(name);
            probe = probe.getParent();
        }
        if (probe == null) return path.toAbsolutePath().normalize();
        Path resolved = probe.toRealPath();
        for (int i = missing.size() - 1; i >= 0; i--) resolved = resolved.resolve(missing.get(i));
        return resolved.normalize();
    }

    public static boolean same(Path first, Path second) throws IOException {
        return canonical(first).equals(canonical(second));
    }
}
