package com.taxonomy.export.service;

import com.taxonomy.export.AnalysisDiagramSource;
import com.taxonomy.export.SparxDiagramHandoff;
import com.taxonomy.export.spi.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/** Fresh-copy XMI handoff. The reviewed bidirectional integration remains a separate operation. */
@Component
public final class SparxExportExtension implements ExportFormatExtension {
    @Override public ExportFormatDescriptor descriptor() {
        return new ExportFormatDescriptor("sparx", "Sparx EA / XMI profile 3 (experimental fresh copy)", "sparx.zip", "application/zip", true);
    }
    @Override public ExportResult export(ExportContext context) {
        var source = context.options().get("analysisSource") instanceof AnalysisDiagramSource analysis ? analysis : null;
        try { return new ExportResult(SparxDiagramHandoff.build(context.diagram(), UUID.randomUUID(), source)); }
        catch (IOException failure) { throw new UncheckedIOException("Sparx handoff could not be created", failure); }
    }
}
