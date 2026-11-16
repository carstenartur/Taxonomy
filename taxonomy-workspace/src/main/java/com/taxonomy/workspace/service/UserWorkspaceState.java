package com.taxonomy.workspace.service;

import com.taxonomy.dto.ContextRef;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.versioning.service.RepositoryStateService;

import java.time.Instant;

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
    private final WorkspaceNavigation legacyNavigation;

    // Projection tracking (per-workspace freshness)
    private volatile String lastProjectionCommit;
    private volatile String lastProjectionBranch;
    private volatile Instant lastProjectionTimestamp;
    private volatile String lastIndexCommit;
    private volatile Instant lastIndexTimestamp;

    // Operation tracking (per-workspace)
    private volatile String operationKind;  // null = no operation

    public UserWorkspaceState(String username, int maxHistory) {
        this(username, maxHistory, "draft");
    }

    UserWorkspaceState(String username, int maxHistory, String branch) {
        this.username = username;
        this.legacyNavigation = new WorkspaceNavigation(maxHistory, branch);
    }

    public String getUsername() { return username; }

    public WorkspaceNavigation legacyNavigation() { return legacyNavigation; }

    public ContextRef getCurrentContext() { return legacyNavigation.getCurrentContext(); }

    public void setCurrentContext(ContextRef context) { legacyNavigation.setCurrentContext(context); }

    public boolean isHistoryEmpty() { return legacyNavigation.isHistoryEmpty(); }

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
