package com.taxonomy.extension.api.plugin;

import java.util.Objects;

/** Durable invocation binding. It contains no credentials, implementation object or classloader. */
public record PluginInvocation(PluginIdentity plugin, String configurationRevision) {
    public PluginInvocation {
        Objects.requireNonNull(plugin, "plugin");
        if (configurationRevision == null || !configurationRevision.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Configuration revision must be a SHA-256 fingerprint");
    }
}
