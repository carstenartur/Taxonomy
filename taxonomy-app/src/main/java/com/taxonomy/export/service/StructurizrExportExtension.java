package com.taxonomy.export.service;

import com.taxonomy.export.StructurizrExportService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * {@link ExportFormatExtension} adapter for Structurizr DSL output.
 *
 * <p>Delegates to {@link StructurizrExportService}, keeping the underlying service
 * unchanged.
 */
@Component
public class StructurizrExportExtension implements ExportFormatExtension {

    /** Stable format ID used for registry lookup. */
    public static final String FORMAT_ID = "structurizr";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID,
            "Structurizr DSL",
            "dsl",
            "text/plain; charset=UTF-8",
            false
    );

    private final StructurizrExportService structurizrExportService;

    public StructurizrExportExtension(StructurizrExportService structurizrExportService) {
        this.structurizrExportService = structurizrExportService;
    }

    @Override
    public ExportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ExportResult export(ExportContext context) {
        String dsl = structurizrExportService.export(context.diagram());
        return new ExportResult(dsl.getBytes(StandardCharsets.UTF_8));
    }
}
