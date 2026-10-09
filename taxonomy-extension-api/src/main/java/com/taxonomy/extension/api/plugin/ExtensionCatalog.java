package com.taxonomy.extension.api.plugin;

import com.taxonomy.shared.extension.TaxonomyExtension;
import com.taxonomy.shared.extension.ExtensionKind;
import java.util.List;

/** Public read/acquire port; installation and lifecycle are exclusively host responsibilities. */
public interface ExtensionCatalog {
    CatalogSnapshot snapshot();
    <T extends TaxonomyExtension> ExtensionLease<T> acquire(ExtensionKey key, Class<T> expectedType);
    /** Acquire one consistent kind-specific view. The caller must close every returned lease. */
    <T extends TaxonomyExtension> List<ExtensionLease<T>> acquireAll(ExtensionKind kind, Class<T> expectedType);
}
