package com.taxonomy.catalog.snapshot;

/** Architecture-operation identity supplied by its authorized caller; not an official catalogue version. */
public record CatalogueSourceIdentity(String repositoryId, String workspaceId, String branch,
                                      String sourceCommit,
                                      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                      String semanticModelSha256) {
    public CatalogueSourceIdentity(String repositoryId, String workspaceId, String branch, String sourceCommit) {
        this(repositoryId, workspaceId, branch, sourceCommit, null);
    }

    public CatalogueSourceIdentity {
        bounded(repositoryId, "repositoryId", false);
        bounded(workspaceId, "workspaceId", true);
        bounded(branch, "branch", false);
        bounded(sourceCommit, "sourceCommit", semanticModelSha256 != null);
        if (semanticModelSha256 != null && !semanticModelSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected the complete semantic model digest");
    }

    private static void bounded(String value, String field, boolean nullable) {
        if (value == null && nullable) return;
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be non-blank and at most 256 characters");
        }
    }
}
