package com.taxonomy.workspace.service;

/** Stable admission and fresh publication authority for a specific working-copy fetch. */
public interface WorkspaceFetchAuthority {
    record Grant(String principalId, String loginKind, String bindingKey) { }
    String currentActor(String username);
    default Grant authorize(String username) { return authorize(username, RepositoryScope.WORKSPACE); }
    Grant authorize(String username, RepositoryScope scope);
    default void checkPublication(Grant original, String username, boolean unattended) {
        checkPublication(original, username, RepositoryScope.WORKSPACE, unattended);
    }
    void checkPublication(Grant original, String username, RepositoryScope scope, boolean unattended);

    class FreshLoginRequired extends RuntimeException {
        public FreshLoginRequired() { super("Current external authorization is required to resume this fetch"); }
    }
}
