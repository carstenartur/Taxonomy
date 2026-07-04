package com.taxonomy.catalog.service.importer;

import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.TaxonomyExtension;

/**
 * Extension SPI for import profiles.
 *
 * <p>Each implementation provides a single named import profile (e.g. UAF, APQC, C4).
 * Implementations are registered as Spring {@code @Component}s and discovered
 * automatically by {@link ImportProfileRegistry}.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Component
 * public class MyImportProfileExtension implements ImportProfileExtension {
 *     @Override
 *     public ImportProfileDescriptor descriptor() { ... }
 *
 *     @Override
 *     public FrameworkImportResult preview(ImportInput input) { ... }
 *
 *     @Override
 *     public FrameworkImportResult importData(ImportInput input) { ... }
 * }
 * }</pre>
 */
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
        ImportProfileDescriptor descriptor = descriptor();
        return "Imports framework models from %s files".formatted(descriptor.acceptedFileFormat());
    }

    @Override
    default ExtensionKind kind() {
        return ExtensionKind.IMPORT_PROFILE;
    }

    /**
     * Returns the static descriptor for this profile (ID, display name, supported types, format).
     */
    ImportProfileDescriptor descriptor();

    /**
     * Dry-run: parse and map the input without writing to the database.
     *
     * @param input the import input (file stream and optional branch)
     * @return preview result with mapping statistics; {@code success} reflects parse/map outcome
     */
    FrameworkImportResult preview(ImportInput input);

    /**
     * Full import: parse, map, serialize to DSL, and materialize into the database.
     *
     * @param input the import input (file stream and branch)
     * @return import result including created relations and document ID
     */
    FrameworkImportResult importData(ImportInput input);
}
