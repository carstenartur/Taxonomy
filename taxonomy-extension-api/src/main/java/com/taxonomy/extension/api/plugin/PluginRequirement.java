package com.taxonomy.extension.api.plugin;

import java.io.Serializable;

public record PluginRequirement(String id, String versionRange) implements Serializable {
    public PluginRequirement {
        PluginIdentity.requireId(id);
        if (versionRange == null || versionRange.isBlank())
            throw new IllegalArgumentException("Plugin dependency version range is required");
    }
}
