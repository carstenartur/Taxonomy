package com.taxonomy.dto;

import java.time.Instant;

/**
 * One entry in the context navigation history.
 *
 * <p>Records a navigation from one context to another, together with the
 * reason for the transition. The history behaves like a browser history:
 * users can go back and forward through their navigation trail.
 *
 * @param fromContextId  the context navigated away from
 * @param toContextId    the context navigated to
 * @param reason         why the navigation happened
 * @param createdAt      when the navigation occurred
 */
public record ContextHistoryEntry(
    String fromContextId,
    String toContextId,
    NavigationReason reason,
    Instant createdAt
) {}
