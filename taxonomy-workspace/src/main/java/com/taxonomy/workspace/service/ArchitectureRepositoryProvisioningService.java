package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.CommitProvenance;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;

/** Provisions central repositories and durable forks in logical JGit storage. */
@Service
public class ArchitectureRepositoryProvisioningService {

    private static final String MINIMAL_DSL = "meta { language: \"taxdsl\"; }\n";
    private static final String INITIAL_MESSAGE = "Initialize architecture repository";

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
                "Could not initialize central repository ", null);
    }

    /** Stable-slug lifecycle creation and recovery share the catalog's durable claim. */
    public SystemRepositoryService.CreationClaim createOrResumePrivateRepository(
            String displayName, String slug, String description, String ownerId) {
        var claim = systemRepositoryService.claimPrivateCreation(displayName, slug, description, ownerId);
        SystemRepository metadata = claim.repository();
        if (metadata.getLifecycleState() == RepositoryLifecycleState.ACTIVE) return claim;
        String repositoryId = metadata.getRepositoryId();
        try {
            membershipService.assignOwner(repositoryId, ownerId);
            DslGitRepository git = repositoryFactory.createCentralRepository(repositoryId, metadata.getStorageRepositoryName());
            initializeOrVerifyCreation(git, metadata, ownerId);
            return new SystemRepositoryService.CreationClaim(systemRepositoryService.markProvisioningReady(repositoryId), claim.created());
        } catch (IOException | RuntimeException failure) {
            // A failed catalogue completion can follow a successful Git write. Retain that exact
            // source for the next retry; recovery must never delete existing logical Git storage.
            try { systemRepositoryService.markProvisioningFailed(repositoryId, safeMessage(failure)); }
            catch (RuntimeException stateFailure) { failure.addSuppressed(stateFailure); }
            if (failure instanceof ResponseStatusException conflict) throw conflict;
            throw new IllegalStateException("Could not initialize central repository " + repositoryId, failure);
        }
    }

    private static void initializeOrVerifyCreation(DslGitRepository git, SystemRepository metadata, String owner) throws IOException {
        String branch = metadata.getDefaultBranch();
        var repository = git.getGitRepository();
        synchronized (repository) {
            var refs = repository.getRefDatabase().getRefsByPrefix(Constants.R_REFS);
            String head = git.getHeadCommit(branch);
            if (head == null) {
                if (!refs.isEmpty()) throw retainedSourceConflict();
                git.commitDslIfHeadMatches(branch, null, MINIMAL_DSL, owner, INITIAL_MESSAGE);
                return;
            }
            if (refs.size() != 1 || !refs.getFirst().getName().equals(Constants.R_HEADS + branch)
                    || !MINIMAL_DSL.equals(git.getDslAtCommit(head))) throw retainedSourceConflict();
            try (var walk = new RevWalk(repository); var tree = new TreeWalk(repository)) {
                var initial = walk.parseCommit(ObjectId.fromString(head));
                if (initial.getParentCount() != 0 || !INITIAL_MESSAGE.equals(initial.getFullMessage())
                        || !owner.equals(initial.getAuthorIdent().getName())
                        || !owner.equals(initial.getCommitterIdent().getName())) throw retainedSourceConflict();
                tree.addTree(initial.getTree());
                if (!tree.next() || !DslGitRepository.DSL_FILENAME.equals(tree.getPathString())
                        || !FileMode.REGULAR_FILE.equals(tree.getFileMode(0)) || tree.next()) throw retainedSourceConflict();
            }
            git.verifyExpectedHead(branch, head);
        }
    }

    private static ResponseStatusException retainedSourceConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "This creation already retains different Git content; it was preserved and requires explicit review");
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
        CommitProvenance provenance;
        try {
            DslGitRepository sourceGit =
                    repositoryFactory.getCentralRepository(source.getRepositoryId());
            sourceCommit = sourceGit.getHeadCommit(sourceBranch);
            if (sourceCommit == null) throw new IOException("Source branch has no commit");
            provenance = CommitProvenance.capture("fork", sourceGit, source.getRepositoryId(),
                    sourceBranch, sourceCommit, null);
            sourceDsl = sourceGit.getDslAtCommit(sourceCommit);
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
                "Could not initialize fork repository ", provenance);
    }

    private SystemRepository provisionRepository(
            SystemRepository metadata,
            String dsl,
            String ownerId,
            String commitMessage,
            String failurePrefix,
            CommitProvenance provenance) {
        String repositoryId = metadata.getRepositoryId();
        boolean storageAttempted = false;
        try {
            membershipService.assignOwner(repositoryId, ownerId);
            storageAttempted = true;
            DslGitRepository git = repositoryFactory.createCentralRepository(
                    repositoryId, metadata.getStorageRepositoryName());
            if (provenance == null) git.commitDsl(
                    metadata.getDefaultBranch(),
                    dsl,
                    ownerId,
                    commitMessage);
            else git.commitDsl(metadata.getDefaultBranch(), dsl, ownerId, commitMessage, provenance);
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
