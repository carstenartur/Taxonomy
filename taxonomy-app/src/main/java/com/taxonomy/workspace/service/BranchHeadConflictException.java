package com.taxonomy.workspace.service;

import java.io.IOException;

/**
 * Workspace-owned failure of an exact branch-head precondition.
 * Null expected or actual heads denote branch absence. The storage adapter
 * preserves the original diagnostic message, including any ref-update result.
 */
public final class BranchHeadConflictException extends IOException {
    private final String branch;
    private final String expectedHeadCommit;
    private final String actualHeadCommit;

    public BranchHeadConflictException(
            String branch,
            String expectedHeadCommit,
            String actualHeadCommit,
            String message) {
        super(message);
        this.branch = branch;
        this.expectedHeadCommit = expectedHeadCommit;
        this.actualHeadCommit = actualHeadCommit;
    }

    public String getBranch() {
        return branch;
    }

    public String getExpectedHeadCommit() {
        return expectedHeadCommit;
    }

    public String getActualHeadCommit() {
        return actualHeadCommit;
    }
}
