package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.model.WorkspaceRelationshipType;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Creates isolated working copies from an explicitly selected central repository. */
@Service
public class RepositoryWorkspaceService {

    private static final String WORKSPACE_BRANCH = "draft";
    private static final String TRACKING_BRANCH = "sync-base";

    private WorkspaceActorAccess actorAccess = WorkspaceActorAccess.unavailable();

    @org.springframework.beans.factory.annotation.Autowired
    void configureActorAccess(WorkspaceActorAccess actorAccess) {
        this.actorAccess = java.util.Objects.requireNonNull(actorAccess);
    }

    private final UserWorkspaceRepository workspaceRepository;
    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepositoryFactory repositoryFactory;
    private final RepositoryMembershipService memberships;

    public RepositoryWorkspaceService(
            UserWorkspaceRepository workspaceRepository,
            SystemRepositoryService systemRepositoryService,
            DslGitRepositoryFactory repositoryFactory,
            RepositoryMembershipService memberships) {
        this.workspaceRepository = workspaceRepository;
        this.systemRepositoryService = systemRepositoryService;
        this.repositoryFactory = repositoryFactory;
        this.memberships = memberships;
    }

    /**
     * Create and provision a personal working copy from the requested repository and branch.
     *
     * <p>This method deliberately has no surrounding Spring transaction. The initial
     * {@link WorkspaceProvisioningStatus#PROVISIONING} metadata must commit before JGit
     * allocation starts, and a later {@link WorkspaceProvisioningStatus#FAILED} update must
     * remain durable even though the caller receives an exception. Each Spring Data
     * {@code save} operation supplies its own transaction boundary.</p>
     *
     * <p>Persist the captured source checkpoint before copying its Git objects. Start
     * {@code draft} and {@code sync-base} at that commit even if the source advances.</p>
     */
    public UserWorkspace createWorkingCopy(
            String username,
            String sourceRepositoryId,
            String requestedSourceBranch,
            String displayName,
            String description) {
        String user = requireText(username, "username");
        SystemRepository source = systemRepositoryService.getRepository(sourceRepositoryId);
        if (source.getLifecycleState() != RepositoryLifecycleState.ACTIVE) {
            throw new IllegalStateException(
                    "Source repository is not active: " + sourceRepositoryId);
        }

        String sourceBranch = requestedSourceBranch == null || requestedSourceBranch.isBlank()
                ? source.getDefaultBranch()
                : requestedSourceBranch.strip();
        String name = requireText(displayName, "displayName");
        requireContribution(source, user);
        Instant now = Instant.now();

        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(UUID.randomUUID().toString());
        workspace.setUsername(user);
        actorAccess.currentOwnerPrincipal(user).ifPresent(workspace::assignOwnerPrincipal);
        workspace.setDisplayName(name);
        workspace.setDescription(description);
        workspace.setCurrentBranch(WORKSPACE_BRANCH);
        workspace.setBaseBranch(sourceBranch);
        workspace.setSourceBranch(sourceBranch);
        workspace.setSourceRepositoryId(source.getRepositoryId());
        workspace.setRelationshipType(WorkspaceRelationshipType.WORKING_COPY);
        workspace.setShared(false);
        workspace.setDefault(false);
        workspace.setArchived(false);
        workspace.setTopologyMode(source.getTopologyMode());
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
        workspace.setSyncTargetBranch(sourceBranch);
        workspace.setCreatedAt(now);
        workspace.setLastAccessedAt(now);
        workspace.setProvisioningSyncBaseBranch(TRACKING_BRANCH);
        var claim = new WorkspaceProvisioningClaim(workspace);
        claim.initialize();
        boolean reservationAttempted = false;
        boolean workspaceStorageAttempted = false;
        try {
            DslGitRepository sourceGit =
                    repositoryFactory.getCentralRepository(source.getRepositoryId());
            String sourceCommit = sourceGit.getHeadCommit(sourceBranch);
            if (sourceCommit == null) throw new IOException("Source branch has no commit");
            sourceGit.verifySnapshot(sourceCommit);
            String sourceDsl = sourceGit.getDslAtCommit(sourceCommit, GitHistoryTransfer.MAX_OBJECT_BYTES);
            if (sourceDsl == null || sourceDsl.isBlank()) {
                throw new IllegalStateException(
                        "Source branch has no architecture content: " + sourceBranch);
            }

            workspace.setBaseCommit(sourceCommit);
            reservationAttempted = true;
            workspaceRepository.save(workspace);
            workspaceStorageAttempted = true;
            DslGitRepository workspaceGit = repositoryFactory.openWorkspaceRepository(workspace.getWorkspaceId());
            GitHistoryTransfer.copyCommitClosure(sourceGit, workspaceGit, sourceCommit, List.of());
            return claim.apply(workspaceRepository, current -> {
                systemRepositoryService.readCurrentAuthorization(source.getRepositoryId(),
                        metadata -> requireContribution(metadata, user));
                workspaceGit.recordUpstreamForkPoint(source.getRepositoryId(), sourceBranch, sourceCommit, user);
                workspaceGit.fastForwardIfHeadMatches(WORKSPACE_BRANCH, null, sourceCommit, user,
                        "Create working copy from " + sourceBranch);
                WorkspaceProvisioningClaim.adoptIntegrationBase(workspaceGit, TRACKING_BRANCH, sourceCommit);
                current.setLastFetchedCommit(sourceCommit);
                current.setLastIntegratedCommit(sourceCommit);
                WorkspaceProvisioningClaim.markProvisioned(current, sourceCommit);
            });
        } catch (IOException | RuntimeException exception) {
            if (reservationAttempted) {
                // An insert can commit before its acknowledgement is lost. Only the current
                // worker may record failure or remove storage after that point.
                boolean cleanupAllocatedStorage = workspaceStorageAttempted;
                claim.fail(workspaceRepository, exception, current -> {
                    if (cleanupAllocatedStorage) repositoryFactory.deleteWorkspaceRepository(current.getWorkspaceId());
                });
            } else {
                workspace.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
                workspace.setProvisioningError(WorkspaceProvisioningClaim.message(exception));
                workspaceRepository.save(workspace);
            }
            if (exception instanceof org.springframework.web.server.ResponseStatusException conflict) throw conflict;
            if (exception instanceof AccessDeniedException denied) {
                throw denied;
            }
            throw new IllegalStateException(
                    "Could not provision working copy from repository " + sourceRepositoryId,
                    exception);
        }
    }

    private void requireContribution(SystemRepository repository, String user) {
        if (!memberships.canContribute(repository, user)) {
            throw new AccessDeniedException("Current source repository CONTRIBUTOR role is required");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

}
