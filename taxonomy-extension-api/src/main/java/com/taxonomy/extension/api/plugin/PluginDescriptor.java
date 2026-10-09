package com.taxonomy.extension.api.plugin;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.Objects;

/** SemVer ranges refer to the host SPI, independently of the product release version. */
public record PluginDescriptor(PluginIdentity identity, String hostApiRange,
        List<PluginRequirement> requires, Set<String> capabilities, PluginMode mode) implements Serializable {
    public static final String HOST_API_VERSION = "1.0.0";
    public PluginDescriptor {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(mode, "mode");
        if (hostApiRange == null || hostApiRange.isBlank())
            throw new IllegalArgumentException("Host API version range is required");
        requires = List.copyOf(Objects.requireNonNull(requires, "requires"));
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (requires.stream().map(PluginRequirement::id).distinct().count() != requires.size())
            throw new IllegalArgumentException("Duplicate plugin requirement");
        if (requires.stream().anyMatch(r -> r.id().equals(identity.id())))
            throw new IllegalArgumentException("Plugin cannot require itself");
        for (String capability : capabilities) {
            if (!capability.matches("[a-z][a-z0-9.:-]{0,127}"))
                throw new IllegalArgumentException("Invalid plugin capability");
        }
    }
}
