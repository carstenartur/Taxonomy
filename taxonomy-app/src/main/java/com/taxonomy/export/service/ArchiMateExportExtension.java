package com.taxonomy.export.service;

import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.ArchiMateXmlExporter;
import org.springframework.stereotype.Component;

/**
 * {@link ExportFormatExtension} adapter for ArchiMate XML output.
 *
 * <p>Delegates to {@link ArchiMateDiagramService} and {@link ArchiMateXmlExporter},
 * keeping both underlying services unchanged.
 */
@Component
public class ArchiMateExportExtension implements ExportFormatExtension {

    /** Stable format ID used for registry lookup. */
    public static final String FORMAT_ID = "archimate";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID,
            "ArchiMate XML",
            "xml",
            "application/xml",
            false
    );

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
