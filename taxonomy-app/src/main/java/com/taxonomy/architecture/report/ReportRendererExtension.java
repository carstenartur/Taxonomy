package com.taxonomy.architecture.report;

import com.taxonomy.shared.extension.TaxonomyExtension;

/**
 * Extension SPI for report format rendering.
 */
public interface ReportRendererExtension extends TaxonomyExtension {

    ReportFormatDescriptor descriptor();

    ReportRenderResult render(ReportRenderContext context);
}
