package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    /** Active workspace states keyed by workspace ID. */
    private final ConcurrentMap<String, UserWorkspaceState> activeWorkspaces =
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

    /** Return or create the user's default workspace state. */
    public UserWorkspaceState getOrCreateWorkspace(String username) {
        if (username == null || username.isBlank()) {
            username = DEFAULT_USER;
        }
        String user = username;

        String activeWorkspaceId = activeWorkspaceByUser.get(user);
        if (activeWorkspaceId != null) {
            UserWorkspaceState existing = activeWorkspaces.get(activeWorkspaceId);
            if (existing != null) {
                return existing;
            }
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
        UserWorkspaceState state = activeWorkspaces.computeIfAbsent(workspaceId, id -> {
            log.info("Creating workspace state for user '{}', workspaceId='{}'", user, id);
            return new UserWorkspaceState(user, maxHistory);
        });
        activeWorkspaceByUser.put(user, workspaceId);
        return state;
    }

    /** Return or create state for one explicitly selected workspace. */
    public UserWorkspaceState getOrCreateWorkspace(String username, String workspaceId) {
        if (username == null || username.isBlank()) {
            username = DEFAULT_USER;
        }
        String user = username;
        UserWorkspaceState state = activeWorkspaces.computeIfAbsent(workspaceId, id -> {
            log.info("Creating workspace state for user '{}', workspaceId='{}'", user, id);
            return new UserWorkspaceState(user, maxHistory);
        });
        activeWorkspaceByUser.put(user, workspaceId);
        return state;
    }

    /** Return the active in-memory workspace state for a user, or {@code null}. */
    public UserWorkspaceState getWorkspace(String username) {
        String user = username != null ? username : DEFAULT_USER;
        String activeWorkspaceId = activeWorkspaceByUser.get(user);
        return activeWorkspaceId != null ? activeWorkspaces.get(activeWorkspaceId) : null;
    }

    /** Return summaries of all currently active workspaces. */
    public List<WorkspaceInfo> listActiveWorkspaces() {
        return activeWorkspaces.values().stream()
                .map(this::toWorkspaceInfo)
                .toList();
    }

    /** Return the current workspace summary for a user. */
    public WorkspaceInfo getWorkspaceInfo(String username) {
        return toWorkspaceInfo(getOrCreateWorkspace(username));
    }

    /** Remove only a user's in-memory workspace state. */
    public void evictWorkspace(String username) {
        String activeWorkspaceId = activeWorkspaceByUser.remove(username);
        if (activeWorkspaceId == null) {
            return;
        }
        UserWorkspaceState removed = activeWorkspaces.remove(activeWorkspaceId);
        if (removed != null) {
            log.info("Evicted workspace state for user '{}', workspaceId='{}'",
                    username, activeWorkspaceId);
        }
    }

    /** Return the number of active in-memory workspaces. */
    public int getActiveWorkspaceCount() {
        return activeWorkspaces.size();
    }

    /** Find a user's persistent workspace, preferring the active one. */
    public UserWorkspace findUserWorkspace(String username) {
        try {
            UserWorkspace active = findActiveWorkspace(username);
            if (active != null) {
                return active;
            }
            return workspaceRepository.findByUsernameAndSharedFalse(username).orElse(null);
        } catch (Exception exception) {
            log.debug("Could not find workspace for '{}': {}",
                    username, exception.getMessage());
            return null;
        }
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

        String oldWorkspaceId = activeWorkspaceByUser.get(username);
        if (oldWorkspaceId != null) {
            activeWorkspaces.remove(oldWorkspaceId);
        }

        activeWorkspaceByUser.put(username, workspaceId);
        activeWorkspaces.computeIfAbsent(
                workspaceId,
                id -> new UserWorkspaceState(username, maxHistory));

        workspace.setLastAccessedAt(Instant.now());
        workspaceRepository.save(workspace);
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
        activeWorkspaces.remove(workspaceId);
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
    public void deleteWorkspace(String workspaceId, String username) {
        UserWorkspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
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

        activeWorkspaces.remove(workspaceId);
        activeWorkspaceByUser.entrySet()
                .removeIf(entry -> entry.getValue().equals(workspaceId));

        if (repositoryFactory != null) {
            repositoryFactory.deleteWorkspaceRepository(workspaceId);
        }
        workspaceRepository.delete(workspace);
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
        try {
            return workspaceRepository.findByWorkspaceId(activeWorkspaceId).orElse(null);
        } catch (Exception exception) {
            log.debug("Could not find active workspace '{}' for '{}': {}",
                    activeWorkspaceId, username, exception.getMessage());
            return null;
        }
    }

    /** Return a workspace by ID, or {@code null} when it cannot be read. */
    public UserWorkspace getWorkspaceById(String workspaceId) {
        try {
            return workspaceRepository.findByWorkspaceId(workspaceId).orElse(null);
        } catch (Exception exception) {
            log.debug("Could not find workspace '{}': {}",
                    workspaceId, exception.getMessage());
            return null;
        }
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
    public UserWorkspace provisionWorkspaceRepository(String username) {
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

        if (workspace.getProvisioningStatus() == WorkspaceProvisioningStatus.READY) {
            return workspace;
        }

        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
        workspaceRepository.save(workspace);

        try {
            var systemRepository = systemRepositoryService.getPrimaryRepository();
            String baseBranch = systemRepository.getDefaultBranch();

            if (repositoryFactory != null) {
                DslGitRepository workspaceGit =
                        repositoryFactory.getWorkspaceRepository(workspace.getWorkspaceId());
                DslGitRepository systemGit = repositoryFactory.getSystemRepository();

                String systemDsl = systemGit.getDslAtHead(baseBranch);
                if (systemDsl != null) {
                    workspaceGit.commitDsl(
                            "main",
                            systemDsl,
                            username,
                            "Fork from shared/" + baseBranch);
                }

                workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
                workspace.setSourceRepositoryId(systemRepository.getRepositoryId());
                workspace.setTopologyMode(systemRepository.getTopologyMode());
                workspace.setBaseBranch(baseBranch);
                workspace.setBaseCommit(systemGit.getHeadCommit(baseBranch));
                workspace.setCurrentBranch("main");
                workspace.setCurrentCommit(workspaceGit.getHeadCommit("main"));
                workspace.setSyncTargetBranch(baseBranch);
                workspace.setProvisionedAt(Instant.now());
                workspace.setProvisioningError(null);
                workspaceRepository.save(workspace);

                log.info("Provisioned workspace for user '{}': repo='ws-{}', base='{}'",
                        username, workspace.getWorkspaceId(), baseBranch);
            } else {
                String userBranch = username + "/workspace/" + workspace.getWorkspaceId();
                String baseCommit = gitRepository.getHeadCommit(baseBranch);
                if (baseCommit != null) {
                    gitRepository.createBranch(userBranch, baseBranch);
                }

                workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
                workspace.setSourceRepositoryId(systemRepository.getRepositoryId());
                workspace.setTopologyMode(systemRepository.getTopologyMode());
                workspace.setBaseBranch(baseBranch);
                workspace.setBaseCommit(baseCommit);
                workspace.setCurrentBranch(userBranch);
                workspace.setCurrentCommit(baseCommit);
                workspace.setSyncTargetBranch(baseBranch);
                workspace.setProvisionedAt(Instant.now());
                workspace.setProvisioningError(null);
                workspaceRepository.save(workspace);

                log.info("Provisioned workspace for user '{}': branch='{}', base='{}'",
                        username, userBranch, baseBranch);
            }
            return workspace;
        } catch (Exception exception) {
            workspace.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
            workspace.setProvisioningError(exception.getMessage());
            workspaceRepository.save(workspace);
            throw new RuntimeException(
                    "Could not provision workspace for " + username,
                    exception);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────

    private void ensurePersistentWorkspace(String username) {
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
        String provisioningStatus = "READY";
        String topologyMode = "INTERNAL_SHARED";
        String sourceRepositoryId = null;
        String workspaceId = state.getUsername() + "-workspace";
        String displayName = state.getUsername() + "'s workspace";
        String description = null;
        boolean archived = false;
        boolean defaultWorkspace = false;

        try {
            UserWorkspace workspace = findActiveWorkspace(state.getUsername());
            if (workspace == null) {
                workspace = workspaceRepository
                        .findByUsernameAndSharedFalse(state.getUsername())
                        .orElse(null);
            }
            if (workspace != null) {
                provisioningStatus = workspace.getProvisioningStatus().name();
                topologyMode = workspace.getTopologyMode().name();
                sourceRepositoryId = workspace.getSourceRepositoryId();
                workspaceId = workspace.getWorkspaceId();
                displayName = workspace.getDisplayName();
                description = workspace.getDescription();
                archived = workspace.isArchived();
                defaultWorkspace = workspace.isDefault();
            }
        } catch (Exception exception) {
            log.debug("Could not read provisioning info for '{}': {}",
                    state.getUsername(), exception.getMessage());
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
