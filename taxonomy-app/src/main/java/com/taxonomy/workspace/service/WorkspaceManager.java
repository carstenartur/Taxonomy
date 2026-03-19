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
 * <p>The manager maintains an in-memory map of active workspace states and
 * persists workspace metadata (branch, timestamps) via {@link UserWorkspaceRepository}.
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

    private final ConcurrentMap<String, UserWorkspaceState> activeWorkspaces = new ConcurrentHashMap<>();

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
     * Get or create the workspace state for a user.
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
        return activeWorkspaces.computeIfAbsent(user, u -> {
            log.info("Creating workspace state for user '{}'", u);
            ensurePersistentWorkspace(u);
            return new UserWorkspaceState(u, maxHistory);
        });
    }

    /**
     * Get the workspace state for a user, or null if not active.
     *
     * @param username the user's username
     * @return the workspace state, or null if the user has no active workspace
     */
    public UserWorkspaceState getWorkspace(String username) {
        return activeWorkspaces.get(username != null ? username : DEFAULT_USER);
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
        UserWorkspaceState removed = activeWorkspaces.remove(username);
        if (removed != null) {
            log.info("Evicted workspace state for user '{}'", username);
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
     * Find the persistent workspace entity for a user.
     *
     * @param username the user's username
     * @return the workspace entity, or null if not found
     */
    public UserWorkspace findUserWorkspace(String username) {
        try {
            return workspaceRepository.findByUsernameAndSharedFalse(username).orElse(null);
        } catch (Exception e) {
            log.debug("Could not find workspace for '{}': {}", username, e.getMessage());
            return null;
        }
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
        UserWorkspace ws = workspaceRepository.findByUsernameAndSharedFalse(username)
                .orElseThrow(() -> new IllegalStateException("No workspace metadata for " + username));

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

        try {
            UserWorkspace ws = workspaceRepository.findByUsernameAndSharedFalse(state.getUsername())
                    .orElse(null);
            if (ws != null) {
                provisioningStatus = ws.getProvisioningStatus().name();
                topologyMode = ws.getTopologyMode().name();
                sourceRepositoryId = ws.getSourceRepositoryId();
            }
        } catch (Exception e) {
            log.debug("Could not read provisioning info for '{}': {}", state.getUsername(), e.getMessage());
        }

        return new WorkspaceInfo(
                state.getUsername() + "-workspace",
                state.getUsername(),
                state.getUsername() + "'s workspace",
                ctx != null ? ctx.branch() : "draft",
                "draft",
                false,
                ctx,
                Instant.now(),
                Instant.now(),
                provisioningStatus,
                topologyMode,
                sourceRepositoryId
        );
    }
}
