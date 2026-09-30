package com.taxonomy.backup;

import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.SystemRepositoryRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;

/** Uses current persisted grants and the existing membership policy at every access boundary. */
public final class PersistentBackupAccessPolicy implements BackupAccessPolicy {
    private final PrincipalIdentityService identities;
    private final SystemRepositoryRepository repositories;
    private final UserWorkspaceRepository workspaces;
    private final RepositoryMembershipService memberships;

    public PersistentBackupAccessPolicy(PrincipalIdentityService identities, SystemRepositoryRepository repositories,
                                       UserWorkspaceRepository workspaces, RepositoryMembershipService memberships) {
        this.identities = identities; this.repositories = repositories;
        this.workspaces = workspaces; this.memberships = memberships;
    }

    @Override public boolean isEnabled(PrincipalId principal) { return identities.isEnabled(principal); }

    @Override public boolean hasCapability(PrincipalId principal, BackupCapability capability, BackupScope scope) {
        return identities.hasCapability(principal, capability);
    }

    @Override public boolean canRead(PrincipalId principal, BackupRepositoryKey selected) {
        if (!isEnabled(principal)) return false;
        var identity = identities.find(principal).orElseThrow();
        var repository = repositories.findByRepositoryId(selected.repositoryId());
        if (repository.isEmpty() || !selected.repositoryId().equals(repository.get().getRepositoryId())
                || !memberships.canRead(repository.get(), identity.scopeKey())) return false;
        if (selected.workspaceId() == null) return true;
        return workspaces.findByWorkspaceId(selected.workspaceId())
                .filter(workspace -> selected.workspaceId().equals(workspace.getWorkspaceId()))
                .filter(workspace -> selected.repositoryId().equals(workspace.getSourceRepositoryId()))
                .filter(workspace -> !workspace.isArchived() && workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.READY)
                .filter(workspace -> workspace.isShared() || identity.scopeKey().equals(workspace.getUsername()))
                .isPresent();
    }

    @Override public boolean canReadVersion(PrincipalId principal, BackupRepositoryKey repository, String commit) {
        return canRead(principal, repository) && identities.hasVersionAccess(principal, repository, commit);
    }
}
