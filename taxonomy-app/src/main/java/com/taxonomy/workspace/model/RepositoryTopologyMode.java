package com.taxonomy.workspace.model;

/**
 * Defines how the system repository relates to external sources.
 *
 * <p>The topology mode determines the synchronization strategy and
 * controls whether the system hosts the shared integration repository
 * internally or relies on an external canonical source.
 */
public enum RepositoryTopologyMode {

    /** System hosts the shared integration repository internally. */
    INTERNAL_SHARED,

    /** External Git repository acts as the canonical central source. */
    EXTERNAL_CANONICAL
}
