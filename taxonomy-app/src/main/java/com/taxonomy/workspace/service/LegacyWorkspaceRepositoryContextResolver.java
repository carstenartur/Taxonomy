package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Workspace-owned resolution for callers that already supply a compatibility selection. */
@Service
public class LegacyWorkspaceRepositoryContextResolver implements WorkspaceRepositoryContextPort {

    private final SystemRepositoryService repositories;
    private final UserWorkspaceRepository workspaces;

    public LegacyWorkspaceRepositoryContextResolver(
            SystemRepositoryService repositories, UserWorkspaceRepository workspaces) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @Override
    public RepositoryContext resolve(WorkspaceContext selection) {
        WorkspaceContext legacy = selection != null ? selection : WorkspaceContext.SHARED;
        String username = normalizeOptional(legacy.username());
        username = username != null ? username : "system";
        String branch = normalizeOptional(legacy.currentBranch());
        String workspaceId = normalizeOptional(legacy.workspaceId());
        String requestedRepositoryId = requireText(
                legacy.repositoryId(), "workspaceContext.repositoryId");
        boolean primarySelection = WorkspaceContext.LEGACY_REPOSITORY_ID.equals(requestedRepositoryId);

        if (workspaceId == null) {
            SystemRepository selectedRepository = primarySelection
                    ? repositories.getPrimaryRepository()
                    : repositories.getRepository(requestedRepositoryId);
            return RepositoryContext.centralRead(
                    selectedRepository.getRepositoryId(),
                    branch != null ? branch : selectedRepository.getDefaultBranch(),
                    username);
        }

        UserWorkspace workspace = workspaces.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workspace not found while resolving repository context: " + workspaceId));
        if (workspace.getUsername() != null
                && !workspace.getUsername().equals(username)
                && !"system".equals(username)) {
            throw new IllegalArgumentException(
                    "Workspace does not belong to the active user: " + workspaceId);
        }
        String repositoryId = requireText(workspace.getSourceRepositoryId(), "workspace.sourceRepositoryId");
        if (!primarySelection && !repositoryId.equals(requestedRepositoryId)) {
            throw new IllegalArgumentException(
                    "Workspace repository does not match the selected repository: " + workspaceId);
        }
        String workspaceBranch = branch != null ? branch : normalizeOptional(workspace.getCurrentBranch());
        return RepositoryContext.workspace(
                repositoryId, workspaceId, workspaceBranch != null ? workspaceBranch : "draft", username);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
