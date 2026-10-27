package com.taxonomy.export.service;

import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.export.spi.ExportContext;
import com.taxonomy.export.spi.ExportFormatDescriptor;
import com.taxonomy.export.spi.ExportFormatExtension;
import com.taxonomy.export.spi.ExportResult;
import com.taxonomy.visio.VisioDocument;
import com.taxonomy.visio.VisioExportMetadata;
import com.taxonomy.visio.VisioProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Spring adapter for Visio VSDX output. */
@Component
public class VisioExportExtension implements ExportFormatExtension {

    public static final String FORMAT_ID = "visio";

    private static final ExportFormatDescriptor DESCRIPTOR = new ExportFormatDescriptor(
            FORMAT_ID,
            "Visio",
            "vsdx",
            "application/vnd.ms-visio.drawing",
            true);

    private final VisioDiagramService visioDiagramService;
    private final VisioPackageBuilder visioPackageBuilder;

    private final VisioWorkingViewMetadata workingViewMetadata;

    /** Compatibility constructor for isolated callers without application context. */
    public VisioExportExtension(VisioDiagramService visioDiagramService,
                                VisioPackageBuilder visioPackageBuilder) {
        this(visioDiagramService, visioPackageBuilder, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VisioExportExtension(VisioDiagramService visioDiagramService,
                                VisioPackageBuilder visioPackageBuilder,
                                VisioWorkingViewMetadata workingViewMetadata) {
        this.visioDiagramService = visioDiagramService;
        this.visioPackageBuilder = visioPackageBuilder;
        this.workingViewMetadata = workingViewMetadata;
    }

    @Override
    public ExportFormatDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ExportResult export(ExportContext context) {
        var elements = new LinkedHashMap<String, Map<String, VisioProperty>>();
        if (context.options().get("elementReasons") instanceof Map<?, ?> reasons) {
            reasons.forEach((key, value) -> {
                if (key instanceof String code && value instanceof String reason && !reason.isBlank())
                    elements.put(code, Map.of("taxonomy.presenceReason", VisioProperty.text(reason)));
            });
        }
        String originalText = context.options().get("originalRequirementText") instanceof String text ? text : null;
        VisioDocument document = visioDiagramService.convert(context.diagram(),
                new VisioExportMetadata(VisioWorkingViewMetadata.document(originalText, workingViewMetadata),
                        elements, Map.of(), VisioExportMetadata.unbound().losses()));
        try {
            return new ExportResult(visioPackageBuilder.build(document));
        } catch (IOException e) {
            throw new UncheckedIOException("Visio export failed", e);
        }
    }
}
