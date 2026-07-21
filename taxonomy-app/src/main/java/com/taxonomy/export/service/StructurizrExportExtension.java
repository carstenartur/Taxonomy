package com.taxonomy.export.service;

import com.taxonomy.export.StructurizrExportService;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Spring adapter for Structurizr DSL output. */
@Component
public class StructurizrExportExtension implements ExportFormatExtension {

    public static final String FORMAT_ID = "structurizr";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID, "Structurizr DSL", "dsl", "text/plain; charset=UTF-8", false);

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
