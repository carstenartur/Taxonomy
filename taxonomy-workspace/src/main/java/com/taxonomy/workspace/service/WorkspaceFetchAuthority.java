package com.taxonomy.workspace.service;

/** Stable admission and fresh publication authority for a specific working-copy fetch. */
public interface WorkspaceFetchAuthority {
    record Grant(String principalId, String loginKind, String bindingKey) { }
    String currentActor(String username);
    Grant authorize(String username);
    void checkPublication(Grant original, String username, boolean unattended);

    class FreshLoginRequired extends RuntimeException {
        public FreshLoginRequired() { super("Current external authorization is required to resume this fetch"); }
    }
}
