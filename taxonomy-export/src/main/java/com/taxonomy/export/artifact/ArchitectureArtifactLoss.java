package com.taxonomy.export.artifact;

import java.util.Objects;

/** One stable, machine-readable disposition of source architecture information. */
public record ArchitectureArtifactLoss(
        String code,
        String sourcePath,
        Disposition disposition,
        String detail) implements Comparable<ArchitectureArtifactLoss> {

    public ArchitectureArtifactLoss {
        code = ArchitectureArtifactText.requireSafeText(code, "code");
        sourcePath = ArchitectureArtifactText.requireSafeText(
                sourcePath,
                "sourcePath");
        disposition = Objects.requireNonNull(disposition, "disposition");
        detail = ArchitectureArtifactText.requireSafeText(detail, "detail");
    }

    @Override
    public int compareTo(ArchitectureArtifactLoss other) {
        int byCode = code.compareTo(other.code);
        if (byCode != 0) {
            return byCode;
        }
        int byPath = sourcePath.compareTo(other.sourcePath);
        if (byPath != 0) {
            return byPath;
        }
        int byDisposition = disposition.name().compareTo(
                other.disposition.name());
        if (byDisposition != 0) {
            return byDisposition;
        }
        return detail.compareTo(other.detail);
    }

    public enum Disposition {
        PRESERVED,
        MAPPED,
        REGENERATED,
        VISUAL_ONLY,
        OMITTED
    }
}
