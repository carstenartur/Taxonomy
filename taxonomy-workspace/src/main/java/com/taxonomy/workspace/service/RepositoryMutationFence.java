package com.taxonomy.workspace.service;

/** Existing repository aggregate fence, acquired before feature scope/project locks in the caller's transaction. */
@FunctionalInterface
public interface RepositoryMutationFence {
    void lock(String repositoryId);
}
