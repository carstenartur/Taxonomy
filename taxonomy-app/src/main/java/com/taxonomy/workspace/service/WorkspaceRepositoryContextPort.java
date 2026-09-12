package com.taxonomy.workspace.service;

/**
 * Resolves a supplied compatibility selection to its exact repository context.
 *
 * <p>Workspace owns repository selection, owner validation and stored workspace
 * provenance. Consumers must not repeat those lookups or infer a repository from
 * a branch name. Resolution does not provision a workspace, publish a version or
 * change the supplied actor's access scope.</p>
 */
@FunctionalInterface
public interface WorkspaceRepositoryContextPort {

    /**
     * Resolve the selection, rejecting unknown workspaces, foreign owners and
     * mismatched repository provenance. Only the explicit legacy repository
     * sentinel (including a null selection's shared compatibility view) resolves
     * through the configured primary repository. Central selections stay read-only.
     */
    RepositoryContext resolve(WorkspaceContext selection);
}
