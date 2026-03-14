package com.taxonomy.service;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Manages the current architecture context and navigation history.
 *
 * <p>Provides browser-like navigation: the user can open read-only snapshots,
 * switch branches, return to origin, and navigate back through history.
 *
 * <p>This service is session-scoped in spirit (volatile fields for a single
 * application instance). In a multi-user deployment, the state is shared
 * but each navigation is atomic.
 */
@Service
public class ContextNavigationService {

    private static final Logger log = LoggerFactory.getLogger(ContextNavigationService.class);

    private final DslGitRepository gitRepository;
    private final RepositoryStateService stateService;
    private final int maxHistory;

    private volatile ContextRef currentContext;
    private final Deque<ContextHistoryEntry> history = new ArrayDeque<>();

    public ContextNavigationService(DslGitRepository gitRepository,
                                    RepositoryStateService stateService,
                                    @Value("${taxonomy.context.max-history:50}") int maxHistory) {
        this.gitRepository = gitRepository;
        this.stateService = stateService;
        this.maxHistory = maxHistory;
        // Initialise with default editable context on draft branch
        this.currentContext = buildEditableContext("draft");
    }

    /**
     * Get the current context reference.
     *
     * @return the current context, never null
     */
    public ContextRef getCurrentContext() {
        return currentContext;
    }

    /**
     * Open a read-only context for a specific branch and commit.
     *
     * @param branch     the branch to view
     * @param commitId   the commit SHA (null for HEAD)
     * @param searchQuery the search query that led here (may be null)
     * @param elementId  the matched element ID (may be null)
     * @return the new read-only context
     */
    public ContextRef openReadOnly(String branch, String commitId,
                                   String searchQuery, String elementId) {
        String resolvedCommit = resolveCommit(branch, commitId);

        ContextRef previous = currentContext;
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

        recordNavigation(previous.contextId(), newCtx.contextId(),
                searchQuery != null ? NavigationReason.SEARCH_OPEN : NavigationReason.MANUAL_SWITCH);
        this.currentContext = newCtx;
        log.info("Opened read-only context: branch='{}', commit='{}'",
                branch, abbreviateSha(resolvedCommit));
        return newCtx;
    }

    /**
     * Switch the working context to a different branch/commit.
     *
     * <p>The new context is editable (unless the branch does not exist).
     *
     * @param branch   the branch to switch to
     * @param commitId the commit SHA (null for HEAD)
     * @return the new context
     */
    public ContextRef switchContext(String branch, String commitId) {
        String resolvedCommit = resolveCommit(branch, commitId);

        ContextRef previous = currentContext;
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

        recordNavigation(previous.contextId(), newCtx.contextId(), NavigationReason.MANUAL_SWITCH);
        this.currentContext = newCtx;
        log.info("Switched context: branch='{}', commit='{}'",
                branch, abbreviateSha(resolvedCommit));
        return newCtx;
    }

    /**
     * Return to the origin context (the context from which we navigated away).
     *
     * @return the origin context, or the current context if no origin exists
     */
    public ContextRef returnToOrigin() {
        ContextRef current = this.currentContext;
        if (current.originContextId() == null) {
            log.debug("No origin context to return to");
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

        recordNavigation(current.contextId(), origin.contextId(), NavigationReason.RETURN);
        this.currentContext = origin;
        log.info("Returned to origin: branch='{}'", origin.branch());
        return origin;
    }

    /**
     * Go one step back in navigation history.
     *
     * @return the previous context, or the current context if history is empty
     */
    public ContextRef back() {
        if (history.isEmpty()) {
            log.debug("Navigation history is empty — cannot go back");
            return currentContext;
        }

        ContextHistoryEntry lastEntry = history.peekLast();
        if (lastEntry == null) {
            return currentContext;
        }

        // Find the from-context in history and rebuild it
        String targetBranch = currentContext.originBranch() != null
                ? currentContext.originBranch() : "draft";
        String targetCommit = currentContext.originCommitId();

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

        // Remove the last history entry (we're going back)
        history.pollLast();
        this.currentContext = backCtx;
        log.info("Navigated back to: branch='{}'", targetBranch);
        return backCtx;
    }

    /**
     * Get the full navigation history.
     *
     * @return list of navigation entries (newest last)
     */
    public List<ContextHistoryEntry> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Create a new branch variant from the current context.
     *
     * @param variantName the name for the new branch
     * @return the new context on the variant branch
     * @throws IOException if the branch creation fails
     */
    public ContextRef createVariantFromCurrent(String variantName) throws IOException {
        ContextRef current = this.currentContext;
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

        recordNavigation(current.contextId(), variantCtx.contextId(), NavigationReason.VARIANT_CREATED);
        this.currentContext = variantCtx;
        log.info("Created variant '{}' from branch '{}'", variantName, sourceBranch);
        return variantCtx;
    }

    /**
     * Check if the current context is read-only.
     *
     * @return true if edits should be blocked
     */
    public boolean isReadOnly() {
        return currentContext.mode() == ContextMode.READ_ONLY
            || currentContext.mode() == ContextMode.TEMPORARY;
    }

    // ── Internal helpers ────────────────────────────────────────────

    private ContextRef buildEditableContext(String branch) {
        String commitId = resolveCommit(branch, null);
        return new ContextRef(
                UUID.randomUUID().toString(),
                branch,
                commitId,
                Instant.now(),
                ContextMode.EDITABLE,
                null,
                null,
                null,
                null,
                null,
                false
        );
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

    private void recordNavigation(String fromId, String toId, NavigationReason reason) {
        history.addLast(new ContextHistoryEntry(fromId, toId, reason, Instant.now()));
        while (history.size() > maxHistory) {
            history.pollFirst();
        }
    }

    private String abbreviateSha(String sha) {
        if (sha == null) return "null";
        return sha.substring(0, Math.min(7, sha.length()));
    }
}
