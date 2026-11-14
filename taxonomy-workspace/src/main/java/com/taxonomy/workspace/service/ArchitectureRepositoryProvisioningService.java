package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Provisions central repositories and durable forks in logical JGit storage. */
@Service
public class ArchitectureRepositoryProvisioningService {

    private static final String MINIMAL_DSL = "meta { language: \"taxdsl\"; }\n";
    private static final String INITIAL_MESSAGE = "Initialize architecture repository";

    private final ProjectCatalogueSeed projectSeed;
    private final SystemRepositoryService systemRepositoryService;
    private final RepositoryMembershipService membershipService;
    private final DslGitRepositoryFactory repositoryFactory;

    public ArchitectureRepositoryProvisioningService(
            SystemRepositoryService systemRepositoryService,
            RepositoryMembershipService membershipService,
            DslGitRepositoryFactory repositoryFactory,
            ProjectCatalogueSeed projectSeed) {
        this.projectSeed = projectSeed;
        this.systemRepositoryService = systemRepositoryService;
        this.membershipService = membershipService;
        this.repositoryFactory = repositoryFactory;
    }

    private CentralForkInitialTransfer initialTransfers;

    @org.springframework.beans.factory.annotation.Autowired
    void useInitialTransfers(CentralForkInitialTransfer transfers) { this.initialTransfers = transfers; }

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
        return provisionRepository(metadata, ownerId);
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

