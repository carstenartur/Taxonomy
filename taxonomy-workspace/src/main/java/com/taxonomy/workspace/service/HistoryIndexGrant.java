package com.taxonomy.workspace.service;

import com.taxonomy.workspace.service.WorkspaceFetchAuthority.Grant;

/** Authenticated originator of a history-index maintenance command. */
public interface HistoryIndexGrant {
    Grant authorizeHistoryIndex(String username, boolean purge);
    String currentActor(String username);
    void checkHistoryIndex(Grant recorded, String username, boolean purge, boolean background);
}
