package com.taxonomy.catalog.snapshot;

/** Architecture-operation identity supplied by its authorized caller; not an official catalogue version. */
public record CatalogueSourceIdentity(String repositoryId, String workspaceId, String branch,
                                      String sourceCommit) {
    public CatalogueSourceIdentity {
        bounded(repositoryId, "repositoryId", false);
        bounded(workspaceId, "workspaceId", true);
        bounded(branch, "branch", false);
        bounded(sourceCommit, "sourceCommit", false);
    }

    private static void bounded(String value, String field, boolean nullable) {
        if (value == null && nullable) return;
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be non-blank and at most 256 characters");
        }
    }
}
