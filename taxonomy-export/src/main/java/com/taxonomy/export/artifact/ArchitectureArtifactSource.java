package com.taxonomy.export.artifact;

/** Immutable authority coordinates of the architecture snapshot being exported. */
public record ArchitectureArtifactSource(
        String snapshotId,
        String snapshotRevision,
        String workspaceId,
        String branchName) {

    public ArchitectureArtifactSource {
        snapshotId = ArchitectureArtifactText.requireSafeText(
                snapshotId,
                "snapshotId");
        snapshotRevision = ArchitectureArtifactText.requireSafeText(
                snapshotRevision,
                "snapshotRevision");
        workspaceId = ArchitectureArtifactText.requireSafeText(
                workspaceId,
                "workspaceId");
        branchName = ArchitectureArtifactText.requireSafeText(
                branchName,
                "branchName");
    }
}
