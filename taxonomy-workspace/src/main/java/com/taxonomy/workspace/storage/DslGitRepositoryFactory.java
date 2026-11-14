package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates and caches Taxonomy's logical Git repositories.
 *
 * <p>The application owns repository naming, workspace routing and seeding. The
 * reusable {@code jgit-storage-hibernate-core} library owns database-backed JGit
 * object, ref and reflog persistence.</p>
 */
public class DslGitRepositoryFactory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DslGitRepositoryFactory.class);

    /** The well-known storage name retained for the migrated primary repository. */
    static final String SYSTEM_REPO_NAME = SystemRepositoryService.PRIMARY_STORAGE_NAME;

    /** Prefix for workspace repository names. */
    static final String WORKSPACE_REPO_PREFIX = "ws-";

    /** Fallback prefix used by in-memory tests without a repository catalog. */
    static final String CENTRAL_REPO_PREFIX = "central-";

    private final HibernateRepositoryFactory storageFactory;
    private final SystemRepositoryService systemRepositoryService;
    private final GitCommitSignatureService signatures;
    private final io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory<PinCleanup> existingPins;
    private final ConcurrentMap<String, DslGitRepository> cache = new ConcurrentHashMap<>();

    /** Legacy/in-memory constructor retained for tests. */
    public DslGitRepositoryFactory(HibernateRepositoryFactory storageFactory) {
        this(storageFactory, null);
    }

    /** Create a logical-repository factory with explicit catalog resolution. */
    public DslGitRepositoryFactory(
            HibernateRepositoryFactory storageFactory,
            SystemRepositoryService systemRepositoryService) {
        this(storageFactory, systemRepositoryService, GitCommitSignatureService.disabled());
    }

    public DslGitRepositoryFactory(
            HibernateRepositoryFactory storageFactory,
            SystemRepositoryService systemRepositoryService,
            GitCommitSignatureService signatures) {
        this(storageFactory,systemRepositoryService,signatures,null);
    }

    DslGitRepositoryFactory(HibernateRepositoryFactory storageFactory, SystemRepositoryService systemRepositoryService,
                            GitCommitSignatureService signatures, org.hibernate.SessionFactory sessions) {
        this.storageFactory = storageFactory;
        this.systemRepositoryService = systemRepositoryService;
        this.signatures = java.util.Objects.requireNonNull(signatures, "signatures");
        this.existingPins = sessions == null ? null :
                new io.github.carstenartur.jgit.storage.hibernate.SecuredHibernateRepositoryFactory<>(sessions,(access,request) -> {
                    if (!access.name().equals(request.repositoryName())) throw new SecurityException("Mismatched publication storage");
                    switch (request.operation()) {
                        case DISCOVER, READ -> { }
                        case DELETE_REF -> {
                            if (access.ref()==null || !access.ref().equals(request.refName())) throw new SecurityException("Mismatched publication pin");
                            access.authorize().run();
                        }
                        default -> throw new SecurityException("Publication cleanup only releases its own private ref");
                    }
                });
    }

    /**
     * Return the migrated primary repository.
     *
     * @deprecated repository-sensitive code should resolve an explicit repository ID.
     */
    @Deprecated(forRemoval = false)
    public DslGitRepository getSystemRepository() {
        return cache.computeIfAbsent(SYSTEM_REPO_NAME, this::createRepository);
    }

    /** Isolated administrative evidence, outside selectable model and workspace repositories. */
    public DslGitRepository getCatalogueReviewRepository() {
        return cache.computeIfAbsent("administrative-catalogue-reviews", this::createRepository);
    }

    private record PinCleanup(RepositoryName name,String ref,Runnable authorize) { }

    /** Reopen existing storage without creating it, then conditionally remove the exact private pin. */
    public void releasePublicationPin(String repositoryId,String workspaceId,String operationId,String branch,
                                      String recordedCandidate,Runnable authorize) throws IOException {
        authorize.run();
        String name = workspaceId == null ? storageNameFor(repositoryId) : workspaceRepositoryName(workspaceId);
        var committer = new ExpectedHeadDslCommitter();
        if (existingPins != null) {
            var access = new PinCleanup(new RepositoryName(name),ExpectedHeadDslCommitter.pinRef(operationId,branch),authorize);
            try (var existing = existingPins.open(access.name(),access)) {
                releasePin(committer,existing.repository(),operationId,branch,recordedCandidate,authorize);
            } catch (io.github.carstenartur.jgit.storage.hibernate.RepositoryDoesNotExistException missing) {
                throw new IOException("The recorded publication storage is unavailable",missing);
            } catch (IOException storage) {
                // JGit wraps policy exceptions raised during its final conditional DELETE_REF in an IOException.
                for (Throwable cause=storage.getCause(); cause!=null; cause=cause.getCause()) {
                    if (cause instanceof org.springframework.security.access.AccessDeniedException denial) throw denial;
                    if (cause instanceof com.taxonomy.workspace.service.WorkspaceFetchAuthority.FreshLoginRequired fresh) throw fresh;
                }
                throw storage;
            }
            return;
        }
        // A caller-supplied factory has no existing-only reopen contract. Prevent cleanup from allocating a repository.
        DslGitRepository opened=cache.get(name);
        if (opened != null) releasePin(committer,opened.getGitRepository(),operationId,branch,recordedCandidate,authorize);
        else if (storageFactory != null) throw new IOException("An existing repository handle is required for publication cleanup");
    }

    /** A missing owner does not prove its physical storage has disappeared. Never inspect or alter orphaned Git refs. */
    public void requireRemovedWorkspaceStorage(String workspaceId,Runnable authorize) throws IOException {
        authorize.run();
        String name=workspaceRepositoryName(workspaceId);
        if (existingPins!=null) {
            var routing=new PinCleanup(new RepositoryName(name),null,authorize);
            try (var existing=existingPins.open(routing.name(),routing)) {
                throw new IOException("The removed working copy still has physical storage");
            } catch (io.github.carstenartur.jgit.storage.hibernate.RepositoryDoesNotExistException gone) {
                return;
            }
        }
        if (cache.containsKey(name) || storageFactory!=null) throw new IOException("Cannot confirm removal of the recorded working copy storage");
    }

    private static void releasePin(ExpectedHeadDslCommitter committer, org.eclipse.jgit.lib.Repository repository,
                                   String operation,String branch,String recordedCandidate,Runnable authorize) throws IOException {
        String retained = committer.findPrepared(repository,operation,branch);
        if (retained==null) return;
        if (recordedCandidate!=null && !recordedCandidate.equals(retained)) throw new IOException("Publication pin differs from its recorded candidate");
        authorize.run();
        committer.release(repository,operation,branch,retained);
    }

    /** Return one explicitly selected central architecture repository. */
    public DslGitRepository getCentralRepository(String repositoryId) {
        return cache.computeIfAbsent(storageNameFor(repositoryId), this::createRepository);
    }

    /** Initialize/open a central repository under an already-reserved catalog storage name. */
    public DslGitRepository createCentralRepository(String repositoryId, String storageName) {
        requireText(repositoryId, "repositoryId");
        requireText(storageName, "storageName");
        if (systemRepositoryService != null) {
            SystemRepository metadata = systemRepositoryService.getRepository(repositoryId);
            if (!storageName.equals(metadata.getStorageRepositoryName())) {
                throw new IllegalArgumentException(
                        "Storage name does not match repository catalog entry: " + repositoryId);
            }
        }
        return cache.computeIfAbsent(storageName, this::createRepository);
    }

    /**
     * Return a repository isolated by workspace ID.
     *
     * <p>This compatibility path retains the historic primary-repository seed.
     * New multi-repository provisioning must use
     * {@link #createWorkspaceRepository(String, String, String)}.</p>
     */
    public DslGitRepository getWorkspaceRepository(String workspaceId) {
        String repoName = workspaceRepositoryName(workspaceId);
        return cache.computeIfAbsent(repoName, name -> {
            DslGitRepository repository = createRepository(name);
            String sourceRepositoryId = systemRepositoryService == null
                    ? WorkspaceContext.LEGACY_REPOSITORY_ID
                    : systemRepositoryService.getPrimaryRepository().getRepositoryId();
            seedFromCentral(repository, getSystemRepository(), sourceRepositoryId,
                    "draft", "system repository");
            return repository;
        });
    }

    /**
     * Open an existing workspace repository without inferring or seeding any source.
     * Explicit repository contexts use this path so a missing workspace can never
     * be accidentally initialized from the primary repository.
     */
    public DslGitRepository openWorkspaceRepository(String workspaceId) {
        return cache.computeIfAbsent(
                workspaceRepositoryName(workspaceId), this::createRepository);
    }

    /** Create/open an isolated workspace and seed it from the selected central source. */
    public DslGitRepository createWorkspaceRepository(
            String workspaceId,
            String sourceRepositoryId,
            String sourceBranch) {
        String repoName = workspaceRepositoryName(workspaceId);
        String sourceId = requireText(sourceRepositoryId, "sourceRepositoryId");
        DslGitRepository source = getCentralRepository(sourceId);
        String branch = requireText(sourceBranch, "sourceBranch");
        return cache.computeIfAbsent(repoName, name -> {
            DslGitRepository repository = createRepository(name);
            seedFromCentral(repository, source, sourceId, branch,
                    "central repository " + sourceId);
            return repository;
        });
    }

    /** Resolve the repository selected by an explicit repository context. */
    public DslGitRepository resolveRepository(RepositoryContext context) {
        if (context == null) {
            throw new IllegalArgumentException("RepositoryContext must not be null");
        }
        if (context.workspaceId() != null) {
            return openWorkspaceRepository(context.workspaceId());
        }
        return getCentralRepository(context.repositoryId());
    }

    /**
     * Resolve the compatibility workspace context.
     *
     * <p>Request-bound compatibility contexts carry an exact repository ID and
     * are routed with the same fail-closed semantics as {@link RepositoryContext}.
     * Only direct legacy callers using {@link WorkspaceContext#LEGACY_REPOSITORY_ID}
     * retain the historic primary-repository fallback.</p>
     *
     * @deprecated use {@link #resolveRepository(RepositoryContext)} when repository
     *             identity is available.
     */
    @Deprecated(forRemoval = false)
    public DslGitRepository resolveRepository(WorkspaceContext context) {
        if (context == null) {
            return getSystemRepository();
        }
        if (!WorkspaceContext.LEGACY_REPOSITORY_ID.equals(context.repositoryId())) {
            if (context.workspaceId() != null) {
                return openWorkspaceRepository(context.workspaceId());
            }
            return getCentralRepository(context.repositoryId());
        }
        if (context.workspaceId() == null) {
            return getSystemRepository();
        }
        return getWorkspaceRepository(context.workspaceId());
    }

    /** Close and remove a cached workspace handle without deleting persisted data. */
    public void evict(String workspaceId) {
        closeQuietly(cache.remove(workspaceRepositoryName(workspaceId)));
    }

    /** Delete all persisted Git state belonging to one workspace repository. */
    public RepositoryDeletionResult deleteWorkspaceRepository(String workspaceId) {
        String repoName = workspaceRepositoryName(workspaceId);
        closeQuietly(cache.remove(repoName));
        if (storageFactory == null) {
            return new RepositoryDeletionResult(0, 0, 0);
        }
        return storageFactory.deleteRepository(new RepositoryName(repoName));
    }

    /** Delete storage for a non-primary central repository. */
    public RepositoryDeletionResult deleteCentralRepository(String repositoryId) {
        String storageName = storageNameFor(repositoryId);
        if (SYSTEM_REPO_NAME.equals(storageName)) {
            throw new IllegalArgumentException("The primary repository cannot be deleted");
        }
        closeQuietly(cache.remove(storageName));
        if (storageFactory == null) {
            return new RepositoryDeletionResult(0, 0, 0);
        }
        return storageFactory.deleteRepository(new RepositoryName(storageName));
    }

    /** Create either a persistent library-backed repository or an owned in-memory repository. */
    protected DslGitRepository createRepository(String name) {
        if (storageFactory != null) {
            log.info("Opening database-backed DslGitRepository '{}'", name);
            return new DslGitRepository(storageFactory, name).withSignatureService(signatures, name);
        }
        log.info("Creating in-memory DslGitRepository '{}' (test mode)", name);
        return new DslGitRepository(
                new InMemoryRepository(new DfsRepositoryDescription(name)), true)
                .withSignatureService(signatures, name);
    }

    private void seedFromCentral(
            DslGitRepository workspace,
            DslGitRepository source,
            String sourceRepositoryId,
            String sourceBranch,
            String sourceDescription) {
        try {
            if (!workspace.getBranchNames().isEmpty()) {
                log.debug("Workspace repository already contains refs; skipping seed");
                return;
            }

            String sourceHead = source.getHeadCommit(sourceBranch);
            String dsl = sourceHead == null ? null : source.getDslAtCommit(sourceHead);
            if (dsl != null && !dsl.isBlank()) {
                var provenance = CommitProvenance.capture("fork", source, sourceRepositoryId,
                        sourceBranch, sourceHead, null);
                workspace.commitDsl("draft", dsl, "system",
                        "Initial working copy from " + sourceDescription + "/" + sourceBranch, provenance);
                log.info("Seeded workspace repository from {}/{}",
                        sourceDescription, sourceBranch);
            } else {
                log.debug("Source branch {}/{} is empty; skipping workspace seed",
                        sourceDescription, sourceBranch);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to seed workspace repository from " + sourceDescription,
                    exception);
        }
    }

    private String storageNameFor(String repositoryId) {
        String id = requireText(repositoryId, "repositoryId");
        if (systemRepositoryService == null) {
            return CENTRAL_REPO_PREFIX + id;
        }
        SystemRepository metadata = systemRepositoryService.getRepository(id);
        String storageName = metadata.getStorageRepositoryName();
        if (storageName == null || storageName.isBlank()) {
            if (metadata.isPrimaryRepo()) {
                return SYSTEM_REPO_NAME;
            }
            throw new IllegalStateException(
                    "Repository has no storageRepositoryName: " + repositoryId);
        }
        return storageName;
    }

    /** Shared storage identity convention; computing a name never opens or seeds a repository. */
    public static String workspaceRepositoryName(String workspaceId) {
        return WORKSPACE_REPO_PREFIX + requireText(workspaceId, "workspaceId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static void closeQuietly(DslGitRepository repository) {
        if (repository == null) {
            return;
        }
        try {
            repository.close();
        } catch (RuntimeException exception) {
            log.warn("Failed to close cached Git repository: {}", exception.getMessage());
        }
    }

    /** Close all cached repository handles during application shutdown. */
    @Override
    public void close() {
        cache.values().forEach(DslGitRepositoryFactory::closeQuietly);
        cache.clear();
    }
}
