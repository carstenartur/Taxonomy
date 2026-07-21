package com.taxonomy.export.service;

import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import com.taxonomy.visio.VisioDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Spring adapter for Visio VSDX output. */
@Component
public class VisioExportExtension implements ExportFormatExtension {

    public static final String FORMAT_ID = "visio";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID,
            "Visio",
            "vsdx",
            "application/vnd.ms-visio.drawing.main+xml",
            true);

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
        VisioDocument document = visioDiagramService.convert(context.diagram());
        try {
            return new ExportResult(visioPackageBuilder.build(document));
        } catch (IOException e) {
            throw new UncheckedIOException("Visio export failed", e);
        }
    }
}
