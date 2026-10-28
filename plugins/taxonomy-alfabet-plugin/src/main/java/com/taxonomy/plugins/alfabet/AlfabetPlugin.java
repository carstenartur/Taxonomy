package com.taxonomy.plugins.alfabet;

import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import com.taxonomy.shared.extension.TaxonomyExtension;
import java.util.List;

/** Discovery is offline; the host authorizes each explicit read separately. */
public final class AlfabetPlugin implements TaxonomyPlugin {
    @Override public List<TaxonomyExtension> extensions() { return List.of(new AlfabetInventorySource()); }
}
