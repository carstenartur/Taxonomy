package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dto.ContextHistoryEntry;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.NavigationReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.taxonomy.workspace.service.UserWorkspaceState;
import com.taxonomy.workspace.service.WorkspaceManager;

/**
 * Manages per-user architecture context and navigation history.
 *
 * <p>Provides browser-like navigation: the user can open read-only snapshots,
 * switch branches, return to origin, and navigate back through history.
 *
 * <p>All state is isolated per user via {@link WorkspaceManager}. Each user
 * has their own current context, navigation history, and read-only state.
 * Overloaded methods without a {@code username} parameter use
 * {@link WorkspaceManager#DEFAULT_USER} for backward compatibility with
 * tests and unauthenticated callers.
 */
@Service
public class ContextNavigationService {

    private static final Logger log = LoggerFactory.getLogger(ContextNavigationService.class);

    private final DslGitRepository gitRepository;
    private final RepositoryStateService stateService;
    private final WorkspaceManager workspaceManager;
    private final int maxHistory;

    public ContextNavigationService(DslGitRepository gitRepository,
                                    RepositoryStateService stateService,
                                    WorkspaceManager workspaceManager,
                                    @Value("${taxonomy.context.max-history:50}") int maxHistory) {
        this.gitRepository = gitRepository;
        this.stateService = stateService;
        this.workspaceManager = workspaceManager;
        this.maxHistory = maxHistory;
    }

    // ── Workspace-aware methods ─────────────────────────────────────

    /**
     * Get the current context reference for a user.
     *
     * @param username the user whose context to retrieve
     * @return the current context, never null
     */
    public ContextRef getCurrentContext(String username) {
        return resolveState(username).getCurrentContext();
    }

    /**
     * Open a read-only context for a specific branch and commit.
     *
     * @param username    the user performing the navigation
     * @param branch      the branch to view
     * @param commitId    the commit SHA (null for HEAD)
     * @param searchQuery the search query that led here (may be null)
     * @param elementId   the matched element ID (may be null)
     * @return the new read-only context
     */
    public ContextRef openReadOnly(String username, String branch, String commitId,
                                   String searchQuery, String elementId) {
        UserWorkspaceState state = resolveState(username);
        String resolvedCommit = resolveCommit(branch, commitId);

        ContextRef previous = state.getCurrentContext();
        ContextRef newCtx = new ContextRef(
                UUID.randomUUID().toString(),
                branch,
                resolvedCommit,
                Instant.now(),
                ContextMode.READ_ONLY,
                previous.contextId(),
                previous.branch(),
                previous.commitId(),
                searchQuery,
                elementId,
                false
        );

        recordNavigation(state, previous.contextId(), newCtx.contextId(),
                searchQuery != null ? NavigationReason.SEARCH_OPEN : NavigationReason.MANUAL_SWITCH);
        state.setCurrentContext(newCtx);
        log.info("User '{}' opened read-only context: branch='{}', commit='{}'",
                username, branch, abbreviateSha(resolvedCommit));
        return newCtx;
    }

    /**
     * Switch the working context to a different branch/commit.
     *
     * @param username the user performing the switch
     * @param branch   the branch to switch to
     * @param commitId the commit SHA (null for HEAD)
     * @return the new context
     */
    public ContextRef switchContext(String username, String branch, String commitId) {
        UserWorkspaceState state = resolveState(username);
        String resolvedCommit = resolveCommit(branch, commitId);

        ContextRef previous = state.getCurrentContext();
        ContextRef newCtx = new ContextRef(
                UUID.randomUUID().toString(),
                branch,
                resolvedCommit,
                Instant.now(),
                ContextMode.EDITABLE,
                previous.contextId(),
                previous.branch(),
                previous.commitId(),
                null,
                null,
                false
        );

        recordNavigation(state, previous.contextId(), newCtx.contextId(), NavigationReason.MANUAL_SWITCH);
        state.setCurrentContext(newCtx);
        log.info("User '{}' switched context: branch='{}', commit='{}'",
                username, branch, abbreviateSha(resolvedCommit));
        return newCtx;
    }

