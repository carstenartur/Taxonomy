package com.taxonomy.extension.api.importer;

import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;

/** Spring-free extension contract for importing one external framework format. */
public interface ImportProfileExtension extends TaxonomyExtension {

    @Override
    default String id() {
        return descriptor().profileId();
    }

    @Override
    default String displayName() {
        return descriptor().displayName();
    }

    @Override
    default String description() {
        return "Imports framework models from %s files"
                .formatted(descriptor().acceptedFileFormat());
    }

    @Override
    default ExtensionKind kind() {
        return ExtensionKind.IMPORT_PROFILE;
    }

    ImportProfileDescriptor descriptor();

    FrameworkImportResult preview(ImportInput input);

    FrameworkImportResult importData(ImportInput input);
}
