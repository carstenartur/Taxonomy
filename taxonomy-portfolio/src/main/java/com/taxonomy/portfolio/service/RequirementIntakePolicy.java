package com.taxonomy.portfolio.service;

import com.taxonomy.workspace.service.WorkspaceContext;

/** A composition binding may prohibit ordinary provider review, including isolated demo projects. */
@FunctionalInterface
public interface RequirementIntakePolicy {
    boolean allowsProviderReview(Long projectId, String username, WorkspaceContext context);
}
