package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.LlmProvider;

import java.util.Map;
import java.util.Objects;

/** Explicit aliases only: names, URLs and credentials never infer quota sharing. */
public record ArtemisProviderPermitSettings(String destinationPrefix,
                                           Map<LlmProvider, String> providerGroups,
                                           long maximumWaitMillis) {
    public static final String DEFAULT_PREFIX = "taxonomy.analysis.provider-permits";

    public ArtemisProviderPermitSettings {
        if (destinationPrefix == null || !destinationPrefix.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*")
                || destinationPrefix.length() > 160) {
            throw new IllegalArgumentException("Invalid provider permit destination prefix");
        }
        if (maximumWaitMillis < 1 || maximumWaitMillis > 86_400_000) {
            throw new IllegalArgumentException("Provider permit maximum-wait-ms must be within 1..86400000");
        }
        providerGroups = Map.copyOf(Objects.requireNonNull(providerGroups, "providerGroups"));
        providerGroups.forEach((provider, group) -> {
            if (provider == LlmProvider.LOCAL_ONNX) {
                throw new IllegalArgumentException("LOCAL_ONNX has no physical provider HTTP permits");
            }
            validateGroup(group);
        });
    }

    static void validateGroup(String group) {
        if (group == null || !group.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Quota group must contain 1..64 lower-case letters, digits or hyphens");
        }
    }

    public String queueName(String group) {
        validateGroup(group);
        return destinationPrefix + "." + group;
    }
}
