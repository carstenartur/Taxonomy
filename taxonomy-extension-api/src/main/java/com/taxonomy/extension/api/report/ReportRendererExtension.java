package com.taxonomy.extension.api.report;

import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;

/** Spring-free extension contract for report format rendering. */
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

    ReportFormatDescriptor descriptor();

    ReportRenderResult render(ReportRenderContext context);
}
