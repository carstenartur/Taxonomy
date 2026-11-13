package com.taxonomy.workspace.service;

/** Stable authenticated identity and current ARCHITECTURE_PUBLISH authority across an interrupted publication. */
public interface WorkspacePublicationAuthority {
    String currentActor(String username);
    WorkspaceFetchAuthority.Grant authorizePublication(String username);
    void checkPublicationGrant(WorkspaceFetchAuthority.Grant original, String username, boolean unattended);
}
