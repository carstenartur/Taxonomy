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
            workspaceRepository.save(workspace);
            workspaceStorageAttempted = true;
            DslGitRepository workspaceGit = repositoryFactory.openWorkspaceRepository(workspace.getWorkspaceId());
            GitHistoryTransfer.copyCommitClosure(sourceGit, workspaceGit, sourceCommit, List.of());
            systemRepositoryService.readCurrentAuthorization(source.getRepositoryId(),
                    current -> requireContribution(current, user));
            workspaceGit.recordUpstreamForkPoint(source.getRepositoryId(), sourceBranch, sourceCommit, user);
            workspaceGit.fastForwardIfHeadMatches(WORKSPACE_BRANCH, null, sourceCommit, user,
                    "Create working copy from " + sourceBranch);
            workspaceGit.createBranchAtCommit(TRACKING_BRANCH, sourceCommit);

            workspace.setCurrentCommit(sourceCommit);
            workspace.setLastFetchedCommit(sourceCommit);
            workspace.setLastIntegratedCommit(sourceCommit);
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
            workspace.setProvisionedAt(Instant.now());
            workspace.setProvisioningError(null);
            return workspaceRepository.save(workspace);
        } catch (IOException | RuntimeException exception) {
            if (workspaceStorageAttempted) {
                try {
                    repositoryFactory.deleteWorkspaceRepository(workspace.getWorkspaceId());
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
            workspace.setProvisioningError(safeMessage(exception));
            workspace.setLastAccessedAt(Instant.now());
            workspaceRepository.save(workspace);
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

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }
}
