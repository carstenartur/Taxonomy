package com.taxonomy.plugins.leanix;

import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import com.taxonomy.shared.extension.TaxonomyExtension;
import java.util.List;

/** No configuration lookup, credential resolution or remote call during discovery. */
public final class LeanIxPlugin implements TaxonomyPlugin {
    @Override public List<TaxonomyExtension> extensions() { return List.of(new LeanIxInventorySource()); }
}