    /**
     * Return to the origin context for a user.
     *
     * @param username the user performing the navigation
     * @return the origin context, or the current context if no origin exists
     */
    public ContextRef returnToOrigin(String username) {
        UserWorkspaceState state = resolveState(username);
        ContextRef current = state.getCurrentContext();
        if (current.originContextId() == null) {
            log.debug("User '{}': no origin context to return to", username);
            return current;
        }

        ContextRef origin = new ContextRef(
                UUID.randomUUID().toString(),
                current.originBranch() != null ? current.originBranch() : "draft",
                current.originCommitId(),
                Instant.now(),
                ContextMode.EDITABLE,
                null,
                null,
                null,
                null,
                null,
                false
        );

        recordNavigation(state, current.contextId(), origin.contextId(), NavigationReason.RETURN);
        state.setCurrentContext(origin);
        log.info("User '{}' returned to origin: branch='{}'", username, origin.branch());
        return origin;
    }

    /**
     * Go one step back in navigation history for a user.
     *
     * @param username the user performing the navigation
     * @return the previous context, or the current context if history is empty
     */
    public ContextRef back(String username) {
        UserWorkspaceState state = resolveState(username);
        if (state.isHistoryEmpty()) {
            log.debug("User '{}': navigation history is empty — cannot go back", username);
            return state.getCurrentContext();
        }

        ContextHistoryEntry lastEntry = state.peekLastHistory();
        if (lastEntry == null) {
            return state.getCurrentContext();
        }

        ContextRef current = state.getCurrentContext();
        String targetBranch = current.originBranch() != null
                ? current.originBranch() : "draft";
        String targetCommit = current.originCommitId();

        ContextRef backCtx = new ContextRef(
                UUID.randomUUID().toString(),
                targetBranch,
                targetCommit,
                Instant.now(),
                ContextMode.EDITABLE,
                null,
                null,
                null,
                null,
                null,
                false
        );

        state.pollLastHistory();
        state.setCurrentContext(backCtx);
        log.info("User '{}' navigated back to: branch='{}'", username, targetBranch);
        return backCtx;
    }

    /**
     * Get the full navigation history for a user.
     *
     * @param username the user whose history to retrieve
     * @return list of navigation entries (newest last)
     */
    public List<ContextHistoryEntry> getHistory(String username) {
        return resolveState(username).getHistory();
    }

    /**
     * Create a new branch variant from a user's current context.
     *
     * @param username    the user creating the variant
     * @param variantName the name for the new branch
     * @return the new context on the variant branch
     * @throws IOException if the branch creation fails
     */
    public ContextRef createVariantFromCurrent(String username, String variantName) throws IOException {
        UserWorkspaceState state = resolveState(username);
        ContextRef current = state.getCurrentContext();
        String sourceBranch = current.branch();

        String newCommitId = gitRepository.createBranch(variantName, sourceBranch);

        ContextRef variantCtx = new ContextRef(
                UUID.randomUUID().toString(),
                variantName,
                newCommitId,
                Instant.now(),
                ContextMode.EDITABLE,
                current.contextId(),
                current.branch(),
                current.commitId(),
                null,
                null,
                false
        );

        recordNavigation(state, current.contextId(), variantCtx.contextId(), NavigationReason.VARIANT_CREATED);
        state.setCurrentContext(variantCtx);
        log.info("User '{}' created variant '{}' from branch '{}'", username, variantName, sourceBranch);
        return variantCtx;
    }

    /**
     * Check if a user's current context is read-only.
     *
     * @param username the user to check
     * @return true if edits should be blocked
     */
    public boolean isReadOnly(String username) {
        ContextRef ctx = resolveState(username).getCurrentContext();
        return ctx.mode() == ContextMode.READ_ONLY
            || ctx.mode() == ContextMode.TEMPORARY;
    }

    // ── Internal helpers ────────────────────────────────────────────

    private UserWorkspaceState resolveState(String username) {
        return workspaceManager.getOrCreateWorkspace(username);
    }

    private String resolveCommit(String branch, String commitId) {
        if (commitId != null) {
            return commitId;
        }
        try {
            return gitRepository.getHeadCommit(branch);
        } catch (IOException e) {
            log.warn("Could not resolve HEAD for branch '{}': {}", branch, e.getMessage());
            return null;
        }
    }

    private void recordNavigation(UserWorkspaceState state, String fromId,
                                  String toId, NavigationReason reason) {
        state.addHistoryEntry(new ContextHistoryEntry(fromId, toId, reason, Instant.now()));
    }

    private String abbreviateSha(String sha) {
        if (sha == null) return "null";
        return sha.substring(0, Math.min(7, sha.length()));
    }
}
