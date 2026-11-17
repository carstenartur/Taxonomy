package com.taxonomy.workspace.service;

import org.hibernate.Session;

/** Current model-branch policy evaluated inside the storage transaction that publishes the ref. */
@FunctionalInterface
public interface RepositoryRefPolicy {
    enum Operation { UPDATE, FORCE, DELETE }

    /**
     * The supplied session belongs to the active JGit ref transaction. Any policy
     * fence acquired here remains held until publication commits or rolls back.
     */
    void require(Session storageTransaction, String storageName, String branch, Operation operation);

    /** Remove source-owned restrictions only after the owner's physical workspace deletion succeeds. */
    default void removeWorkspace(String repositoryId, String workspaceId, String actorScope) { }
}
