package com.taxonomy.export.service;

import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.export.SvgDiagramRenderer;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic full-model SVG export using the neutral diagram scene. */
@Component
public class SvgExportExtension implements ExportFormatExtension {

    public static final String FORMAT_ID = "svg";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID, "SVG", "svg", "image/svg+xml", true);

    private final LayeredDiagramLayoutService layoutService;
    private final SvgDiagramRenderer renderer;

    public SvgExportExtension(LayeredDiagramLayoutService layoutService,
                              SvgDiagramRenderer renderer) {
        this.layoutService = layoutService;
        this.renderer = renderer;
    }

    @Override
    public ExportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ExportResult export(ExportContext context) {
        var nodeReasons = new LinkedHashMap<String, String>();
        if (context.options().get("elementReasons") instanceof Map<?, ?> reasons) {
            reasons.forEach((key, value) -> {
                if (key instanceof String id && value instanceof String reason && !reason.isBlank()) {
                    nodeReasons.put(id, reason);
                }
            });
        }
        String svg = renderer.render(layoutService.layout(context.diagram()), nodeReasons);
        return new ExportResult(svg.getBytes(StandardCharsets.UTF_8));
    }
}
