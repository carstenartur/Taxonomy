package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Provisions central repositories and durable forks in logical JGit storage. */
@Service
public class ArchitectureRepositoryProvisioningService {

    private static final String MINIMAL_DSL = "meta { language: \"taxdsl\"; }\n";

    private final SystemRepositoryService systemRepositoryService;
    private final RepositoryMembershipService membershipService;
    private final DslGitRepositoryFactory repositoryFactory;

    public ArchitectureRepositoryProvisioningService(
            SystemRepositoryService systemRepositoryService,
            RepositoryMembershipService membershipService,
            DslGitRepositoryFactory repositoryFactory) {
        this.systemRepositoryService = systemRepositoryService;
        this.membershipService = membershipService;
        this.repositoryFactory = repositoryFactory;
    }

    /** Create a new independent central repository with a valid initial DSL commit. */
    public SystemRepository createRepository(
            String displayName,
            String slug,
            String description,
            RepositoryVisibility visibility,
            String ownerId,
            String defaultBranch) {
        SystemRepository metadata = systemRepositoryService.createCentralRepository(
                displayName, slug, description, visibility, ownerId, defaultBranch);
        return provisionRepository(
                metadata,
                MINIMAL_DSL,
                ownerId,
                "Initialize architecture repository",
                "Could not initialize central repository ");
    }

    /** Create a durable central fork from the selected source repository/branch. */
    public SystemRepository createFork(
            String sourceRepositoryId,
            String requestedSourceBranch,
            String displayName,
            String slug,
            String description,
            RepositoryVisibility visibility,
            String ownerId) {
        SystemRepository source = systemRepositoryService.getRepository(sourceRepositoryId);
        if (source.getLifecycleState() != RepositoryLifecycleState.ACTIVE) {
            throw new IllegalStateException(
                    "Source repository is not active: " + sourceRepositoryId);
        }
        String sourceBranch = requestedSourceBranch == null || requestedSourceBranch.isBlank()
                ? source.getDefaultBranch()
                : requestedSourceBranch.strip();

        String sourceDsl;
        String sourceCommit;
        try {
            DslGitRepository sourceGit =
                    repositoryFactory.getCentralRepository(source.getRepositoryId());
            sourceDsl = sourceGit.getDslAtHead(sourceBranch);
            sourceCommit = sourceGit.getHeadCommit(sourceBranch);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "Could not read source repository " + sourceRepositoryId,
                    exception);
        }
        if (sourceDsl == null || sourceDsl.isBlank() || sourceCommit == null) {
            throw new IllegalStateException(
                    "Source branch has no architecture content: " + sourceBranch);
        }

        SystemRepository fork = systemRepositoryService.createForkMetadata(
                source.getRepositoryId(),
                sourceBranch,
                sourceCommit,
                displayName,
                slug,
                description,
                visibility,
                ownerId);
        return provisionRepository(
                fork,
                sourceDsl,
                ownerId,
                "Fork from " + source.getSlug() + "/" + sourceBranch,
                "Could not initialize fork repository ");
    }

    private SystemRepository provisionRepository(
            SystemRepository metadata,
            String dsl,
            String ownerId,
            String commitMessage,
            String failurePrefix) {
        String repositoryId = metadata.getRepositoryId();
        boolean storageAttempted = false;
        try {
            membershipService.assignOwner(repositoryId, ownerId);
            storageAttempted = true;
            DslGitRepository git = repositoryFactory.createCentralRepository(
                    repositoryId, metadata.getStorageRepositoryName());
            git.commitDsl(
                    metadata.getDefaultBranch(),
                    dsl,
                    ownerId,
                    commitMessage);
            return systemRepositoryService.markProvisioningReady(repositoryId);
        } catch (IOException | RuntimeException exception) {
            if (storageAttempted) {
                try {
                    repositoryFactory.deleteCentralRepository(repositoryId);
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            try {
                systemRepositoryService.markProvisioningFailed(
                        repositoryId, safeMessage(exception));
            } catch (RuntimeException stateException) {
                exception.addSuppressed(stateException);
            }
            throw new IllegalStateException(failurePrefix + repositoryId, exception);
        }
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }
}
