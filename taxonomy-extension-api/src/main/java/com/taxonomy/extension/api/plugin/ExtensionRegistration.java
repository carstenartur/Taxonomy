package com.taxonomy.extension.api.plugin;

import com.taxonomy.shared.extension.ExtensionDescriptor;
import java.io.Serializable;
import java.util.Objects;

/** Read-only registration metadata, never an implementation or classloader handle. */
public record ExtensionRegistration(ExtensionKey key, PluginIdentity plugin, ExtensionDescriptor descriptor)
        implements Serializable {
    public ExtensionRegistration {
        Objects.requireNonNull(key); Objects.requireNonNull(plugin); Objects.requireNonNull(descriptor);
    }
}
