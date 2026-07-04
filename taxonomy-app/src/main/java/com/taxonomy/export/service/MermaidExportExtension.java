package com.taxonomy.export.service;

import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.MermaidLabels;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@link ExportFormatExtension} adapter for Mermaid diagram output.
 *
 * <p>Delegates to {@link MermaidExportService}, keeping the underlying service
 * unchanged.  Accepts an optional {@code "locale"} entry in
 * {@link ExportContext#options()} to select German labels ({@code "de"}).
 */
@Component
public class MermaidExportExtension implements ExportFormatExtension {

    /** Stable format ID used for registry lookup. */
    public static final String FORMAT_ID = "mermaid";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID,
            "Mermaid",
            "mmd",
            "text/plain; charset=UTF-8",
            false
    );

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
        if (locale instanceof String s && s.startsWith("de")) {
            return MermaidLabels.german();
        }
        return MermaidLabels.english();
    }
}
