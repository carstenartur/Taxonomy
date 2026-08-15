package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves whether workspace metadata may be disclosed to an authenticated
 * principal. The lookup deliberately returns only a boolean so callers cannot
 * accidentally expose a foreign workspace after an authorization decision.
 */
@Service
public class WorkspaceAccessService {

    private final UserWorkspaceRepository workspaceRepository;

    public WorkspaceAccessService(UserWorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Transactional(readOnly = true)
    public boolean canReadWorkspaceMetadata(String workspaceId, String username) {
        if (workspaceId == null || workspaceId.isBlank()
                || username == null || username.isBlank()) {
            return false;
        }
        return workspaceRepository.findByWorkspaceId(workspaceId)
                .filter(workspace -> isVisibleTo(workspace, username))
                .isPresent();
    }

    private static boolean isVisibleTo(UserWorkspace workspace, String username) {
        return workspace.isShared() || username.equals(workspace.getUsername());
    }
}
