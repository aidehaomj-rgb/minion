package com.minion.gui.artifact;

import com.minion.core.config.Config;
import com.minion.core.diagnostics.DiagnosticLog;
import com.minion.core.tools.ProcessCleanup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Offline Office-to-PDF conversion chain:
 * Microsoft Office COM -> WPS COM -> LibreOffice headless.
 */
public final class OfficePreviewConverter {
    private static final long TIMEOUT_SECONDS = 45L;
    private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final Object CONVERSION_LOCK = new Object();
    private static volatile boolean swept;

    private OfficePreviewConverter() { }

    public static final class Conversion {
        public final Path pdf;
        public final String engine;
        Conversion(Path pdf, String engine) {
            this.pdf = pdf;
            this.engine = engine;
        }
    }

    /** 快速缓存命中检查（<1ms，不加锁不启进程）：用于预览首屏直接打开已转好的高保真 PDF */
    public static Conversion findCached(Path source) {
        if (source == null || Boolean.getBoolean("minion.preview.office.disabled"))
            return null;
        String kind = kind(source);
        if (kind == null) return null;
        Path directory = cacheDirectory(source, kind);
        if (!Files.isDirectory(directory)) return null;
        Path standard = directory.resolve("document.pdf");
        if (validPdf(standard)) return new Conversion(standard.toAbsolutePath(), "cache");
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        Path named = directory.resolve(base + ".pdf");
        if (validPdf(named)) return new Conversion(named.toAbsolutePath(), "cache");
        return null;
    }

