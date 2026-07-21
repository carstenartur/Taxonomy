package com.taxonomy.export.service;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import org.springframework.stereotype.Component;

/** Spring adapter for ArchiMate XML output. */
@Component
public class ArchiMateExportExtension implements ExportFormatExtension {

    public static final String FORMAT_ID = "archimate";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID, "ArchiMate XML", "xml", "application/xml", false);

    private final ArchiMateDiagramService archiMateDiagramService;
    private final ArchiMateXmlExporter archiMateXmlExporter;

    public ArchiMateExportExtension(ArchiMateDiagramService archiMateDiagramService,
                                    ArchiMateXmlExporter archiMateXmlExporter) {
        this.archiMateDiagramService = archiMateDiagramService;
        this.archiMateXmlExporter = archiMateXmlExporter;
    }

    @Override
    public ExportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ExportResult export(ExportContext context) {
        ArchiMateModel model = archiMateDiagramService.convert(context.diagram());
        return new ExportResult(archiMateXmlExporter.export(model));
    }
}
