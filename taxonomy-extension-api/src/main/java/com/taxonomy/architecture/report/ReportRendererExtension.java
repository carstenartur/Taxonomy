package com.taxonomy.architecture.report;

import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;

/**
 * Extension SPI for report format rendering.
 */
public interface ReportRendererExtension extends TaxonomyExtension {

    @Override
    default String id() {
        return descriptor().id();
    }

    @Override
    default String displayName() {
        return descriptor().displayName();
    }

    @Override
    default String description() {
        return "Renders architecture reports as %s".formatted(descriptor().displayName());
    }

    @Override
    default ExtensionKind kind() {
        return ExtensionKind.REPORT_RENDERER;
    }

    /**
     * Returns the static descriptor for this report format.
     */
    ReportFormatDescriptor descriptor();

    /**
     * Renders the given report context into serialized bytes.
     *
     * @param context generated report input data plus optional render options
     * @return rendered output payload
     */
    ReportRenderResult render(ReportRenderContext context);
}
