package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Provisions central repositories and durable forks in logical JGit storage. */
@Service
public class ArchitectureRepositoryProvisioningService {

    private static final String MINIMAL_DSL = "meta { language: \"taxdsl\"; }\n";

    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepositoryFactory repositoryFactory;

    public ArchitectureRepositoryProvisioningService(
            SystemRepositoryService systemRepositoryService,
            DslGitRepositoryFactory repositoryFactory) {
        this.systemRepositoryService = systemRepositoryService;
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
        try {
            DslGitRepository git = repositoryFactory.createCentralRepository(
                    metadata.getRepositoryId(), metadata.getStorageRepositoryName());
            git.commitDsl(
                    metadata.getDefaultBranch(),
                    MINIMAL_DSL,
                    ownerId,
                    "Initialize architecture repository");
            return metadata;
        } catch (IOException | RuntimeException exception) {
            systemRepositoryService.markProvisioningFailed(
                    metadata.getRepositoryId(), exception.getMessage());
            throw new IllegalStateException(
                    "Could not initialize central repository " + metadata.getRepositoryId(),
                    exception);
        }
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
        String sourceBranch = requestedSourceBranch == null || requestedSourceBranch.isBlank()
                ? source.getDefaultBranch()
                : requestedSourceBranch.strip();

        try {
            DslGitRepository sourceGit =
                    repositoryFactory.getCentralRepository(source.getRepositoryId());
            String sourceDsl = sourceGit.getDslAtHead(sourceBranch);
            String sourceCommit = sourceGit.getHeadCommit(sourceBranch);
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
            try {
                DslGitRepository forkGit = repositoryFactory.createCentralRepository(
                        fork.getRepositoryId(), fork.getStorageRepositoryName());
                forkGit.commitDsl(
                        fork.getDefaultBranch(),
                        sourceDsl,
                        ownerId,
                        "Fork from " + source.getSlug() + "/" + sourceBranch);
                return fork;
            } catch (IOException | RuntimeException exception) {
                systemRepositoryService.markProvisioningFailed(
                        fork.getRepositoryId(), exception.getMessage());
                throw exception;
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read source repository " + sourceRepositoryId,
                    exception);
        }
    }
}
