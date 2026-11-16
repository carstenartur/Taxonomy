package com.taxonomy.versioning.service;

import com.taxonomy.dto.ContextHistoryEntry;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.NavigationReason;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceNavigation;
import com.taxonomy.workspace.service.WorkspaceNavigationSession;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manages per-user architecture context and navigation history.
 *
 * <p>Provides browser-like navigation: the user can open read-only snapshots,
 * switch branches, return to origin, and navigate back through history.
 *
 * <p>Authenticated browser documents select a navigation cursor within the authorized
 * workspace. Headerless clients use the per-actor cursor managed by {@link WorkspaceManager}.
 * Overloaded methods without a {@code username} parameter use
 * {@link WorkspaceManager#DEFAULT_USER} for backward compatibility with
 * tests and unauthenticated callers.
 */
@Service
public class ContextNavigationService {

    private static final Logger log = LoggerFactory.getLogger(ContextNavigationService.class);

    private final DslGitRepositoryFactory repositoryFactory;
    private final RepositoryStateService stateService;
    private final WorkspaceManager workspaceManager;
    private final int maxHistory;

    public ContextNavigationService(DslGitRepositoryFactory repositoryFactory,
                                    RepositoryStateService stateService,
                                    WorkspaceManager workspaceManager,
                                    @Value("${taxonomy.context.max-history:50}") int maxHistory) {
        this.repositoryFactory = repositoryFactory;
        this.stateService = stateService;
        this.workspaceManager = workspaceManager;
        this.maxHistory = maxHistory;
    }

    /**
     * Resolve the Git repository for the given workspace context.
     *
     * @param ctx the workspace context (use {@link WorkspaceContext#SHARED}
     *            for the system repository)
     * @return the resolved DslGitRepository
     */
    private DslGitRepository resolveRepository(WorkspaceContext ctx) {
        return repositoryFactory.resolveRepository(ctx);
    }

    // ── Workspace-aware methods ─────────────────────────────────────

    /**
     * Get the current context reference for a user.
     *
     * @param username the user whose context to retrieve
     * @return the current context, never null
     */
    public ContextRef getCurrentContext(String username) {
        WorkspaceNavigation state = legacyWorkspaceNavigation(username);
        if (state != null) {
            return state.getCurrentContext();
        }
        return centralReadContext(username, WorkspaceContext.LEGACY_REPOSITORY_ID,
                stateService.resolveWorkspaceBranch(username));
    }

    /** Read the context of the repository selection authorized for this request. */
    public ContextRef getCurrentContextForScope(WorkspaceContext scope) {
        if (scope.workspaceId() != null) {
            WorkspaceNavigation browser = browserNavigation(scope, true);
            return (browser == null ? legacyScopedNavigation(scope) : browser).getCurrentContext();
        }
        return centralReadContext(scope.username(), scope.repositoryId(), scope.currentBranch());
    }

    record TransferTarget(WorkspaceNavigation state, WorkspaceNavigation.NavigationSelection selection, String branch) {
        /** The caller holds the same state monitor as setCurrentContext through the Git ref update. */
        void requireCurrent() {
            if (state.getNavigationSelection() != selection) throw copyBackContextChanged();
        }
    }

    TransferTarget getEditableTransferTarget(WorkspaceContext scope, String expectedContextId) {
        var state = scopedNavigation(scope);
        var selection = state.getNavigationSelection();
        var current = selection.current();
        if (expectedContextId != null && !expectedContextId.equals(current.contextId())) {
            throw copyBackContextChanged();
        }
        if (current.mode() == ContextMode.EDITABLE) {
            return new TransferTarget(state, selection, current.branch());
        }
        var origin = selection.origin();
        if (current.mode() == ContextMode.READ_ONLY && origin != null && origin.mode() == ContextMode.EDITABLE) {
            return new TransferTarget(state, selection, origin.branch());
        }
        throw new AccessDeniedException("Copy-back requires an editable workspace context or origin");
    }

    private static ResponseStatusException copyBackContextChanged() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "The copy-back context changed. Reopen the transfer and preview it again.");
    }

    private ContextRef centralReadContext(String username, String repositoryId, String branch) {
        String actor = username == null ? WorkspaceManager.DEFAULT_USER : username;
        String contextId = UUID.nameUUIDFromBytes(
                ("central-read:" + actor.length() + ":" + actor
                        + repositoryId.length() + ":" + repositoryId + branch)
                        .getBytes(StandardCharsets.UTF_8)).toString();
        return new ContextRef(contextId, branch, null, Instant.now(), ContextMode.READ_ONLY,
                null, null, null, null, null, false);
    }

    /**
     * Open a read-only context for a specific branch and commit.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #openReadOnly(String, String, String, WorkspaceContext, String, String)}
     * for workspace-aware resolution.
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
        return openReadOnly(username, branch, commitId, WorkspaceContext.SHARED, searchQuery, elementId);
    }

    /**
     * Open a read-only context for a specific branch and commit.
     *
     * @param username    the user performing the navigation
     * @param branch      the branch to view
     * @param commitId    the commit SHA (null for HEAD)
     * @param ctx         the workspace context for repository resolution
     * @param searchQuery the search query that led here (may be null)
     * @param elementId   the matched element ID (may be null)
     * @return the new read-only context
     */
    public ContextRef openReadOnly(String username, String branch, String commitId,
                                   WorkspaceContext ctx, String searchQuery, String elementId) {
        WorkspaceNavigation state = resolveState(username, ctx);
        String resolvedCommit = resolveCommit(branch, commitId, ctx);

        synchronized (state) {
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
    }

    /**
     * Switch the working context to a different branch/commit.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #switchContext(String, String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param username the user performing the switch
     * @param branch   the branch to switch to
     * @param commitId the commit SHA (null for HEAD)
     * @return the new context
     */
    public ContextRef switchContext(String username, String branch, String commitId) {
        return switchContext(username, branch, commitId, WorkspaceContext.SHARED);
    }

    /**
     * Switch the working context to a different branch/commit.
     *
     * @param username the user performing the switch
     * @param branch   the branch to switch to
     * @param commitId the commit SHA (null for HEAD)
     * @param ctx      the workspace context for repository resolution
     * @return the new context
     */
    public ContextRef switchContext(String username, String branch, String commitId,
                                   WorkspaceContext ctx) {
        WorkspaceNavigation state = resolveState(username, ctx);
        String resolvedCommit = resolveCommit(branch, commitId, ctx);

        synchronized (state) {
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
    }

    /**
     * Return to the origin context for a user.
     *
     * @param username the user performing the navigation
     * @return the origin context, or the current context if no origin exists
     */
    public ContextRef returnToOrigin(String username) {
        return returnToOrigin(username, WorkspaceContext.SHARED);
    }

    public ContextRef returnToOrigin(String username, WorkspaceContext scope) {
        return returnToOrigin(username, scope, null);
    }

    public ContextRef returnToOrigin(String username, WorkspaceContext scope, String expectedContextId) {
        WorkspaceNavigation state = resolveState(username, scope);
        synchronized (state) {
            ContextRef current = requireNavigationSelection(state, expectedContextId);
            ContextRef restored = state.restorePreviousContext();
            if (restored != current) {
                recordNavigation(state, current.contextId(), restored.contextId(), NavigationReason.RETURN);
                log.info("User '{}' returned to origin: branch='{}'", username, restored.branch());
            }
            return restored;
        }
    }

    /** Go one step back in navigation history for a user. */
    public ContextRef back(String username) {
        return back(username, WorkspaceContext.SHARED);
    }

    public ContextRef back(String username, WorkspaceContext scope) {
        return back(username, scope, null);
    }

    public ContextRef back(String username, WorkspaceContext scope, String expectedContextId) {
        WorkspaceNavigation state = resolveState(username, scope);
        synchronized (state) {
            ContextRef current = requireNavigationSelection(state, expectedContextId);
            if (state.isHistoryEmpty()) return current;
            ContextRef restored = state.restorePreviousContext();
            if (restored != current) {
                state.pollLastHistory();
                log.info("User '{}' navigated back to: branch='{}'", username, restored.branch());
            }
            return restored;
        }
    }

    private static ContextRef requireNavigationSelection(WorkspaceNavigation state, String expectedContextId) {
        ContextRef current = state.getCurrentContext();
        if (expectedContextId != null && !expectedContextId.equals(current.contextId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The navigation context changed. Refresh and try again.");
        }
        return current;
    }

    /**
     * Get the full navigation history for a user.
     *
     * @param username the user whose history to retrieve
     * @return list of navigation entries (newest last)
     */
    public List<ContextHistoryEntry> getHistory(String username) {
        WorkspaceNavigation state = legacyWorkspaceNavigation(username);
        return state == null ? List.of() : state.getHistory();
    }

    /** Read the history of the workspace authorized for this request. */
    public List<ContextHistoryEntry> getHistoryForScope(WorkspaceContext scope) {
        return scope.workspaceId() == null
                ? List.of() : scopedNavigation(scope).getHistory();
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
        return createVariantFromCurrent(username, variantName, WorkspaceContext.SHARED);
    }

    /**
     * Create a new branch variant from a user's current context.
     *
     * @param username    the user creating the variant
     * @param variantName the name for the new branch
     * @param ctx         the workspace context for repository resolution
     * @return the new context on the variant branch
     * @throws IOException if the branch creation fails
     */
    public ContextRef createVariantFromCurrent(String username, String variantName,
                                               WorkspaceContext ctx) throws IOException {
        WorkspaceNavigation state = resolveState(username, ctx);
        synchronized (state) {
            ContextRef current = state.getCurrentContext();
            String sourceBranch = current.branch();

            DslGitRepository repository = resolveRepository(ctx);
            String newCommitId = current.commitId() == null
                    ? repository.createBranch(variantName, sourceBranch)
                    : repository.createBranchAtVerifiedCommit(variantName, current.commitId());

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
    }

    /**
     * Check if a user's current context is read-only.
     *
     * @param username the user to check
     * @return true if edits should be blocked
     */
    public boolean isReadOnly(String username) {
        WorkspaceNavigation state = legacyWorkspaceNavigation(username);
        return state == null || isReadOnly(state.getCurrentContext());
    }

    /** Check navigation mode in the same authorized scope used by a write guard. */
    public boolean isReadOnlyInScope(WorkspaceContext scope) {
        if (WorkspaceContext.LEGACY_REPOSITORY_ID.equals(scope.repositoryId())) {
            return isReadOnly(scope.username());
        }
        if (scope.workspaceId() == null) {
            return true;
        }
        WorkspaceNavigation state = browserNavigation(scope, false);
        if (state == null) {
            var shared = workspaceManager.getScopedWorkspaceState(scope);
            state = shared == null ? null : shared.legacyNavigation();
        }
        return state != null && isReadOnly(state.getCurrentContext());
    }

    private static boolean isReadOnly(ContextRef ctx) {
        return ctx.mode() == ContextMode.READ_ONLY
            || ctx.mode() == ContextMode.TEMPORARY;
    }

    // ── Internal helpers ────────────────────────────────────────────

    private WorkspaceNavigation resolveState(String username) {
        WorkspaceNavigation state = legacyWorkspaceNavigation(username);
        if (state == null) {
            throw new AccessDeniedException("An existing workspace or provisioning permission is required");
        }
        return state;
    }

    private WorkspaceNavigation resolveState(String username, WorkspaceContext scope) {
        if (WorkspaceContext.LEGACY_REPOSITORY_ID.equals(scope.repositoryId())) {
            return resolveState(username);
        }
        if (scope.workspaceId() == null) {
            throw new AccessDeniedException("Navigation changes require a workspace");
        }
        return scopedNavigation(scope);
    }

    private WorkspaceNavigation legacyWorkspaceNavigation(String username) {
        var state = workspaceManager.getOrCreateWorkspace(username);
        return state == null ? null : state.legacyNavigation();
    }

    private WorkspaceNavigation scopedNavigation(WorkspaceContext scope) {
        WorkspaceNavigation browser = browserNavigation(scope, false);
        return browser != null ? browser : legacyScopedNavigation(scope);
    }

    private WorkspaceNavigation legacyScopedNavigation(WorkspaceContext scope) {
        return workspaceManager.getOrCreateScopedWorkspaceState(scope).legacyNavigation();
    }

    private WorkspaceNavigation browserNavigation(WorkspaceContext scope, boolean initialize) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return WorkspaceNavigationSession.resolve(attributes.getRequest(), attributes.getResponse(),
                    scope, initialize, maxHistory);
        }
        return null;
    }

    private String resolveCommit(String branch, String commitId, WorkspaceContext ctx) {
        if (commitId != null) {
            return commitId;
        }
        try {
            return resolveRepository(ctx).getHeadCommit(branch);
        } catch (IOException e) {
            log.warn("Could not resolve HEAD for branch '{}': {}", branch, e.getMessage());
            return null;
        }
    }

    private void recordNavigation(WorkspaceNavigation state, String fromId,
                                  String toId, NavigationReason reason) {
        state.addHistoryEntry(new ContextHistoryEntry(fromId, toId, reason, Instant.now()));
    }

    private String abbreviateSha(String sha) {
        if (sha == null) return "null";
        return sha.substring(0, Math.min(7, sha.length()));
    }
}
