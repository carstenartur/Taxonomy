package com.taxonomy.export.artifact;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Canonically ordered loss evidence for one export profile. */
public record ArchitectureArtifactLossManifest(
        String profileVersion,
        List<ArchitectureArtifactLoss> losses) {

    public ArchitectureArtifactLossManifest {
        profileVersion = ArchitectureArtifactText.requireSafeText(
                profileVersion,
                "profileVersion");
        losses = canonicalize(losses);
    }

    public static ArchitectureArtifactLossManifest lossless(
            String profileVersion) {
        return new ArchitectureArtifactLossManifest(
                profileVersion,
                List.of());
    }

    private static List<ArchitectureArtifactLoss> canonicalize(
            List<ArchitectureArtifactLoss> losses) {
        if (losses == null) {
            throw new IllegalArgumentException("losses must not be null");
        }
        Set<String> codes = new HashSet<>();
        List<ArchitectureArtifactLoss> canonical = losses.stream()
                .map(loss -> Objects.requireNonNull(loss, "loss"))
                .sorted()
                .toList();
        for (ArchitectureArtifactLoss loss : canonical) {
            if (!codes.add(loss.code())) {
                throw new IllegalArgumentException("Duplicate loss code");
            }
        }
        return canonical;
    }
}
