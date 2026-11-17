package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.eclipse.jgit.lib.Repository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Resolve only the configured default and the exact target; lock catalogue and memberships before ref mutations. */
@Component
class BranchAdoptionTargets {
    private final SystemRepositoryService catalogue;
    private final RepositoryMembershipService memberships;
    private final UserWorkspaceRepository workspaces;
    private final DslGitRepositoryFactory git;

    BranchAdoptionTargets(SystemRepositoryService catalogue, RepositoryMembershipService memberships,
            UserWorkspaceRepository workspaces, DslGitRepositoryFactory git) {
        this.catalogue = catalogue;
        this.memberships = memberships;
        this.workspaces = workspaces;
        this.git = git;
    }

    record Target(DslGitRepository source, DslGitRepository local, String sourceBranch, UserWorkspace workspace) {
    }

    void lockRepository(String id) {
        catalogue.lock(id);
    }

    String defaultBranch(RepositoryContext selected) {
        return catalogue.getDefaultBranch(selected.repositoryId());
    }

    /** The selected target was read-authorized; stopping does not require upstream contribution. */
    boolean canStop(RepositoryContext selected, WorkspaceFetchAuthority.Grant grant, String originalPrincipal) {
        if (originalPrincipal.equals(grant.principalId()))
            return true;
        if (selected.workspaceId() != null)
            return false;
        var row = catalogue.lockCurrentPublicationSource(selected.repositoryId());
        memberships.lockForRefMutation(selected.repositoryId());
        return memberships.canMaintain(row, selected.username());
    }

    Target lock(RepositoryContext selected, WorkspaceFetchAuthority.Grant grant) {
        var row = catalogue.lockCurrentPublicationSource(selected.repositoryId());
        memberships.lockForRefMutation(selected.repositoryId());
        if (!memberships.canContribute(row, selected.username()))
            throw denied();
        String source = row.getDefaultBranch();
        if (!valid(source) || !valid(selected.branch())
                || (selected.workspaceId() == null && source.equals(selected.branch())))
            throw new IllegalArgumentException("A working branch distinct from the configured default is required");
        var central = git.getCentralRepository(selected.repositoryId());
        if (selected.workspaceId() == null)
            return new Target(central, central, source, null);
        var workspace = workspaces.findByWorkspaceIdForUpdate(selected.workspaceId())
                .orElseThrow(BranchAdoptionTargets::denied);
        if (!workspace.matchesOwner(selected.username(), grant.principalId()) || workspace.isShared()
                || workspace.isArchived()
                || !selected.repositoryId().equals(workspace.getSourceRepositoryId())
                || workspace.getProvisioningStatus() != WorkspaceProvisioningStatus.READY)
            throw denied();
        if (!selected.branch().equals(workspace.getCurrentBranch()))
            throw new IllegalStateException("The working copy is on a different branch");
        return new Target(central, git.openWorkspaceRepository(selected.workspaceId()), source, workspace);
    }

    private static boolean valid(String branch) {
        return branch != null && Repository.isValidRefName("refs/heads/" + branch)
                && !branch.startsWith(DslGitRepository.EXTERNAL_SYNC_BRANCH_PREFIX);
    }

    static AccessDeniedException denied() {
        return new AccessDeniedException("Current branch adoption authority is required");
    }
}