    public static Conversion convert(Path source) {
        if (source == null || Boolean.getBoolean("minion.preview.office.disabled"))
            return null;
        String kind = kind(source);
        if (kind == null) return null;
        Conversion fast = findCached(source);
        if (fast != null) return fast;
        synchronized (CONVERSION_LOCK) {
            cleanupCacheOnce();
            try {
                Conversion cached = findCached(source);
                if (cached != null) return cached;
                Path directory = cacheDirectory(source, kind);
                Files.createDirectories(directory);
                Path targetPdf = directory.resolve("document.pdf");
                Files.deleteIfExists(targetPdf);

                // 1/2：本机 Microsoft Office / WPS COM（经 ASCII 副本避免中文路径编码问题）
                if (isWindows() && !Boolean.getBoolean("minion.office.com.disabled")) {
                    for (String progId : officeProgIds(kind)) {
                        if (runCom(source, targetPdf, kind, progId)) {
                            return new Conversion(targetPdf.toAbsolutePath(), "Office/WPS COM " + progId);
                        }
                    }
                }
                Files.deleteIfExists(targetPdf);
                // 3：LibreOffice headless（真正无窗口，适合 Office/WPS 均不可用的机器）
                String office = findLibreOffice();
                if (office != null && runLibreOffice(office, source, directory)) {
                    long deadline = System.currentTimeMillis() + 8_000L;
                    while (System.currentTimeMillis() < deadline) {
                        if (validPdf(targetPdf)) return new Conversion(targetPdf.toAbsolutePath(), "LibreOffice");
                        Path generated = findConvertedPdf(directory, "document");
                        if (validPdf(generated)) return new Conversion(generated.toAbsolutePath(), "LibreOffice");
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Exception unavailable) {
                DiagnosticLog.info("preview", "Office PDF conversion unavailable: " + unavailable);
            }
        }
        return null;
    }

    private static boolean runCom(Path source, Path pdf, String kind, String progId) {
        Path script = null;
        Path asciiSource = null;
        Process process = null;
        try {
            Path directory = pdf.getParent();
            String sourceName = source.getFileName().toString();
            int dot = sourceName.lastIndexOf('.');
            String extension = dot < 0 ? ".bin" : sourceName.substring(dot);
            asciiSource = directory.resolve("document" + extension);
            Files.copy(source, asciiSource, StandardCopyOption.REPLACE_EXISTING);
            script = directory.resolve("_convert.ps1");
            Files.write(script, powershellScript().getBytes(StandardCharsets.UTF_8));
            process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                    asciiSource.toAbsolutePath().toString(), pdf.toAbsolutePath().toString(),
                    kind, progId)
                    .redirectErrorStream(true)
                    .start();
            drain(process.getInputStream());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ProcessCleanup.terminate(process);
                return false;
            }
            return process.exitValue() == 0 && validPdf(pdf);
        } catch (Exception unavailable) {
            if (process != null) ProcessCleanup.terminate(process);
            return false;
        } finally {
            if (process != null && process.isAlive()) ProcessCleanup.terminate(process);
            if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) {}
            if (asciiSource != null) try { Files.deleteIfExists(asciiSource); } catch (IOException ignored) {}
        }
    }

    private static boolean runLibreOffice(String executable, Path source, Path directory) {
        Process process = null;
        Path profile = null;
        Path asciiSource = null;
        try {
            profile = directory.resolve("lo-profile-"
                    + java.util.UUID.randomUUID().toString());
            Files.createDirectories(profile);
            String sourceName = source.getFileName().toString();
            int dot = sourceName.lastIndexOf('.');
            String extension = dot < 0 ? ".bin" : sourceName.substring(dot);
            asciiSource = directory.resolve("document" + extension);
            Files.copy(source, asciiSource, StandardCopyOption.REPLACE_EXISTING);
            process = new ProcessBuilder(
                    executable, "-env:UserInstallation=" + profile.toUri().toASCIIString(),
                    "--headless", "--convert-to", "pdf",
                    "--outdir", directory.toString(), asciiSource.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(directory.resolve("libreoffice.log").toFile())
                    .start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ProcessCleanup.terminate(process);
                return false;
            }
            if (process.exitValue() != 0)
                DiagnosticLog.warn("preview", "LibreOffice exit=" + process.exitValue()
                        + " log=" + directory.resolve("libreoffice.log"));
            return process.exitValue() == 0;
        } catch (Exception unavailable) {
            return false;
        } finally {
            if (process != null && process.isAlive()) ProcessCleanup.terminate(process);
            if (asciiSource != null) try { Files.deleteIfExists(asciiSource); } catch (IOException ignored) {}
            if (profile != null) try { deleteRecursively(profile); } catch (IOException ignored) {}
        }
    }

    private static void drain(final InputStream input) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buffer = new byte[4096];
                try {
                    while (input.read(buffer) >= 0) { }
                } catch (IOException ignored) {
                } finally {
                    try { input.close(); } catch (IOException ignored) { }
                }
            }
        }, "minion-office-preview-drain");
        thread.setDaemon(true);
        thread.start();
    }

    private static String kind(Path source) {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pptx") || name.endsWith(".ppt") || name.endsWith(".pps")
                || name.endsWith(".ppsx")) return "ppt";
        if (name.endsWith(".docx") || name.endsWith(".doc")) return "word";
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return "excel";
        return null;
    }

    private static List<String> officeProgIds(String kind) {
        if ("ppt".equals(kind))
            return Arrays.asList("PowerPoint.Application", "WPP.Application", "KWPP.Application");
        if ("word".equals(kind))
            return Arrays.asList("Word.Application", "KWps.Application", "WPS.Application");
        return Arrays.asList("Excel.Application", "KET.Application");
    }

    private static String powershellScript() {
        return "$ErrorActionPreference='Stop'\n"
                + "$source=$args[0];$target=$args[1];$kind=$args[2];$prog=$args[3]\n"
                + "$app=$null;$doc=$null\n"
                + "try{\n"
                + " $app=New-Object -ComObject $prog\n"
                + " if($kind -eq 'ppt'){\n"
                + "  $doc=$app.Presentations.Open($source,$true,$false,$false)\n"
                + "  try { $doc.SaveAs($target,32) } catch { $doc.ExportAsFixedFormat($target,2) }\n"
                + " } elseif($kind -eq 'word'){\n"
                + "  $doc=$app.Documents.Open($source,$false,$true)\n"
                + "  $doc.ExportAsFixedFormat($target,17)\n"
                + " } elseif($kind -eq 'excel'){\n"
                + "  $doc=$app.Workbooks.Open($source,0,$true)\n"
                + "  $doc.ExportAsFixedFormat(0,$target)\n"
                + " } else { throw 'Unsupported Office kind' }\n"
                + " if($doc -ne $null){$doc.Close()}\n"
                + "} finally {\n"
                + " if($app -ne $null){try{$app.Quit()}catch{}}\n"
                + "}\n";
    }

    private static Path cacheDirectory(Path source, String kind) {
        try {
            long size = Files.size(source);
            long modified = Files.getLastModifiedTime(source).toMillis();
            String key = Long.toHexString(size) + "-" + Long.toHexString(modified)
                    + "-" + Integer.toHexString(source.toAbsolutePath().toString().hashCode())
                    + "-" + kind;
            return Paths.get(System.getProperty("java.io.tmpdir"),
                    "minion-office-preview", key);
        } catch (IOException unavailable) {
            return Paths.get(System.getProperty("java.io.tmpdir"),
                    "minion-office-preview", Integer.toHexString(source.toString().hashCode()));
        }
    }

    private static Path findConvertedPdf(Path directory, String base) throws IOException {
        Path exact = directory.resolve(base + ".pdf");
        if (Files.isRegularFile(exact)) return exact;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.pdf")) {
            for (Path path : stream) return path;
        }
        return exact;
    }

    private static boolean validPdf(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) < 128) return false;
            byte[] head = new byte[5];
            try (InputStream input = Files.newInputStream(path)) {
                int offset = 0;
                while (offset < head.length) {
                    int read = input.read(head, offset, head.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
            }
            return new String(head, StandardCharsets.US_ASCII).equals("%PDF-");
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static void cleanupCacheOnce() {
        if (swept) return;
        swept = true;
        try {
            Path root = Paths.get(System.getProperty("java.io.tmpdir"), "minion-office-preview");
            if (!Files.isDirectory(root)) return;
            long cutoff = System.currentTimeMillis() - CACHE_TTL_MS;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path child : stream) {
                    try {
                        if (Files.getLastModifiedTime(child).toMillis() < cutoff)
                            deleteRecursively(child);
                    } catch (IOException ignored) { }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) deleteRecursively(child);
            }
        }
        Files.deleteIfExists(path);
    }

    private static String findLibreOffice() {
        List<String> candidates = new ArrayList<String>();
        candidates.add(System.getProperty("minion.libreoffice.path"));
        candidates.add(System.getenv("MINION_LIBREOFFICE"));
        candidates.add(System.getenv("MINION_SOFFICE"));
        try {
            candidates.add(Config.load().officePath());
        } catch (RuntimeException ignored) {
        }
        for (String configured : candidates) {
            if (configured != null && !configured.trim().isEmpty()) {
                Path candidate = Paths.get(configured.trim());
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String part : path.split(java.io.File.pathSeparator)) {
                for (String name : new String[]{"soffice.exe", "soffice"}) {
                    Path candidate = Paths.get(part.trim()).resolve(name);
                    if (Files.isRegularFile(candidate)) return candidate.toString();
                }
            }
        }
        for (String raw : Arrays.asList(
                "C:/Program Files/LibreOffice/program/soffice.exe",
                "C:/Program Files (x86)/LibreOffice/program/soffice.exe")) {
            Path candidate = Paths.get(raw);
            if (Files.isRegularFile(candidate)) return candidate.toString();
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
