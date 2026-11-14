package com.taxonomy.workspace.service;

import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves whether workspace metadata may be disclosed to an authenticated
 * principal. The lookup deliberately returns only a scalar count so callers
 * cannot accidentally expose or materialize a foreign workspace after an
 * authorization decision.
 */
@Service
public class WorkspaceAccessService {

    private final UserWorkspaceRepository workspaceRepository;

    private final WorkspaceActorAccess actors;

    public WorkspaceAccessService(UserWorkspaceRepository workspaceRepository, WorkspaceActorAccess actors) {
        this.actors = actors;
        this.workspaceRepository = workspaceRepository;
    }

    @Transactional(readOnly = true)
    public boolean canReadWorkspaceMetadata(String workspaceId, String username) {
        if (workspaceId == null || workspaceId.isBlank()
                || username == null || username.isBlank()) {
            return false;
        }
        try {
            String principal = actors.currentNavigationPrincipal(username).map(java.util.UUID::toString).orElse(null);
            return workspaceRepository.countVisibleWorkspaceMetadata(workspaceId.strip(), username.strip(), principal) > 0;
        } catch (org.springframework.security.access.AccessDeniedException denied) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public boolean canUsePrivateWorkspace(RepositoryContext context) {
        if (context == null || context.scope() != RepositoryScope.WORKSPACE) return false;
        try {
            String principal = actors.currentNavigationPrincipal(context.username()).map(java.util.UUID::toString).orElse(null);
            return workspaceRepository.countOwnedPrivateWorkspace(context.workspaceId(), context.username(), context.repositoryId(), principal) == 1;
        } catch (org.springframework.security.access.AccessDeniedException denied) {
            return false;
        }
    }
}
