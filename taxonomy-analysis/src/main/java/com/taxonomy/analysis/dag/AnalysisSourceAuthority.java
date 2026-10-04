package com.taxonomy.analysis.dag;

import java.util.Objects;

/**
 * Exact repository/workspace/branch/source-commit identity a task must read.
 *
 * <p>This is routing and provenance data, not authorization: a consumer
 * revalidates scope and authority before any effect. A worker must never fall
 * back to another branch, commit, workspace or a global "current" taxonomy.</p>
 *
 * @param repositoryId exact logical repository
 * @param workspaceId  workspace, or {@code null} for a central scope
 * @param branch       exact Git branch, or {@code null} only when the compatibility
 *                     scope could not resolve one (never substituted by a guess)
 * @param sourceCommit exact commit the analysis reads, or {@code null} when the
 *                     repository has not yet recorded a commit
 */
public record AnalysisSourceAuthority(String repositoryId, String workspaceId, String branch,
                                      String sourceCommit) {

    public AnalysisSourceAuthority {
        requireBounded(repositoryId, "repositoryId", false);
        requireBounded(workspaceId, "workspaceId", true);
        requireBounded(branch, "branch", true);
        requireBounded(sourceCommit, "sourceCommit", true);
    }

    private static void requireBounded(String value, String name, boolean nullable) {
        if (value == null) {
            if (nullable) return;
            throw new NullPointerException(name);
        }
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must be non-blank and at most 256 characters");
        }
    }

    /** Equality is the authority check; an equal value names the same exact read scope. */
    public boolean sameAs(AnalysisSourceAuthority other) {
        return Objects.equals(this, other);
    }
}
