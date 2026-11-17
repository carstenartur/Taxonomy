package com.taxonomy.reporting.render.decision;

import com.taxonomy.extension.api.report.ReportFormatDescriptor;
import com.taxonomy.extension.api.report.ReportRenderContext;
import com.taxonomy.extension.api.report.ReportRenderResult;
import com.taxonomy.extension.api.report.ReportRendererExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;

/** Optional PDF view of the generated Visio drawings, including their page sizes and order. */
@ConditionalOnProperty(name = "taxonomy.reporting.visio-pdf.enabled", havingValue = "true")
public final class DecisionDrawingPdfRenderer implements ReportRendererExtension {
    private static final ReportFormatDescriptor FORMAT = new ReportFormatDescriptor("visio-pdf", "Visio PDF", "pdf", "application/pdf", true);
    private final DecisionRationaleVisioRenderer drawings;
    private final Semaphore slot = new Semaphore(1);

    public DecisionDrawingPdfRenderer(DecisionRationaleVisioRenderer drawings) { this.drawings = drawings; }
    @Override public String reportTypeId() { return drawings.reportTypeId(); }
    @Override public Class<?> reportModelType() { return drawings.reportModelType(); }
    @Override public ReportFormatDescriptor descriptor() { return FORMAT; }

    @Override public ReportRenderResult render(ReportRenderContext context) {
        if (!slot.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Drawing PDF converter is busy");
        try {
            var drawing = drawings.render(context);
            var source = Files.createTempFile("taxonomy-decision-", ".vsdx");
            try {
                Files.write(source, drawing.bytes());
                return new ReportRenderResult(VisioDrawingPdf.convert(source, "soffice", "pdfunite", "pdfinfo"), drawing.artifactMetadata());
            } finally { Files.deleteIfExists(source); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Drawing PDF conversion interrupted", interrupted);
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Drawing PDF conversion unavailable", failure);
        } finally {
            slot.release();
        }
    }
}
