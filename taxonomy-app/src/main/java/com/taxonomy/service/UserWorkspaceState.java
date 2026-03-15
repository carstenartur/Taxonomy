package com.taxonomy.service;

import com.taxonomy.dto.ContextHistoryEntry;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Per-user in-memory workspace state.
 *
 * <p>Holds all the volatile state that was previously shared across all users
 * in the singleton {@link ContextNavigationService} and {@link RepositoryStateService}.
 * Each user gets their own instance via the {@link WorkspaceManager}.
 *
 * <p>This includes:
 * <ul>
 *   <li>The current architecture context (branch, commit, mode)</li>
 *   <li>Navigation history (browser-like back/forward)</li>
 *   <li>Projection tracking (which commit the DB projection was built from)</li>
 *   <li>Operation tracking (whether a merge/cherry-pick is in progress)</li>
 * </ul>
 *
 * <p>Thread safety: individual fields are volatile for visibility; compound
 * operations should be synchronized externally if needed.
 */
public class UserWorkspaceState {

    private final String username;
    private final int maxHistory;

    // Context navigation state
    private volatile ContextRef currentContext;
    private final Deque<ContextHistoryEntry> history = new ArrayDeque<>();

    // Projection tracking (per-workspace freshness)
    private volatile String lastProjectionCommit;
    private volatile String lastProjectionBranch;
    private volatile Instant lastProjectionTimestamp;
    private volatile String lastIndexCommit;
    private volatile Instant lastIndexTimestamp;

    // Operation tracking (per-workspace)
    private volatile String operationKind;  // null = no operation

    public UserWorkspaceState(String username, int maxHistory) {
        this.username = username;
        this.maxHistory = maxHistory;
        // Initialize with default editable context on draft branch
        this.currentContext = new ContextRef(
                UUID.randomUUID().toString(),
                "draft",
                null,
                Instant.now(),
                ContextMode.EDITABLE,
                null, null, null, null, null, false
        );
    }

    // ── Username ────────────────────────────────────────────────────

    public String getUsername() {
        return username;
    }

    // ── Context navigation ──────────────────────────────────────────

    public ContextRef getCurrentContext() {
        return currentContext;
    }

    public void setCurrentContext(ContextRef ctx) {
        this.currentContext = ctx;
    }

    public List<ContextHistoryEntry> getHistory() {
        return new ArrayList<>(history);
    }

    public void addHistoryEntry(ContextHistoryEntry entry) {
        history.addLast(entry);
        while (history.size() > maxHistory) {
            history.pollFirst();
        }
    }

    public ContextHistoryEntry peekLastHistory() {
        return history.peekLast();
    }

    public ContextHistoryEntry pollLastHistory() {
        return history.pollLast();
    }

    public boolean isHistoryEmpty() {
        return history.isEmpty();
    }

    // ── Projection tracking ─────────────────────────────────────────

    public String getLastProjectionCommit() {
        return lastProjectionCommit;
    }

    public String getLastProjectionBranch() {
        return lastProjectionBranch;
    }

    public Instant getLastProjectionTimestamp() {
        return lastProjectionTimestamp;
    }

    public void recordProjection(String commitId, String branch) {
        this.lastProjectionCommit = commitId;
        this.lastProjectionBranch = branch;
        this.lastProjectionTimestamp = Instant.now();
    }

    public String getLastIndexCommit() {
        return lastIndexCommit;
    }

    public Instant getLastIndexTimestamp() {
        return lastIndexTimestamp;
    }

    public void recordIndexBuild(String commitId) {
        this.lastIndexCommit = commitId;
        this.lastIndexTimestamp = Instant.now();
    }

    // ── Operation tracking ──────────────────────────────────────────

    public String getOperationKind() {
        return operationKind;
    }

    public boolean isOperationInProgress() {
        return operationKind != null;
    }

    public void beginOperation(String kind) {
        this.operationKind = kind;
    }

    public void endOperation() {
        this.operationKind = null;
    }
}
