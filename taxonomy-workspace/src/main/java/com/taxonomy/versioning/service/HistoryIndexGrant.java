package com.taxonomy.versioning.service;

import com.taxonomy.workspace.service.WorkspaceFetchAuthority.Grant;

/** Authenticated originator of a history-index maintenance command. */
public interface HistoryIndexGrant {
    Grant authorize(String username, boolean purge);
    String currentActor(String username);
    void verify(Grant recorded, String username, boolean purge, boolean background);
}
