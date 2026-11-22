package com.minion.gui.artifact;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class PagedSlideViewerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void pagedViewerAndWorkbookRenderOnFxThread() throws Exception {
        Path pdf = pdf(3);
        CompletableFuture<Void> done = new CompletableFuture<Void>();
        new JFXPanel();
        Platform.runLater(() -> {
            PagedSlideViewer viewer = null;
            try {
                viewer = new PagedSlideViewer(new PagedPdfPreview(pdf));
                new Scene(viewer, 900, 650);
                viewer.goTo(3);
                TabPane sheets = workbookTabs();
                assertEquals(2, sheets.getTabs().size());
                assertEquals("Summary", sheets.getTabs().get(0).getText());
                DocumentPreviewLoader.SlidePreview slide =
                        new DocumentPreviewLoader.SlidePreview(1, "Title",
                                Arrays.asList("First", "Second"), java.util.Collections.emptyList());
                PptxCardViewer cards = new PptxCardViewer(Arrays.asList(slide));
                new Scene(cards, 900, 650);
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            } finally {
                if (viewer != null) viewer.close();
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    private static TabPane workbookTabs() throws Exception {
        XlsxPreview.Sheet first = new XlsxPreview.Sheet("Summary",
                Arrays.asList(Arrays.asList("A", "B"), Arrays.asList("1", "2")), false);
        XlsxPreview.Sheet second = new XlsxPreview.Sheet("Details",
                Arrays.asList(Arrays.asList("Ready")), false);
        WorkbookPreviewView view = new WorkbookPreviewView(Arrays.asList(first, second));
        java.lang.reflect.Field field = WorkbookPreviewView.class.getDeclaredField("tabs");
        field.setAccessible(true);
        return (TabPane) field.get(view);
    }

    private Path pdf(int pages) throws Exception {
        Path file = temp.newFile("viewer.pdf").toPath();
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            document.save(file.toFile());
        }
        return Files.copy(file, temp.newFile("viewer-copy.pdf").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
