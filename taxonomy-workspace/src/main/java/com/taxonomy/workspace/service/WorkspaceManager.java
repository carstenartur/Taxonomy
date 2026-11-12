package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.RepositoryLifecycleState;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Manages per-user workspace state for multi-user architecture editing.
 *
 * <p>Workspace metadata is persisted through {@link UserWorkspaceRepository},
 * while active navigation state stays in memory. When a
 * {@link DslGitRepositoryFactory} is available, each workspace uses its own
 * logical JGit repository name in the shared database.</p>
 */
@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    /** Default username used when no authentication context is available. */
    public static final String DEFAULT_USER = "anonymous";

    private final UserWorkspaceRepository workspaceRepository;
    private final int maxHistory;
    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepository gitRepository;
    private final DslGitRepositoryFactory repositoryFactory;
    private RepositoryMembershipService sourceMemberships;
    private TransactionTemplate provisioningOutsideReadOnlyTransaction;
    private WorkspaceProvisionPermission provisionPermission = actor -> true;

    @Autowired(required = false)
    void setWorkspaceProvisionPermission(WorkspaceProvisionPermission provisionPermission) {
        this.provisionPermission = java.util.Objects.requireNonNull(provisionPermission);
    }

    /** Only automatic read/default paths use this policy; explicit system provisioning stays separate. */
    public boolean mayProvisionDefaultWorkspace(String username) {
        return provisionPermission.mayProvision(username);
    }

    @Autowired
    void configureProvisioningTransactions(PlatformTransactionManager transactions) {
        provisioningOutsideReadOnlyTransaction = new TransactionTemplate(transactions);
        provisioningOutsideReadOnlyTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Autowired
    void configureSourceMemberships(RepositoryMembershipService sourceMemberships) {
        this.sourceMemberships = sourceMemberships;
    }

    /** Navigation state belongs to one actor and workspace, including pinned inactive tabs. */
    private record WorkspaceStateKey(String username, String workspaceId) {}

    private final ConcurrentMap<WorkspaceStateKey, UserWorkspaceState> workspaceStates =
            new ConcurrentHashMap<>();

    /** Maps a username to its currently active workspace ID. */
    private final ConcurrentMap<String, String> activeWorkspaceByUser =
            new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceManager(
            UserWorkspaceRepository workspaceRepository,
            @Value("${taxonomy.context.max-history:50}") int maxHistory,
            SystemRepositoryService systemRepositoryService,
            DslGitRepositoryFactory repositoryFactory) {
        this.workspaceRepository = workspaceRepository;
        this.maxHistory = maxHistory;
        this.systemRepositoryService = systemRepositoryService;
        this.gitRepository = repositoryFactory.getSystemRepository();
        this.repositoryFactory = repositoryFactory;
    }

    /** Legacy constructor retained for unit tests using one caller-supplied repository. */
    public WorkspaceManager(
            UserWorkspaceRepository workspaceRepository,
            int maxHistory,
            SystemRepositoryService systemRepositoryService,
            DslGitRepository gitRepository) {
        this.workspaceRepository = workspaceRepository;
        this.maxHistory = maxHistory;
        this.systemRepositoryService = systemRepositoryService;
        this.gitRepository = gitRepository;
        this.repositoryFactory = null;
    }

    /**
     * Return or create the user's default workspace state.
     * Returns {@code null} when automatic creation is denied and no READY copy exists.
     */
    public UserWorkspaceState getOrCreateWorkspace(String username) {
        if (username == null || username.isBlank()) {
            username = DEFAULT_USER;
        }
        String user = username;

        String activeWorkspaceId = activeWorkspaceByUser.get(user);
        if (activeWorkspaceId != null) {
            UserWorkspaceState existing = workspaceStates.get(new WorkspaceStateKey(user, activeWorkspaceId));
            if (existing != null) {
                return existing;
            }
        }

        if (!mayProvisionDefaultWorkspace(user)) {
            UserWorkspace workspace = findUserWorkspace(user);
            if (workspace == null || !user.equals(workspace.getUsername())
                    || workspace.isArchived() || workspace.isShared()
                    || workspace.getProvisioningStatus() != WorkspaceProvisioningStatus.READY) {
                return null;
            }
            // An existing working copy may gain navigation state without writing
            // metadata or allocating a repository through an implicit read.
            return getOrCreateWorkspace(user, workspace.getWorkspaceId());
        }

        ensurePersistentWorkspace(user);

        UserWorkspace workspace = safeOptional(
                workspaceRepository.findByUsernameAndIsDefaultTrue(user));
        if (workspace != null && workspace.isArchived()) {
            workspace = null;
        }
        if (workspace == null) {
            workspace = safeOptional(workspaceRepository.findByUsernameAndSharedFalse(user));
            if (workspace != null && workspace.isArchived()) {
                workspace = null;
            }
        }

        String workspaceId = workspace != null
                ? workspace.getWorkspaceId()
                : user + "-workspace";
        UserWorkspaceState state = getOrCreateWorkspaceState(user, workspaceId,
                workspace == null ? null : workspace.getCurrentBranch());
        activeWorkspaceByUser.put(user, workspaceId);
        return state;
    }

    /** Return or create state for one explicitly selected workspace. */
    public UserWorkspaceState getOrCreateWorkspace(String username, String workspaceId) {
        if (username == null || username.isBlank()) {
            username = DEFAULT_USER;
        }
        UserWorkspaceState state = getOrCreateWorkspaceState(username, workspaceId, null);
        activeWorkspaceByUser.put(username, workspaceId);
        return state;
    }

    /** Use an authorized workspace from the current request without changing the active selection. */
    public UserWorkspaceState getOrCreateScopedWorkspaceState(WorkspaceContext context) {
        return getOrCreateWorkspaceState(context.username(), context.workspaceId(), context.currentBranch());
    }

    /** Look up an authorized selection without initializing navigation state. */
    public UserWorkspaceState getScopedWorkspaceState(WorkspaceContext context) {
        return workspaceStates.get(new WorkspaceStateKey(context.username(), context.workspaceId()));
    }

    private UserWorkspaceState getOrCreateWorkspaceState(String username, String workspaceId, String branch) {
        var key = new WorkspaceStateKey(username, Objects.requireNonNull(workspaceId));
        return workspaceStates.computeIfAbsent(key, ignored -> {
            log.info("Creating workspace state for user '{}', workspaceId='{}'", username, workspaceId);
            return new UserWorkspaceState(username, maxHistory, branch);
        });
    }

    /** Return the active in-memory workspace state for a user, or {@code null}. */
    public UserWorkspaceState getWorkspace(String username) {
        String user = username != null ? username : DEFAULT_USER;
        String activeWorkspaceId = activeWorkspaceByUser.get(user);
        return activeWorkspaceId != null
                ? workspaceStates.get(new WorkspaceStateKey(user, activeWorkspaceId)) : null;
    }

    /** Return summaries of all currently active workspaces. */
    public List<WorkspaceInfo> listActiveWorkspaces() {
        return activeWorkspaceStates()
                .map(this::toWorkspaceInfo)
                .toList();
    }

    /** Return the current workspace summary for a user. */
    public WorkspaceInfo getWorkspaceInfo(String username) {
        if (!mayProvisionDefaultWorkspace(username)) {
            UserWorkspace workspace = findUserWorkspace(username);
            if (workspace == null || workspace.isArchived() || workspace.isShared()
                    || (workspace.isDefault()
                    && (workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.NOT_PROVISIONED
                    || workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.PROVISIONING))) {
                return null;
            }
            return getWorkspaceMetadataInfo(username, workspace);
        }
        return toWorkspaceInfo(getOrCreateWorkspace(username));
    }

    /** Summarize already-authorized metadata without changing the active selection. */
    public WorkspaceInfo getWorkspaceMetadataInfo(String actor, UserWorkspace workspace) {
        UserWorkspaceState state = workspaceStates.get(new WorkspaceStateKey(actor, workspace.getWorkspaceId()));
        return new WorkspaceInfo(workspace.getWorkspaceId(), workspace.getUsername(),
                workspace.getDisplayName(), workspace.getCurrentBranch(), workspace.getBaseBranch(),
                workspace.isShared(), state == null ? null : state.getCurrentContext(),
                workspace.getCreatedAt(), workspace.getLastAccessedAt(),
                workspace.getProvisioningStatus() == null ? null : workspace.getProvisioningStatus().name(),
                workspace.getTopologyMode() == null ? null : workspace.getTopologyMode().name(),
                workspace.getSourceRepositoryId(), workspace.getDescription(),
                workspace.isArchived(), workspace.isDefault());
    }

    /** Remove only a user's in-memory workspace state. */
    public void evictWorkspace(String username) {
        activeWorkspaceByUser.remove(username);
        if (workspaceStates.keySet().removeIf(key -> username.equals(key.username()))) {
            log.info("Evicted workspace state for user '{}'", username);
        }
    }

    /** Return the number of active in-memory workspaces. */
    public int getActiveWorkspaceCount() {
        return Math.toIntExact(activeWorkspaceStates().count());
    }

    private Stream<UserWorkspaceState> activeWorkspaceStates() {
        return activeWorkspaceByUser.entrySet().stream()
                .map(entry -> workspaceStates.get(new WorkspaceStateKey(entry.getKey(), entry.getValue())))
                .filter(state -> state != null);
    }

    /** Find a user's persistent workspace, preferring the active one. */
    public UserWorkspace findUserWorkspace(String username) {
        UserWorkspace active = findActiveWorkspace(username);
        if (active != null) {
            return active;
        }
        return workspaceRepository.findByUsernameAndSharedFalse(username).orElse(null);
    }

    // ── Multi-workspace management ─────────────────────────────────

    /** Create a new, initially unprovisioned workspace. */
    public UserWorkspace createWorkspace(
            String username,
            String displayName,
            String description) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(UUID.randomUUID().toString());
        workspace.setUsername(username);
        workspace.setDisplayName(displayName);
        workspace.setDescription(description);
        workspace.setCurrentBranch("draft");
        workspace.setBaseBranch("draft");
        workspace.setShared(false);
        workspace.setArchived(false);
        workspace.setDefault(false);
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        workspace.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        workspace.setCreatedAt(Instant.now());
        workspace.setLastAccessedAt(Instant.now());
        workspaceRepository.save(workspace);
        log.info("Created new workspace '{}' for user '{}'", displayName, username);
        return workspace;
    }

    /** Switch the user's active workspace. */
    public UserWorkspace switchWorkspace(String username, String workspaceId) {
        UserWorkspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Workspace not found: " + workspaceId));

        if (!workspace.getUsername().equals(username)) {
            throw new IllegalArgumentException(
                    "Cannot switch to workspace owned by another user");
        }
        if (workspace.isArchived()) {
            throw new IllegalArgumentException("Cannot switch to an archived workspace");
        }
        if (workspace.isShared()) {
            throw new IllegalArgumentException("Cannot switch to the shared workspace");
        }

        workspace.setLastAccessedAt(Instant.now());
        workspaceRepository.save(workspace);
        getOrCreateWorkspaceState(username, workspaceId, workspace.getCurrentBranch());
        activeWorkspaceByUser.put(username, workspaceId);
        log.info("User '{}' switched to workspace '{}'", username, workspaceId);
        return workspace;
    }

    /** Rename a workspace owned by the requesting user. */
    public UserWorkspace renameWorkspace(
            String username,
            String workspaceId,
            String newName) {
        UserWorkspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Workspace not found: " + workspaceId));

        if (!workspace.getUsername().equals(username)) {
            throw new IllegalArgumentException(
                    "User '" + username + "' does not own workspace: " + workspaceId);
        }
        if (workspace.isArchived()) {
            throw new IllegalStateException(
                    "Archived workspace cannot be renamed: " + workspaceId);
        }
        if (workspace.isShared()) {
            throw new IllegalStateException(
                    "Shared workspace cannot be renamed: " + workspaceId);
        }

        workspace.setDisplayName(newName);
        workspaceRepository.save(workspace);
        log.info("User '{}' renamed workspace '{}' to '{}'",
                username, workspaceId, newName);
        return workspace;
    }

    /** Archive a non-default workspace without removing its Git history. */
    public UserWorkspace archiveWorkspace(String workspaceId, String username) {
        UserWorkspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Workspace not found: " + workspaceId));

        if (!workspace.getUsername().equals(username)) {
            throw new IllegalArgumentException(
                    "Cannot archive workspace owned by another user");
        }
        if (workspace.isShared()) {
            throw new IllegalArgumentException("Cannot archive the shared workspace");
        }
        if (workspace.isDefault()) {
            throw new IllegalArgumentException("Cannot archive the default workspace");
        }

        workspace.setArchived(true);
        workspaceRepository.save(workspace);
        workspaceStates.keySet().removeIf(key -> workspaceId.equals(key.workspaceId()));
        activeWorkspaceByUser.entrySet()
                .removeIf(entry -> entry.getValue().equals(workspaceId));
        log.info("Archived workspace '{}' for user '{}'", workspaceId, username);
        return workspace;
    }

    /**
     * Permanently delete a workspace and its isolated logical Git repository.
     *
     * <p>Storage deletion happens before metadata deletion. If the storage library
     * rejects or cannot complete deletion, the workspace row remains available for
     * diagnosis and retry rather than becoming an orphaned Git namespace.</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteWorkspace(String workspaceId, String username) {
        UserWorkspace workspace = workspaceRepository.findByWorkspaceIdForUpdate(workspaceId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Workspace not found: " + workspaceId));

        if (!workspace.getUsername().equals(username)) {
            throw new IllegalArgumentException(
                    "Cannot delete workspace owned by another user");
        }
        if (workspace.isShared()) {
            throw new IllegalArgumentException("Cannot delete the shared workspace");
        }
        if (workspace.isDefault()) {
            throw new IllegalArgumentException("Cannot delete the default workspace");
        }

        // Settle or archive exactly this owner's receipts while the workspace and matching operations are locked.
        // A storage error rolls this transaction back and leaves the original receipts available for retry.
        workspaceRepository.archiveFetchesForRemoval(workspaceId, workspace.getSourceRepositoryId(), username,
                System.currentTimeMillis());
        if (repositoryFactory != null) {
            repositoryFactory.deleteWorkspaceRepository(workspaceId);
        }
        workspaceRepository.delete(workspace);
        workspaceStates.keySet().removeIf(key -> workspaceId.equals(key.workspaceId()));
        activeWorkspaceByUser.entrySet()
                .removeIf(entry -> entry.getValue().equals(workspaceId));

        log.info("Deleted workspace '{}' and its logical Git repository for user '{}'",
                workspaceId, username);
    }

    /** Return all non-archived workspaces for a user. */
    public List<UserWorkspace> listUserWorkspaces(String username) {
        return workspaceRepository
                .findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc(username);
    }

    /** Find the persistent entity for the user's active workspace. */
    public UserWorkspace findActiveWorkspace(String username) {
        String activeWorkspaceId = activeWorkspaceByUser.get(username);
        if (activeWorkspaceId == null) {
            return null;
        }
        return workspaceRepository.findByWorkspaceId(activeWorkspaceId).orElse(null);
    }

    /** Return a workspace by ID, or {@code null} only after a successful empty lookup. */
    public UserWorkspace getWorkspaceById(String workspaceId) {
        return workspaceRepository.findByWorkspaceId(workspaceId).orElse(null);
    }

    /** Update the description of a non-archived workspace. */
    public UserWorkspace updateDescription(
            String username,
            String workspaceId,
            String description) {
        UserWorkspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Workspace not found: " + workspaceId));

        if (!workspace.getUsername().equals(username)) {
            throw new IllegalArgumentException(
                    "User '" + username + "' does not own workspace: " + workspaceId);
        }
        if (workspace.isArchived()) {
            throw new IllegalStateException(
                    "Archived workspace cannot be updated: " + workspaceId);
        }

        workspace.setDescription(description);
        workspaceRepository.save(workspace);
        log.info("User '{}' updated description for workspace '{}'", username, workspaceId);
        return workspace;
    }

    // ── Provisioning ───────────────────────────────────────────────

    /** Lazily provision the active workspace repository. */
    public synchronized UserWorkspace provisionWorkspaceRepository(String username) {
        UserWorkspace workspace = null;
        String activeWorkspaceId = activeWorkspaceByUser.get(username);
        if (activeWorkspaceId != null) {
            workspace = workspaceRepository.findByWorkspaceId(activeWorkspaceId).orElse(null);
        }
        if (workspace == null) {
            workspace = workspaceRepository.findByUsernameAndSharedFalse(username)
                    .orElseThrow(() ->
                            new IllegalStateException("No workspace metadata for " + username));
        }

        return provisionWorkspaceRepository(username, workspace);
    }

    /** Provision exactly the owned workspace selected by a caller, without switching other tabs. */
    public synchronized UserWorkspace provisionWorkspaceRepository(String username, String workspaceId) {
        UserWorkspace workspace = workspaceRepository.findByWorkspaceId(workspaceId).orElse(null);
        return provisionWorkspaceRepository(username, workspace);
    }

    /** Initialize an automatic default on its existing branch, without redirecting a tab pin. */
    public synchronized UserWorkspace provisionDefaultWorkspaceRepository(String username, String workspaceId) {
        UserWorkspace workspace = workspaceRepository.findByWorkspaceId(workspaceId).orElse(null);
        if (workspace == null || !workspace.isDefault()) {
            throw new AccessDeniedException("No automatic default workspace was selected");
        }
        if (!username.equals(workspace.getUsername()) || workspace.isArchived() || workspace.isShared()) {
            throw new AccessDeniedException("Workspace is not available to the authenticated user");
        }
        // A concurrent implicit reader may have observed PROVISIONING before
        // obtaining this monitor. Re-read here, but never turn the winner's
        // failure into an unrequested automatic retry.
        if (workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Default workspace initialization failed; retry provisioning explicitly");
        }
        String branch = workspace.getCurrentBranch();
        if (branch == null || branch.isBlank()) {
            throw new IllegalStateException("Default workspace has no current branch");
        }
        if (workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.NOT_PROVISIONED
                && (workspace.getBaseCommit() == null || workspace.getBaseCommit().isBlank())
                && defaultSourceHeadIsMissing(workspace)) {
            // Bootstrap publishes the initial source checkpoint after application
            // readiness. No allocation has started, so keep this UUID retryable.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Source repository is still initializing; retry when its initial checkpoint is available");
        }
        return provisionWorkspaceRepository(username, workspace, branch);
    }

    private boolean defaultSourceHeadIsMissing(UserWorkspace workspace) {
        String sourceRepositoryId = workspace.getSourceRepositoryId();
        boolean recordedSource = sourceRepositoryId != null && !sourceRepositoryId.isBlank();
        var source = recordedSource
                ? systemRepositoryService.getRepository(sourceRepositoryId.strip())
                : systemRepositoryService.getPrimaryRepository();
        if (source.getLifecycleState() != RepositoryLifecycleState.ACTIVE) {
            throw new IllegalStateException("Source repository is not active: " + source.getRepositoryId());
        }
        if (repositoryFactory == null && recordedSource
                && !sourceRepositoryId.strip().equals(
                        systemRepositoryService.getPrimaryRepository().getRepositoryId())) {
            throw new IllegalStateException("Legacy storage cannot open the recorded source repository");
        }
        String sourceBranch = workspace.getSourceBranch();
        String baseBranch = sourceBranch != null && !sourceBranch.isBlank()
                ? sourceBranch.strip() : source.getDefaultBranch();
        DslGitRepository sourceGit = repositoryFactory == null ? gitRepository
                : recordedSource ? repositoryFactory.getCentralRepository(sourceRepositoryId.strip())
                : repositoryFactory.getSystemRepository();
        try {
            return sourceGit.getHeadCommit(baseBranch) == null;
        } catch (IOException failure) {
            // A failed read is not evidence of a missing bootstrap checkpoint.
            // Preserve its cause without claiming a workspace allocation.
            throw new UncheckedIOException("Could not inspect the default workspace source checkpoint", failure);
        }
    }

    private UserWorkspace provisionWorkspaceRepository(String username, UserWorkspace workspace) {
        // Repository-specific working copies persist their destination branch before
        // allocation. Retrying them must not redirect an existing tab to "main".
        boolean allocated = workspace != null && workspace.getCurrentCommit() != null;
        String targetBranch = workspace != null && (allocated
                || (workspace.getSourceBranch() != null && !workspace.getSourceBranch().isBlank()))
                ? workspace.getCurrentBranch() : "main";
        return provisionWorkspaceRepository(username, workspace, targetBranch);
    }

    private UserWorkspace provisionWorkspaceRepository(
            String username, UserWorkspace workspace, String targetBranch) {
        if (workspace == null || !username.equals(workspace.getUsername())
                || workspace.isArchived() || workspace.isShared()) {
            throw new AccessDeniedException("Workspace is not available to the authenticated user");
        }
        if (workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.READY) {
            return workspace;
        }
        if (workspace.getProvisioningStatus() == null
                || (workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.PROVISIONING
                    && workspace.getProvisioningLeaseUntil() != null && workspace.getProvisioningLeaseUntil().isAfter(Instant.now()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workspace provisioning is already running or its state is unavailable");
        }

        // Lazy first use may be reached from a read-only report/query. Suspend
        // only that transaction so each repository write owns a writable boundary;
        // keep existing writable callers joined, including atomic integration flows.
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            if (provisioningOutsideReadOnlyTransaction == null) {
                throw new IllegalStateException("Provisioning transaction boundary is not configured");
            }
            String workspaceId = workspace.getWorkspaceId();
            return provisioningOutsideReadOnlyTransaction.execute(status ->
                    provisionWorkspaceRepository(username,
                            workspaceRepository.findByWorkspaceId(workspaceId).orElse(null), targetBranch));
        }
        var claim = new WorkspaceProvisioningClaim(workspace);
        if (workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.PROVISIONING
                && workspace.getProvisioningLeaseUntil() == null) {
            claim.observeLegacyWorker(workspaceRepository);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Legacy workspace provisioning is awaiting recovery");
        }
        if (!claim.acquire(workspaceRepository)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workspace provisioning was claimed or its lifecycle changed");
        }
        try {
            if (targetBranch == null || targetBranch.isBlank()) {
                throw new IllegalStateException("Workspace has no destination branch");
            }
            String sourceRepositoryId = workspace.getSourceRepositoryId();
            boolean recordedSource = sourceRepositoryId != null && !sourceRepositoryId.isBlank();
            var systemRepository = recordedSource
                    ? systemRepositoryService.getRepository(sourceRepositoryId.strip())
                    : systemRepositoryService.getPrimaryRepository();
            if (systemRepository.getLifecycleState() != RepositoryLifecycleState.ACTIVE) {
                throw new IllegalStateException("Source repository is not active: "
                        + systemRepository.getRepositoryId());
            }
            if (repositoryFactory != null) {
                requireProvisioningAuthority(systemRepository.getRepositoryId(), username);
            }
            String recordedBase = workspace.getBaseCommit();
            String sourceBranch = workspace.getSourceBranch();
            if ((sourceBranch == null || sourceBranch.isBlank()) && recordedBase != null && !recordedBase.isBlank()) {
                sourceBranch = workspace.getBaseBranch();
            }
            String baseBranch = sourceBranch != null && !sourceBranch.isBlank()
                    ? sourceBranch.strip() : systemRepository.getDefaultBranch();
            if (repositoryFactory == null && recordedSource
                    && !sourceRepositoryId.strip().equals(
                            systemRepositoryService.getPrimaryRepository().getRepositoryId())) {
                throw new IllegalStateException("Legacy storage cannot open the recorded source repository");
            }
            DslGitRepository systemGit = repositoryFactory == null ? gitRepository
                    : recordedSource ? repositoryFactory.getCentralRepository(sourceRepositoryId.strip())
                    : repositoryFactory.getSystemRepository();
            String legacyBranch = username + "/workspace/" + workspace.getWorkspaceId();
            String existingLegacyHead = repositoryFactory == null
                    ? gitRepository.getHeadCommit(legacyBranch) : null;
            String baseCommit = recordedBase != null && !recordedBase.isBlank()
                    ? recordedBase : systemGit.getHeadCommit(baseBranch);
            String systemDsl = baseCommit == null ? null : systemGit.getDslAtCommit(baseCommit);
            if (baseCommit == null || systemDsl == null || systemDsl.isBlank()) {
                throw new IllegalStateException("Source repository has no readable checkpoint for " + baseBranch);
            }
            systemGit.verifySnapshot(baseCommit);

            String destination = repositoryFactory == null ? legacyBranch : targetBranch;
            claim.apply(workspaceRepository, current -> {
                if (current.getBaseCommit() != null && !current.getBaseCommit().equals(baseCommit)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The captured provisioning checkpoint changed");
                }
                current.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
                current.setSourceRepositoryId(systemRepository.getRepositoryId());
                current.setSourceBranch(baseBranch);
                current.setTopologyMode(systemRepository.getTopologyMode());
                current.setBaseBranch(baseBranch);
                current.setBaseCommit(baseCommit);
                current.setCurrentBranch(destination);
                current.setSyncTargetBranch(baseBranch);
            });

            if (repositoryFactory == null) {
                return claim.apply(workspaceRepository, current -> {
                    if (existingLegacyHead == null) {
                        gitRepository.createBranchAtCommit(legacyBranch, baseCommit);
                    } else if (recordedBase != null && recordedBase.equals(existingLegacyHead)) {
                        gitRepository.verifyExpectedHead(legacyBranch, recordedBase);
                    } else {
                        throw new IllegalStateException("Existing workspace branch does not match its recorded checkpoint");
                    }
                    WorkspaceProvisioningClaim.markProvisioned(current, baseCommit);
                });
            }

            DslGitRepository workspaceGit = repositoryFactory.openWorkspaceRepository(workspace.getWorkspaceId());
            String existingHead = workspaceGit.getHeadCommit(targetBranch);
            boolean originalHistory = existingHead == null || existingHead.equals(baseCommit);
            if (originalHistory) {
                // Object transfer is outside the lease lock. The next owner can adopt any verified objects.
                GitHistoryTransfer.copyCommitClosure(systemGit, workspaceGit, baseCommit, List.of());
            } else {
                if (recordedBase == null || !existingHead.equals(workspace.getCurrentCommit())
                        || !isRootCommit(workspaceGit, existingHead)
                        || !systemDsl.equals(workspaceGit.getDslAtCommit(existingHead))) {
                    throw new IllegalStateException("Existing workspace content does not match its recorded allocation");
                }
                workspaceGit.verifySnapshot(existingHead);
            }
            return claim.apply(workspaceRepository, current -> {
                requireProvisioningAuthority(systemRepository.getRepositoryId(), username);
                if (originalHistory) {
                    workspaceGit.recordUpstreamForkPoint(systemRepository.getRepositoryId(), baseBranch, baseCommit, username);
                    if (existingHead == null) {
                        workspaceGit.fastForwardIfHeadMatches(targetBranch, null, baseCommit, username,
                                "Provision workspace from " + baseBranch);
                    } else {
                        workspaceGit.verifyExpectedHead(targetBranch, baseCommit);
                    }
                    String integrationBaseBranch = current.getProvisioningSyncBaseBranch();
                    if (integrationBaseBranch != null) {
                        WorkspaceProvisioningClaim.adoptIntegrationBase(workspaceGit, integrationBaseBranch, baseCommit);
                        current.setLastFetchedCommit(baseCommit);
                        current.setLastIntegratedCommit(baseCommit);
                    }
                } else {
                    workspaceGit.verifyExpectedHead(targetBranch, existingHead);
                }
                WorkspaceProvisioningClaim.markProvisioned(current, originalHistory ? baseCommit : existingHead);
            });
        } catch (Exception exception) {
            claim.fail(workspaceRepository, exception, ignored -> {});
            if (exception instanceof ResponseStatusException conflict) throw conflict;
            if (exception instanceof AccessDeniedException denied) throw denied;
            throw new RuntimeException(
                    "Could not provision workspace for " + username,
                    exception);
        }
    }

    private void requireProvisioningAuthority(String repositoryId, String username) {
        if (sourceMemberships == null) return; // Direct legacy fixtures have no membership catalogue.
        systemRepositoryService.readCurrentAuthorization(repositoryId, current -> {
            if (!sourceMemberships.canContribute(current, username)) {
                throw new AccessDeniedException("Current source repository CONTRIBUTOR role is required");
            }
        });
    }

    private static boolean isRootCommit(DslGitRepository repository, String commit) throws IOException {
        try (var walk = new RevWalk(repository.getGitRepository())) {
            return walk.parseCommit(ObjectId.fromString(commit)).getParentCount() == 0;
        }
    }

    // ── Internal helpers ───────────────────────────────────────────

    private synchronized void ensurePersistentWorkspace(String username) {
        try {
            if (!workspaceRepository.existsByUsername(username)) {
                UserWorkspace workspace = new UserWorkspace();
                workspace.setWorkspaceId(UUID.randomUUID().toString());
                workspace.setUsername(username);
                workspace.setDisplayName(username + "'s workspace");
                workspace.setCurrentBranch("draft");
                workspace.setBaseBranch("draft");
                workspace.setShared(false);
                workspace.setDefault(true);
                workspace.setArchived(false);
                workspace.setProvisioningStatus(
                        WorkspaceProvisioningStatus.NOT_PROVISIONED);
                workspace.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
                workspace.setCreatedAt(Instant.now());
                workspace.setLastAccessedAt(Instant.now());
                workspaceRepository.save(workspace);
                log.debug("Created persistent workspace for user '{}'", username);
            } else {
                workspaceRepository.findByUsernameAndSharedFalse(username)
                        .ifPresent(workspace -> {
                            workspace.setLastAccessedAt(Instant.now());
                            workspaceRepository.save(workspace);
                        });
            }
        } catch (Exception exception) {
            log.warn("Could not persist workspace for user '{}': {}",
                    username, exception.getMessage());
        }
    }

    private WorkspaceInfo toWorkspaceInfo(UserWorkspaceState state) {
        ContextRef context = state.getCurrentContext();
        String provisioningStatus = "NOT_PROVISIONED";
        String topologyMode = "INTERNAL_SHARED";
        String sourceRepositoryId = null;
        String workspaceId = state.getUsername() + "-workspace";
        String displayName = state.getUsername() + "'s workspace";
        String description = null;
        boolean archived = false;
        boolean defaultWorkspace = false;

        UserWorkspace workspace = findActiveWorkspace(state.getUsername());
        if (workspace == null) {
            workspace = workspaceRepository
                    .findByUsernameAndSharedFalse(state.getUsername())
                    .orElse(null);
        }
        if (workspace != null) {
            provisioningStatus = workspace.getProvisioningStatus() == null
                    ? "NOT_PROVISIONED" : workspace.getProvisioningStatus().name();
            topologyMode = workspace.getTopologyMode() == null
                    ? topologyMode : workspace.getTopologyMode().name();
            sourceRepositoryId = workspace.getSourceRepositoryId();
            workspaceId = workspace.getWorkspaceId();
            displayName = workspace.getDisplayName();
            description = workspace.getDescription();
            archived = workspace.isArchived();
            defaultWorkspace = workspace.isDefault();
        }

        return new WorkspaceInfo(
                workspaceId,
                state.getUsername(),
                displayName,
                context != null ? context.branch() : "draft",
                "draft",
                false,
                context,
                Instant.now(),
                Instant.now(),
                provisioningStatus,
                topologyMode,
                sourceRepositoryId,
                description,
                archived,
                defaultWorkspace);
    }

    /** Safely unwrap an Optional that may itself be null from an unstubbed mock. */
    private static <T> T safeOptional(java.util.Optional<T> optional) {
        return optional != null ? optional.orElse(null) : null;
    }
}
