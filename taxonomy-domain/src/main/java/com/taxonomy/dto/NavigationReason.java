package com.taxonomy.dto;

/**
 * Reason for a context navigation event.
 *
 * <p>Tracks why the user moved from one context to another,
 * enabling meaningful breadcrumb trails in the UI.
 */
public enum NavigationReason {
    /** User opened a search result from commit history. */
    SEARCH_OPEN,
    /** User opened a compare/diff view between two contexts. */
    COMPARE,
    /** User created a new branch variant from the current context. */
    VARIANT_CREATED,
    /** User returned to the origin context. */
    RETURN,
    /** User manually switched branch or commit. */
    MANUAL_SWITCH
}
