package com.taxonomy.extension.api.plugin;

import com.taxonomy.shared.extension.TaxonomyExtension;

/** Use only within the lease lifetime. close is idempotent and releases the captured version. */
public interface ExtensionLease<T extends TaxonomyExtension> extends AutoCloseable {
    T extension();
    PluginIdentity plugin();
    @Override void close();
}
