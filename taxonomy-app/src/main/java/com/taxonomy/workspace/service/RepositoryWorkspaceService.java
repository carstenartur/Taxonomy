package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.model.WorkspaceRelationshipType;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/** Creates isolated working copies from an explicitly selected central repository. */
@Service
public class RepositoryWorkspaceService {

    private static final String WORKSPACE_BRANCH = "main";

    private final UserWorkspaceRepository workspaceRepository;
    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepositoryFactory repositoryFactory;

    public RepositoryWorkspaceService(
            UserWorkspaceRepository workspaceRepository,
            SystemRepositoryService systemRepositoryService,
            DslGitRepositoryFactory repositoryFactory) {
        this.workspaceRepository = workspaceRepository;
        this.systemRepositoryService = systemRepositoryService;
        this.repositoryFactory = repositoryFactory;
    }

    /**
     * Create and provision a personal working copy from the requested repository and branch.
     *
     * <p>Metadata is persisted before JGit provisioning. A failed provisioning remains visible
     * as {@link WorkspaceProvisioningStatus#FAILED}, so storage and metadata can be diagnosed
     * and retried rather than being silently orphaned.</p>
     */
    @Transactional
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
        workspaceRepository.save(workspace);

        try {
            DslGitRepository sourceGit =
                    repositoryFactory.getCentralRepository(source.getRepositoryId());
            String sourceDsl = sourceGit.getDslAtHead(sourceBranch);
            String sourceCommit = sourceGit.getHeadCommit(sourceBranch);
            if (sourceDsl == null || sourceDsl.isBlank() || sourceCommit == null) {
                throw new IllegalStateException(
                        "Source branch has no architecture content: " + sourceBranch);
            }

            DslGitRepository workspaceGit = repositoryFactory.createWorkspaceRepository(
                    workspace.getWorkspaceId(), source.getRepositoryId(), sourceBranch);
            workspaceGit.commitDsl(
                    WORKSPACE_BRANCH,
                    sourceDsl,
                    user,
                    "Create working copy from " + source.getSlug() + "/" + sourceBranch);

            workspace.setBaseCommit(sourceCommit);
            workspace.setCurrentCommit(workspaceGit.getHeadCommit(WORKSPACE_BRANCH));
            workspace.setLastFetchedCommit(sourceCommit);
            workspace.setLastIntegratedCommit(sourceCommit);
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
            workspace.setProvisionedAt(Instant.now());
            workspace.setProvisioningError(null);
            return workspaceRepository.save(workspace);
        } catch (IOException | RuntimeException exception) {
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
            workspace.setProvisioningError(exception.getMessage());
            workspaceRepository.save(workspace);
            throw new IllegalStateException(
                    "Could not provision working copy from repository " + sourceRepositoryId,
                    exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
