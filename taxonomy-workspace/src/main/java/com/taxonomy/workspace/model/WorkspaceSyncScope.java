package com.taxonomy.workspace.model;

import com.taxonomy.identity.StableIdentityHash;

import org.eclipse.jgit.lib.Repository;

/** Actor and exact Git scope of an observed synchronization. */
public record WorkspaceSyncScope(String username, String repositoryId, String workspaceId, String branch) {
    public WorkspaceSyncScope {
        exactText(username);
        exactText(repositoryId);
        if (workspaceId != null) exactText(workspaceId);
        exactText(branch);
        if (!Repository.isValidRefName("refs/heads/" + branch)) throw new IllegalArgumentException("Invalid synchronization branch");
    }

    private static void exactText(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 255) {
            throw new IllegalArgumentException("Invalid synchronization scope");
        }
    }

    public String key() {
        var workspace = workspaceId == null ? RepositoryTenantIdentity.CENTRAL_SCOPE
                : RepositoryTenantIdentity.WORKSPACE_SCOPE_PREFIX + workspaceId;
        var identity = new RepositoryTenantIdentity(repositoryId, workspace, branch);
        return StableIdentityHash.sha256(username.length() + ":" + username + identity.scopeKey());
    }
}
