package com.taxonomy.workspace.service;

/**
 * Workspace-owned stale-state signal exposed across bounded-context ports.
 * Persistence-specific conflict types must not leak to integration consumers.
 */
public final class WorkspaceRevisionConflict extends RuntimeException {
    private final long expected;
    private final long actual;

    public WorkspaceRevisionConflict(long expected, long actual) {
        super("Workspace revision moved from " + expected + " to " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() {
        return expected;
    }

    public long actual() {
        return actual;
    }
}
