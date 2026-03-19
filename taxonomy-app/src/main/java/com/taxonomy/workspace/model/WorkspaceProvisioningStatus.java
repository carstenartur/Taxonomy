package com.taxonomy.workspace.model;

/**
 * Tracks the provisioning lifecycle of a user workspace.
 *
 * <p>New workspaces start as {@code NOT_PROVISIONED} (metadata only).
 * When the user first needs a personal branch, provisioning transitions
 * through {@code PROVISIONING} to {@code READY} (or {@code FAILED} on error).
 */
public enum WorkspaceProvisioningStatus {

    /** Workspace metadata exists, but no Git branch has been created yet. */
    NOT_PROVISIONED,

    /** Branch creation is in progress. */
    PROVISIONING,

    /** Workspace is fully provisioned and ready for use. */
    READY,

    /** Provisioning failed; see {@code provisioningError} for details. */
    FAILED
}
