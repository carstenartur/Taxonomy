package com.taxonomy.architecture.controller;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.controller.ExplicitRepositoryReadSelection;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;

/** The authorized, immutable revision used by an architecture query and its HTTP response. */
record ArchitectureQuerySource(RepositoryContext context, String commit) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static ArchitectureQuerySource resolve(ExplicitRepositoryReadSelection selection, String repositoryId,
                                           String branch, String commit, HttpServletRequest request) {
        return new ArchitectureQuerySource(selection.resolve(repositoryId, branch, commit, request),
                WorkspaceDslReadPort.normalizeCommitId(commit));
    }

    ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder response) {
        response.cacheControl(CacheControl.noStore())
                .header("X-Taxonomy-Repository", context.repositoryId())
                .header("X-Taxonomy-Workspace-Id", Objects.toString(context.workspaceId(), ""))
                .header("X-Taxonomy-Branch", context.branch());
        if (commit != null) response.header("X-Taxonomy-Commit", commit);
        return response;
    }

    <T> ResponseEntity<T> response(T body) {
        var identity = List.of("architecture-query-v1", context.repositoryId(), Objects.toString(context.workspaceId(), ""),
                context.branch(), commit, body);
        String tag = StableIdentityHash.sha256(JSON.writeValueAsString(identity));
        return headers(ResponseEntity.ok()).header("X-Taxonomy-Source-State", "READY")
                .eTag("W/\"" + tag + "\"").body(body);
    }
}
