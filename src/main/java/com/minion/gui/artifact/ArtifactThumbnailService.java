package com.minion.gui.artifact;

import com.minion.gui.artifact.DocumentPreviewLoader.EmbeddedImage;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** First-page cover thumbnails for PDF and presentation artifacts. */
public final class ArtifactThumbnailService {
    public interface Consumer { void accept(Image image); }
    private static final ThreadPoolExecutor WORKER =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(8), r -> {
                        Thread thread = new Thread(r, "minion-artifact-thumbnail");
                        thread.setDaemon(true);
                        return thread;
                    }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static volatile boolean swept;

    private ArtifactThumbnailService() { }

    public static void load(Path source, Consumer consumer) {
        cleanupOnce();
        WORKER.submit(() -> {
            Image image = null;
            try {
                if (isPdf(source)) {
                    image = pdfCover(source);
                } else if (isPresentation(source)) {
                    image = presentationCover(source);
                }
            } catch (Exception ignored) {
            }
            if (image != null) {
                final Image ready = image;
                Platform.runLater(() -> consumer.accept(ready));
            }
        });
    }

    private static void cleanupOnce() {
        if (swept) return;
        swept = true;
        try {
            Path root = Paths.get(System.getProperty("java.io.tmpdir"),
                    "minion-preview-covers");
            if (!Files.isDirectory(root)) return;
            long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path file : stream)
                    if (Files.getLastModifiedTime(file).toMillis() < cutoff)
                        Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
        }
    }

    private static Image pdfCover(Path pdf) throws IOException {
        Path cache = cache(pdf);
        if (Files.isRegularFile(cache)) {
            BufferedImage cached = ImageIO.read(cache.toFile());
            if (cached != null) return SwingFXUtils.toFXImage(cached, null);
        }
        try (PagedPdfPreview preview = new PagedPdfPreview(pdf)) {
            if (preview.pageCount() == 0) return null;
            BufferedImage image = preview.renderPage(0, 360);
            Files.createDirectories(cache.getParent());
            ImageIO.write(image, "png", cache.toFile());
            return SwingFXUtils.toFXImage(image, null);
        }
    }

    private static Image presentationCover(Path source) throws IOException {
        OfficePreviewConverter.Conversion converted = OfficePreviewConverter.convert(source);
        if (converted != null) return pdfCover(converted.pdf);
        DocumentPreviewLoader.Result result = DocumentPreviewLoader.load(source);
        for (DocumentPreviewLoader.SlidePreview slide : result.slides) {
            for (EmbeddedImage image : slide.images) {
                Image fx = new Image(new ByteArrayInputStream(image.bytes),
                        340, 180, true, true);
                if (!fx.isError()) return fx;
            }
        }
        return null;
    }

    private static Path cache(Path source) throws IOException {
        long size = Files.size(source);
        long modified = Files.getLastModifiedTime(source).toMillis();
        String key = Long.toHexString(size) + "-" + Long.toHexString(modified) + "-"
                + Integer.toHexString(source.toAbsolutePath().toString().hashCode()) + ".png";
        return Paths.get(System.getProperty("java.io.tmpdir"),
                "minion-preview-covers", key);
    }

    private static boolean isPdf(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static boolean isPresentation(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pptx") || name.endsWith(".ppt")
                || name.endsWith(".ppsx") || name.endsWith(".pps");
    }
}
