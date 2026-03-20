package com.taxonomy.workspace.service;

import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.dto.WorkspaceRole;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.taxonomy.dsl.storage.DslGitRepository;

/**
 * Manages per-user workspace state for multi-user architecture editing.
 *
 * <p>Each authenticated user gets their own {@link UserWorkspaceState} with
 * independent context navigation, projection tracking, and operation state.
 * The underlying Git repository ({@code DslGitRepository}) is shared; workspaces
 * provide logical isolation via branches.
 *
 * <p>The manager maintains an in-memory map of active workspace states (keyed by
 * workspaceId) and persists workspace metadata (branch, timestamps) via
 * {@link UserWorkspaceRepository}. A secondary map tracks the currently active
 * workspace per user.
 *
 * <p>A special "shared" workspace exists for the integration repository concept —
 * the canonical team-wide state that users synchronize with.
 */
@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    /** Default username used when no authentication context is available (e.g. tests). */
    public static final String DEFAULT_USER = "anonymous";

    private final UserWorkspaceRepository workspaceRepository;
    private final int maxHistory;
    private final SystemRepositoryService systemRepositoryService;
    private final DslGitRepository gitRepository;

    /** Active workspace states keyed by workspaceId. */
    private final ConcurrentMap<String, UserWorkspaceState> activeWorkspaces = new ConcurrentHashMap<>();

    /** Maps username to the currently active workspaceId for that user. */
    private final ConcurrentMap<String, String> activeWorkspaceByUser = new ConcurrentHashMap<>();

    public WorkspaceManager(UserWorkspaceRepository workspaceRepository,
                            @Value("${taxonomy.context.max-history:50}") int maxHistory,
                            SystemRepositoryService systemRepositoryService,
                            DslGitRepository gitRepository) {
        this.workspaceRepository = workspaceRepository;
        this.maxHistory = maxHistory;
        this.systemRepositoryService = systemRepositoryService;
        this.gitRepository = gitRepository;
    }

    /**
     * Get or create the workspace state for a user's default workspace.
     *
     * <p>If no in-memory state exists, a new one is created and a persistent
     * workspace record is ensured in the database.
     *
     * @param username the authenticated user's username
     * @return the user's workspace state (never null)
     */
    public UserWorkspaceState getOrCreateWorkspace(String username) {
        if (username == null || username.isBlank()) {
            username = DEFAULT_USER;
        }
        String user = username;

        // Check if the user already has an active workspace
        String activeWsId = activeWorkspaceByUser.get(user);
        if (activeWsId != null) {
            UserWorkspaceState existing = activeWorkspaces.get(activeWsId);
            if (existing != null) {
                return existing;
            }
        }

        // Ensure a persistent workspace exists and activate it
        ensurePersistentWorkspace(user);

        // Find the default (or first) workspace for this user
        UserWorkspace ws = workspaceRepository.findByUsernameAndIsDefaultTrue(user)
                .orElseGet(() -> workspaceRepository.findByUsernameAndSharedFalse(user).orElse(null));

        String workspaceId;
        if (ws != null) {
            workspaceId = ws.getWorkspaceId();
        } else {
            workspaceId = user + "-workspace";
        }

        String wsId = workspaceId;
        UserWorkspaceState state = activeWorkspaces.computeIfAbsent(wsId, id -> {
            log.info("Creating workspace state for user '{}', workspaceId='{}'", user, id);
            return new UserWorkspaceState(user, maxHistory);
        });
        activeWorkspaceByUser.put(user, wsId);
        return state;
    }

    /**
     * Get or create the workspace state for a specific workspace.
     *
     * @param username    the authenticated user's username
     * @param workspaceId the workspace ID to load
     * @return the workspace state (never null)
     */
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

    /**
     * Get the workspace state for a user, or null if not active.
     *
     * @param username the user's username
     * @return the workspace state, or null if the user has no active workspace
     */
    public UserWorkspaceState getWorkspace(String username) {
        String user = username != null ? username : DEFAULT_USER;
        String activeWsId = activeWorkspaceByUser.get(user);
        if (activeWsId != null) {
            return activeWorkspaces.get(activeWsId);
        }
        return null;
    }

    /**
     * List all active workspaces as {@link WorkspaceInfo} DTOs.
     *
     * @return list of active workspace summaries
     */
    public List<WorkspaceInfo> listActiveWorkspaces() {
        return activeWorkspaces.values().stream()
                .map(this::toWorkspaceInfo)
                .toList();
    }

    /**
     * Get workspace info for a specific user.
     *
     * @param username the user's username
     * @return workspace info, or null if not found
     */
    public WorkspaceInfo getWorkspaceInfo(String username) {
        UserWorkspaceState state = getOrCreateWorkspace(username);
        return toWorkspaceInfo(state);
    }

    /**
     * Remove a user's workspace state from the in-memory cache.
     *
     * <p>This does not delete the persistent workspace record; the user's
     * workspace will be recreated on next access. Useful for cleanup on logout.
     *
     * @param username the user's username
     */
    public void evictWorkspace(String username) {
        String activeWsId = activeWorkspaceByUser.remove(username);
        if (activeWsId != null) {
            UserWorkspaceState removed = activeWorkspaces.remove(activeWsId);
            if (removed != null) {
                log.info("Evicted workspace state for user '{}', workspaceId='{}'", username, activeWsId);
            }
        }
    }

    /**
     * Get the number of active workspaces.
     *
     * @return count of active user workspaces
     */
    public int getActiveWorkspaceCount() {
        return activeWorkspaces.size();
    }

    /**
     * Find the persistent workspace entity for a user (active workspace first).
     *
     * @param username the user's username
     * @return the workspace entity, or null if not found
     */
    public UserWorkspace findUserWorkspace(String username) {
        try {
            // Try active workspace first
            UserWorkspace active = findActiveWorkspace(username);
            if (active != null) {
                return active;
            }
            return workspaceRepository.findByUsernameAndSharedFalse(username).orElse(null);
        } catch (Exception e) {
            log.debug("Could not find workspace for '{}': {}", username, e.getMessage());
            return null;
        }
    }

    // ── Multi-Workspace Management ──────────────────────────────────

    /**
     * Create a new workspace for the given user.
     *
     * @param username    the owner
     * @param displayName the human-readable name
     * @param description optional description
     * @return the newly created workspace entity
     */
    public UserWorkspace createWorkspace(String username, String displayName, String description) {
        UserWorkspace ws = new UserWorkspace();
        ws.setWorkspaceId(UUID.randomUUID().toString());
        ws.setUsername(username);
        ws.setDisplayName(displayName);
        ws.setDescription(description);
        ws.setCurrentBranch("draft");
        ws.setBaseBranch("draft");
        ws.setShared(false);
        ws.setArchived(false);
        ws.setDefault(false);
        ws.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        ws.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        ws.setCreatedAt(Instant.now());
        ws.setLastAccessedAt(Instant.now());
        workspaceRepository.save(ws);
        log.info("Created new workspace '{}' for user '{}'", displayName, username);
        return ws;
    }

    /**
     * Switch the user's active workspace to the given workspace.
     *
     * @param username    the user
     * @param workspaceId the workspace to switch to
     * @return the workspace entity that was switched to
     */
    public UserWorkspace switchWorkspace(String username, String workspaceId) {
        UserWorkspace ws = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        // Evict old active workspace from memory
        String oldWsId = activeWorkspaceByUser.get(username);
        if (oldWsId != null) {
            activeWorkspaces.remove(oldWsId);
        }

        // Activate new workspace
        activeWorkspaceByUser.put(username, workspaceId);
        activeWorkspaces.computeIfAbsent(workspaceId, id ->
                new UserWorkspaceState(username, maxHistory));

        ws.setLastAccessedAt(Instant.now());
        workspaceRepository.save(ws);
        log.info("User '{}' switched to workspace '{}'", username, workspaceId);
        return ws;
    }

    /**
     * Rename a workspace.
     *
     * @param workspaceId the workspace to rename
     * @param newName     the new display name
     * @return the updated workspace entity
     */
    public UserWorkspace renameWorkspace(String workspaceId, String newName) {
        UserWorkspace ws = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        ws.setDisplayName(newName);
        workspaceRepository.save(ws);
        log.info("Renamed workspace '{}' to '{}'", workspaceId, newName);
        return ws;
    }

    /**
     * Archive a workspace (soft-delete).
     *
     * @param workspaceId the workspace to archive
     * @return the archived workspace entity
     */
    public UserWorkspace archiveWorkspace(String workspaceId) {
        UserWorkspace ws = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        ws.setArchived(true);
        workspaceRepository.save(ws);

        // Remove from active state if loaded
        activeWorkspaces.remove(workspaceId);
        activeWorkspaceByUser.values().remove(workspaceId);
        log.info("Archived workspace '{}'", workspaceId);
        return ws;
    }

    /**
     * Hard-delete a workspace (only own, non-shared).
     *
     * @param workspaceId the workspace to delete
     * @param username    the requesting user (must be the owner)
     */
    public void deleteWorkspace(String workspaceId, String username) {
        UserWorkspace ws = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        if (!ws.getUsername().equals(username)) {
            throw new IllegalArgumentException("Cannot delete workspace owned by another user");
        }
        if (ws.isShared()) {
            throw new IllegalArgumentException("Cannot delete the shared workspace");
        }
        if (ws.isDefault()) {
            throw new IllegalArgumentException("Cannot delete the default workspace");
        }

        // Remove from active state
        activeWorkspaces.remove(workspaceId);
        activeWorkspaceByUser.values().remove(workspaceId);

        workspaceRepository.delete(ws);
        log.info("Deleted workspace '{}' for user '{}'", workspaceId, username);
    }

    /**
     * List all non-archived workspaces for a user.
     *
     * @param username the user
     * @return list of non-archived workspaces ordered by last accessed
     */
    public List<UserWorkspace> listUserWorkspaces(String username) {
        return workspaceRepository.findByUsernameAndArchivedFalseOrderByLastAccessedAtDesc(username);
    }

    /**
     * Find the currently active workspace for a user from the in-memory map.
     *
     * @param username the user
     * @return the active workspace entity, or null if none is active
     */
    public UserWorkspace findActiveWorkspace(String username) {
        String activeWsId = activeWorkspaceByUser.get(username);
        if (activeWsId != null) {
            try {
                return workspaceRepository.findByWorkspaceId(activeWsId).orElse(null);
            } catch (Exception e) {
                log.debug("Could not find active workspace '{}' for '{}': {}",
                        activeWsId, username, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Get a workspace by its ID.
     *
     * @param workspaceId the workspace ID
     * @return the workspace entity, or null if not found
     */
    public UserWorkspace getWorkspaceById(String workspaceId) {
        try {
            return workspaceRepository.findByWorkspaceId(workspaceId).orElse(null);
        } catch (Exception e) {
            log.debug("Could not find workspace '{}': {}", workspaceId, e.getMessage());
            return null;
        }
    }

    /**
     * Update the description of a workspace.
     *
     * @param workspaceId the workspace to update
     * @param description the new description
     * @return the updated workspace entity
     */
    public UserWorkspace updateDescription(String workspaceId, String description) {
        UserWorkspace ws = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));
        ws.setDescription(description);
        workspaceRepository.save(ws);
        log.info("Updated description for workspace '{}'", workspaceId);
        return ws;
    }

    // ── Provisioning ───────────────────────────────────────────────

    /**
     * Provision a user's workspace repository by creating a personal Git branch.
     *
     * <p>This method implements lazy provisioning: workspace metadata is created
     * eagerly on first access, but the actual Git branch is only created when
     * explicitly requested (e.g. when the user first needs write access).
     *
     * <p>If the workspace is already provisioned ({@code READY}), this method
     * is a no-op and returns the existing workspace.
     *
     * @param username the authenticated user's username
     * @return the provisioned workspace entity
     * @throws RuntimeException if provisioning fails
     */
    public UserWorkspace provisionWorkspaceRepository(String username) {
        // Try to find workspace by active workspaceId first, fall back to username lookup
        UserWorkspace ws = null;
        String activeWsId = activeWorkspaceByUser.get(username);
        if (activeWsId != null) {
            ws = workspaceRepository.findByWorkspaceId(activeWsId).orElse(null);
        }
        if (ws == null) {
            ws = workspaceRepository.findByUsernameAndSharedFalse(username)
                    .orElseThrow(() -> new IllegalStateException("No workspace metadata for " + username));
        }

        if (ws.getProvisioningStatus() == WorkspaceProvisioningStatus.READY) {
            return ws;
        }

        ws.setProvisioningStatus(WorkspaceProvisioningStatus.PROVISIONING);
        workspaceRepository.save(ws);

        try {
            var sysRepo = systemRepositoryService.getPrimaryRepository();
            String baseBranch = sysRepo.getDefaultBranch();
            String userBranch = username + "/workspace";

            String baseCommit = gitRepository.getHeadCommit(baseBranch);
            if (baseCommit != null) {
                gitRepository.createBranch(userBranch, baseBranch);
            }

            ws.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
            ws.setSourceRepositoryId(sysRepo.getRepositoryId());
            ws.setTopologyMode(sysRepo.getTopologyMode());
            ws.setBaseBranch(baseBranch);
            ws.setBaseCommit(baseCommit);
            ws.setCurrentBranch(userBranch);
            ws.setCurrentCommit(baseCommit);
            ws.setSyncTargetBranch(baseBranch);
            ws.setProvisionedAt(Instant.now());
            ws.setProvisioningError(null);
            workspaceRepository.save(ws);

            log.info("Provisioned workspace for user '{}': branch='{}', base='{}'",
                    username, userBranch, baseBranch);
            return ws;
        } catch (Exception e) {
            ws.setProvisioningStatus(WorkspaceProvisioningStatus.FAILED);
            ws.setProvisioningError(e.getMessage());
            workspaceRepository.save(ws);
            throw new RuntimeException("Could not provision workspace for " + username, e);
        }
    }

    // ── Internal ────────────────────────────────────────────────────

    private void ensurePersistentWorkspace(String username) {
        try {
            if (!workspaceRepository.existsByUsername(username)) {
                UserWorkspace ws = new UserWorkspace();
                ws.setWorkspaceId(UUID.randomUUID().toString());
                ws.setUsername(username);
                ws.setDisplayName(username + "'s workspace");
                ws.setCurrentBranch("draft");
                ws.setBaseBranch("draft");
                ws.setShared(false);
                ws.setDefault(true);
                ws.setArchived(false);
                ws.setProvisioningStatus(WorkspaceProvisioningStatus.NOT_PROVISIONED);
                ws.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
                ws.setCreatedAt(Instant.now());
                ws.setLastAccessedAt(Instant.now());
                workspaceRepository.save(ws);
                log.debug("Created persistent workspace for user '{}'", username);
            } else {
                workspaceRepository.findByUsernameAndSharedFalse(username)
                        .ifPresent(ws -> {
                            ws.setLastAccessedAt(Instant.now());
                            workspaceRepository.save(ws);
                        });
            }
        } catch (Exception e) {
            // Non-fatal: workspace state works in-memory even if persistence fails
            log.warn("Could not persist workspace for user '{}': {}", username, e.getMessage());
        }
    }

    private WorkspaceInfo toWorkspaceInfo(UserWorkspaceState state) {
        ContextRef ctx = state.getCurrentContext();
        String provisioningStatus = "READY";
        String topologyMode = "INTERNAL_SHARED";
        String sourceRepositoryId = null;
        String wsId = state.getUsername() + "-workspace";
        String displayName = state.getUsername() + "'s workspace";
        String description = null;
        boolean archived = false;
        boolean isDefault = false;

        try {
            UserWorkspace ws = findActiveWorkspace(state.getUsername());
            if (ws == null) {
                ws = workspaceRepository.findByUsernameAndSharedFalse(state.getUsername())
                        .orElse(null);
            }
            if (ws != null) {
                provisioningStatus = ws.getProvisioningStatus().name();
                topologyMode = ws.getTopologyMode().name();
                sourceRepositoryId = ws.getSourceRepositoryId();
                wsId = ws.getWorkspaceId();
                displayName = ws.getDisplayName();
                description = ws.getDescription();
                archived = ws.isArchived();
                isDefault = ws.isDefault();
            }
        } catch (Exception e) {
            log.debug("Could not read provisioning info for '{}': {}", state.getUsername(), e.getMessage());
        }

        return new WorkspaceInfo(
                wsId,
                state.getUsername(),
                displayName,
                ctx != null ? ctx.branch() : "draft",
                "draft",
                false,
                ctx,
                Instant.now(),
                Instant.now(),
                provisioningStatus,
                topologyMode,
                sourceRepositoryId,
                description,
                archived,
                isDefault
        );
    }
}
