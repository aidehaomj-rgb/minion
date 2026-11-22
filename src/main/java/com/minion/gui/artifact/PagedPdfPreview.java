package com.minion.gui.artifact;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lazy, bounded PDF backend for the workspace reader. Call render/text methods
 * from worker threads; the UI thread only consumes the returned values.
 */
public final class PagedPdfPreview implements AutoCloseable {
    public static final int MAX_CACHED_PAGES = 6;
    private static final long MAX_CACHED_PIXELS = 24_000_000L;
    private static final int MAX_RENDER_SIDE = 2400;

    private final PDDocument document;
    private final PDFRenderer renderer;
    private final LinkedHashMap<Integer, BufferedImage> cache =
            new LinkedHashMap<Integer, BufferedImage>(8, 0.75f, true);
    private long cachedPixels;
    private boolean closed;

    public PagedPdfPreview(Path pdf) throws IOException {
        document = PDDocument.load(
                pdf.toFile(), MemoryUsageSetting.setupTempFileOnly());
        renderer = new PDFRenderer(document);
    }

    public synchronized int pageCount() {
        ensureOpen();
        return document.getNumberOfPages();
    }

    public synchronized double aspectRatio(int pageIndex) {
        ensureOpen();
        PDRectangle box = pageBox(pageIndex);
        return box.getHeight() <= 0 ? 16.0 / 9.0 : box.getWidth() / box.getHeight();
    }

    /**
     * Render one page to a bounded bitmap. Larger requested widths are capped
     * by MAX_RENDER_SIDE to protect the 8GB Win7 target.
     */
    public synchronized BufferedImage renderPage(int pageIndex, int requestedWidth)
            throws IOException {
        ensureOpen();
        final int page = clampPage(pageIndex);
        final int width = Math.max(120, Math.min(MAX_RENDER_SIDE, requestedWidth));
        BufferedImage cached = cache.get(page);
        if (cached != null && cached.getWidth() >= width - 80) return cached;

        PDRectangle box = pageBox(page);
        double safeWidth = Math.max(1.0, box.getWidth());
        double safeHeight = Math.max(1.0, box.getHeight());
        double scale = Math.min(2.4, width / safeWidth);
        if (safeHeight * scale > MAX_RENDER_SIDE)
            scale = MAX_RENDER_SIDE / safeHeight;
        if (safeWidth * scale * safeHeight * scale > 16_000_000d)
            scale = Math.sqrt(16_000_000d / (safeWidth * safeHeight));

        BufferedImage image = renderer.renderImage(page, (float) scale, ImageType.RGB);
        cache.put(page, image);
        cachedPixels += (long) image.getWidth() * image.getHeight();
        trimCache();
        return image;
    }

    public synchronized String pageText(int pageIndex) throws IOException {
        ensureOpen();
        int page = clampPage(pageIndex);
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(page + 1);
        stripper.setEndPage(page + 1);
        return stripper.getText(document);
    }

    public synchronized String fullText(int maxChars) throws IOException {
        ensureOpen();
        int limit = Math.max(0, maxChars);
        StringBuilder value = new StringBuilder(Math.min(limit, 64 * 1024));
        int pages = document.getNumberOfPages();
        for (int page = 1; page <= pages && value.length() < limit; page++) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = stripper.getText(document);
            int keep = Math.min(pageText.length(), limit - value.length());
            if (keep > 0 && Character.isHighSurrogate(pageText.charAt(keep - 1))) keep--;
            value.append(pageText, 0, keep);
            if (page < pages && value.length() < limit) value.append('\n');
        }
        if (value.length() > limit) value.setLength(limit);
        if (value.length() > 0 && Character.isHighSurrogate(value.charAt(value.length() - 1)))
            value.setLength(value.length() - 1);
        return value.toString();
    }

    private void trimCache() {
        while ((cache.size() > MAX_CACHED_PAGES || cachedPixels > MAX_CACHED_PIXELS)
                && !cache.isEmpty()) {
            Map.Entry<Integer, BufferedImage> eldest =
                    cache.entrySet().iterator().next();
            BufferedImage removed = eldest.getValue();
            cachedPixels -= (long) removed.getWidth() * removed.getHeight();
            cache.remove(eldest.getKey());
            removed.flush();
        }
    }

    private int clampPage(int pageIndex) {
        int count = document.getNumberOfPages();
        return Math.max(0, Math.min(count - 1, pageIndex));
    }

    private PDRectangle pageBox(int pageIndex) {
        return document.getPage(clampPage(pageIndex)).getCropBox();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("PDF preview is closed");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (BufferedImage image : cache.values()) image.flush();
        cache.clear();
        cachedPixels = 0;
        try {
            document.close();
        } catch (IOException ignored) {
        }
    }
}