    private void initializeOrVerifyCreation(DslGitRepository git, SystemRepository metadata, String owner) throws IOException {
        String branch = metadata.getDefaultBranch();
        var repository = git.getGitRepository();
        synchronized (repository) {
            var refs = repository.getRefDatabase().getRefsByPrefix(Constants.R_REFS);
            String head = git.getHeadCommit(branch);
            if (head == null) {
                if (!refs.isEmpty()) throw retainedSourceConflict();
                git.commitDslIfHeadMatches(branch, null, projectSeed.newProject("default"), owner, INITIAL_MESSAGE);
                return;
            }
            if (refs.size() != 1 || !refs.getFirst().getName().equals(Constants.R_HEADS + branch)) throw retainedSourceConflict();
            String retained = git.getDslAtCommit(head);
            if (!MINIMAL_DSL.equals(retained) && !com.taxonomy.dsl.model.ProjectCatalogue.isInitial(retained, "default"))
                throw retainedSourceConflict();
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
        requireForkSource(source, ownerId, visibility);
        String sourceBranch = requestedSourceBranch == null || requestedSourceBranch.isBlank()
                ? source.getDefaultBranch()
                : requestedSourceBranch.strip();

        if (initialTransfers != null) {
            var existing = systemRepositoryService.byCreationSlug(slug, displayName).orElse(null);
            if (existing != null && initialTransfers.isJournalled(existing.getRepositoryId())) {
                if (!SystemRepositoryService.matchesForkRequest(existing, sourceRepositoryId, sourceBranch, displayName.strip(),
                        description, visibility, ownerId)) throw retainedSourceConflict();
                if (existing.getLifecycleState() == RepositoryLifecycleState.ACTIVE) verifyRetainedFork(existing);
                return initialTransfers.complete(existing, ownerId);
            }
        }

        var recovered = systemRepositoryService.acknowledgePublishedFork(sourceRepositoryId, sourceBranch,
                displayName, slug, description, visibility, ownerId, retained -> {
                    verifyRetainedFork(retained);
                    systemRepositoryService.readCurrentAuthorization(sourceRepositoryId, current -> {
                        if (current.getLifecycleState() != RepositoryLifecycleState.ACTIVE) throw retainedSourceConflict();
                        requireForkSource(current, ownerId, retained.getVisibility());
                    });
                });
        if (recovered != null) return recovered;

        DslGitRepository sourceGit;
        String sourceCommit;
        try {
            sourceGit = repositoryFactory.getCentralRepository(source.getRepositoryId());
            sourceCommit = sourceGit.getHeadCommit(sourceBranch);
            if (sourceCommit == null) throw new IOException("Source branch has no commit");
            verifyForkSnapshot(sourceGit, sourceCommit);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "Could not read source repository " + sourceRepositoryId,
                    exception);
        }
        if (initialTransfers != null) {
            var fork = initialTransfers.admit(sourceRepositoryId, sourceBranch, sourceCommit, displayName, slug, description, visibility, ownerId);
            return initialTransfers.complete(fork, ownerId);
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
        return provisionFork(fork, sourceGit, ownerId);
    }

    private void verifyRetainedFork(SystemRepository fork) {
        verifyRetainedFork(repositoryFactory.getCentralRepository(fork.getRepositoryId()), fork);
    }

    static void verifyRetainedFork(DslGitRepository target, SystemRepository fork) {
        String commit = fork.getForkPointCommit();
        var upstream = new GitUpstreamRef(fork.getUpstreamRepositoryId(), fork.getUpstreamBranch());
        try {
            String branch = Constants.R_HEADS + fork.getDefaultBranch();
            var git = target.getGitRepository();
            target.readFreshRef(branch);
            var refs = git.getRefDatabase().getRefsByPrefix(Constants.R_REFS);
            if (refs.size() != 2 || refs.stream().anyMatch(ref -> ref.isSymbolic() || ref.getObjectId() == null
                    || !commit.equals(ref.getObjectId().name()) || !(branch.equals(ref.getName()) || upstream.name().equals(ref.getName()))))
                throw retainedSourceConflict();
            verifyForkSnapshot(target, commit);
            try (var objects = new ObjectWalk(git)) {
                objects.markStart(objects.parseCommit(ObjectId.fromString(commit)));
                objects.checkConnectivity();
            }
            target.verifyIncoming(commit, null);
            target.verifyExpectedHead(fork.getDefaultBranch(), commit);
            if (!commit.equals(upstream.head(target))) throw retainedSourceConflict();
        } catch (IOException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The retained fork cannot be verified", failure);
        }
    }

    static void verifyForkSnapshot(DslGitRepository source, String commit) throws IOException {
        source.verifySnapshot(commit);
        var repository = source.getGitRepository();
        try (var walk = new RevWalk(repository)) {
            var tip = walk.parseCommit(ObjectId.fromString(commit));
            try (var tree = TreeWalk.forPath(repository, DslGitRepository.DSL_FILENAME, tip.getTree())) {
                if (tree == null || !FileMode.REGULAR_FILE.equals(tree.getFileMode(0))) {
                    throw new IOException("Source branch has no regular architecture DSL document");
                }
                var loader = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                if (new String(loader.getBytes(GitHistoryTransfer.MAX_OBJECT_BYTES), StandardCharsets.UTF_8).isBlank()) {
                    throw new IOException("Source branch has no architecture content");
                }
            }
        }
    }

    private void requireForkSource(SystemRepository source, String actor, RepositoryVisibility visibility) {
        if (!membershipService.canContribute(source, actor)) {
            throw new AccessDeniedException("Current source repository CONTRIBUTOR role is required");
        }
        SystemRepositoryService.requirePermittedForkVisibility(source, visibility);
    }

    private SystemRepository provisionFork(SystemRepository fork, DslGitRepository source, String ownerId) {
        String repositoryId = fork.getRepositoryId();
        String branch = fork.getDefaultBranch();
        String forkPoint = fork.getForkPointCommit();
        try {
            membershipService.assignOwner(repositoryId, ownerId);
            var destination = repositoryFactory.createCentralRepository(repositoryId, fork.getStorageRepositoryName());
            destination.verifyExpectedHead(branch, null);
            GitHistoryTransfer.copyCommitClosure(source, destination, forkPoint, List.of());
            systemRepositoryService.readCurrentAuthorization(fork.getUpstreamRepositoryId(),
                    current -> requireForkSource(current, ownerId, fork.getVisibility()));
            source.verifyExpectedHead(fork.getUpstreamBranch(), forkPoint);
            destination.recordUpstreamForkPoint(fork.getUpstreamRepositoryId(), fork.getUpstreamBranch(), forkPoint, ownerId);
            destination.fastForwardIfHeadMatches(branch, null, forkPoint, ownerId, "Provision repository fork");
            return systemRepositoryService.markProvisioningReady(repositoryId);
        } catch (BranchHeadConflictException conflict) {
            recordProvisioningFailure(repositoryId, conflict);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A branch changed while provisioning the fork", conflict);
        } catch (AccessDeniedException | IllegalArgumentException rejected) {
            recordProvisioningFailure(repositoryId, rejected);
            throw rejected;
        } catch (IOException | RuntimeException failure) {
            // Keep any published ref for recovery if catalog completion failed after Git publication.
            recordProvisioningFailure(repositoryId, failure);
            throw new IllegalStateException("Could not initialize fork repository " + repositoryId, failure);
        }
    }

    private SystemRepository provisionRepository(SystemRepository metadata, String ownerId) {
        String repositoryId = metadata.getRepositoryId();
        boolean storageAttempted = false;
        try {
            membershipService.assignOwner(repositoryId, ownerId);
            storageAttempted = true;
            DslGitRepository git = repositoryFactory.createCentralRepository(
                    repositoryId, metadata.getStorageRepositoryName());
            git.commitDsl(metadata.getDefaultBranch(), projectSeed.newProject("default"), ownerId, INITIAL_MESSAGE);
            return systemRepositoryService.markProvisioningReady(repositoryId);
        } catch (IOException | RuntimeException exception) {
            if (storageAttempted) {
                try {
                    repositoryFactory.deleteCentralRepository(repositoryId);
                } catch (RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            recordProvisioningFailure(repositoryId, exception);
            throw new IllegalStateException("Could not initialize central repository " + repositoryId, exception);
        }
    }

    private void recordProvisioningFailure(String repositoryId, Exception failure) {
        try {
            systemRepositoryService.markProvisioningFailed(repositoryId, safeMessage(failure));
        } catch (RuntimeException stateFailure) {
            failure.addSuppressed(stateFailure);
        }
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }
}
