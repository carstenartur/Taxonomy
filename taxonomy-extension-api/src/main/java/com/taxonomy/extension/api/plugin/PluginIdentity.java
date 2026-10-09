package com.taxonomy.extension.api.plugin;

import java.io.Serializable;

/** Identity of the exact installed artifact; the host computes the SHA-256. */
public record PluginIdentity(String id, String version, String artifactSha256) implements Serializable {
    public PluginIdentity {
        requireId(id);
        if (version == null || version.isBlank() || version.length() > 128)
            throw new IllegalArgumentException("Plugin version is required");
        if (artifactSha256 == null || !artifactSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Plugin artifact SHA-256 must be 64 lowercase hexadecimal characters");
    }

    public static String requireId(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9.-]{0,127}"))
            throw new IllegalArgumentException("Invalid plugin ID");
        return id;
    }
}
