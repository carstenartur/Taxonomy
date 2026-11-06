package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import com.taxonomy.shared.extension.TaxonomyExtension;
import java.util.List;

/** Discovery is offline. All file operations are admitted through the host's reviewed workflow. */
public final class SparxPlugin implements TaxonomyPlugin {
    @Override public List<TaxonomyExtension> extensions() { return List.of(new SparxQeaConnector(), new SparxQeaWriterConnector()); }
}
