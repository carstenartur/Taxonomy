package com.taxonomy.export.service;

import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.visio.VisioDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * {@link ExportFormatExtension} adapter for Visio (.vsdx) output.
 *
 * <p>Delegates to {@link VisioDiagramService} and {@link VisioPackageBuilder},
 * keeping both underlying services unchanged.  Any {@link IOException} from
 * the package builder is wrapped as {@link UncheckedIOException}.
 */
@Component
public class VisioExportExtension implements ExportFormatExtension {

    /** Stable format ID used for registry lookup. */
    public static final String FORMAT_ID = "visio";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID,
            "Visio",
            "vsdx",
            "application/vnd.ms-visio.drawing.main+xml",
            true
    );

    private final VisioDiagramService visioDiagramService;
    private final VisioPackageBuilder visioPackageBuilder;

    public VisioExportExtension(VisioDiagramService visioDiagramService,
                                 VisioPackageBuilder visioPackageBuilder) {
        this.visioDiagramService = visioDiagramService;
        this.visioPackageBuilder = visioPackageBuilder;
    }

    @Override
    public ExportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ExportResult export(ExportContext context) {
        VisioDocument doc = visioDiagramService.convert(context.diagram());
        try {
            return new ExportResult(visioPackageBuilder.build(doc));
        } catch (IOException e) {
            throw new UncheckedIOException("Visio export failed", e);
        }
    }
}
