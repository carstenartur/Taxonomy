package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryRole;

import java.util.Optional;

/**
 * Resolves trusted repository grants bound to the current authenticated actor.
 * Implementations must match the supplied actor exactly to the authentication name,
 * and return no grant for unauthenticated or untrusted identity sources.
 */
@FunctionalInterface
public interface RepositoryRoleGrantProvider {
    Optional<RepositoryRole> findRole(String repositoryId, String actor);
}
