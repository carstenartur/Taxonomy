package com.taxonomy.architecture.controller;

import com.taxonomy.workspace.controller.ExplicitRepositoryReadSelection;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import java.util.Objects;

/** The authorized, immutable revision used by an architecture query and its HTTP response. */
record ArchitectureQuerySource(RepositoryContext context, String commit) {
    static ArchitectureQuerySource resolve(ExplicitRepositoryReadSelection selection, String repositoryId,
                                           String branch, String commit, HttpServletRequest request) {
        return new ArchitectureQuerySource(selection.resolve(repositoryId, branch, commit, request),
                WorkspaceDslReadPort.normalizeCommitId(commit));
    }

    <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Taxonomy-Repository", context.repositoryId())
                .header("X-Taxonomy-Workspace-Id", Objects.toString(context.workspaceId(), ""))
                .header("X-Taxonomy-Branch", context.branch())
                .header("X-Taxonomy-Commit", commit)
                .body(body);
    }
}
