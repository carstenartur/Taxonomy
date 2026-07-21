package com.taxonomy.export.service;

import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.MermaidLabels;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Spring adapter for Mermaid diagram output. */
@Component
public class MermaidExportExtension implements ExportFormatExtension {

    public static final String FORMAT_ID = "mermaid";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID, "Mermaid", "mmd", "text/plain; charset=UTF-8", false);

    private final MermaidExportService mermaidExportService;

    public MermaidExportExtension(MermaidExportService mermaidExportService) {
        this.mermaidExportService = mermaidExportService;
    }

    @Override
    public ExportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ExportResult export(ExportContext context) {
        MermaidLabels labels = resolveLabels(context.options());
        String mermaid = mermaidExportService.export(context.diagram(), labels);
        return new ExportResult(mermaid.getBytes(StandardCharsets.UTF_8));
    }

    private MermaidLabels resolveLabels(Map<String, Object> options) {
        Object locale = options.get("locale");
        return locale instanceof String s && s.startsWith("de")
                ? MermaidLabels.german() : MermaidLabels.english();
    }
}
