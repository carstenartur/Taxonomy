package com.taxonomy.extension.api.plugin;

import java.util.List;

/** One complete immutable publication. Lists are deterministically ordered by the host. */
public record CatalogSnapshot(long revision, List<PluginDescriptor> plugins,
                              List<ExtensionRegistration> extensions) {
    public CatalogSnapshot { plugins = List.copyOf(plugins); extensions = List.copyOf(extensions); }
}
