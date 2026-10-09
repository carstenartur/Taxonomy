package com.taxonomy.plugins.mermaid;

import com.taxonomy.export.MermaidExportService;
import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import com.taxonomy.shared.extension.TaxonomyExtension;
import java.util.List;

/** Independently installable adapter; contains no Spring or host application classes. */
public final class MermaidPlugin implements TaxonomyPlugin {
    @Override public List<TaxonomyExtension> extensions() {
        return List.of(new MermaidExportExtension(new MermaidExportService()));
    }
}
