package com.taxonomy.workspace.model;

/** Provisioning/lifecycle state of a central architecture repository. */
public enum RepositoryLifecycleState {
    PROVISIONING,
    ACTIVE,
    ARCHIVED,
    DELETING,
    FAILED
}
