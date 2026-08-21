package com.taxonomy.extension.api.report;

import com.taxonomy.dto.ArchitectureReport;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;

/**
 * Spring-free extension contract for rendering one report family in one format.
 *
 * <p>Renderers are addressed by the pair {@code reportTypeId + formatId}. Existing
 * architecture renderers remain source-compatible because the default report type and
 * model are {@code architecture} and {@link ArchitectureReport}.</p>
 */
public interface ReportRendererExtension extends TaxonomyExtension {

    String DEFAULT_REPORT_TYPE_ID = "architecture";

    /** Stable identifier of the report family rendered by this extension. */
    default String reportTypeId() {
        return DEFAULT_REPORT_TYPE_ID;
    }

    /** Runtime model type accepted by this renderer. */
    default Class<?> reportModelType() {
        return ArchitectureReport.class;
    }

    @Override
    default String id() {
        String formatId = descriptor().id();
        if (DEFAULT_REPORT_TYPE_ID.equals(reportTypeId())) {
            return formatId;
        }
        return reportTypeId() + ":" + formatId;
    }

    @Override
    default String displayName() {
        return descriptor().displayName();
    }

    @Override
    default String description() {
        return "Renders %s reports as %s".formatted(
                reportTypeId(), descriptor().displayName());
    }

    @Override
    default ExtensionKind kind() {
        return ExtensionKind.REPORT_RENDERER;
    }

    ReportFormatDescriptor descriptor();

    ReportRenderResult render(ReportRenderContext context);
}
