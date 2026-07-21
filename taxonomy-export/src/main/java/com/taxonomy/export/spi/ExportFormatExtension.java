package com.taxonomy.export.spi;

import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;

/**
 * Spring-free SPI for converting a projected diagram into a concrete export format.
 * Implementations live in adapter/application modules and are discovered by the
 * application registry.
 */
public interface ExportFormatExtension extends TaxonomyExtension {

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
        return "Exports diagrams as %s".formatted(descriptor().displayName());
    }

    @Override
    default ExtensionKind kind() {
        return ExtensionKind.EXPORT_FORMAT;
    }

    ExportFormatDescriptor descriptor();

    ExportResult export(ExportContext context);
}
